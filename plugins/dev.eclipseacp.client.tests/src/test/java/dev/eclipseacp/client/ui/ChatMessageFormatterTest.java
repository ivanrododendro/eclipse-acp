package dev.eclipseacp.client.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import dev.eclipseacp.client.agent.FileDiff;
import dev.eclipseacp.client.agent.PermissionOption;
import dev.eclipseacp.client.agent.Usage;

public class ChatMessageFormatterTest {
    @Test
    public void formatsOnlyAvailableUsageFields() {
        assertEquals("inputTokens=12, totalTokens=20",
                ChatMessageFormatter.usage(new Usage(12L, null, 20L, "")));
        assertEquals("updated", ChatMessageFormatter.usage(new Usage(null, null, null, null)));
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
    public void permissionDefaultsToFirstRejectOption() {
        assertEquals(1, AcpChatDialogs.defaultPermissionIndex(List.of(
                new PermissionOption("allow", "Allow", "allow_once"),
                new PermissionOption("reject", "Reject", "reject_once"),
                new PermissionOption("always", "Always", "allow_always"))));
    }
}
