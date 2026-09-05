package dev.eclipseacp.client.acp;

/** ACP {@code fs/read_text_file} request delegated to the Eclipse workspace. */
public record FileReadRequest(String sessionId, String path, Integer line, Integer limit) {
}
