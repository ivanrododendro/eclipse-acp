package dev.eclipseacp.client.acp;

import java.util.List;

import dev.eclipseacp.client.agent.AgentClient;
import dev.eclipseacp.client.agent.AgentListener;
import dev.eclipseacp.client.agent.AgentProvider;
import dev.eclipseacp.client.mcp.McpServerConfig;

/** Creates the ACP v1 implementation of the protocol-neutral {@link AgentClient} port. */
public final class AcpClientFactory {
    private AcpClientFactory() {
    }

    public static AgentClient create(AgentProvider provider, AgentListener listener, boolean reviewFileChanges,
            List<McpServerConfig> servers) {
        return new AcpClient(provider.command(), provider.arguments(), listener, reviewFileChanges, servers);
    }
}
