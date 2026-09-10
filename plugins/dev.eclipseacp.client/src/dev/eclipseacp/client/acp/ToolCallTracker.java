package dev.eclipseacp.client.acp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.eclipseacp.client.agent.FileDiff;
import dev.eclipseacp.client.agent.ToolCall;
import dev.eclipseacp.client.agent.ToolLocation;

/** Merges ACP {@code tool_call} and {@code tool_call_update} payloads by toolCallId. */
final class ToolCallTracker {
    private final Map<String, ToolCall> calls = new LinkedHashMap<>();

    synchronized ToolCall accept(JsonObject update) {
        String id = string(update, "toolCallId");
        if (id.isBlank()) return null;
        ToolCall previous = calls.get(id);
        String title = updatedString(update, "title", previous == null ? "Tool call" : previous.title());
        String kind = updatedString(update, "kind", previous == null ? "other" : previous.kind());
        String status = updatedString(update, "status", previous == null ? "pending" : previous.status());
        List<FileDiff> diffs = update.has("content") && !update.get("content").isJsonNull()
                ? diffs(update.get("content")) : previous == null ? List.of() : previous.diffs();
        List<ToolLocation> locations = update.has("locations") && !update.get("locations").isJsonNull()
                ? locations(update.get("locations")) : previous == null ? List.of() : previous.locations();
        JsonObject rawInput = update.has("rawInput") ? object(update.get("rawInput")) : null;
        JsonObject rawOutput = update.has("rawOutput") ? object(update.get("rawOutput")) : null;
        ToolCall merged = new ToolCall(id, title, kind, status, diffs, locations,
                updatedRawString(rawInput, "command", previous == null ? "" : previous.command()),
                updatedRawString(rawInput, "cwd", previous == null ? "" : previous.workingDirectory()),
                updatedRawString(rawInput, "path", previous == null ? "" : previous.path()),
                updatedRawString(rawOutput, "output", previous == null ? "" : previous.terminalOutput()));
        calls.put(id, merged);
        return merged;
    }

    synchronized void clear() {
        calls.clear();
    }

    private static List<FileDiff> diffs(JsonElement content) {
        if (content == null || !content.isJsonArray()) return List.of();
        List<FileDiff> result = new ArrayList<>();
        for (JsonElement entry : content.getAsJsonArray()) {
            if (!entry.isJsonObject()) continue;
            JsonObject diff = entry.getAsJsonObject();
            if (!"diff".equals(string(diff, "type")) || !diff.has("newText") || !diff.get("newText").isJsonPrimitive()) continue;
            String path = string(diff, "path");
            if (!path.isBlank()) result.add(new FileDiff(path,
                    diff.has("oldText") && !diff.get("oldText").isJsonNull() ? string(diff, "oldText") : null,
                    string(diff, "newText")));
        }
        return List.copyOf(result);
    }

    private static List<ToolLocation> locations(JsonElement locations) {
        if (locations == null || !locations.isJsonArray()) return List.of();
        List<ToolLocation> result = new ArrayList<>();
        for (JsonElement entry : locations.getAsJsonArray()) {
            if (!entry.isJsonObject()) continue;
            JsonObject location = entry.getAsJsonObject();
            String path = string(location, "path");
            if (path.isBlank()) continue;
            Integer line = location.has("line") && location.get("line").isJsonPrimitive()
                    ? location.get("line").getAsInt() : null;
            result.add(new ToolLocation(path, line));
        }
        return List.copyOf(result);
    }

    private static String updatedString(JsonObject object, String name, String fallback) {
        return object.has(name) && !object.get(name).isJsonNull() ? string(object, name) : fallback;
    }

    private static String string(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() ? object.get(name).getAsString() : "";
    }

    private static JsonObject object(JsonElement value) {
        return value != null && value.isJsonObject() ? value.getAsJsonObject() : new JsonObject();
    }

    private static String updatedRawString(JsonObject object, String name, String fallback) {
        return object != null && object.has(name) ? string(object, name) : fallback;
    }
}
