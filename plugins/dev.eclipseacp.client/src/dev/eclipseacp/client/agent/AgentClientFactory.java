package dev.eclipseacp.client.agent;

import dev.eclipseacp.client.acp.AcpClient;
import dev.eclipseacp.client.acp.AcpListener;

public final class AgentClientFactory {
    private AgentClientFactory() {}
    public static AgentClient create(AgentProvider provider, AcpListener listener, boolean reviewFileChanges) {
        return new AcpClient(provider.command(), provider.arguments(), listener, reviewFileChanges);
    }
}
