package dev.eclipseacp.client.agent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import org.junit.Test;

public class PromptAttachmentTest {
    @Test
    public void cleanupPreservesUserFilesAndDeletesOnlyTemporaryAttachments() throws Exception {
        var path = Files.createTempFile("acp-attachment-test-", ".png");
        try {
            new PromptAttachment(path, "image/png").deleteTemporaryFile();
            assertTrue(Files.exists(path));
            var temporary = new PromptAttachment(path, "image/png", true);
            temporary.deleteTemporaryFile();
            temporary.deleteTemporaryFile();
            assertFalse(Files.exists(path));
        } finally {
            Files.deleteIfExists(path);
        }
    }
}
