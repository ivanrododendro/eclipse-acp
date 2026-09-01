package dev.eclipseacp.client.preferences;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jface.preference.IPreferenceStore;
import org.eclipse.ui.preferences.ScopedPreferenceStore;

import dev.eclipseacp.client.PluginIds;

public final class AcpPreferences {
    public static final String AGENT_NAME = "agentName";
    public static final String AGENT_COMMAND = "agentCommand";
    public static final String AGENT_ARGUMENTS = "agentArguments";

    private static final IPreferenceStore STORE =
            new ScopedPreferenceStore(InstanceScope.INSTANCE, PluginIds.PLUGIN_ID);

    private AcpPreferences() {
    }

    public static IPreferenceStore store() {
        return STORE;
    }
}
