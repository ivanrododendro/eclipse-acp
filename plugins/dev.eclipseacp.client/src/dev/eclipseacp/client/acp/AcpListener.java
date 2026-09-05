package dev.eclipseacp.client.acp;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.eclipseacp.client.agent.AuthMethod;

public interface AcpListener {
    void onAgentText(String text);

    void onStatus(String status);

    void onError(String message, Throwable error);

    CompletableFuture<String> requestPermission(String title, List<PermissionOption> options);

    /** Receives every ACP update, including variants the current UI does not render yet. */
    default void onSessionUpdate(AcpSessionUpdate update) {
        // Optional for listeners that only render plain agent text.
    }

    /** Announces authentication methods without assuming that login is required yet. */
    default void onAuthenticationMethods(List<AuthMethod> methods) {
        // Optional for clients that provide an authentication UI.
    }
}
