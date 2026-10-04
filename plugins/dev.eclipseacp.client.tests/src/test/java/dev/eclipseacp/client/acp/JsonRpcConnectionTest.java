package dev.eclipseacp.client.acp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class JsonRpcConnectionTest {
    @Test
    public void eofClosesTheTransportFailsPendingRequestsAndRejectsLaterRequests() throws Exception {
        BlockingEofReader reader = new BlockingEofReader();
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch terminal = new CountDownLatch(1);
        JsonRpcConnection connection = connection(reader, new StringWriter(), error -> {
            failures.incrementAndGet();
            terminal.countDown();
        });

        try {
            connection.start();
            CompletableFuture<JsonObject> pending = connection.request("session/prompt", new JsonObject());
            reader.release.countDown();

            assertTrue("EOF did not report a terminal transport failure", terminal.await(2, TimeUnit.SECONDS));
            assertFails(pending);
            assertFails(connection.request("session/prompt", new JsonObject()));
            assertEquals("the terminal callback must run once", 1, failures.get());
        } finally {
            reader.release.countDown();
            connection.close();
        }
    }

    @Test
    public void readerExceptionClosesTheTransportAndReportsItOnce() throws Exception {
        AtomicInteger failures = new AtomicInteger();
        CountDownLatch terminal = new CountDownLatch(1);
        JsonRpcConnection connection = connection(new FailingReader(), new StringWriter(), error -> {
            failures.incrementAndGet();
            terminal.countDown();
        });

        try {
            connection.start();
            assertTrue("reader exception did not report a terminal transport failure", terminal.await(2, TimeUnit.SECONDS));
            assertFails(connection.request("session/prompt", new JsonObject()));
            assertEquals("the terminal callback must run once", 1, failures.get());
        } finally {
            connection.close();
        }
    }

    @Test
    public void queuesRequestsInsteadOfBlockingTheCallerOnWriterBackpressure() throws Exception {
        BlockingWriter writer = new BlockingWriter();
        JsonRpcConnection connection = new JsonRpcConnection(new StringReader(""), writer, new JsonRpcHandler() {
            @Override public void onNotification(String method, JsonObject params) { }
            @Override public CompletableFuture<JsonElement> onRequest(String method, JsonObject params) {
                return CompletableFuture.completedFuture(new JsonObject());
            }
        }, error -> { });

        try {
            long started = System.nanoTime();
            connection.request("session/prompt", new JsonObject());
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue("request must return without waiting for writer.flush(), took " + elapsedMillis + " ms",
                    elapsedMillis < 100);
            assertTrue("writer task was not started", writer.writeStarted.await(2, TimeUnit.SECONDS));
        } finally {
            writer.release.countDown();
            connection.close();
        }
    }

    @Test
    public void silentAgentTimesOutAndRemovesThePendingRequest() throws Exception {
        BlockingEofReader reader = new BlockingEofReader();
        JsonRpcConnection connection = new JsonRpcConnection(reader, new StringWriter(), new JsonRpcHandler() {
            @Override public void onNotification(String method, JsonObject params) { }
            @Override public CompletableFuture<JsonElement> onRequest(String method, JsonObject params) {
                return CompletableFuture.completedFuture(new JsonObject());
            }
        }, error -> { }, method -> 20L);

        try {
            connection.start();
            CompletableFuture<JsonObject> pending = connection.request("session/list", new JsonObject());
            assertFails(pending);
            assertEquals("timed out requests must be removed", 0, connection.pendingRequestCount());
        } finally {
            reader.release.countDown();
            connection.close();
        }
    }

    private static final class BlockingWriter extends Writer {
        final CountDownLatch writeStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override public void write(char[] characters, int offset, int length) throws IOException {
            writeStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while simulating a blocked ACP writer", exception);
            }
        }
        @Override public void flush() { }
        @Override public void close() { }
    }

    private static JsonRpcConnection connection(Reader reader, Writer writer, java.util.function.Consumer<Throwable> errorHandler) {
        return new JsonRpcConnection(reader, writer, new JsonRpcHandler() {
            @Override public void onNotification(String method, JsonObject params) { }
            @Override public CompletableFuture<JsonElement> onRequest(String method, JsonObject params) {
                return CompletableFuture.completedFuture(new JsonObject());
            }
        }, errorHandler);
    }

    private static void assertFails(CompletableFuture<?> future) throws Exception {
        try {
            future.get(2, TimeUnit.SECONDS);
        } catch (ExecutionException expected) {
            return;
        }
        throw new AssertionError("expected the future to fail");
    }

    private static final class BlockingEofReader extends Reader {
        final CountDownLatch release = new CountDownLatch(1);

        @Override public int read(char[] characters, int offset, int length) throws IOException {
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting to return EOF", exception);
            }
            return -1;
        }
        @Override public void close() { release.countDown(); }
    }

    private static final class FailingReader extends Reader {
        @Override public int read(char[] characters, int offset, int length) throws IOException {
            throw new IOException("Simulated ACP reader failure");
        }
        @Override public void close() { }
    }
}
