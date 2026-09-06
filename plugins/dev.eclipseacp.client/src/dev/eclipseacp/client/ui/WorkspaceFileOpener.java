package dev.eclipseacp.client.ui;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.Path;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.ide.IDE;
import org.eclipse.ui.texteditor.ITextEditor;

/** Handles the internal links emitted by {@link WorkspaceFileLinks}. */
final class WorkspaceFileOpener {
    private WorkspaceFileOpener() { }

    static boolean open(IWorkbenchPage page, IProject project, String location) {
        try {
            URI uri = URI.create(location);
            if (!"eclipse-acp".equals(uri.getScheme()) || !"open".equals(uri.getHost())) return false;
            Map<String, String> query = query(uri.getRawQuery());
            String value = query.get("path");
            if (value == null || value.isBlank()) return true;
            Path path = new Path(value);
            if (path.isAbsolute() || path.segmentCount() == 0 || hasParentTraversal(path)) return true;
            IFile file = project.getFile(path);
            if (!file.exists()) return true;
            IEditorPart editor = IDE.openEditor(page, file, true);
            Integer line = positiveInteger(query.get("line"));
            ITextEditor textEditor = editor.getAdapter(ITextEditor.class);
            if (line != null && textEditor != null && textEditor.getDocumentProvider() != null) {
                IDocument document = textEditor.getDocumentProvider().getDocument(textEditor.getEditorInput());
                if (document != null && line <= document.getNumberOfLines()) {
                    textEditor.selectAndReveal(document.getLineOffset(line - 1), 0);
                }
            }
            return true;
        } catch (Exception ignored) {
            return true; // A malformed internal link is consumed, never delegated to an external browser.
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw == null || raw.isBlank()) return result;
        for (String part : raw.split("&")) {
            int separator = part.indexOf('=');
            if (separator <= 0) continue;
            result.put(URLDecoder.decode(part.substring(0, separator), StandardCharsets.UTF_8),
                    URLDecoder.decode(part.substring(separator + 1), StandardCharsets.UTF_8));
        }
        return result;
    }

    private static Integer positiveInteger(String value) {
        try {
            Integer parsed = value == null ? null : Integer.valueOf(value);
            return parsed != null && parsed > 0 ? parsed : null;
        }
        catch (NumberFormatException ignored) { return null; }
    }

    private static boolean hasParentTraversal(Path path) {
        for (String segment : path.segments()) if ("..".equals(segment)) return true;
        return false;
    }
}
