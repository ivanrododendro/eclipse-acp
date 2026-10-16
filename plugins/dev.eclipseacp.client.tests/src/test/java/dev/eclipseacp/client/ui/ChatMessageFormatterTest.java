package dev.eclipseacp.client.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.util.List;

import org.junit.Test;

import dev.eclipseacp.client.agent.FileDiff;
import dev.eclipseacp.client.agent.PermissionOption;
import dev.eclipseacp.client.agent.PromptAttachment;
import dev.eclipseacp.client.agent.ToolCall;
import dev.eclipseacp.client.agent.Usage;

public class ChatMessageFormatterTest {
    @Test
    public void formatsOnlyAvailableUsageFields() {
        assertEquals("inputTokens=12, totalTokens=20",
                ChatMessageFormatter.usage(new Usage(12L, null, 20L, "")));
        assertEquals("updated", ChatMessageFormatter.usage(new Usage(null, null, null, null)));
    }

    @Test
    public void formatsContextWindowUsageFromAcp() {
        assertEquals("contextUsed=64000, contextSize=200000",
                ChatMessageFormatter.usage(new Usage(null, null, null, null, 64_000L, 200_000L)));
    }

    @Test
    public void limitsEachSideOfDiffPreview() {
        String manyLines = String.join("\n", java.util.Collections.nCopies(42, "line"));
        String preview = ChatMessageFormatter.diffPreview(List.of(new FileDiff("src/A.java", manyLines, manyLines)));

        assertTrue(preview.contains("--- src/A.java"));
        assertTrue(preview.contains("-… 2 more lines"));
        assertTrue(preview.contains("+… 2 more lines"));
    }

    @Test
    public void formatsCommandsAsASingleToolComponent() {
        ToolCall tool = new ToolCall("run", "Run tests", "terminal", "completed", List.of(), List.of(),
                "mvn test", "/workspace", "", "Tests passed");

        String command = ChatMessageFormatter.toolCall(tool);
        assertTrue(command.startsWith("\n```acp-tool\n"));
        String html = GfmRenderer.document(command);
        assertTrue(html.contains("class=\"agent-command\""));
        assertTrue(html.contains("class=\"tool-preview\""));
        assertTrue(html.contains("title=\"mvn test\""));
        assertTrue(html.contains("class=\"tool-status completed\">completed"));
        assertTrue(html.contains("Terminal output"));
        assertTrue(html.contains("Tests passed"));
        assertTrue(ChatMessageFormatter.terminalOutput(tool.terminalOutput()).contains("```terminal"));
    }

    @Test
    public void embedsSmallPromptImagesAndKeepsAttachmentAndSourceLabels() throws Exception {
        var image = Files.createTempFile("transcript-preview-", ".png");
        try {
            Files.write(image, new byte[] { 1, 2, 3 });
            String context = ChatMessageFormatter.promptContext(
                    List.of(new PromptAttachment(image, "image/png")), List.of("Example.java"));

            assertTrue(context.contains("**Attachments:**"));
            assertTrue(context.contains("data:image/png;base64,AQID"));
            assertTrue(context.contains("**Source files:** `Example.java`"));
        } finally {
            Files.deleteIfExists(image);
        }
    }

    @Test
    public void permissionDefaultsToFirstRejectOption() {
        assertEquals(1, AcpChatDialogs.defaultPermissionIndex(List.of(
                new PermissionOption("allow", "Allow", "allow_once"),
                new PermissionOption("reject", "Reject", "reject_once"),
                new PermissionOption("always", "Always", "allow_always"))));
    }
}
