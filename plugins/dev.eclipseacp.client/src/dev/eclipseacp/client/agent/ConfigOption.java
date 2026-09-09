package dev.eclipseacp.client.agent;

import java.util.List;

/** Agent-advertised, session-scoped configuration option. */
public record ConfigOption(String id, String name, String description, String category, Object value,
        List<Choice> choices) {
    public ConfigOption {
        choices = choices == null ? List.of() : List.copyOf(choices);
    }

    /** One selectable value advertised by an ACP {@code config_option_update}. */
    public record Choice(String value, String label, String description) {
    }
}
