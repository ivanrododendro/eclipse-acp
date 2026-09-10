package dev.eclipseacp.client.ui;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;

import dev.eclipseacp.client.agent.FileDiff;
import dev.eclipseacp.client.agent.FileReadRequest;

/** Owns staging, acceptance, rejection and undo of one chat session's workspace changes. */
final class ChangeReviewService {
    private final IProject project;
    private final WorkspaceDiffApplier applier = new WorkspaceDiffApplier();
    private final Map<String, FileDiff> pending = new LinkedHashMap<>();

    ChangeReviewService(IProject project) { this.project = project; }
    synchronized String read(FileReadRequest request) throws CoreException, IOException { return applier.read(project, request.path(), request.line(), request.limit()); }
    synchronized FileDiff preview(String path, String content) throws CoreException, IOException { return applier.preview(project, path, content); }
    synchronized void stage(FileDiff diff) { pending.put(diff.path(), diff); }
    synchronized void stageAll(List<FileDiff> diffs) { diffs.forEach(this::stage); }
    synchronized List<FileDiff> pending() { return List.copyOf(pending.values()); }
    synchronized void clear() { pending.clear(); }
    synchronized int apply(List<FileDiff> diffs) throws CoreException, IOException { return applier.apply(project, diffs); }
    synchronized int reject(List<FileDiff> diffs) throws CoreException, IOException { return applier.reject(project, diffs); }
    synchronized int undo() throws CoreException, IOException { return applier.undo(); }
    synchronized boolean canUndo() { return applier.canUndo(); }
}
