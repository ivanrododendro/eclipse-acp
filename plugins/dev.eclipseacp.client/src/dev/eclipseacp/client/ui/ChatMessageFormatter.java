package dev.eclipseacp.client.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import com.google.gson.Gson;

import dev.eclipseacp.client.agent.FileDiff;
import dev.eclipseacp.client.agent.PromptAttachment;
import dev.eclipseacp.client.agent.Usage;
import dev.eclipseacp.client.agent.ToolCall;

/** Markdown formatting for protocol events and prompt context displayed in the transcript. */
final class ChatMessageFormatter {
    private static final int MAX_DIFF_LINES = 40;
    private static final long MAX_INLINE_IMAGE_BYTES = 5L * 1024 * 1024;
    private static final Gson GSON = new Gson();
    private ChatMessageFormatter() { }

    static String toolCallStatus(ToolCall toolCall) {
        String title = toolCall.title() == null || toolCall.title().isBlank() ? toolCall.kind() : toolCall.title();
        String status = toolCall.status() == null || toolCall.status().isBlank() ? "updated" : toolCall.status();
        return "Agent command: " + title + " — " + status;
    }

    static String usage(Usage usage) {
        List<String> entries = new ArrayList<>();
        if (usage.used() != null) entries.add("contextUsed=" + usage.used());
        if (usage.size() != null) entries.add("contextSize=" + usage.size());
        if (usage.inputTokens() != null) entries.add("inputTokens=" + usage.inputTokens());
        if (usage.outputTokens() != null) entries.add("outputTokens=" + usage.outputTokens());
        if (usage.totalTokens() != null) entries.add("totalTokens=" + usage.totalTokens());
        if (usage.cost() != null && !usage.cost().isBlank()) entries.add("cost=" + usage.cost());
        return entries.isEmpty() ? "updated" : String.join(", ", entries);
    }

    static String terminalOutput(String output) {
        return output == null || output.isBlank() ? ""
                : "> **Terminal output**\n\n" + fencedCode("terminal", output) + "\n";
    }

    static String toolCall(ToolCall toolCall) {
        String json = GSON.toJson(toolCall);
        String payload = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        return "\n```acp-tool\n" + payload + "\n```\n\n";
    }

    static String promptContext(List<PromptAttachment> attachments, List<String> sourceFileNames) {
        StringBuilder markdown = new StringBuilder();
        if (attachments != null && !attachments.isEmpty()) {
            markdown.append("> **Attachments:** ");
            for (int index = 0; index < attachments.size(); index++) {
                if (index > 0) markdown.append(", ");
                PromptAttachment attachment = attachments.get(index);
                String name = attachment == null || attachment.path() == null
                        ? "attachment" : attachment.path().getFileName().toString();
                markdown.append('`').append(inlineCode(name)).append('`');
            }
            markdown.append("\n\n");
            for (PromptAttachment attachment : attachments) appendImagePreview(markdown, attachment);
        }
        if (sourceFileNames != null && !sourceFileNames.isEmpty()) {
            markdown.append("> **Source files:** ");
            for (int index = 0; index < sourceFileNames.size(); index++) {
                if (index > 0) markdown.append(", ");
                markdown.append('`').append(inlineCode(sourceFileNames.get(index))).append('`');
            }
            markdown.append("\n\n");
        }
        return markdown.toString();
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

    private static void appendImagePreview(StringBuilder markdown, PromptAttachment attachment) {
        if (attachment == null || attachment.path() == null || attachment.mimeType() == null
                || !attachment.mimeType().startsWith("image/")) return;
        try {
            long size = Files.size(attachment.path());
            if (size <= 0 || size > MAX_INLINE_IMAGE_BYTES) return;
            String name = attachment.path().getFileName().toString();
            String data = Base64.getEncoder().encodeToString(Files.readAllBytes(attachment.path()));
            markdown.append("![").append(imageAlt(name)).append("](data:")
                    .append(attachment.mimeType()).append(";base64,").append(data).append(" \"")
                    .append(imageTitle(name)).append("\")\n\n");
        } catch (IOException | SecurityException ignored) {
            // The attachment chip remains useful if a preview cannot be read.
        }
    }

    private static String fencedCode(String language, String content) {
        int fenceLength = 3;
        int run = 0;
        for (int index = 0; index < content.length(); index++) {
            if (content.charAt(index) == '`') {
                run++;
                fenceLength = Math.max(fenceLength, run + 1);
            } else {
                run = 0;
            }
        }
        String fence = "`".repeat(fenceLength);
        return fence + language + "\n" + content + (content.endsWith("\n") ? "" : "\n") + fence + "\n";
    }

    private static String inlineText(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("*", "\\*")
                .replace("_", "\\_").replace("[", "\\[").replace("]", "\\]");
    }

    private static String inlineCode(String value) {
        return value == null ? "" : value.replace('`', '\'');
    }

    private static String imageAlt(String value) {
        return value.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]");
    }

    private static String imageTitle(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
