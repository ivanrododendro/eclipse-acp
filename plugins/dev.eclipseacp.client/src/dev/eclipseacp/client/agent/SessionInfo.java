package dev.eclipseacp.client.agent;

import java.util.List;

/** Metadata returned by ACP v1 {@code session/list}. */
public record SessionInfo(String id, String cwd, List<String> additionalDirectories, String title, String updatedAt) {
}
