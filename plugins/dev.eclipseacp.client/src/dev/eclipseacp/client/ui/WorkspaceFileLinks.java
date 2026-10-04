package dev.eclipseacp.client.ui;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;

/** Resolves file references from chat text without ever escaping the session project. */
final class WorkspaceFileLinks implements AutoCloseable {
    private final IProject project;
    private final Map<String, Optional<String>> resolvedPaths = new ConcurrentHashMap<>();
    private final IWorkspace workspace;
    private final IResourceChangeListener resourceChanges = this::resourcesChanged;

    WorkspaceFileLinks(IProject project) {
        this.project = project;
        IWorkspace owner = null;
        try {
            owner = project.getWorkspace();
            owner.addResourceChangeListener(resourceChanges, IResourceChangeEvent.POST_CHANGE);
        } catch (RuntimeException exception) {
            // Some headless model tests use a minimal project proxy without a workspace.
        }
        workspace = owner;
    }

    String hrefFor(String reference) {
        Reference parsed = Reference.parse(reference);
        if (parsed == null) return null;
        String path = resolvedPaths.computeIfAbsent(parsed.path(), value -> Optional.ofNullable(findProjectPath(value)))
                .orElse(null);
        if (path == null) return null;
        String href = "eclipse-acp://open?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
        return parsed.line() == null ? href : href + "&line=" + parsed.line();
    }

    private void resourcesChanged(IResourceChangeEvent event) {
        IResourceDelta delta = event.getDelta();
        if (delta != null && delta.findMember(project.getFullPath()) != null) resolvedPaths.clear();
    }

    @Override public void close() {
        if (workspace != null) workspace.removeResourceChangeListener(resourceChanges);
        resolvedPaths.clear();
    }

    private String findProjectPath(String value) {
        IPath path = new Path(value);
        if (path.isAbsolute()) {
            IPath projectLocation = project.getLocation();
            if (projectLocation == null || !projectLocation.isPrefixOf(path)) return null;
            path = path.makeRelativeTo(projectLocation);
        }
        if (path.segmentCount() == 0 || hasParentTraversal(path)) return null;
        IFile direct = project.getFile(path);
        if (direct.exists()) return direct.getProjectRelativePath().toPortableString();
        if (path.segmentCount() != 1) return null;
        try {
            IFile[] candidate = { null };
            boolean[] ambiguous = { false };
            project.accept(resource -> {
                if (resource.getType() != IResource.FILE || !resource.getName().equals(value)) return true;
                if (candidate[0] == null) candidate[0] = (IFile) resource;
                else ambiguous[0] = true; // Ambiguous basenames must not become arbitrary links.
                return true;
            });
            return candidate[0] == null || ambiguous[0] ? null : candidate[0].getProjectRelativePath().toPortableString();
        } catch (CoreException ignored) {
            return null;
        }
    }

    private static boolean hasParentTraversal(IPath path) {
        for (String segment : path.segments()) if ("..".equals(segment)) return true;
        return false;
    }

    private record Reference(String path, Integer line) {
        static Reference parse(String value) {
            if (value == null || value.isBlank()) return null;
            String candidate = value.trim().replace('\\', '/');
            Integer line = null;
            int separator = candidate.lastIndexOf(':');
            if (separator > 0 && candidate.substring(separator + 1).matches("[1-9][0-9]*(?:-[1-9][0-9]*)?")) {
                String location = candidate.substring(separator + 1);
                int rangeSeparator = location.indexOf('-');
                line = Integer.valueOf(rangeSeparator < 0 ? location : location.substring(0, rangeSeparator));
                candidate = candidate.substring(0, separator);
            }
            while (candidate.startsWith("./")) candidate = candidate.substring(2);
            if (candidate.startsWith("file:")) {
                try { candidate = java.nio.file.Path.of(URI.create(candidate)).toString().replace('\\', '/'); }
                catch (RuntimeException exception) { return null; }
            }
            if (candidate.isBlank() || candidate.contains("//")) return null;
            return new Reference(candidate, line);
        }
    }
}
