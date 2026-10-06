package dev.eclipseacp.client.acp;

import static org.junit.Assert.*;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class AcpConnectionLifecycleTest {
    private static final Path DIRECTORY = Path.of("/workspace/project");
    private static final JsonRpcHandler HANDLER = new JsonRpcHandler() {
        @Override public void onNotification(String method, JsonObject params) { }
        @Override public CompletableFuture<JsonElement> onRequest(String method, JsonObject params) {
            throw new AssertionError("Lifecycle must not interpret protocol requests");
        }
    };

    @Test
    public void startsRoutesRpcAndClosesProcessBeforeTransportExactlyOnce() throws Exception {
        Fixture f = new Fixture();
        try (AcpConnectionLifecycle lifecycle = f.lifecycle()) {
            start(lifecycle).join();
            assertTrue(lifecycle.isConnected());
            assertEquals(1, f.transport.starts);
            assertSame(f.transport.response, lifecycle.request("test", new JsonObject()).join());
            lifecycle.notification("cancel", new JsonObject());
            assertEquals(List.of("request:test", "notification:cancel"), f.transport.calls);

            assertTrue(lifecycle.beginClose());
            assertFalse(lifecycle.beginClose());
            assertTrue(lifecycle.isClosing());
            assertTrue(lifecycle.isConnected());
            lifecycle.request("session/close", new JsonObject()).join();
            lifecycle.close();
            lifecycle.close();

            assertEquals(List.of("process", "transport"), f.closed);
            assertEquals(1, f.process.closes);
            assertEquals(1, f.transport.closes);
            assertFalse(lifecycle.isConnected());
            assertTrue(lifecycle.request("test", new JsonObject()).isCompletedExceptionally());
        }
    }

    @Test
    public void rejectsDuplicateStartupWithoutReplacingOwnedResources() {
        Fixture f = new Fixture();
        try (AcpConnectionLifecycle lifecycle = f.lifecycle()) {
            start(lifecycle).join();
            assertTrue(start(lifecycle).isCompletedExceptionally());
            assertEquals(1, f.launches);
            assertEquals(1, f.transport.starts);
            assertEquals(0, f.process.closes);
        }
    }

    @Test
    public void closingBeforeStartupDoesNotLaunchAProcess() {
        Fixture f = new Fixture();
        AcpConnectionLifecycle lifecycle = f.lifecycle();
        lifecycle.close();

        assertTrue(start(lifecycle).isCompletedExceptionally());
        assertEquals(0, f.launches);
    }

    @Test
    public void factoryFailureReleasesTheLaunchedProcessAndCanBeRetried() {
        Fixture f = new Fixture();
        boolean[] fail = { true };
        try (AcpConnectionLifecycle lifecycle = new AcpConnectionLifecycle("agent", "", f.launcher(),
                (reader, writer, handler, errors) -> {
                    if (fail[0]) throw new IllegalStateException("factory failed");
                    return f.transport;
                }, f.diagnostics)) {
            assertTrue(start(lifecycle).isCompletedExceptionally());
            assertEquals(1, f.process.closes);
            assertFalse(lifecycle.isConnected());
            fail[0] = false;
            start(lifecycle).join();
            assertTrue(lifecycle.isConnected());
        }
    }

    @Test
    public void transportStartFailureClosesBothPublishedResources() {
        Fixture f = new Fixture();
        f.transport.onStart = () -> { throw new IllegalStateException("start failed"); };
        try (AcpConnectionLifecycle lifecycle = f.lifecycle()) {
            assertTrue(start(lifecycle).isCompletedExceptionally());
            assertFalse(lifecycle.isConnected());
            assertEquals(List.of("process", "transport"), f.closed);
            lifecycle.close();
            assertEquals(1, f.process.closes);
            assertEquals(1, f.transport.closes);
        }
    }

    @Test
    public void reentrantCloseDuringStartDoesNotDoubleCloseResourcesEvenIfStartThrows() {
        Fixture f = new Fixture();
        try (AcpConnectionLifecycle lifecycle = f.lifecycle()) {
            f.transport.onStart = () -> {
                lifecycle.close();
                throw new IllegalStateException("closed during start");
            };
            assertTrue(start(lifecycle).isCompletedExceptionally());
            assertEquals(List.of("process", "transport"), f.closed);
            assertEquals(1, f.process.closes);
            assertEquals(1, f.transport.closes);
            assertTrue(lifecycle.isClosing());
        }
    }

    @Test
    public void closeRacingWithLaunchReleasesResourcesThatArriveLater() throws Exception {
        Fixture f = new Fixture();
        CountDownLatch launched = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (AcpConnectionLifecycle lifecycle = new AcpConnectionLifecycle("agent", "", (command, arguments, cwd, out, error) -> {
            launched.countDown();
            try {
                if (!release.await(2, TimeUnit.SECONDS)) throw new IOException("test launch timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            return f.process;
        }, (reader, writer, handler, errors) -> f.transport, f.diagnostics)) {
            CompletableFuture<Void> started = CompletableFuture.runAsync(() -> start(lifecycle).join());
            try {
                assertTrue(launched.await(2, TimeUnit.SECONDS));
                lifecycle.close();
            } finally {
                release.countDown();
            }
            started.handle((ignored, failure) -> null).get(2, TimeUnit.SECONDS);
            assertTrue(started.isCompletedExceptionally());
            assertFalse(lifecycle.isConnected());
            assertEquals(0, f.transport.starts);
            assertEquals(1, f.process.closes);
            assertEquals(1, f.transport.closes);
        }
    }

    @Test
    public void closeDuringTransportCreationReleasesUnpublishedResources() {
        Fixture f = new Fixture();
        AtomicReference<AcpConnectionLifecycle> ref = new AtomicReference<>();
        try (AcpConnectionLifecycle lifecycle = new AcpConnectionLifecycle("agent", "", f.launcher(),
                (reader, writer, handler, errors) -> {
                    ref.get().close();
                    return f.transport;
                }, f.diagnostics)) {
            ref.set(lifecycle);
            assertTrue(start(lifecycle).isCompletedExceptionally());
            assertEquals(0, f.transport.starts);
            assertEquals(List.of("process", "transport"), f.closed);
        }
    }

    @Test
    public void cleanupFailuresDoNotSkipTheOtherResource() {
        Fixture f = new Fixture();
        f.process.closeFailure = new IOException("process cleanup failed");
        f.transport.closeFailure = new IOException("transport cleanup failed");
        try (AcpConnectionLifecycle lifecycle = f.lifecycle()) {
            start(lifecycle).join();
            lifecycle.close();
            assertEquals(List.of("process", "transport"), f.closed);
            assertEquals(2, f.diagnostics.warnings);
            assertFalse(lifecycle.isConnected());
        }
    }

    @Test
    public void uncheckedProcessCleanupFailureStillClosesTheTransport() {
        Fixture f = new Fixture();
        f.process.runtimeCloseFailure = new IllegalStateException("unexpected cleanup failure");
        try (AcpConnectionLifecycle lifecycle = f.lifecycle()) {
            start(lifecycle).join();
            lifecycle.close();
            assertEquals(List.of("process", "transport"), f.closed);
            assertEquals(1, f.diagnostics.warnings);
        }
    }

    @Test
    public void keepsOnlyRecentStderrForTheReportedError() {
        Fixture f = new Fixture();
        try (AcpConnectionLifecycle lifecycle = new AcpConnectionLifecycle("agent", "", (command, arguments, cwd, out, error) -> {
            for (int i = 0; i < 10; i++) out.accept("line-" + i);
            return f.process;
        }, (reader, writer, handler, errors) -> f.transport, f.diagnostics)) {
            start(lifecycle).join();
            IOException failure = lifecycle.withAgentDiagnostics(new IOException("initialize failed"));
            assertFalse(failure.getMessage().contains("line-0"));
            assertFalse(failure.getMessage().contains("line-1"));
            assertTrue(failure.getMessage().contains("line-2"));
            assertTrue(failure.getMessage().contains("line-9"));
        }
    }

    private static CompletableFuture<Void> start(AcpConnectionLifecycle lifecycle) {
        return lifecycle.start(DIRECTORY, HANDLER, line -> {}, error -> {}, error -> {});
    }

    private static final class Fixture {
        final List<String> closed = new ArrayList<>();
        final Process process = new Process(closed);
        final Transport transport = new Transport(closed);
        final Diagnostics diagnostics = new Diagnostics();
        int launches;
        AgentProcessLauncher launcher() {
            return (command, arguments, directory, out, error) -> {
                assertEquals("agent", command);
                assertEquals(DIRECTORY, directory);
                launches++;
                return process;
            };
        }
        AcpConnectionLifecycle lifecycle() {
            return new AcpConnectionLifecycle("agent", "", launcher(), (reader, writer, handler, errors) -> {
                assertSame(HANDLER, handler);
                return transport;
            }, diagnostics);
        }
    }

    private static final class Process implements AgentProcess {
        final List<String> closed;
        int closes;
        IOException closeFailure;
        RuntimeException runtimeCloseFailure;
        Process(List<String> closed) { this.closed = closed; }
        @Override public Reader standardOutput() { return new StringReader(""); }
        @Override public Writer standardInput() { return new StringWriter(); }
        @Override public void close() throws IOException {
            closes++;
            closed.add("process");
            if (closeFailure != null) throw closeFailure;
            if (runtimeCloseFailure != null) throw runtimeCloseFailure;
        }
    }

    private static final class Transport implements JsonRpcTransport {
        final List<String> closed;
        final List<String> calls = new ArrayList<>();
        final JsonObject response = new JsonObject();
        int starts;
        int closes;
        Runnable onStart = () -> {};
        IOException closeFailure;
        Transport(List<String> closed) { this.closed = closed; }
        @Override public void start() { starts++; onStart.run(); }
        @Override public CompletableFuture<JsonObject> request(String method, JsonObject params) {
            calls.add("request:" + method);
            return CompletableFuture.completedFuture(response);
        }
        @Override public void notification(String method, JsonObject params) { calls.add("notification:" + method); }
        @Override public void close() throws IOException {
            closes++;
            closed.add("transport");
            if (closeFailure != null) throw closeFailure;
        }
    }

    private static final class Diagnostics implements DiagnosticSink {
        int warnings;
        @Override public void info(String message) { }
        @Override public void trace(Supplier<String> message) { }
        @Override public void warn(String message, Throwable error) { warnings++; }
        @Override public void error(String message, Throwable error) { }
    }
}
