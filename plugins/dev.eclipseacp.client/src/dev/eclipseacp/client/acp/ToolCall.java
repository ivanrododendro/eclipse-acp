package dev.eclipseacp.client.acp;

import java.util.List;

import com.google.gson.JsonElement;

/** Current, merged representation of an ACP tool call. */
public record ToolCall(String id, String title, String kind, String status,
        List<FileDiff> diffs, List<ToolLocation> locations, JsonElement rawInput, JsonElement rawOutput) {
    public ToolCall {
        diffs = List.copyOf(diffs == null ? List.of() : diffs);
        locations = List.copyOf(locations == null ? List.of() : locations);
    }

    public boolean hasDiffs() { return !diffs.isEmpty(); }
}
