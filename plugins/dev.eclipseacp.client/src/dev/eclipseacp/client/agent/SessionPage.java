package dev.eclipseacp.client.agent;

import java.util.List;

/** One cursor-based page returned by ACP v1 {@code session/list}. */
public record SessionPage(List<SessionInfo> sessions, String nextCursor) {
    public SessionPage {
        sessions = List.copyOf(sessions);
    }
}
