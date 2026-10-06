package dev.eclipseacp.client.ui;

import static org.junit.Assert.*;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.Test;

public class SessionOperationScopeTest {
    @Test
    public void cancellingOneOwnerLeavesOtherOwnersActive() {
        SessionOperationScope operations = new SessionOperationScope(Runnable::run);
        Object first = new Object();
        CompletableFuture<Void> firstSource = new CompletableFuture<>();
        CompletableFuture<Void> secondSource = new CompletableFuture<>();
        CompletableFuture<Void> firstResult = operations.run(first, () -> true, () -> firstSource);
        CompletableFuture<Void> secondResult = operations.run(new Object(), () -> true, () -> secondSource);

        operations.cancel(first);

        assertTrue(firstResult.isCancelled());
        assertTrue(firstSource.isCancelled());
        assertFalse(secondSource.isDone());
        secondSource.complete(null);
        assertTrue(secondResult.isDone());
        assertFalse(secondResult.isCompletedExceptionally());
    }

    @Test
    public void cancellationDuringSynchronousPreparationCancelsTheReturnedFuture() {
        SessionOperationScope operations = new SessionOperationScope(Runnable::run);
        CompletableFuture<Void> source = new CompletableFuture<>();
        CompletableFuture<Void> result = operations.run(this, () -> true, () -> {
            operations.cancel(this);
            return source;
        });

        assertTrue(result.isCancelled());
        assertTrue(source.isCancelled());
    }

    @Test
    public void shutdownWaitsForEarlierAndFinalCleanupAndRejectsNewWork() throws Exception {
        Queue<Runnable> io = new ArrayDeque<>();
        SessionOperationScope operations = new SessionOperationScope(io::add);
        AtomicBoolean earlierClosed = new AtomicBoolean();
        AtomicBoolean finalClosed = new AtomicBoolean();
        operations.cleanup(() -> earlierClosed.set(true));
        assertTrue(operations.stopAccepting());
        assertFalse(operations.stopAccepting());
        operations.cleanup(() -> finalClosed.set(true));
        operations.shutdown();
        CompletableFuture<Void> later = operations.run(this, () -> true, () -> {
            throw new AssertionError("No agent operation may start after shutdown");
        });

        assertFalse(operations.shutdownCompletion().isDone());
        io.remove().run();
        assertTrue(earlierClosed.get());
        assertFalse(operations.shutdownCompletion().isDone());
        io.remove().run();
        operations.shutdownCompletion().get(2, TimeUnit.SECONDS);
        assertTrue(finalClosed.get());
        io.remove().run();
        assertTrue(later.isCancelled());
    }

    @Test
    public void executorRejectionBecomesAFailedFuture() throws Exception {
        SessionOperationScope operations = new SessionOperationScope(action -> {
            throw new RejectedExecutionException("closed");
        });
        CompletableFuture<Void> result = operations.run(this, () -> true, () -> {
            throw new AssertionError("Rejected work must not run");
        });
        try {
            result.get(2, TimeUnit.SECONDS);
            fail("Expected executor rejection");
        } catch (ExecutionException expected) {
            assertEquals("ACP I/O executor is shut down", expected.getCause().getMessage());
            assertTrue(expected.getCause().getCause() instanceof RejectedExecutionException);
        }
    }
}
