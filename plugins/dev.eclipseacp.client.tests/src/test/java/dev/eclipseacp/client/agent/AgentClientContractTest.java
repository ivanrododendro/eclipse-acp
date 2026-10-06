package dev.eclipseacp.client.agent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.Test;

/** Contract of the defaults inherited by adapters implementing the minimal port. */
public class AgentClientContractTest {
    private static final Path DIRECTORY = Path.of("project");

    @Test
    public void emptyAttachmentsPreserveTheTextPromptAndItsCompletion() {
        MinimalClient client = new MinimalClient();

        assertSame(client.promptResult, client.prompt("hello", List.of()));
        assertSame(client.promptResult, client.prompt("hello", null));
        assertEquals(2, client.promptCount);
        assertEquals("hello", client.lastText);
        assertTrue("the default must not acknowledge an unfinished prompt", !client.promptResult.isDone());
    }

    @Test
    public void attachmentsAreRejectedWithoutSendingATextOnlyPrompt() {
        MinimalClient client = new MinimalClient();

        assertUnsupported(client.prompt("hello", List.of(
                new PromptAttachment(Path.of("image.png"), "image/png"))));

        assertEquals(0, client.promptCount);
    }

    @Test
    public void replacingAListenerDoesNotFallBackToOpeningASessionWithTheOldListener() {
        MinimalClient client = new MinimalClient() {
            @Override public CompletableFuture<Void> startNewSession(Path directory) {
                newSessionCount++;
                return CompletableFuture.completedFuture(null);
            }
        };

        assertUnsupported(client.startNewSession(DIRECTORY, new SilentListener()));

        assertEquals(0, client.newSessionCount);
    }

    @Test
    public void unsupportedOptionalOperationsFailExplicitly() {
        MinimalClient client = new MinimalClient();

        assertUnsupported(client.startNewSession(DIRECTORY));
        assertUnsupported(client.restoreSession("saved", DIRECTORY));
        assertUnsupported(client.setConfigOption("mode", new ConfigValue(null, "fast")));
        assertUnsupported(client.authenticate("login"));
        assertUnsupported(client.logout());
        assertUnsupported(client.listSessions(DIRECTORY, null));
        assertUnsupported(client.loadSession("saved", DIRECTORY));
        assertUnsupported(client.resumeSession("saved", DIRECTORY));
        assertUnsupported(client.closeSession());
        assertUnsupported(client.deleteSession("saved"));
        assertEquals(0, client.promptCount);
    }

    private static void assertUnsupported(CompletableFuture<?> result) {
        assertTrue("unsupported operations must fail immediately", result.isCompletedExceptionally());
        CompletionException error = assertThrows(CompletionException.class, result::join);
        assertTrue(error.getCause() instanceof UnsupportedOperationException);
    }

    private static class MinimalClient implements AgentClient {
        final CompletableFuture<Void> promptResult = new CompletableFuture<>();
        int promptCount;
        int newSessionCount;
        String lastText;

        @Override public CompletableFuture<Void> connect(Path directory) {
            return CompletableFuture.completedFuture(null);
        }
        @Override public CompletableFuture<Void> prompt(String text) {
            promptCount++;
            lastText = text;
            return promptResult;
        }
        @Override public void cancel() { }
        @Override public AgentCapabilities capabilities() { return AgentCapabilities.NONE; }
        @Override public void close() { }
    }

    private static final class SilentListener implements AgentListener {
        @Override public void onAgentText(String text) { }
        @Override public void onStatus(String status) { }
        @Override public void onError(String message, Throwable error) { }
        @Override public CompletableFuture<String> requestPermission(String title, List<PermissionOption> options) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
