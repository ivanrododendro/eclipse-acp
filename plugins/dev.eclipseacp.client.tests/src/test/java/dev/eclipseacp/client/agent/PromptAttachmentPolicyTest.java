package dev.eclipseacp.client.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;

import org.junit.Test;

public class PromptAttachmentPolicyTest {
    @Test
    public void acceptsOnlyTheBinaryContentTypesTheAcpCodecCanRepresent() {
        assertTrue(PromptAttachmentPolicy.isSupportedMimeType("image/png"));
        assertTrue(PromptAttachmentPolicy.isSupportedMimeType("audio/wav"));
        assertTrue(PromptAttachmentPolicy.isSupportedMimeType("application/pdf"));

        assertFalse(PromptAttachmentPolicy.isSupportedMimeType("text/plain"));
        assertFalse(PromptAttachmentPolicy.isSupportedMimeType(null));
    }

    @Test
    public void recognizesJpegFromItsExtensionWhenThePlatformMimeTypeIsUnavailable() throws Exception {
        var file = Files.createTempFile("eclipse-acp-attachment-", ".jpeg");
        try {
            assertEquals("image/jpeg", PromptAttachmentPolicy.mimeType(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    public void recognizesPdfFromItsExtensionWhenThePlatformMimeTypeIsUnavailable() throws Exception {
        var file = Files.createTempFile("eclipse-acp-attachment-", ".pdf");
        try {
            assertEquals("application/pdf", PromptAttachmentPolicy.mimeType(file));
        } finally {
            Files.deleteIfExists(file);
        }
    }
}
