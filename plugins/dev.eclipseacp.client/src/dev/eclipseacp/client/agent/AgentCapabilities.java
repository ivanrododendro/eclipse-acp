package dev.eclipseacp.client.agent;

/** Optional features advertised by an agent after negotiation. */
public record AgentCapabilities(boolean permissions, boolean modes, boolean fileSystem, boolean terminal) {
    public static final AgentCapabilities NONE = new AgentCapabilities(false, false, false, false);
}
