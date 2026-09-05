package dev.eclipseacp.client.agent;

/** Optional ACP v1 features advertised by an agent after negotiation. */
public record AgentCapabilities(
        boolean loadSession,
        boolean sessionList,
        boolean sessionResume,
        boolean sessionClose,
        boolean sessionDelete,
        boolean additionalDirectories,
        boolean promptImage,
        boolean promptAudio,
        boolean promptEmbeddedContext,
        boolean mcpHttp,
        boolean mcpSse,
        boolean logout) {
    public static final AgentCapabilities NONE = new AgentCapabilities(
            false, false, false, false, false, false, false, false, false, false, false, false);
}
