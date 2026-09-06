package dev.eclipseacp.client.agent;

import com.google.gson.JsonElement;

/** Agent-advertised, session-scoped configuration option. */
public record ConfigOption(String id, String name, String description, JsonElement value) {
}
