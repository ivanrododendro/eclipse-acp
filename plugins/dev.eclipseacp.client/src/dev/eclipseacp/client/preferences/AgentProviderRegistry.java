package dev.eclipseacp.client.preferences;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jface.preference.IPreferenceStore;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import dev.eclipseacp.client.agent.AgentProvider;

/**
 * Editable provider registry. Changes stay local until {@link #commitTo(IPreferenceStore)} is
 * called by the owner of the preference transaction.
 */
public final class AgentProviderRegistry {
    private static final Gson GSON = new Gson();
    private List<AgentProvider> providers;
    private String activeId;
    private boolean dirty;
    private String invalidJson;

    public AgentProviderRegistry(IPreferenceStore store) { load(store); }
    public List<AgentProvider> list() { return List.copyOf(providers); }
    public AgentProvider active() { return providers.stream().filter(p -> p.id().equals(activeId)).findFirst().orElse(providers.get(0)); }
    public boolean hasInvalidStoredConfiguration() { return invalidJson != null; }
    public boolean isDirty() { return dirty; }

    public void select(String id) { require(id); activeId = id; dirty = true; }
    public void add(AgentProvider provider) {
        if (providers.stream().anyMatch(p -> p.id().equals(provider.id()))) throw new IllegalArgumentException("A provider with this name already exists.");
        providers.add(provider); dirty = true;
    }
    public void update(AgentProvider provider) {
        for (int i = 0; i < providers.size(); i++) if (providers.get(i).id().equals(provider.id())) {
            providers.set(i, provider); dirty = true; return;
        }
        throw new IllegalArgumentException("Unknown provider: " + provider.id());
    }
    public void remove(String id) {
        if (providers.size() == 1) throw new IllegalStateException("At least one provider is required.");
        require(id); providers.removeIf(p -> p.id().equals(id));
        if (id.equals(activeId)) activeId = providers.get(0).id();
        dirty = true;
    }

    /** Restores the provider part of the preference page to its shipped defaults. */
    public void resetToDefaults() {
        providers = new ArrayList<>(List.of(defaultProvider()));
        activeId = providers.getFirst().id();
        dirty = true;
    }

    /** Writes this draft to a preference store but deliberately does not flush it. */
    public void commitTo(IPreferenceStore store) {
        if (!dirty) return;
        if (invalidJson != null) store.setValue(AcpPreferences.PROVIDERS_JSON_RECOVERY, invalidJson);
        store.setValue(AcpPreferences.PROVIDERS_JSON, GSON.toJson(providers));
        store.setValue(AcpPreferences.ACTIVE_PROVIDER, activeId);
    }

    /** Marks the current draft clean after its owning preference store was flushed successfully. */
    public void committed() {
        dirty = false;
        invalidJson = null;
    }

    private void load(IPreferenceStore store) {
        String json = store.getString(AcpPreferences.PROVIDERS_JSON);
        try {
            providers = json.isBlank() ? new ArrayList<>() : GSON.fromJson(json, new TypeToken<List<AgentProvider>>() {}.getType());
        } catch (RuntimeException error) {
            invalidJson = json;
            providers = new ArrayList<>();
        }
        if (providers == null || providers.isEmpty()) {
            providers = new ArrayList<>();
            providers.add(new AgentProvider("vibe", value(store, AcpPreferences.AGENT_NAME, "Mistral Vibe"), value(store, AcpPreferences.AGENT_COMMAND, "vibe-acp"), value(store, AcpPreferences.AGENT_ARGUMENTS, "")));
            activeId = "vibe";
        } else {
            activeId = store.getString(AcpPreferences.ACTIVE_PROVIDER);
            if (activeId.isBlank() || providers.stream().noneMatch(p -> p.id().equals(activeId))) activeId = providers.get(0).id();
        }
    }
    private static AgentProvider defaultProvider() { return new AgentProvider("vibe", "Mistral Vibe", "vibe-acp", ""); }
    private String value(IPreferenceStore store, String key, String fallback) { String value = store.getString(key); return value.isBlank() ? fallback : value; }
    private void require(String id) { if (providers.stream().noneMatch(p -> p.id().equals(id))) throw new IllegalArgumentException("Unknown provider: " + id); }
}
