package dev.eclipseacp.client.acp;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonObject;

/** Transport boundary used by the ACP adapter. */
interface JsonRpcTransport extends Closeable {
    void start();
    CompletableFuture<JsonObject> request(String method, JsonObject params);
    void notification(String method, JsonObject params) throws IOException;
}
