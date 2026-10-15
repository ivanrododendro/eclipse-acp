package dev.eclipseacp.client.agent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/** Shared limits and media types supported by ACP prompt attachments. */
public final class PromptAttachmentPolicy {
    private PromptAttachmentPolicy() { }

    public static boolean isSupportedMimeType(String mimeType) {
        return mimeType != null && (mimeType.startsWith("image/") || mimeType.startsWith("audio/")
                || "application/pdf".equals(mimeType));
    }

    /** Falls back to the extension when the operating system does not expose a MIME type. */
    public static String mimeType(Path path) throws IOException {
        String detected = Files.probeContentType(path);
        if (isSupportedMimeType(detected)) return detected;
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) return detected;
        return switch (name.substring(dot + 1)) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "ogg" -> "audio/ogg";
            case "pdf" -> "application/pdf";
            default -> detected;
        };
    }
}
