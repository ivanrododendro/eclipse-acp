package dev.eclipseacp.client.agent;

/** A workspace location referenced by an agent tool. Lines are one-based when present. */
public record ToolLocation(String path, Integer line) { }
