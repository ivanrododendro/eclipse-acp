package dev.eclipseacp.client.preferences;

import static org.junit.Assert.assertEquals;

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
}
