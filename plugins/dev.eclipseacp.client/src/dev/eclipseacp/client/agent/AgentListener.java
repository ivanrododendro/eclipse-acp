package dev.eclipseacp.client.agent;

import java.util.List;
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
    default void onAvailableCommands(List<AgentCommand> commands) { }
    default void onConfigOptions(List<ConfigOption> options) { }
    default void onUsage(Usage usage) { }
    default void onTerminalOutput(String output) { }
    default void onAuthenticationMethods(List<AuthMethod> methods) { }
    default CompletableFuture<String> requestAuthentication(List<AuthMethod> methods) {
        return CompletableFuture.completedFuture(null);
    }
    default CompletableFuture<String> requestElicitation(ElicitationRequest request) {
        return CompletableFuture.completedFuture(null);
    }
}
