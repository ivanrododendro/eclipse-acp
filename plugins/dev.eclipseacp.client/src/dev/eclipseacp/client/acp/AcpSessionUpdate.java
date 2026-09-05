package dev.eclipseacp.client.acp;

import com.google.gson.JsonObject;

/** A lossless, typed envelope for an ACP {@code session/update} notification. */
public record AcpSessionUpdate(String sessionId, String kind, JsonObject payload) {
}
