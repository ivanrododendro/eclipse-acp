package dev.eclipseacp.client.ui;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.QualifiedName;

/** Persists transcript zoom independently for each Eclipse project. */
final class ProjectChatZoom {
    static final int MINIMUM = 50;
    static final int MAXIMUM = 200;
    static final int DEFAULT = 100;

    private static final QualifiedName PROPERTY =
            new QualifiedName("dev.eclipseacp.client", "chatTranscriptZoom");

    private ProjectChatZoom() { }

    static int normalize(int zoom) {
        return Math.max(MINIMUM, Math.min(MAXIMUM, zoom));
    }

    static int load(IProject project) {
        if (project == null) return DEFAULT;
        try {
            String stored = project.getPersistentProperty(PROPERTY);
            return stored == null || stored.isBlank() ? DEFAULT : normalize(Integer.parseInt(stored));
        } catch (CoreException | NumberFormatException exception) {
            return DEFAULT;
        }
    }

    static void save(IProject project, int zoom) {
        if (project == null) return;
        int normalized = normalize(zoom);
        try {
            project.setPersistentProperty(PROPERTY, normalized == DEFAULT ? null : Integer.toString(normalized));
        } catch (CoreException ignored) {
            // Zoom is a presentation preference; a workspace metadata failure must not break the chat.
        }
    }
}
