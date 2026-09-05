package dev.eclipseacp.client.acp;

/** A workspace location referenced by an ACP tool call. Lines are one-based when present. */
public record ToolLocation(String path, Integer line) {
}
