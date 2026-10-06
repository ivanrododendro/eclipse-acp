package dev.eclipseacp.client.acp;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.Test;

public class PendingAgentInteractionsTest {
    @Test
    public void forwardsTheDecisionAndCancelsItsTimer() {
        Timers timers = new Timers();
        try (PendingAgentInteractions interactions = new PendingAgentInteractions(new Diagnostics(), timers)) {
            CompletableFuture<String> answer = new CompletableFuture<>();
            CompletableFuture<String> result = interactions.request(() -> answer, 10, "permission");
            assertEquals(TimeUnit.MINUTES.toNanos(10), timers.tasks.get(0).delay);

            answer.complete("allow");

            assertEquals("allow", result.join());
            assertTrue(timers.tasks.get(0).isCancelled());
        }
    }

    @Test
    public void timeoutCancelsTheDecisionSourceWithoutWaitingForRealTime() {
        Timers timers = new Timers();
        try (PendingAgentInteractions interactions = new PendingAgentInteractions(new Diagnostics(), timers)) {
            CompletableFuture<String> answer = new CompletableFuture<>();
            CompletableFuture<String> result = interactions.request(() -> answer, 15, "elicitation");

            timers.tasks.get(0).run();

            assertNull(result.join());
            assertTrue(answer.isDone());
            assertNull(answer.join());
        }
    }

    @Test
    public void cancellingPendingDecisionsStillAllowsANewSessionToRequestOne() {
        Timers timers = new Timers();
        try (PendingAgentInteractions interactions = new PendingAgentInteractions(new Diagnostics(), timers)) {
            CompletableFuture<String> permission = new CompletableFuture<>();
            CompletableFuture<String> authentication = new CompletableFuture<>();
            CompletableFuture<String> first = interactions.request(() -> permission, 10, "permission");
            CompletableFuture<String> second = interactions.request(() -> authentication, 15, "authentication");

            interactions.cancelAll();

            assertNull(first.join());
            assertNull(second.join());
            assertTrue(permission.isDone());
            assertTrue(authentication.isDone());
            CompletableFuture<String> next = new CompletableFuture<>();
            assertFalse(interactions.request(() -> next, 10, "permission").isDone());
        }
    }

    @Test
    public void closeCancelsSourcesShutsDownTimersAndRejectsNewDialogs() {
        Timers timers = new Timers();
        PendingAgentInteractions interactions = new PendingAgentInteractions(new Diagnostics(), timers);
        CompletableFuture<String> answer = new CompletableFuture<>();
        CompletableFuture<String> result = interactions.request(() -> answer, 10, "permission");

        interactions.close();
        interactions.close();

        assertNull(result.join());
        assertNull(answer.join());
        assertTrue(timers.isShutdown());
        assertNull(interactions.request(() -> {
            throw new AssertionError("A closed client must not queue a dialog");
        }, 10, "permission").join());
    }

    @Test
    public void boundsPendingDecisionsAndReclaimsCompletedSlots() {
        Timers timers = new Timers();
        Diagnostics diagnostics = new Diagnostics();
        try (PendingAgentInteractions interactions = new PendingAgentInteractions(diagnostics, timers)) {
            List<CompletableFuture<String>> answers = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                CompletableFuture<String> answer = new CompletableFuture<>();
                answers.add(answer);
                interactions.request(() -> answer, 10, "permission");
            }
            assertNull(interactions.request(() -> {
                throw new AssertionError("Excess decisions must not reach the UI");
            }, 10, "permission").join());
            assertEquals(1, diagnostics.warnings);
            assertEquals(16, timers.tasks.size());

            answers.get(0).complete("allow");
            assertEquals("next", interactions.request(() -> CompletableFuture.completedFuture("next"),
                    10, "permission").join());
            assertEquals(17, timers.tasks.size());
        }
    }

    @Test
    public void callbackFailuresCancelTimersAndDoNotLeavePendingDecisions() {
        Timers timers = new Timers();
        try (PendingAgentInteractions interactions = new PendingAgentInteractions(new Diagnostics(), timers)) {
            assertTrue(interactions.request(() -> null, 10, "permission").isCompletedExceptionally());
            assertTrue(interactions.request(() -> { throw new IllegalStateException("callback failed"); },
                    10, "permission").isCompletedExceptionally());
            assertTrue(interactions.request(() -> CompletableFuture.failedFuture(new IllegalStateException("UI failed")),
                    10, "permission").isCompletedExceptionally());
            assertTrue(timers.tasks.stream().allMatch(Timer::isCancelled));
        }
    }

    @Test
    public void cancellationDuringCallbackPreparationAlsoCompletesItsLateSource() {
        Timers timers = new Timers();
        try (PendingAgentInteractions interactions = new PendingAgentInteractions(new Diagnostics(), timers)) {
            CompletableFuture<String> answer = new CompletableFuture<>();
            CompletableFuture<String> result = interactions.request(() -> {
                interactions.cancelAll();
                return answer;
            }, 10, "permission");

            assertNull(result.join());
            assertNull(answer.join());
            assertTrue(timers.tasks.get(0).isCancelled());
        }
    }

    @Test
    public void rejectedSchedulingFailsWithoutInvokingTheCallback() {
        Timers timers = new Timers();
        timers.shutdownNow();
        try (PendingAgentInteractions interactions = new PendingAgentInteractions(new Diagnostics(), timers)) {
            CompletableFuture<String> result = interactions.request(() -> {
                throw new AssertionError("No timeout means no dialog may be queued");
            }, 10, "permission");

            assertTrue(result.isCompletedExceptionally());
            assertTrue(timers.tasks.isEmpty());
        }
    }

    private static final class Diagnostics implements DiagnosticSink {
        int warnings;
        @Override public void info(String message) { }
        @Override public void trace(Supplier<String> message) { }
        @Override public void warn(String message, Throwable error) { warnings++; }
        @Override public void error(String message, Throwable error) { }
    }

    private static final class Timers extends ScheduledThreadPoolExecutor {
        final List<Timer> tasks = new ArrayList<>();
        Timers() { super(1); }
        @Override public ScheduledFuture<?> schedule(Runnable action, long delay, TimeUnit unit) {
            if (isShutdown()) throw new RejectedExecutionException("closed");
            Timer timer = new Timer(action, unit.toNanos(delay));
            tasks.add(timer);
            return timer;
        }
    }

    private static final class Timer extends FutureTask<Void> implements ScheduledFuture<Void> {
        final long delay;
        Timer(Runnable action, long delay) { super(action, null); this.delay = delay; }
        @Override public long getDelay(TimeUnit unit) { return unit.convert(delay, TimeUnit.NANOSECONDS); }
        @Override public int compareTo(Delayed other) {
            return Long.compare(delay, other.getDelay(TimeUnit.NANOSECONDS));
        }
    }
}
