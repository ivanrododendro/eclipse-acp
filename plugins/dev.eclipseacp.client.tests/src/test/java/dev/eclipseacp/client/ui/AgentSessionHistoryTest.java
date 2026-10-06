package dev.eclipseacp.client.ui;

import static org.junit.Assert.*;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import org.junit.Test;

import dev.eclipseacp.client.agent.AgentCapabilities;
import dev.eclipseacp.client.agent.AgentClient;
import dev.eclipseacp.client.agent.SessionInfo;
import dev.eclipseacp.client.agent.SessionPage;

public class AgentSessionHistoryTest {
    private static final Path DIRECTORY = Path.of("/workspace/project");

    @Test
    public void collectsPagesAndKeepsOnlyNormalizedProjectPaths() throws Exception {
        Client client = new Client(cursor -> CompletableFuture.completedFuture(cursor == null
                ? new SessionPage(List.of(info("first", DIRECTORY.toString()), info("other", "/workspace/other")), "next")
                : new SessionPage(List.of(info("second", "/workspace/project/sub/.."), info("invalid", "\0")), "")));
        AgentSessionHistory history = new AgentSessionHistory(new SessionOperationScope(Runnable::run));

        List<SessionInfo> result = history.load(this, client, DIRECTORY, () -> true).get(2, TimeUnit.SECONDS);

        assertEquals(List.of("first", "second"), result.stream().map(SessionInfo::id).toList());
        assertEquals(java.util.Arrays.asList(null, "next"), client.cursors);
        assertEquals(List.of(DIRECTORY, DIRECTORY), client.directories);
    }

    @Test
    public void rejectsRepeatedCursors() throws Exception {
        Client client = new Client(cursor -> CompletableFuture.completedFuture(new SessionPage(List.of(), "again")));
        assertFailure(client, "repeated cursor");
        assertEquals(2, client.cursors.size());
    }

    @Test
    public void limitsHistoryToOneHundredPages() throws Exception {
        Client client = new Client(cursor -> CompletableFuture.completedFuture(
                new SessionPage(List.of(), cursor == null ? "1" : Integer.toString(Integer.parseInt(cursor) + 1))));
        assertFailure(client, "exceeded 100 pages");
        assertEquals(100, client.cursors.size());
    }

    @Test
    public void retirementPreventsTheQueuedNextPageFromReachingTheClient() {
        Queue<Runnable> io = new ArrayDeque<>();
        SessionOperationScope operations = new SessionOperationScope(io::add);
        AtomicBoolean owned = new AtomicBoolean(true);
        Client client = new Client(cursor -> CompletableFuture.completedFuture(new SessionPage(List.of(), "next")));
        CompletableFuture<List<SessionInfo>> result = new AgentSessionHistory(operations)
                .load(this, client, DIRECTORY, owned::get);
        assertTrue(client.cursors.isEmpty());
        io.remove().run();
        assertEquals(1, client.cursors.size());

        owned.set(false);
        operations.cancel(this);
        while (!io.isEmpty()) io.remove().run();

        assertTrue(result.isCompletedExceptionally());
        assertEquals(1, client.cursors.size());
    }

    @Test
    public void retirementCancelsTheActivePageFuture() {
        CompletableFuture<SessionPage> page = new CompletableFuture<>();
        Client client = new Client(cursor -> page);
        SessionOperationScope operations = new SessionOperationScope(Runnable::run);
        CompletableFuture<List<SessionInfo>> result = new AgentSessionHistory(operations)
                .load(this, client, DIRECTORY, () -> true);

        operations.cancel(this);

        assertTrue(page.isCancelled());
        assertTrue(result.isCompletedExceptionally());
        assertEquals(1, client.cursors.size());
    }

    private void assertFailure(Client client, String message) throws Exception {
        CompletableFuture<List<SessionInfo>> result = new AgentSessionHistory(new SessionOperationScope(Runnable::run))
                .load(this, client, DIRECTORY, () -> true);
        try {
            result.get(2, TimeUnit.SECONDS);
            fail("Expected history failure");
        } catch (ExecutionException expected) {
            assertTrue(expected.getCause().getMessage().contains(message));
        }
    }

    private static SessionInfo info(String id, String cwd) {
        return new SessionInfo(id, cwd, List.of(), id, "");
    }

    private static final class Client implements AgentClient {
        final Function<String, CompletableFuture<SessionPage>> pages;
        final List<String> cursors = new ArrayList<>();
        final List<Path> directories = new ArrayList<>();

        Client(Function<String, CompletableFuture<SessionPage>> pages) { this.pages = pages; }
        @Override public CompletableFuture<SessionPage> listSessions(Path directory, String cursor) {
            cursors.add(cursor);
            directories.add(directory);
            return pages.apply(cursor);
        }
        @Override public CompletableFuture<Void> connect(Path directory) { throw new AssertionError(); }
        @Override public CompletableFuture<Void> prompt(String text) { throw new AssertionError(); }
        @Override public void cancel() { throw new AssertionError(); }
        @Override public AgentCapabilities capabilities() { return AgentCapabilities.NONE; }
        @Override public void close() { throw new AssertionError(); }
    }
}
