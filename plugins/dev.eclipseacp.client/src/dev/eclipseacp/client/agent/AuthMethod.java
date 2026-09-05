package dev.eclipseacp.client.agent;

import java.util.List;
import java.util.Map;

/** An ACP v1 authentication method advertised by an agent. */
public record AuthMethod(String id, String name, String description, String type, List<String> arguments,
        Map<String, String> environment) {
    public boolean isTerminal() {
        return "terminal".equals(type);
    }
}
