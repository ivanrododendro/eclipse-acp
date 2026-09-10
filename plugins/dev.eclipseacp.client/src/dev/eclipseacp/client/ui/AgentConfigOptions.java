package dev.eclipseacp.client.ui;

import java.util.Arrays;

import dev.eclipseacp.client.agent.ConfigOption;
import dev.eclipseacp.client.agent.ConfigValue;

/** Lookup and value conversion for well-known ACP configuration options. */
final class AgentConfigOptions {
    private AgentConfigOptions() { }
    static ConfigOption model(ChatSessionModel session) { return find(session, "model", "model"); }
    static ConfigOption collaborationMode(ChatSessionModel session) { return find(session, "collaboration_mode", "collaboration mode"); }
    static ConfigOption thoughtLevel(ChatSessionModel session) { return find(session, "thought_level", "thought level", "reasoning level"); }
    static String text(ConfigValue value) { return value == null || value.value() == null ? "" : String.valueOf(value.value()); }

    private static ConfigOption find(ChatSessionModel session, String key, String... displayNames) {
        if (session == null) return null;
        return session.configOptions.values().stream().filter(option -> !option.choices().isEmpty())
                .filter(option -> key.equalsIgnoreCase(option.category()) || key.equalsIgnoreCase(option.id())
                        || Arrays.stream(displayNames).anyMatch(name -> name.equalsIgnoreCase(option.name())))
                .findFirst().orElse(null);
    }
}
