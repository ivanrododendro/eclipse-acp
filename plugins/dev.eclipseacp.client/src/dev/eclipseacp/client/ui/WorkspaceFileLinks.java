package dev.eclipseacp.client.ui;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;

/** Resolves file references from chat text without ever escaping the session project. */
final class WorkspaceFileLinks {
    private final IProject project;
    private final Map<String, String> resolvedPaths = new HashMap<>();

    WorkspaceFileLinks(IProject project) { this.project = project; }

    String hrefFor(String reference) {
        Reference parsed = Reference.parse(reference);
        if (parsed == null) return null;
        String path = resolvedPaths.computeIfAbsent(parsed.path(), this::findProjectPath);
        if (path == null) return null;
        String href = "eclipse-acp://open?path=" + URLEncoder.encode(path, StandardCharsets.UTF_8);
        return parsed.line() == null ? href : href + "&line=" + parsed.line();
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
            if (separator > 0 && candidate.substring(separator + 1).matches("[1-9][0-9]*")) {
                line = Integer.valueOf(candidate.substring(separator + 1));
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
