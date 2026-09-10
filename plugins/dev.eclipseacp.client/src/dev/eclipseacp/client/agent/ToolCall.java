package dev.eclipseacp.client.agent;

import java.util.List;

/** Current merged representation of an agent tool call. */
public record ToolCall(String id, String title, String kind, String status,
        List<FileDiff> diffs, List<ToolLocation> locations, String command, String workingDirectory,
        String path, String terminalOutput) {
    public ToolCall {
        diffs = List.copyOf(diffs == null ? List.of() : diffs);
        locations = List.copyOf(locations == null ? List.of() : locations);
    }
    public boolean hasDiffs() { return !diffs.isEmpty(); }
}
