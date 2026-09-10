package dev.eclipseacp.client.ui;

import static org.junit.Assert.assertEquals;

import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

import dev.eclipseacp.client.preferences.AcpPreferences;

public class AcpSessionServiceTest {
    @Test
    public void readsTheActiveProviderAgainForEachNewSession() {
        PreferenceStore preferences = new PreferenceStore();
        preferences.setValue(AcpPreferences.PROVIDERS_JSON,
                "[{\"id\":\"codex\",\"name\":\"Codex\",\"command\":\"codex-acp\",\"arguments\":\"\"},"
                + "{\"id\":\"other\",\"name\":\"Other\",\"command\":\"other-acp\",\"arguments\":\"\"}]");
        preferences.setValue(AcpPreferences.ACTIVE_PROVIDER, "codex");
        AcpSessionService service = new AcpSessionService(preferences);

        assertEquals("codex", service.newSessionConfiguration().provider().id());

        preferences.setValue(AcpPreferences.ACTIVE_PROVIDER, "other");

        assertEquals("other", service.newSessionConfiguration().provider().id());
    }
}
