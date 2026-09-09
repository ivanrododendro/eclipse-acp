package dev.eclipseacp.client.agent;

/** A client-mediated request to stage a workspace file write. */
public record FileWriteRequest(String sessionId, String path, String content) { }
