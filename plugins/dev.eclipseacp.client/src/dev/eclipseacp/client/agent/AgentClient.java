package dev.eclipseacp.client.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;

/** UI-facing agent contract; protocol versions must not leak beyond this package. */
public interface AgentClient extends AutoCloseable {
    CompletableFuture<Void> connect(Path workingDirectory);
    /** Connect to an already persisted agent-side session without creating a new one. */
    default CompletableFuture<Void> restoreSession(String sessionId, Path workingDirectory) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Session restore is not supported"));
    }
    /** The active agent-side session ID, once connected. */
    default String sessionId() { return null; }
    CompletableFuture<Void> prompt(String text);
    /** Sends text together with user-selected image or audio attachments when supported. */
    default CompletableFuture<Void> prompt(String text, List<PromptAttachment> attachments) {
        return prompt(text);
    }
    /** Changes an option advertised in a {@code config_option_update}. */
    default CompletableFuture<Void> setConfigOption(String configId, JsonElement value) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Configuration options are not supported"));
    }
    /** Changes an option while preserving the ACP value discriminator when one is required. */
    default CompletableFuture<Void> setConfigOption(String configId, String valueType, JsonElement value) {
        return setConfigOption(configId, value);
    }
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
    default CompletableFuture<Void> closeSession() {
        return CompletableFuture.completedFuture(null);
    }
    default CompletableFuture<Void> deleteSession(String sessionId) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Session deletion is not supported"));
    }
    @Override void close();
}
