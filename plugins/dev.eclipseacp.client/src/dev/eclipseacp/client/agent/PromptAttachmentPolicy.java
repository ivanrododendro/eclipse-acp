package dev.eclipseacp.client.agent;

/** Shared limits and media types supported by ACP prompt attachments. */
public final class PromptAttachmentPolicy {
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    private PromptAttachmentPolicy() { }

    public static boolean isSupportedMimeType(String mimeType) {
        return mimeType != null && (mimeType.startsWith("image/") || mimeType.startsWith("audio/"));
    }
}
