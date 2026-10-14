package dev.eclipseacp.client.agent;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;

import dev.eclipseacp.client.AcpLog;

/** A local binary attachment selected by the user for the next prompt. */
public record PromptAttachment(Path path, String mimeType, boolean temporary) {
    public PromptAttachment(Path path, String mimeType) {
        this(path, mimeType, false);
    }

    /** Only files created by the composer may be deleted. */
    public void deleteTemporaryFile() {
        if (!temporary) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            AcpLog.warn("Could not delete temporary attachment: " + path, exception);
        }
    }
}
