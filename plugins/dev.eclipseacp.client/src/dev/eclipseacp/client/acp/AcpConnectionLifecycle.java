package dev.eclipseacp.client.acp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.google.gson.JsonObject;

/** Owns process/transport resources. ACP negotiation and session state stay in AcpClient. */
final class AcpConnectionLifecycle implements AutoCloseable {
    private enum State { NEW, STARTING, ACTIVE, CLOSING, CLOSED }
    private record Resources(AgentProcess process, JsonRpcTransport transport) { }

    private final String command;
    private final String arguments;
    private final AgentProcessLauncher processLauncher;
    private final JsonRpcTransportFactory transportFactory;
    private final DiagnosticSink diagnostics;
    private final Object ownership = new Object();
    private final List<String> recentAgentDiagnostics = new ArrayList<>();
    private volatile State state = State.NEW;
    private volatile Resources resources;

    AcpConnectionLifecycle(String command, String arguments, AgentProcessLauncher processLauncher,
            JsonRpcTransportFactory transportFactory, DiagnosticSink diagnostics) {
        this.command = command;
        this.arguments = arguments;
        this.processLauncher = Objects.requireNonNull(processLauncher);
        this.transportFactory = Objects.requireNonNull(transportFactory);
        this.diagnostics = diagnostics;
    }

    CompletableFuture<Void> start(Path workingDirectory, JsonRpcHandler handler, Consumer<String> agentDiagnostic,
            Consumer<Throwable> diagnosticError, Consumer<Throwable> transportError) {
        synchronized (ownership) {
            if (state == State.CLOSING || state == State.CLOSED) {
                return CompletableFuture.failedFuture(new IllegalStateException("ACP connection is closed"));
            }
            if (state != State.NEW) {
                return CompletableFuture.failedFuture(new IllegalStateException("ACP connection is already started"));
            }
            state = State.STARTING;
        }
        synchronized (recentAgentDiagnostics) { recentAgentDiagnostics.clear(); }
        AgentProcess launchedProcess = null;
        JsonRpcTransport launchedTransport = null;
        Resources acquired = null;
        boolean published = false;
        try {
            launchedProcess = processLauncher.launch(command, arguments, workingDirectory, line -> {
                rememberAgentDiagnostic(line);
                agentDiagnostic.accept(line);
            }, diagnosticError);
            launchedTransport = transportFactory.create(launchedProcess.standardOutput(), launchedProcess.standardInput(),
                    handler, transportError);
            acquired = new Resources(launchedProcess, launchedTransport);
            synchronized (ownership) {
                if (state == State.STARTING) {
                    resources = acquired;
                    state = State.ACTIVE;
                    published = true;
                    launchedTransport.start();
                }
            }
            if (!published) {
                closeOwned(launchedProcess, launchedTransport);
                return CompletableFuture.failedFuture(new IllegalStateException("ACP connection was closed while starting"));
            }
            return isClosing()
                    ? CompletableFuture.failedFuture(new IllegalStateException("ACP connection was closed while starting"))
                    : CompletableFuture.completedFuture(null);
        } catch (IOException | RuntimeException exception) {
            boolean cleanup = !published;
            synchronized (ownership) {
                if (published && resources == acquired) {
                    resources = null;
                    cleanup = true;
                }
                if (state != State.CLOSING && state != State.CLOSED) state = State.NEW;
            }
            // A reentrant close from start() may already have released published resources.
            if (cleanup) closeOwned(launchedProcess, launchedTransport);
            diagnostics.error("Could not start ACP agent process", exception);
            return CompletableFuture.failedFuture(exception);
        }
    }

    boolean isConnected() {
        return resources != null;
    }

    boolean isClosing() {
        State current = state;
        return current == State.CLOSING || current == State.CLOSED;
    }

    /** Stops startup/reuse first, keeping transport available for the final session/close request. */
    boolean beginClose() {
        synchronized (ownership) {
            if (state == State.CLOSING || state == State.CLOSED) return false;
            state = State.CLOSING;
            return true;
        }
    }

    CompletableFuture<JsonObject> request(String method, JsonObject params) {
        JsonRpcTransport connection = transport();
        return connection == null
                ? CompletableFuture.failedFuture(new IllegalStateException("ACP connection is not connected"))
                : connection.request(method, params);
    }

    void notification(String method, JsonObject params) throws IOException {
        JsonRpcTransport connection = transport();
        if (connection == null) throw new IOException("ACP connection is not connected");
        connection.notification(method, params);
    }

    private JsonRpcTransport transport() {
        Resources current = resources;
        return current == null ? null : current.transport();
    }

    @Override public void close() {
        Resources released;
        synchronized (ownership) {
            state = State.CLOSED;
            released = resources;
            resources = null;
        }
        if (released != null) closeOwned(released.process(), released.transport());
    }

    private void rememberAgentDiagnostic(String line) {
        synchronized (recentAgentDiagnostics) {
            recentAgentDiagnostics.add(line);
            if (recentAgentDiagnostics.size() > 8) recentAgentDiagnostics.remove(0);
        }
    }

    IOException withAgentDiagnostics(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        List<String> diagnostics;
        synchronized (recentAgentDiagnostics) {
            diagnostics = List.copyOf(recentAgentDiagnostics);
        }
        if (diagnostics.isEmpty()) return new IOException(message, error);
        return new IOException(message + "\n\nAgent diagnostics:\n" + String.join("\n", diagnostics), error);
    }

    private void closeOwned(AgentProcess child, JsonRpcTransport activeConnection) {
        // Terminate the child first: this unblocks the JSON-RPC reader before its streams are closed.
        if (child != null) {
            try {
                child.close();
            } catch (IOException | RuntimeException exception) {
                diagnostics.warn("ACP agent process could not be closed cleanly", exception);
            }
        }
        if (activeConnection != null) {
            try {
                activeConnection.close();
            } catch (IOException | RuntimeException ignored) {
                diagnostics.warn("ACP connection streams were already closed", ignored);
                // The child process may already have closed the streams.
            }
        }
    }
}
