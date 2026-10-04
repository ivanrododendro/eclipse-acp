package dev.eclipseacp.client.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

public class AcpPreferencesTest {
    @Test
    public void boundsVisibleRecentSessionsToSupportedRange() {
        PreferenceStore preferences = new PreferenceStore();

        preferences.setValue(AcpPreferences.VISIBLE_RECENT_SESSIONS, -1);
        assertEquals(AcpPreferences.MIN_VISIBLE_RECENT_SESSIONS, AcpPreferences.visibleRecentSessions(preferences));

        preferences.setValue(AcpPreferences.VISIBLE_RECENT_SESSIONS, 99);
        assertEquals(AcpPreferences.MAX_VISIBLE_RECENT_SESSIONS, AcpPreferences.visibleRecentSessions(preferences));

        preferences.setValue(AcpPreferences.VISIBLE_RECENT_SESSIONS, 7);
        assertEquals(7, AcpPreferences.visibleRecentSessions(preferences));
    }

    @Test
    public void providerChangesStayInTheDraftUntilCommitted() {
        PreferenceStore preferences = providers("codex", "other");
        String originalJson = preferences.getString(AcpPreferences.PROVIDERS_JSON);
        AgentProviderRegistry registry = new AgentProviderRegistry(preferences);

        registry.select("other");

        assertTrue(registry.isDirty());
        assertEquals(originalJson, preferences.getString(AcpPreferences.PROVIDERS_JSON));
        assertEquals("codex", preferences.getString(AcpPreferences.ACTIVE_PROVIDER));

        registry.commitTo(preferences);

        assertEquals("other", preferences.getString(AcpPreferences.ACTIVE_PROVIDER));
        assertTrue(registry.isDirty());
        registry.committed();
        assertFalse(registry.isDirty());
    }

    @Test
    public void invalidProviderJsonIsPreservedUntilTheUserReplacesIt() {
        PreferenceStore preferences = new PreferenceStore();
        String invalidJson = "{not valid json";
        preferences.setValue(AcpPreferences.PROVIDERS_JSON, invalidJson);
        AgentProviderRegistry registry = new AgentProviderRegistry(preferences);

        assertTrue(registry.hasInvalidStoredConfiguration());
        assertEquals(invalidJson, preferences.getString(AcpPreferences.PROVIDERS_JSON));

        registry.add(new dev.eclipseacp.client.agent.AgentProvider("codex", "Codex", "codex-acp", ""));
        registry.commitTo(preferences);

        assertEquals(invalidJson, preferences.getString(AcpPreferences.PROVIDERS_JSON_RECOVERY));
        assertTrue(preferences.getString(AcpPreferences.PROVIDERS_JSON).contains("codex-acp"));
    }

    @Test
    public void resettingProvidersRestoresTheShippedProvider() {
        AgentProviderRegistry registry = new AgentProviderRegistry(providers("codex", "other"));

        registry.resetToDefaults();

        assertEquals(1, registry.list().size());
        assertEquals("vibe", registry.active().id());
        assertEquals("vibe-acp", registry.active().command());
    }

    private static PreferenceStore providers(String active, String other) {
        PreferenceStore preferences = new PreferenceStore();
        preferences.setValue(AcpPreferences.PROVIDERS_JSON,
                "[{\"id\":\"" + active + "\",\"name\":\"" + active + "\",\"command\":\"" + active + "-acp\",\"arguments\":\"\"},"
                + "{\"id\":\"" + other + "\",\"name\":\"" + other + "\",\"command\":\"" + other + "-acp\",\"arguments\":\"\"}]");
        preferences.setValue(AcpPreferences.ACTIVE_PROVIDER, active);
        return preferences;
    }
}
