package dev.eclipseacp.client.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

import dev.eclipseacp.client.agent.AgentClient;
import dev.eclipseacp.client.agent.SessionInfo;

/** Loads project history without retaining presentation or session-model state. */
final class AgentSessionHistory {
    private static final int MAX_SESSION_LIST_PAGES = 100;
    private final SessionOperationScope operations;

    AgentSessionHistory(SessionOperationScope operations) {
        this.operations = operations;
    }

    CompletableFuture<List<SessionInfo>> load(Object owner, AgentClient client, Path workingDirectory,
            BooleanSupplier ownership) {
        return list(owner, client, workingDirectory, null, new ArrayList<>(), new HashSet<>(), 0, ownership)
                .thenApply(available -> available.stream()
                        .filter(info -> belongsToProject(info, workingDirectory)).toList());
    }

    private CompletableFuture<List<SessionInfo>> list(Object owner, AgentClient client, Path workingDirectory,
            String cursor, List<SessionInfo> collected, Set<String> seenCursors, int pageCount,
            BooleanSupplier ownership) {
        if (pageCount >= MAX_SESSION_LIST_PAGES) {
            return CompletableFuture.failedFuture(new IllegalStateException("ACP session list exceeded "
                    + MAX_SESSION_LIST_PAGES + " pages"));
        }
        if (cursor != null && !cursor.isBlank() && !seenCursors.add(cursor)) {
            return CompletableFuture.failedFuture(new IllegalStateException("ACP session list repeated cursor: " + cursor));
        }
        return operations.run(owner, ownership, () -> client.listSessions(workingDirectory, cursor)).thenCompose(page -> {
            collected.addAll(page.sessions());
            return page.nextCursor() == null || page.nextCursor().isBlank()
                    ? CompletableFuture.completedFuture(List.copyOf(collected))
                    : list(owner, client, workingDirectory, page.nextCursor(), collected,
                            seenCursors, pageCount + 1, ownership);
        });
    }

    private static boolean belongsToProject(SessionInfo session, Path projectDirectory) {
        try {
            return Path.of(session.cwd()).toAbsolutePath().normalize().equals(projectDirectory.toAbsolutePath().normalize());
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
