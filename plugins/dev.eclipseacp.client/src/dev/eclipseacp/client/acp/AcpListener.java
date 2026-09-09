package dev.eclipseacp.client.acp;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;

import dev.eclipseacp.client.agent.AuthMethod;

public interface AcpListener {
    void onAgentText(String text);

    /** Timing marker emitted when the first streamed agent text reaches the ACP client. */
    default void onPromptFirstAgentChunk(long sentAtNanos, long receivedAtNanos) {
        // Optional for clients that do not display prompt timing.
    }

    /** Timing marker emitted when the ACP session/prompt request has completed. */
    default void onPromptCompleted(long sentAtNanos, long completedAtNanos) {
        // Optional for clients that do not display prompt timing.
    }

    /** Text from a user-message update, normally emitted while an existing session is replayed. */
    default void onUserText(String text) {
        // Optional: ordinary prompts are already rendered by the client UI.
    }

    void onStatus(String status);

    void onError(String message, Throwable error);

    CompletableFuture<String> requestPermission(String title, List<PermissionOption> options);

    /** Called with the full tool context when the agent asks the user for permission. */
    default CompletableFuture<String> requestPermission(PermissionRequest request) {
        return requestPermission(request.title(), request.options());
    }

    /** Receives the merged state of a tool call, including any replacement diffs. */
    default void onToolCall(ToolCall toolCall) {
        // Optional for listeners that do not render tool activity.
    }

    /** Reads an agent-requested file through the client instead of exposing a raw filesystem API. */
    default CompletableFuture<String> readTextFile(FileReadRequest request) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Client file reading is not supported"));
    }

    /** Stages an agent-requested write until the user explicitly applies or rejects it. */
    default CompletableFuture<Void> stageFileWrite(FileWriteRequest request) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Client file writing is not supported"));
    }

    /** Receives every ACP update, including variants the current UI does not render yet. */
    default void onSessionUpdate(AcpSessionUpdate update) {
        // Optional for listeners that only render plain agent text.
    }

    /** Announces authentication methods without assuming that login is required yet. */
    default void onAuthenticationMethods(List<AuthMethod> methods) {
        // Optional for clients that provide an authentication UI.
    }

    /** Chooses an authentication method after the agent rejects a session as unauthenticated. */
    default CompletableFuture<String> requestAuthentication(List<AuthMethod> methods) {
        return CompletableFuture.completedFuture(null);
    }

    /** Requests structured input required by an agent workflow. An empty object means cancelled. */
    default CompletableFuture<JsonObject> requestElicitation(JsonObject request) {
        return CompletableFuture.completedFuture(new JsonObject());
    }
}
