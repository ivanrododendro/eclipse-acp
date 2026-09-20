package dev.eclipseacp.client.ui;

import java.util.ArrayList;
import java.util.List;

import dev.eclipseacp.client.agent.FileDiff;
import dev.eclipseacp.client.agent.Usage;
import dev.eclipseacp.client.agent.ToolCall;

/** Pure Markdown formatting for protocol events displayed in the transcript. */
final class ChatMessageFormatter {
    private static final int MAX_DIFF_LINES = 40;
    private ChatMessageFormatter() { }

    static String toolCallStatus(ToolCall toolCall) {
        String title = toolCall.title() == null || toolCall.title().isBlank() ? toolCall.kind() : toolCall.title();
        String status = toolCall.status() == null || toolCall.status().isBlank() ? "updated" : toolCall.status();
        return "Agent command: " + title + " — " + status;
    }

    static String usage(Usage usage) {
        List<String> entries = new ArrayList<>();
        if (usage.inputTokens() != null) entries.add("inputTokens=" + usage.inputTokens());
        if (usage.outputTokens() != null) entries.add("outputTokens=" + usage.outputTokens());
        if (usage.totalTokens() != null) entries.add("totalTokens=" + usage.totalTokens());
        if (usage.cost() != null && !usage.cost().isBlank()) entries.add("cost=" + usage.cost());
        return entries.isEmpty() ? "updated" : String.join(", ", entries);
    }

    static String terminalOutput(String output) {
        return output == null || output.isBlank() ? "" : "> **Terminal output**\n\n```text\n" + output + "\n```\n\n";
    }

    static String diffPreview(List<FileDiff> diffs) {
        if (diffs.isEmpty()) return "";
        StringBuilder preview = new StringBuilder();
        for (FileDiff diff : diffs) {
            preview.append("\n\n```diff\n--- ").append(diff.path()).append("\n+++ ").append(diff.path()).append('\n');
            appendDiffLines(preview, '-', diff.oldText());
            appendDiffLines(preview, '+', diff.newText());
            preview.append("```");
        }
        return preview.toString();
    }

    private static void appendDiffLines(StringBuilder preview, char prefix, String text) {
        if (text == null) return;
        String[] lines = text.split("\\R", -1);
        for (int index = 0; index < Math.min(lines.length, MAX_DIFF_LINES); index++) {
            preview.append(prefix).append(lines[index]).append('\n');
        }
        if (lines.length > MAX_DIFF_LINES) {
            preview.append(prefix).append("… ").append(lines.length - MAX_DIFF_LINES).append(" more lines\n");
        }
    }
}
