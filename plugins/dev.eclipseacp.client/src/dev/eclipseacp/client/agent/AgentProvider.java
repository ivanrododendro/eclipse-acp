package dev.eclipseacp.client.agent;

import java.util.Objects;

public record AgentProvider(String id, String name, String command, String arguments) {
    public AgentProvider {
        id = Objects.requireNonNull(id).trim();
        name = Objects.requireNonNull(name).trim();
        command = Objects.requireNonNull(command).trim();
        arguments = arguments == null ? "" : arguments;
        if (id.isEmpty() || name.isEmpty() || command.isEmpty()) throw new IllegalArgumentException("Provider id, name and command are required");
    }
}
