package dev.eclipseacp.client.mcp;

import java.util.List;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.eclipse.jface.preference.IPreferenceStore;
import dev.eclipseacp.client.preferences.AcpPreferences;

/** Global declarations can be restricted to one ACP provider and/or Eclipse project. */
public final class McpServerRegistry {
    private static final Gson GSON = new Gson(); private final IPreferenceStore store;
    public McpServerRegistry(IPreferenceStore store) { this.store = store; }
    public List<McpServerConfig> forSession(String providerId, String projectName) { return load().stream().filter(server -> server.appliesTo(providerId, projectName)).toList(); }
    public List<McpServerConfig> load() { try { List<McpServerConfig> servers = GSON.fromJson(store.getString(AcpPreferences.MCP_SERVERS_JSON), new TypeToken<List<McpServerConfig>>(){}.getType()); return servers == null ? List.of() : List.copyOf(servers); } catch (RuntimeException error) { return List.of(); } }
    public void save(List<McpServerConfig> servers) { store.setValue(AcpPreferences.MCP_SERVERS_JSON, GSON.toJson(servers)); }
}
