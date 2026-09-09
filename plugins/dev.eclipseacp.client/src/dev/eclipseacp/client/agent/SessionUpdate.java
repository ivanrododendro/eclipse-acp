package dev.eclipseacp.client.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Collections;

/** Protocol-neutral, structured session update delivered to the client UI. */
public record SessionUpdate(String sessionId, String kind, Map<String, Object> payload) {
    public SessionUpdate {
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload == null ? Map.of() : payload));
    }
}
