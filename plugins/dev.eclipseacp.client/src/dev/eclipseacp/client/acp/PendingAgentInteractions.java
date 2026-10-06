package dev.eclipseacp.client.acp;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/** Owns local user decisions and their timeouts, without ACP payloads or UI dependencies. */
final class PendingAgentInteractions implements AutoCloseable {
    private static final int MAX_PENDING_INTERACTIONS = 16;
    private final DiagnosticSink diagnostics;
    private final ScheduledExecutorService timeouts;
    private final Set<CompletableFuture<String>> pending = new HashSet<>();
    private final Object lock = new Object();
    private boolean closed;

    PendingAgentInteractions(DiagnosticSink diagnostics) {
        this(diagnostics, Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "eclipse-acp-interaction-timeout");
            thread.setDaemon(true);
            return thread;
        }));
    }

    PendingAgentInteractions(DiagnosticSink diagnostics, ScheduledExecutorService timeouts) {
        this.diagnostics = diagnostics;
        this.timeouts = timeouts;
    }

    CompletableFuture<String> request(Supplier<CompletableFuture<String>> request,
            long timeoutMinutes, String kind) {
        CompletableFuture<String> result = new CompletableFuture<>();
        synchronized (lock) {
            if (closed) return CompletableFuture.completedFuture(null);
            if (pending.size() >= MAX_PENDING_INTERACTIONS) {
                diagnostics.warn("Rejecting ACP " + kind + " request because too many user interactions are pending", null);
                return CompletableFuture.completedFuture(null);
            }
            pending.add(result);
            try {
                ScheduledFuture<?> timeout = timeouts.schedule(() -> { result.complete(null); }, timeoutMinutes,
                        TimeUnit.MINUTES);
                result.whenComplete((answer, failure) -> {
                    synchronized (lock) { pending.remove(result); }
                    timeout.cancel(false);
                });
            } catch (RuntimeException failure) {
                pending.remove(result);
                result.completeExceptionally(failure);
                return result;
            }
        }
        try {
            if (result.isDone()) return result;
            CompletableFuture<String> answer = request.get();
            if (answer == null) {
                result.completeExceptionally(new IllegalStateException("ACP " + kind + " request returned no future"));
            } else {
                answer.whenComplete((value, failure) -> {
                    if (failure == null) result.complete(value);
                    else result.completeExceptionally(failure);
                });
                // Cancel queued dialogs too, including a source returned after cancellation.
                result.whenComplete((value, failure) -> {
                    if (!answer.isDone()) answer.complete(null);
                });
            }
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    /** Cancels the current decisions; a reused connection may accept new ones afterwards. */
    void cancelAll() {
        List<CompletableFuture<String>> snapshot;
        synchronized (lock) { snapshot = List.copyOf(pending); }
        snapshot.forEach(interaction -> interaction.complete(null));
    }

    @Override public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
        }
        cancelAll();
        timeouts.shutdownNow();
    }
}
