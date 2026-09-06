package dev.eclipseacp.client.agent;

import java.nio.file.Path;

/** A local binary attachment selected by the user for the next prompt. */
public record PromptAttachment(Path path, String mimeType) {
}
