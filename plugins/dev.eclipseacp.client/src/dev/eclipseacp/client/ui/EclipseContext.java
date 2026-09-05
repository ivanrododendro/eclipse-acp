package dev.eclipseacp.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IAdaptable;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.IWorkbenchPart;

/** Builds explicitly requested Eclipse context; no editor or workspace data is sent implicitly. */
final class EclipseContext {
    private static final Pattern REFERENCE = Pattern.compile("(?<![\\w@])@(selection|file|problems|console|java|folder)(?::([^\\s]+))?");

    private EclipseContext() { }

    static String expand(String prompt, IProject project, IWorkbenchPage page) {
        Matcher matcher = REFERENCE.matcher(prompt);
        StringBuffer result = new StringBuffer();
        boolean found = false;
        while (matcher.find()) {
            found = true;
            String replacement = switch (matcher.group(1)) {
                case "selection" -> selection(page);
                case "file" -> activeFile(page);
                case "problems" -> problems(project);
                case "console" -> console(page);
                case "java" -> javaElement(page);
                case "folder" -> folder(project, matcher.group(2));
                default -> "";
            };
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        if (!found) return prompt;
        matcher.appendTail(result);
        return result.toString();
    }

    static String actionPrompt(String action, IProject project, IWorkbenchPage page) {
        String context = "@file\n@selection\n@java\n@problems\n@console";
        return switch (action) {
            case "explain" -> "Explain the selected code and its role.\n\n" + context;
            case "fix" -> "Diagnose and fix the relevant errors. Explain the proposed change before applying it.\n\n" + context;
            case "refactor" -> "Suggest and implement a safe refactoring for the selected code.\n\n" + context;
            case "test" -> "Propose or implement focused tests for the selected code.\n\n" + context;
            default -> context;
        };
    }

    private static String selection(IWorkbenchPage page) {
        IEditorPart editor = editor(page);
        TextInfo selection = editor == null ? null : text(editor.getSite().getSelectionProvider().getSelection());
        if (selection == null || selection.value().isEmpty()) return "[No editor selection is available]";
        return "<eclipse-selection file=\"" + path(editor) + "\" startLine=\"" + (selection.startLine() + 1)
                + "\" endLine=\"" + (selection.endLine() + 1) + "\">\n" + selection.value()
                + "\n</eclipse-selection>";
    }

    private static String activeFile(IWorkbenchPage page) {
        IEditorPart editor = editor(page);
        IFile file = file(editor);
        if (file == null) return "[No workspace file is active]";
        return "<eclipse-file path=\"" + file.getProjectRelativePath() + "\" project=\"" + file.getProject().getName()
                + "\"/>";
    }

    private static String javaElement(IWorkbenchPage page) {
        IEditorPart editor = editor(page);
        IFile file = file(editor);
        if (editor == null || file == null) return "[No Java element is active]";
        Object element;
        try {
            Class<?> javaCore = Class.forName("org.eclipse.jdt.core.JavaCore");
            element = javaCore.getMethod("create", IResource.class).invoke(null, file);
            if (element == null || !(Boolean) element.getClass().getMethod("exists").invoke(element)) {
                return "[Active file is not a Java source element]";
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            return "[Java model is not installed in this Eclipse distribution]";
        }
        int offset = 0;
        ISelection current = editor.getSite().getSelectionProvider().getSelection();
        TextInfo selected = text(current);
        if (selected != null) offset = selected.offset();
        try {
            try {
                Object atOffset = element.getClass().getMethod("getElementAt", int.class).invoke(element, offset);
                if (atOffset != null) element = atOffset;
            } catch (NoSuchMethodException ignored) {
                // The active Java element is not a compilation unit.
            }
            Object project = element.getClass().getMethod("getJavaProject").invoke(element);
            String projectName = project == null ? "" : (String) project.getClass().getMethod("getElementName").invoke(project);
            return "<java-model element=\"" + element.getClass().getMethod("getElementName").invoke(element)
                    + "\" kind=\"" + element.getClass().getMethod("getElementType").invoke(element)
                    + "\" project=\"" + projectName + "\"/>";
        } catch (ReflectiveOperationException | LinkageError exception) {
            return "[Java model unavailable: " + exception.getMessage() + "]";
        }
    }

    private static String problems(IProject project) {
        if (project == null || !project.isAccessible()) return "[Project problems are unavailable]";
        try {
            IMarker[] markers = project.findMarkers(IMarker.PROBLEM, true, IResource.DEPTH_INFINITE);
            List<String> lines = new ArrayList<>();
            for (IMarker marker : markers) {
                int severity = marker.getAttribute(IMarker.SEVERITY, IMarker.SEVERITY_INFO);
                if (severity < IMarker.SEVERITY_WARNING) continue;
                IResource resource = marker.getResource();
                lines.add((severity == IMarker.SEVERITY_ERROR ? "error" : "warning") + " "
                        + resource.getProjectRelativePath() + ":" + marker.getAttribute(IMarker.LINE_NUMBER, 0)
                        + " — " + marker.getAttribute(IMarker.MESSAGE, ""));
                if (lines.size() == 50) break;
            }
            return lines.isEmpty() ? "[No warnings or errors in this project]"
                    : "<eclipse-problems>\n" + String.join("\n", lines) + "\n</eclipse-problems>";
        } catch (CoreException exception) {
            return "[Could not read project problems: " + exception.getMessage() + "]";
        }
    }

    /** Console API does not expose all output safely; send only text the user has selected in the active view. */
    private static String console(IWorkbenchPage page) {
        if (page == null) return "[No console selection is available]";
        IWorkbenchPart activePart = page.getActivePart();
        if (activePart == null || activePart.getSite().getSelectionProvider() == null) {
            return "[No console selection is available]";
        }
        ISelection selected = activePart.getSite().getSelectionProvider().getSelection();
        TextInfo text = text(selected);
        if (text == null || text.value().isEmpty()) {
            return "[Select console output before using @console]";
        }
        return "<eclipse-console-selection>\n" + text.value() + "\n</eclipse-console-selection>";
    }

    private static String folder(IProject project, String requestedPath) {
        IResource resource = requestedPath == null || requestedPath.isBlank() ? project : project.findMember(requestedPath);
        if (resource == null || !resource.exists()) return "[Folder not found: " + (requestedPath == null ? "" : requestedPath) + "]";
        List<String> entries = new ArrayList<>();
        try {
            resource.accept(candidate -> {
                if (candidate.getType() == IResource.FILE) entries.add(candidate.getProjectRelativePath().toString());
                return entries.size() < 100 && candidate.getProjectRelativePath().segmentCount() - resource.getProjectRelativePath().segmentCount() < 3;
            });
        } catch (CoreException exception) {
            return "[Could not list folder: " + exception.getMessage() + "]";
        }
        return "<eclipse-folder path=\"" + resource.getProjectRelativePath() + "\">\n"
                + String.join("\n", entries) + "\n</eclipse-folder>";
    }

    private static IEditorPart editor(IWorkbenchPage page) { return page == null ? null : page.getActiveEditor(); }
    private static IFile file(IEditorPart editor) {
        if (editor == null) return null;
        IEditorInput input = editor.getEditorInput();
        return input instanceof IAdaptable adaptable ? adaptable.getAdapter(IFile.class) : null;
    }
    private static String path(IEditorPart editor) {
        IFile file = file(editor);
        return file == null ? "" : file.getProjectRelativePath().toString();
    }

    /* ITextSelection lives in the optional Eclipse Text bundle. Reflection keeps this plug-in usable
       in minimal Eclipse installs while still accepting the standard editor/console selection type. */
    private static TextInfo text(ISelection selection) {
        if (selection == null) return null;
        try {
            Class<?> type = selection.getClass();
            String value = (String) type.getMethod("getText").invoke(selection);
            int startLine = (Integer) type.getMethod("getStartLine").invoke(selection);
            int endLine = (Integer) type.getMethod("getEndLine").invoke(selection);
            int offset = (Integer) type.getMethod("getOffset").invoke(selection);
            return new TextInfo(value == null ? "" : value, startLine, endLine, offset);
        } catch (ReflectiveOperationException | ClassCastException exception) {
            return null;
        }
    }

    private record TextInfo(String value, int startLine, int endLine, int offset) { }
}
