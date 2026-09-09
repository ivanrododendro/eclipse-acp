package dev.eclipseacp.client.agent;

import java.util.List;

/** Details to present before returning an agent permission choice. */
public record PermissionRequest(String title, ToolCall toolCall, List<PermissionOption> options) {
    public PermissionRequest { options = List.copyOf(options == null ? List.of() : options); }
}
