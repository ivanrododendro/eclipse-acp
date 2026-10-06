package dev.eclipseacp.client.ui;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Runs agent I/O and owns local cancellation and cleanup. Owners are opaque keys;
 * the session service alone decides whether a client still belongs to a session.
 */
final class SessionOperationScope {
    private final Executor io;
    private final ExecutorService shutdownableIo;
    private final AtomicBoolean acceptingOperations = new AtomicBoolean(true);
    private final Map<Object, Set<CompletableFuture<?>>> sessionOperations = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<?>> cleanupOperations = ConcurrentHashMap.newKeySet();
    private volatile CompletableFuture<Void> shutdownCompletion = CompletableFuture.completedFuture(null);

    SessionOperationScope() {
        this(Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "eclipse-acp-io");
            thread.setDaemon(true);
            return thread;
        }));
    }

    SessionOperationScope(Executor io) {
        this.io = io;
        this.shutdownableIo = io instanceof ExecutorService service ? service : null;
    }

    boolean isAccepting() { return acceptingOperations.get(); }
    boolean stopAccepting() { return acceptingOperations.compareAndSet(true, false); }

    <T> CompletableFuture<T> run(Object owner, BooleanSupplier ownership,
            Supplier<CompletableFuture<T>> operation) {
        // Register before scheduling: a direct executor may complete synchronously.
        CompletableFuture<T> result = new CompletableFuture<>();
        sessionOperations.compute(owner, (key, pending) -> {
            if (pending == null) pending = ConcurrentHashMap.newKeySet();
            pending.add(result);
            return pending;
        });
        result.whenComplete((ignored, failure) -> sessionOperations.computeIfPresent(owner, (key, pending) -> {
            pending.remove(result);
            return pending.isEmpty() ? null : pending;
        }));
        submit(result, () -> isAccepting() && ownership.getAsBoolean() && !result.isCancelled(), operation);
        return result;
    }

    void cancel(Object owner) {
        Set<CompletableFuture<?>> pending = sessionOperations.remove(owner);
        if (pending != null) pending.forEach(operation -> operation.cancel(true));
    }

    CompletableFuture<Void> cleanup(Runnable operation) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        cleanupOperations.add(result);
        result.whenComplete((ignored, failure) -> cleanupOperations.remove(result));
        submit(result, () -> true, () -> {
            operation.run();
            return CompletableFuture.completedFuture(null);
        });
        return result;
    }

    /** Called after intake is stopped and all owned clients have been scheduled for cleanup. */
    void shutdown() {
        shutdownCompletion = CompletableFuture.allOf(new ArrayList<>(cleanupOperations)
                .toArray(CompletableFuture[]::new)).handle((ignored, failure) -> null);
        if (shutdownableIo != null) shutdownableIo.shutdown();
    }

    CompletableFuture<Void> shutdownCompletion() { return shutdownCompletion; }

    private <T> void submit(CompletableFuture<T> result, BooleanSupplier ownership,
            Supplier<CompletableFuture<T>> operation) {
        AtomicReference<CompletableFuture<T>> activeOperation = new AtomicReference<>();
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                CompletableFuture<T> active = activeOperation.get();
                if (active != null) active.cancel(true);
            }
        });
        try {
            io.execute(() -> {
                try {
                    if (!ownership.getAsBoolean()) {
                        result.completeExceptionally(new CancellationException("ACP session is retired"));
                        return;
                    }
                    CompletableFuture<T> operationResult = operation.get();
                    if (operationResult == null) {
                        result.completeExceptionally(new IllegalStateException("ACP I/O operation returned no future"));
                    } else {
                        activeOperation.set(operationResult);
                        if (result.isCancelled()) operationResult.cancel(true);
                        operationResult.whenComplete((value, failure) -> {
                            if (failure == null) result.complete(value);
                            else result.completeExceptionally(failure);
                        });
                    }
                } catch (RuntimeException exception) {
                    result.completeExceptionally(exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            result.completeExceptionally(new IllegalStateException("ACP I/O executor is shut down", exception));
        }
    }
}
