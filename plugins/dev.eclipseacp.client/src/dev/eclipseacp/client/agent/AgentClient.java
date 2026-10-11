package dev.eclipseacp.client.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * UI-facing agent contract; protocol versions must not leak beyond this package.
 *
 * <p>Threading: callers serialize session-changing operations and invoke methods off
 * the UI thread. Returning a future does not guarantee that preparation, process
 * startup or cleanup is nonblocking. Future continuations and listener callbacks
 * have no UI-thread affinity; consumers must dispatch UI work themselves.
 *
 * <p>Completion: future-returning methods return a non-null future. Successful
 * completion means the requested operation has completed, not merely been queued.
 * Unsupported optional operations return a failed future without performing a
 * reduced operation or changing the active session. Capabilities describe negotiated
 * support and do not replace the operation's failure handling.
 *
 * <p>Cancellation: cancelling a returned future is a local observation and does not
 * guarantee cancellation at the agent. Use {@link #cancel()} to request that the
 * agent stop the active prompt, and {@link #close()} to release the client.
 */
public interface AgentClient extends AutoCloseable {
    CompletableFuture<Void> connect(Path workingDirectory);
    /** Initializes an ACP connection without creating a new agent-side session. */
    default CompletableFuture<Void> connectForSessionList(Path workingDirectory) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Connecting without creating a session is not supported"));
    }
    /** Opens another session on this already initialized ACP connection. */
    default CompletableFuture<Void> startNewSession(Path workingDirectory) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Opening another session is not supported"));
    }
    /**
     * Replace the active session while retaining the initialized connection.
     * On success, events for the new session are delivered to {@code listener};
     * late events from the retired session must not reach it. Adapters that cannot
     * replace the listener must fail without opening another session.
     */
    default CompletableFuture<Void> startNewSession(Path workingDirectory, AgentListener listener) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Replacing the session listener is not supported"));
    }
    /** Connect to an already persisted agent-side session without creating a new one. */
    default CompletableFuture<Void> restoreSession(String sessionId, Path workingDirectory) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Session restore is not supported"));
    }
    /** The active agent-side session ID, once connected. */
    default String sessionId() { return null; }
    CompletableFuture<Void> prompt(String text);
    /**
     * Sends text together with user-selected image or audio attachments when supported.
     * A null or empty list is equivalent to {@link #prompt(String)}. A nonempty list
     * must be handled in full or rejected; attachments must never be silently dropped.
     */
    default CompletableFuture<Void> prompt(String text, List<PromptAttachment> attachments) {
        if (attachments != null && !attachments.isEmpty()) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("Prompt attachments are not supported"));
        }
        return prompt(text);
    }
    /** Changes an option advertised in a {@code config_option_update}. */
    default CompletableFuture<Void> setConfigOption(String configId, ConfigValue value) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Configuration options are not supported"));
    }
    /**
     * Requests cancellation of the active prompt and pending user interactions.
     * Returning does not acknowledge that the agent has stopped; the prompt future
     * reports its eventual outcome. An inactive client may treat this as a no-op.
     */
    void cancel() throws IOException;
    AgentCapabilities capabilities();
    default List<AuthMethod> authenticationMethods() { return List.of(); }
    default CompletableFuture<Void> authenticate(String methodId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Authentication is not supported"));
    }
    default CompletableFuture<Void> logout() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Logout is not supported"));
    }
    default CompletableFuture<SessionPage> listSessions(Path workingDirectory, String cursor) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Session listing is not supported"));
    }
    default CompletableFuture<Void> loadSession(String sessionId, Path workingDirectory) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Session loading is not supported"));
    }
    default CompletableFuture<Void> resumeSession(String sessionId, Path workingDirectory) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Session resume is not supported"));
    }
    /**
     * Detaches the active session, retaining the initialized connection. An adapter
     * may detach locally when remote close is unavailable, but must not report
     * success while leaving the old session active. An implementation with no active
     * session may complete successfully without work.
     */
    default CompletableFuture<Void> closeSession() {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Session close is not supported"));
    }
    default CompletableFuture<Void> deleteSession(String sessionId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Session deletion is not supported"));
    }
    /** Releases owned resources and pending interactions; repeated calls are harmless. */
    @Override void close();
}
