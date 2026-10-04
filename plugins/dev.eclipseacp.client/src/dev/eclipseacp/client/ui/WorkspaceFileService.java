package dev.eclipseacp.client.ui;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;

import dev.eclipseacp.client.agent.FileDiff;
import dev.eclipseacp.client.agent.FileReadRequest;
import dev.eclipseacp.client.agent.FileWriteRequest;

/** Performs asynchronous file operations for one chat session. */
final class WorkspaceFileService {
    private final IProject project;
    private final WorkspaceDiffApplier applier = new WorkspaceDiffApplier();

    WorkspaceFileService(IProject project) { this.project = project; }
    synchronized String read(FileReadRequest request) throws CoreException, IOException { return applier.read(project, request.path(), request.line(), request.limit()); }
    synchronized FileDiff preview(String path, String content) throws CoreException, IOException { return applier.preview(project, path, content); }
    synchronized int apply(List<FileDiff> diffs) throws CoreException, IOException { return applier.apply(project, diffs); }

    CompletableFuture<String> readAsync(FileReadRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try { return read(request); }
            catch (CoreException | IOException error) { throw new CompletionException(error); }
        });
    }

    CompletableFuture<FileDiff> writeAsync(FileWriteRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                FileDiff diff = preview(request.path(), request.content());
                apply(List.of(diff));
                return diff;
            } catch (CoreException | IOException error) { throw new CompletionException(error); }
        });
    }

    CompletableFuture<Integer> applyAsync(List<FileDiff> diffs) {
        return CompletableFuture.supplyAsync(() -> {
            try { return apply(diffs); }
            catch (CoreException | IOException error) { throw new CompletionException(error); }
        });
    }

}
