package dev.eclipseacp.client.agent;

/** A slash command advertised by the active agent session. */
public record AgentCommand(String name, String description) {
}
