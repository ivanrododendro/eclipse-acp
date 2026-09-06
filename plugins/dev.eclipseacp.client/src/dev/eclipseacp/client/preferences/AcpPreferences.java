package dev.eclipseacp.client.preferences;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

import dev.eclipseacp.client.PluginIds;

public final class AcpPreferences {
    public static final String AGENT_NAME = "agentName";
    public static final String AGENT_COMMAND = "agentCommand";
    public static final String AGENT_ARGUMENTS = "agentArguments";
    public static final String PROVIDERS_JSON = "providersJson";
    public static final String ACTIVE_PROVIDER = "activeProvider";
    /** When enabled, ACP file writes are staged for Apply/Reject review. Disabled by default. */
    public static final String REVIEW_FILE_CHANGES = "reviewFileChanges";
    /** When enabled, agent tool calls are kept out of the chat transcript. Enabled by default. */
    public static final String HIDE_AGENT_COMMANDS_IN_CHAT = "hideAgentCommandsInChat";
    /** JSON array of MCP server declarations; secrets should use environment-variable expansion, not literals. */
    public static final String MCP_SERVERS_JSON = "mcpServersJson";

    private static final IPreferenceStore STORE =
            new ScopedPreferenceStore(InstanceScope.INSTANCE, PluginIds.PLUGIN_ID);

    private AcpPreferences() {
    }

    public static IPreferenceStore store() {
        return STORE;
    }
}
