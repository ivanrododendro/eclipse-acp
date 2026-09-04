package dev.eclipseacp.client.preferences;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.eclipseacp.client.agent.AgentProvider;

/** Persistent provider registry with a one-time migration from the original Vibe fields. */
public final class AgentProviderRegistry {
    private static final Gson GSON = new Gson();
    private final IPreferenceStore store;
    private List<AgentProvider> providers;
    private String activeId;

    public AgentProviderRegistry(IPreferenceStore store) { this.store = store; load(); }
    public List<AgentProvider> list() { return List.copyOf(providers); }
    public AgentProvider active() { return providers.stream().filter(p -> p.id().equals(activeId)).findFirst().orElse(providers.get(0)); }
    public void select(String id) { require(id); activeId = id; save(); }
    public void add(AgentProvider provider) { if (providers.stream().anyMatch(p -> p.id().equals(provider.id()))) throw new IllegalArgumentException("Duplicate provider id"); providers.add(provider); save(); }
    public void update(AgentProvider provider) { for (int i = 0; i < providers.size(); i++) if (providers.get(i).id().equals(provider.id())) { providers.set(i, provider); save(); return; } throw new IllegalArgumentException("Unknown provider: " + provider.id()); }
    public void remove(String id) { if (providers.size() == 1) throw new IllegalStateException("At least one provider is required"); require(id); providers.removeIf(p -> p.id().equals(id)); if (id.equals(activeId)) activeId = providers.get(0).id(); save(); }

    private void load() {
        String json = store.getString(AcpPreferences.PROVIDERS_JSON);
        try {
            providers = json.isBlank() ? new ArrayList<>() : GSON.fromJson(json, new TypeToken<List<AgentProvider>>() {}.getType());
        } catch (RuntimeException ignored) {
            providers = new ArrayList<>();
        }
        if (providers == null || providers.isEmpty()) {
            providers = new ArrayList<>();
            providers.add(new AgentProvider("vibe", value(AcpPreferences.AGENT_NAME, "Mistral Vibe"), value(AcpPreferences.AGENT_COMMAND, "vibe-acp"), value(AcpPreferences.AGENT_ARGUMENTS, "")));
            activeId = "vibe"; save();
        } else {
            activeId = store.getString(AcpPreferences.ACTIVE_PROVIDER);
            if (activeId.isBlank() || providers.stream().noneMatch(p -> p.id().equals(activeId))) activeId = providers.get(0).id();
        }
    }
    private String value(String key, String fallback) { String value = store.getString(key); return value.isBlank() ? fallback : value; }
    private void require(String id) { if (providers.stream().noneMatch(p -> p.id().equals(id))) throw new IllegalArgumentException("Unknown provider: " + id); }
    private void save() { store.setValue(AcpPreferences.PROVIDERS_JSON, GSON.toJson(providers)); store.setValue(AcpPreferences.ACTIVE_PROVIDER, activeId); }
}
