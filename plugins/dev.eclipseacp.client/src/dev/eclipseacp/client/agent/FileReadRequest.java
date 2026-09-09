package dev.eclipseacp.client.agent;

/** A client-mediated request to read a workspace file. */
public record FileReadRequest(String sessionId, String path, Integer line, Integer limit) { }
