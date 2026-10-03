package dev.eclipseacp.client.ui;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/** Formats ACP session timestamps as compact, human-readable ages. */
final class SessionAge {
    private SessionAge() { }

    static String format(String updatedAt, Instant now) {
        if (updatedAt == null || updatedAt.isBlank()) return "";
        try {
            long seconds = Math.max(0, Duration.between(Instant.parse(updatedAt), now).getSeconds());
            if (seconds < 60) return "now";
            if (seconds < 3_600) return seconds / 60 + "m";
            if (seconds < 86_400) return seconds / 3_600 + "h";
            return seconds / 86_400 + "d";
        } catch (DateTimeParseException exception) {
            return "";
        }
    }
}
