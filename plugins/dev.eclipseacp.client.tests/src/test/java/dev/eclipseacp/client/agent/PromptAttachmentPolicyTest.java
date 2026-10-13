package dev.eclipseacp.client.agent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PromptAttachmentPolicyTest {
    @Test
    public void acceptsOnlyTheBinaryContentTypesTheAcpCodecCanRepresent() {
        assertTrue(PromptAttachmentPolicy.isSupportedMimeType("image/png"));
        assertTrue(PromptAttachmentPolicy.isSupportedMimeType("audio/wav"));

        assertFalse(PromptAttachmentPolicy.isSupportedMimeType("text/plain"));
        assertFalse(PromptAttachmentPolicy.isSupportedMimeType("application/pdf"));
        assertFalse(PromptAttachmentPolicy.isSupportedMimeType(null));
    }
}
