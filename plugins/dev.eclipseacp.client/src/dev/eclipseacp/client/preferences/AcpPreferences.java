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
    /** When enabled, logs every ACP JSON-RPC payload at DEBUG level. Disabled by default. */
    public static final String DEBUG_ACP_MESSAGES = "debugAcpMessages";
    /** Number of recent chats shown directly in the chat picker. */
    public static final String VISIBLE_RECENT_SESSIONS = "visibleRecentSessions";
    public static final int DEFAULT_VISIBLE_RECENT_SESSIONS = 3;
    public static final int MIN_VISIBLE_RECENT_SESSIONS = 1;
    public static final int MAX_VISIBLE_RECENT_SESSIONS = 20;
    /** JSON array of MCP server declarations; secrets should use environment-variable expansion, not literals. */
    public static final String MCP_SERVERS_JSON = "mcpServersJson";

    private static final IPreferenceStore STORE =
            new ScopedPreferenceStore(InstanceScope.INSTANCE, PluginIds.PLUGIN_ID);

    private AcpPreferences() {
    }

    public static IPreferenceStore store() {
        return STORE;
    }

    /** Returns a safe UI value even if the stored preference was edited manually. */
    public static int visibleRecentSessions(IPreferenceStore preferences) {
        int value = preferences.getInt(VISIBLE_RECENT_SESSIONS);
        return Math.clamp(value, MIN_VISIBLE_RECENT_SESSIONS, MAX_VISIBLE_RECENT_SESSIONS);
    }
}
