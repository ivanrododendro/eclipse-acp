package dev.eclipseacp.client.acp;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface AcpListener {
    void onAgentText(String text);

    void onStatus(String status);

    void onError(String message, Throwable error);

    CompletableFuture<String> requestPermission(String title, List<PermissionOption> options);
}
