package dev.eclipseacp.client.agent;

import dev.eclipseacp.client.acp.AcpClient;
import dev.eclipseacp.client.acp.AcpListener;
import java.util.List;
import dev.eclipseacp.client.mcp.McpServerConfig;
import dev.eclipseacp.client.preferences.AcpPreferences;

public final class AgentClientFactory {
    private AgentClientFactory() {}
    public static AgentClient create(AgentProvider provider, AcpListener listener, boolean reviewFileChanges) {
        return decorate(new AcpClient(provider.command(), provider.arguments(), listener, reviewFileChanges));
    }
    public static AgentClient create(AgentProvider provider, AcpListener listener, boolean reviewFileChanges, List<McpServerConfig> servers) {
        return decorate(new AcpClient(provider.command(), provider.arguments(), listener, reviewFileChanges, servers));
    }
    public static AgentClient create(AgentProvider provider, AcpListener listener, boolean reviewFileChanges,
            List<McpServerConfig> servers, boolean enableJdtCliIntegration) {
        AgentClient client = new AcpClient(provider.command(), provider.arguments(), listener, reviewFileChanges, servers);
        return enableJdtCliIntegration ? new JdtCliAwareAgentClient(client) : client;
    }
    private static AgentClient decorate(AgentClient client) {
        return AcpPreferences.store().getBoolean(AcpPreferences.ENABLE_JDT_CLI_INTEGRATION)
                ? new JdtCliAwareAgentClient(client) : client;
    }
}
