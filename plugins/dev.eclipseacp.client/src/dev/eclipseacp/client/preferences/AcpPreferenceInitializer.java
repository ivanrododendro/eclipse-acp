package dev.eclipseacp.client.preferences;

import org.eclipse.core.runtime.preferences.AbstractPreferenceInitializer;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.osgi.service.prefs.Preferences;

import dev.eclipseacp.client.PluginIds;

public final class AcpPreferenceInitializer extends AbstractPreferenceInitializer {
    @Override
    public void initializeDefaultPreferences() {
        Preferences preferences = DefaultScope.INSTANCE.getNode(PluginIds.PLUGIN_ID);
        preferences.put(AcpPreferences.AGENT_NAME, "Mistral Vibe");
        preferences.put(AcpPreferences.AGENT_COMMAND, "vibe-acp");
        preferences.put(AcpPreferences.AGENT_ARGUMENTS, "");
        preferences.putBoolean(AcpPreferences.REVIEW_FILE_CHANGES, false);
    }
}
