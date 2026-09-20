package dev.eclipseacp.client.ui;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.CoreException;

import dev.eclipseacp.client.agent.FileDiff;
import dev.eclipseacp.client.agent.FileReadRequest;
import dev.eclipseacp.client.agent.FileWriteRequest;

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
    synchronized int apply(List<FileDiff> diffs) throws CoreException, IOException { return applier.apply(project, diffs); }
    synchronized int reject(List<FileDiff> diffs) throws CoreException, IOException { return applier.reject(project, diffs); }
    synchronized int undo() throws CoreException, IOException { return applier.undo(); }
    synchronized boolean canUndo() { return applier.canUndo(); }

    CompletableFuture<String> readAsync(FileReadRequest request) {
        return CompletableFuture.supplyAsync(() -> {
            try { return read(request); }
            catch (CoreException | IOException error) { throw new CompletionException(error); }
        });
    }

    CompletableFuture<FileDiff> writeAsync(FileWriteRequest request, boolean review) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                FileDiff diff = preview(request.path(), request.content());
                if (review) stage(diff);
                else apply(List.of(diff));
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

    CompletableFuture<Integer> applyPendingAsync() {
        List<FileDiff> reviewed = pending();
        return applyAsync(reviewed).thenApply(count -> {
            removeReviewed(reviewed);
            return count;
        });
    }

    CompletableFuture<Integer> rejectPendingAsync() {
        List<FileDiff> reviewed = pending();
        return CompletableFuture.supplyAsync(() -> {
            try {
                int reverted = reject(reviewed);
                removeReviewed(reviewed);
                return reverted;
            } catch (CoreException | IOException error) { throw new CompletionException(error); }
        });
    }

    CompletableFuture<Integer> undoAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try { return undo(); }
            catch (CoreException | IOException error) { throw new CompletionException(error); }
        });
    }

    private synchronized void removeReviewed(List<FileDiff> reviewed) {
        reviewed.forEach(diff -> pending.remove(diff.path(), diff));
    }
}
