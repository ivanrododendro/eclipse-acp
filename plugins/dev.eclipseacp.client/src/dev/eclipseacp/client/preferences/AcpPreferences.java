package dev.eclipseacp.client.preferences;

import java.io.IOException;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

import dev.eclipseacp.client.PluginIds;

public final class AcpPreferences {
    public static final String AGENT_NAME = "agentName";
    public static final String AGENT_COMMAND = "agentCommand";
    public static final String AGENT_ARGUMENTS = "agentArguments";
    public static final String PROVIDERS_JSON = "providersJson";
    /** Original invalid provider JSON retained before a repaired configuration replaces it. */
    public static final String PROVIDERS_JSON_RECOVERY = "providersJsonRecovery";
    public static final String ACTIVE_PROVIDER = "activeProvider";
    /** When enabled, agent tool calls are kept out of the chat transcript. Enabled by default. */
    public static final String HIDE_AGENT_COMMANDS_IN_CHAT = "hideAgentCommandsInChat";
    /** Enables bounded protocol tracing. Structured secret fields are redacted. Disabled by default. */
    public static final String DEBUG_ACP_MESSAGES = "debugAcpMessages";
    /** Number of recent chats shown directly in the chat picker. */
    public static final String VISIBLE_RECENT_SESSIONS = "visibleRecentSessions";
    public static final int DEFAULT_VISIBLE_RECENT_SESSIONS = 3;
    public static final boolean DEFAULT_HIDE_AGENT_COMMANDS_IN_CHAT = true;
    public static final boolean DEFAULT_DEBUG_ACP_MESSAGES = false;
    public static final int MIN_VISIBLE_RECENT_SESSIONS = 1;
    public static final int MAX_VISIBLE_RECENT_SESSIONS = 20;
    /** JSON array of MCP server declarations; secrets should use environment-variable expansion, not literals. */
    public static final String MCP_SERVERS_JSON = "mcpServersJson";

    private static final ScopedPreferenceStore STORE =
            new ScopedPreferenceStore(InstanceScope.INSTANCE, PluginIds.PLUGIN_ID);

    private AcpPreferences() {
    }

    public static IPreferenceStore store() {
        return STORE;
    }

    /** Flushes instance-scope preferences so they survive an Eclipse restart. */
    public static void save() throws IOException {
        STORE.save();
    }

    /** Returns a safe UI value even if the stored preference was edited manually. */
    public static int visibleRecentSessions(IPreferenceStore preferences) {
        int value = preferences.getInt(VISIBLE_RECENT_SESSIONS);
        return Math.clamp(value, MIN_VISIBLE_RECENT_SESSIONS, MAX_VISIBLE_RECENT_SESSIONS);
    }
}
