package dev.eclipseacp.client.agent;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** UI-facing callback port; protocol-specific messages are converted before reaching it. */
public interface AgentListener {
    void onAgentText(String text);
    default void onPromptFirstAgentChunk(long sentAtNanos, long receivedAtNanos) { }
    default void onPromptCompleted(long sentAtNanos, long completedAtNanos) { }
    default void onUserText(String text) { }
    void onStatus(String status);
    void onError(String message, Throwable error);
    CompletableFuture<String> requestPermission(String title, List<PermissionOption> options);
    default CompletableFuture<String> requestPermission(PermissionRequest request) {
        return requestPermission(request.title(), request.options());
    }
    default void onToolCall(ToolCall toolCall) { }
    default CompletableFuture<String> readTextFile(FileReadRequest request) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Client file reading is not supported"));
    }
    default CompletableFuture<Void> stageFileWrite(FileWriteRequest request) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Client file writing is not supported"));
    }
    default void onSessionUpdate(SessionUpdate update) { }
    default void onAuthenticationMethods(List<AuthMethod> methods) { }
    default CompletableFuture<String> requestAuthentication(List<AuthMethod> methods) {
        return CompletableFuture.completedFuture(null);
    }
    default CompletableFuture<Map<String, Object>> requestElicitation(Map<String, Object> request) {
        return CompletableFuture.completedFuture(Map.of());
    }
}
