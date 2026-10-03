package dev.eclipseacp.client.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class ProjectChatZoomTest {
    @Test
    public void clampsZoomToSupportedBounds() {
        assertEquals(ProjectChatZoom.MINIMUM, ProjectChatZoom.normalize(10));
        assertEquals(80, ProjectChatZoom.normalize(80));
        assertEquals(ProjectChatZoom.MAXIMUM, ProjectChatZoom.normalize(500));
    }

    @Test
    public void usesDefaultWithoutAProject() {
        assertEquals(ProjectChatZoom.DEFAULT, ProjectChatZoom.load(null));
    }
}
