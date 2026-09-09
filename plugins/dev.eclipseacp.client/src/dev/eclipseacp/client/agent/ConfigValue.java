package dev.eclipseacp.client.agent;

/** A protocol-neutral configuration value, optionally accompanied by its declared type. */
public record ConfigValue(String type, Object value) {
    public static ConfigValue of(Object value) { return new ConfigValue(null, value); }
}
