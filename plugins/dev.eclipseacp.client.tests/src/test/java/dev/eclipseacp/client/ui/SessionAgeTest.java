package dev.eclipseacp.client.ui;

import static org.junit.Assert.assertEquals;

import java.time.Instant;

import org.junit.Test;

public class SessionAgeTest {
    private static final Instant NOW = Instant.parse("2026-10-03T12:00:00Z");

    @Test
    public void formatsAgeUsingTheLargestCompactUnit() {
        assertEquals("now", SessionAge.format("2026-10-03T11:59:30Z", NOW));
        assertEquals("2m", SessionAge.format("2026-10-03T11:58:00Z", NOW));
        assertEquals("2h", SessionAge.format("2026-10-03T09:30:00Z", NOW));
        assertEquals("2d", SessionAge.format("2026-10-01T10:00:00Z", NOW));
    }

    @Test
    public void omitsMissingOrInvalidTimestamps() {
        assertEquals("", SessionAge.format(null, NOW));
        assertEquals("", SessionAge.format("", NOW));
        assertEquals("", SessionAge.format("not-a-timestamp", NOW));
    }

    @Test
    public void treatsFutureTimestampsAsCurrent() {
        assertEquals("now", SessionAge.format("2026-10-03T12:01:00Z", NOW));
    }
}
