package dev.eclipseacp.client.ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ui.IEditorPart;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

import dev.eclipseacp.client.agent.FileDiff;

/** Applies reviewed ACP replacement diffs atomically enough for workspace use and records one undo step. */
final class WorkspaceDiffApplier {
    private record Before(IFile file, boolean existed, String text, String appliedText) { }
    private List<Before> lastApplied = List.of();

    synchronized FileDiff preview(IProject project, String path, String newText) throws CoreException, IOException {
        IFile file = resolve(project, List.of(new FileDiff(path, "", newText))).keySet().iterator().next();
        return new FileDiff(path, file.exists() ? read(file) : null, newText);
    }

    synchronized String read(IProject project, String path, Integer line, Integer limit) throws CoreException, IOException {
        IFile file = resolve(project, List.of(new FileDiff(path, "", ""))).keySet().iterator().next();
        if (!file.exists()) throw new IOException("File does not exist: " + file.getProjectRelativePath());
        String content = read(file);
        if (line == null && limit == null) return content;
        String[] lines = content.split("\\R", -1);
        int first = line == null ? 0 : Math.max(0, line - 1);
        int last = limit == null ? lines.length : Math.min(lines.length, first + Math.max(0, limit));
        return String.join("\n", java.util.Arrays.copyOfRange(lines, first, last));
    }

    synchronized int apply(IProject project, List<FileDiff> diffs) throws CoreException, IOException {
        Map<IFile, FileDiff> files = resolve(project, diffs);
        List<Before> before = new ArrayList<>();
        for (Map.Entry<IFile, FileDiff> entry : files.entrySet()) {
            IFile file = entry.getKey();
            FileDiff diff = entry.getValue();
            if (isDirty(file)) throw new IOException("Save or revert the open editor before applying " + file.getProjectRelativePath());
            boolean exists = file.exists();
            String current = exists ? read(file) : null;
            boolean matchesOriginal = diff.oldText() == null ? !exists : diff.oldText().equals(current);
            boolean alreadyApplied = diff.newText().equals(current);
            if (!matchesOriginal && !alreadyApplied) {
                throw new IOException("The workspace version of " + file.getProjectRelativePath()
                        + " no longer matches the reviewed diff");
            }
            before.add(new Before(file, diff.oldText() != null,
                    matchesOriginal ? current : diff.oldText(), diff.newText()));
        }
        ResourcesPlugin.getWorkspace().run(monitor -> {
            for (Map.Entry<IFile, FileDiff> entry : files.entrySet()) write(entry.getKey(), entry.getValue().newText(), monitor);
        }, project, 0, new NullProgressMonitor());
        lastApplied = List.copyOf(before);
        return before.size();
    }

    synchronized int undo() throws CoreException, IOException {
        if (lastApplied.isEmpty()) return 0;
        for (Before before : lastApplied) {
            if (isDirty(before.file())) throw new IOException("Save or revert the open editor before undoing "
                    + before.file().getProjectRelativePath());
            String current = before.file().exists() ? read(before.file()) : null;
            if (!before.appliedText().equals(current)) {
                throw new IOException("The workspace version of " + before.file().getProjectRelativePath()
                        + " changed after the reviewed diff was applied");
            }
        }
        List<Before> undo = lastApplied;
        ResourcesPlugin.getWorkspace().run(monitor -> {
            for (Before before : undo) {
                if (before.existed()) write(before.file(), before.text(), monitor);
                else if (before.file().exists()) before.file().delete(IResource.FORCE, monitor);
            }
        }, null, 0, new NullProgressMonitor());
        lastApplied = List.of();
        return undo.size();
    }

    /** Reverts direct agent writes only when the on-disk text still matches the reviewed proposal. */
    synchronized int reject(IProject project, List<FileDiff> diffs) throws CoreException, IOException {
        Map<IFile, FileDiff> files = resolve(project, diffs);
        List<Map.Entry<IFile, FileDiff>> directWrites = new ArrayList<>();
        for (Map.Entry<IFile, FileDiff> entry : files.entrySet()) {
            IFile file = entry.getKey();
            FileDiff diff = entry.getValue();
            if (isDirty(file)) throw new IOException("Save or revert the open editor before rejecting " + file.getProjectRelativePath());
            String current = file.exists() ? read(file) : null;
            boolean unchanged = diff.oldText() == null ? !file.exists() : diff.oldText().equals(current);
            boolean directlyWritten = diff.newText().equals(current);
            if (unchanged) continue; // ACP client-mediated write: it was only staged.
            if (!directlyWritten) throw new IOException("The workspace version of " + file.getProjectRelativePath()
                    + " changed after the diff was proposed");
            directWrites.add(entry);
        }
        ResourcesPlugin.getWorkspace().run(monitor -> {
            for (Map.Entry<IFile, FileDiff> entry : directWrites) {
                if (entry.getValue().oldText() == null) entry.getKey().delete(IResource.FORCE, monitor);
                else write(entry.getKey(), entry.getValue().oldText(), monitor);
            }
        }, project, 0, new NullProgressMonitor());
        return directWrites.size();
    }

    synchronized boolean canUndo() { return !lastApplied.isEmpty(); }

    private static Map<IFile, FileDiff> resolve(IProject project, List<FileDiff> diffs) throws IOException {
        if (diffs.isEmpty()) throw new IOException("There are no reviewed changes to apply");
        Path root = project.getLocation().toFile().toPath().toAbsolutePath().normalize();
        Map<IFile, FileDiff> files = new LinkedHashMap<>();
        for (FileDiff diff : diffs) {
            Path path;
            try { path = Path.of(diff.path()).toAbsolutePath().normalize(); }
            catch (RuntimeException exception) { throw new IOException("Invalid diff path: " + diff.path(), exception); }
            if (!path.startsWith(root) || path.equals(root)) throw new IOException("Diff path is outside the selected project: " + diff.path());
            IFile file = project.getFile(root.relativize(path).toString());
            if (files.put(file, diff) != null) throw new IOException("More than one diff targets " + file.getProjectRelativePath());
        }
        return files;
    }

    private static String read(IFile file) throws CoreException, IOException {
        try (var input = file.getContents()) { return new String(input.readAllBytes(), StandardCharsets.UTF_8); }
    }

    private static void write(IFile file, String text, IProgressMonitor monitor) throws CoreException {
        ByteArrayInputStream input = new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
        if (file.exists()) file.setContents(input, IResource.FORCE | IResource.KEEP_HISTORY, monitor);
        else file.create(input, IResource.FORCE, monitor);
    }

    private static boolean isDirty(IFile file) {
        if (!PlatformUI.isWorkbenchRunning()) return false;
        for (var window : PlatformUI.getWorkbench().getWorkbenchWindows()) {
            for (IWorkbenchPage page : window.getPages()) {
                for (IEditorPart editor : page.getDirtyEditors()) {
                    IFile editorFile = editor.getEditorInput().getAdapter(IFile.class);
                    if (file.equals(editorFile)) return true;
                }
            }
        }
        return false;
    }
}
