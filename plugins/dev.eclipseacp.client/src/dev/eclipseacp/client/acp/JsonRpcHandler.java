package dev.eclipseacp.client.acp;

import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

interface JsonRpcHandler {
    void onNotification(String method, JsonObject params);

    CompletableFuture<JsonElement> onRequest(String method, JsonObject params);
}
