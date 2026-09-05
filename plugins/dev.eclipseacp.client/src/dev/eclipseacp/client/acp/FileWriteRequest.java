package dev.eclipseacp.client.acp;

/** ACP {@code fs/write_text_file} request. The UI stages it; it does not write immediately. */
public record FileWriteRequest(String sessionId, String path, String content) {
}
