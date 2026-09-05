package dev.eclipseacp.client.ui;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.osgi.service.prefs.BackingStoreException;

import dev.eclipseacp.client.PluginIds;
import dev.eclipseacp.client.preferences.AcpPreferences;

/** Small local index. The authoritative session remains owned by the ACP provider. */
final class SessionHistoryStore {
    record Entry(String providerId, String projectName, String projectPath, String sessionId,
            String label, String transcript) { }

    private static final Gson GSON = new Gson();

    List<Entry> load() {
        String json = AcpPreferences.store().getString(AcpPreferences.SESSION_HISTORY_JSON);
        if (json == null || json.isBlank()) return List.of();
        try {
            Entry[] entries = GSON.fromJson(json, Entry[].class);
            if (entries == null) return List.of();
            List<Entry> valid = new ArrayList<>();
            for (Entry entry : entries) {
                if (entry != null && present(entry.providerId()) && present(entry.projectName())
                        && present(entry.projectPath()) && present(entry.sessionId())) valid.add(entry);
            }
            return List.copyOf(valid);
        } catch (RuntimeException exception) {
            return List.of(); // A stale/corrupt local index must never prevent the view opening.
        }
    }

    void save(List<Entry> entries) {
        AcpPreferences.store().setValue(AcpPreferences.SESSION_HISTORY_JSON, GSON.toJson(entries));
        try {
            InstanceScope.INSTANCE.getNode(PluginIds.PLUGIN_ID).flush();
        } catch (BackingStoreException exception) {
            throw new IllegalStateException("Cannot persist ACP session history", exception);
        }
    }

    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
