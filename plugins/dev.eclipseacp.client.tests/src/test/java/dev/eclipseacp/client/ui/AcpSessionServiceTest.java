package dev.eclipseacp.client.ui;

import static org.junit.Assert.*;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.preference.PreferenceStore;
import org.junit.Test;

import dev.eclipseacp.client.agent.AgentCapabilities;
import dev.eclipseacp.client.agent.AgentClient;
import dev.eclipseacp.client.agent.AgentListener;
import dev.eclipseacp.client.agent.ConfigOption;
import dev.eclipseacp.client.agent.ConfigValue;
import dev.eclipseacp.client.agent.FileDiff;
import dev.eclipseacp.client.agent.PromptAttachment;
import dev.eclipseacp.client.agent.SessionInfo;
import dev.eclipseacp.client.agent.SessionPage;
import dev.eclipseacp.client.agent.ToolCall;
import dev.eclipseacp.client.preferences.AcpPreferences;

public class AcpSessionServiceTest {
    @Test
    public void readsTheActiveProviderAgainForEachNewSession() {
        Harness h = new Harness();
        assertEquals("codex", h.service.newSessionConfiguration().provider().id());
        h.preferences.setValue(AcpPreferences.ACTIVE_PROVIDER, "other");
        assertEquals("other", h.service.newSessionConfiguration().provider().id());
    }

    @Test
    public void reselectsOneChatPerProjectWithoutLosingItsTranscript() {
        Harness h = new Harness();
        IProject firstProject = project("first");
        ChatSessionModel first = h.open(firstProject);
        h.service.append(first, "First conversation");
        h.open(project("second"));

        h.service.openSessionFor(firstProject, "Follow up");

        assertSame(first, h.service.activeSession());
        assertEquals(2, h.clients.size());
        assertEquals("First conversation", first.transcriptMarkdown.toString());
        assertEquals("Follow up", h.initialPrompt);
    }

    @Test
    public void transfersAnIdleConnectionToANewSessionAndGuardsTheTransition() {
        Harness h = new Harness();
        ChatSessionModel previous = h.open(project("first"));
        FakeClient client = h.client();
        h.service.append(previous, "Previous transcript");

        h.service.newSession("Selected text");
        ChatSessionModel next = h.service.activeSession();
        assertNotSame(previous, next);
        assertNull(previous.client);
        assertSame(client, next.client);
        assertTrue(next.isBusy());
        assertEquals("Selected text", next.pendingInputText);
        assertEquals("", next.transcriptMarkdown.toString());
        h.service.newSession(null);
        h.service.sendPrompt(next, "Too early");
        assertSame(next, h.service.activeSession());
        assertEquals(0, client.promptCount);
        assertFalse(client.closed.isDone());

        client.newSession.complete(null);
        h.drainUi();
        assertFalse(next.isBusy());
        assertEquals(1, h.clients.size());
        assertEquals(List.of(next), h.service.sessions());
    }

    @Test
    public void replacesTheConnectionWhenTheProviderChanges() throws Exception {
        Harness h = new Harness();
        h.open(project("first"));
        FakeClient previous = h.client();
        h.preferences.setValue(AcpPreferences.ACTIVE_PROVIDER, "other");

        h.service.newSession(null);

        assertEquals("other", h.service.activeSession().provider.id());
        assertEquals(2, h.clients.size());
        previous.closed.get(2, TimeUnit.SECONDS);
    }

    @Test
    public void ignoresConnectionCompletionAfterClosingTheChat() throws Exception {
        Harness h = new Harness();
        h.service.openSessionFor(project("first"), "Initial prompt");
        ChatSessionModel session = h.service.activeSession();
        FakeClient client = h.client();
        h.service.close(session);
        client.connection.complete(null);
        h.drainUi();

        assertNull(h.service.activeSession());
        assertTrue(h.service.sessions().isEmpty());
        assertNull(h.ready);
        client.closed.get(2, TimeUnit.SECONDS);
    }

    @Test
    public void retainsConnectionErrorsAndAllowsAnExplicitRetry() throws Exception {
        Harness h = new Harness();
        IProject project = project("first");
        h.service.openSessionFor(project, null);
        ChatSessionModel failed = h.service.activeSession();
        FakeClient client = h.client();
        client.connection.completeExceptionally(new IOException("Agent unavailable"));
        h.drainUi();

        assertNull(failed.client);
        assertEquals("Error", failed.statusText);
        assertTrue(failed.transcriptMarkdown.toString().contains("Agent unavailable"));
        client.closed.get(2, TimeUnit.SECONDS);

        ChatSessionModel retried = h.open(project);
        assertNotSame(failed, retried);
        assertEquals(List.of(retried), h.service.sessions());
    }

    @Test
    public void cachesPaginatedHistoryForItsProjectWhileAnotherProjectIsSelected() {
        Harness h = new Harness();
        h.service.openSessionFor(project("first"), null);
        ChatSessionModel first = h.service.activeSession();
        FakeClient client = h.client();
        client.capabilities = historyCapabilities();
        SessionInfo saved = info("saved", "/workspace/first");
        SessionInfo older = info("older", "/workspace/first/.");
        client.pages.put("", new SessionPage(List.of(saved, info("foreign", "/workspace/second")), "next"));
        client.pages.put("next", new SessionPage(List.of(older), null));
        ChatSessionModel second = h.open(project("second"));

        client.connection.complete(null);
        h.drainUi();

        assertSame(second, h.service.activeSession());
        assertEquals(List.of(saved, older), first.savedSessions);
        h.service.select(first);
        h.service.restore(saved);
        assertTrue(h.service.activeSession().acceptingRestoredTranscript);
        assertEquals("saved", h.client().restoredId);
        assertEquals("saved", h.service.activeSession().sessionName);
    }

    @Test
    public void restoresAttachmentsAfterPromptFailureAndRejectsOverlappingPrompts() {
        Harness h = new Harness();
        ChatSessionModel session = h.open(project("first"));
        PromptAttachment attachment = new PromptAttachment(Path.of("/tmp/image.png"), "image/png");
        session.attachments.add(attachment);
        session.acceptingRestoredTranscript = true;

        h.service.sendPrompt(session, "Hello");
        h.service.sendPrompt(session, "Overlapping");
        assertTrue(session.agentMessageOpen);
        assertFalse(session.acceptingRestoredTranscript);
        assertTrue(session.attachments.isEmpty());
        assertEquals(List.of(attachment), h.client().attachments);
        assertEquals(1, h.client().promptCount);

        h.client().prompt.completeExceptionally(new IOException("Prompt rejected"));
        h.drainUi();
        assertFalse(session.agentMessageOpen);
        assertEquals(List.of(attachment), session.attachments);
        assertTrue(session.transcriptMarkdown.toString().contains("Prompt rejected"));
    }

    @Test
    public void preservesTypedConfigValuesAndIgnoresRetiredConnectionUpdates() {
        Harness h = new Harness();
        ChatSessionModel session = h.open(project("first"));
        ConfigOption option = new ConfigOption("enabled", "Enabled", "", "", ConfigValue.of(false), List.of());
        session.configOptions.put(option.id(), option);
        h.service.changeConfigOption(session, option, true, "Updated", "Failed");
        assertEquals(Boolean.TRUE, h.client().configValue.value());
        h.client().config.complete(null);
        h.drainUi();
        assertEquals(Boolean.TRUE, session.configOptions.get("enabled").value().value());

        h.client().config = new CompletableFuture<>();
        h.service.changeConfigOption(session, option, false, "Updated again", "Failed");
        FakeClient old = h.client();
        h.service.close(session);
        old.config.complete(null);
        h.drainUi();
        assertEquals(Boolean.TRUE, session.configOptions.get("enabled").value().value());
    }

    @Test
    public void batchesStreamedTextAndFlushesItBeforePromptCompletion() {
        Harness h = new Harness();
        ChatSessionModel session = h.open(project("first"));
        h.service.sendPrompt(session, "Hello");
        AgentListener listener = h.client().listener;
        int before = h.renders;
        listener.onAgentText("One ");
        listener.onAgentText("response");
        h.drainUi();
        assertEquals(1, h.timers.size());
        assertEquals(before, h.renders);

        listener.onPromptCompleted(0, 2_000_000);
        h.client().prompt.complete(null);
        h.drainUi();
        String completed = session.transcriptMarkdown.toString();
        assertTrue(completed.contains("## Agent\n\nOne response\n> **Timing:"));
        assertFalse(session.agentMessageOpen);
        h.timers.remove().run();
        assertEquals(completed, session.transcriptMarkdown.toString());
    }

    @Test
    public void ignoresQueuedEventsForAReplacedChat() {
        Harness h = new Harness();
        ChatSessionModel old = h.open(project("first"));
        AgentListener listener = h.client().listener;
        listener.onAgentText("Late reply");
        listener.onStatus("Old status");
        h.service.newSession(null);
        ChatSessionModel next = h.service.activeSession();
        h.drainUi();

        assertEquals("", next.transcriptMarkdown.toString());
        assertEquals("", old.transcriptMarkdown.toString());
        assertTrue(h.timers.isEmpty());
    }

    @Test
    public void stagesToolDiffsAndRendersEachPreviewOnlyOnce() {
        Harness h = new Harness();
        ChatSessionModel session = h.open(project("first"));
        FileDiff diff = new FileDiff("/workspace/first/A.java", "old", "new");
        ToolCall tool = new ToolCall("edit", "Edit file", "edit", "completed", List.of(diff),
                List.of(), "", "", "", "");
        h.client().listener.onToolCall(tool);
        h.client().listener.onToolCall(tool);
        h.drainUi();

        assertEquals(List.of(diff), session.changes.pending());
        assertEquals(1, session.transcriptMarkdown.toString().split("\\*\\*File changes:\\*\\*", -1).length - 1);
        assertSame(tool, session.toolCalls.get("edit"));
    }

    @Test
    public void keepsRestoredMessageBoundariesUntilTheNextPrompt() {
        Harness h = new Harness();
        ChatSessionModel session = h.open(project("first"));
        session.acceptingRestoredTranscript = true;
        session.appendRestoredUserText("Earlier question");
        session.appendAgentText("Earlier ");
        session.appendAgentText("answer");
        assertEquals("## You\n\nEarlier question\n\n## Agent\n\nEarlier answer",
                session.transcriptMarkdown.toString());

        session.beginPrompt("New question");
        session.appendRestoredUserText("Late replay");
        session.appendAgentText("New answer");
        assertFalse(session.transcriptMarkdown.toString().contains("Late replay"));
        assertTrue(session.transcriptMarkdown.toString().endsWith("## You\n\nNew question\n\n## Agent\n\nNew answer"));
    }

    private static SessionInfo info(String id, String cwd) {
        return new SessionInfo(id, cwd, List.of(), id, "");
    }

    private static AgentCapabilities historyCapabilities() {
        return new AgentCapabilities(true, true, false, false, false, false,
                false, false, false, false, false, false);
    }

    private static IProject project(String name) {
        return (IProject) Proxy.newProxyInstance(IProject.class.getClassLoader(), new Class<?>[] { IProject.class },
                (proxy, method, args) -> switch (method.getName()) {
                    case "exists", "isOpen" -> true;
                    case "getName", "toString" -> name;
                    case "getLocation" -> org.eclipse.core.runtime.Path.fromOSString("/workspace/" + name);
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static final class Harness implements AcpSessionService.Presentation {
        final PreferenceStore preferences = new PreferenceStore();
        final Queue<Runnable> ui = new ArrayDeque<>();
        final Queue<Runnable> timers = new ArrayDeque<>();
        final List<FakeClient> clients = new ArrayList<>();
        final AcpSessionService service;
        ChatSessionModel ready;
        String initialPrompt;
        int renders;

        Harness() {
            preferences.setValue(AcpPreferences.PROVIDERS_JSON,
                    "[{\"id\":\"codex\",\"name\":\"Codex\",\"command\":\"codex-acp\",\"arguments\":\"\"},"
                    + "{\"id\":\"other\",\"name\":\"Other\",\"command\":\"other-acp\",\"arguments\":\"\"}]");
            preferences.setValue(AcpPreferences.ACTIVE_PROVIDER, "codex");
            preferences.setValue(AcpPreferences.REVIEW_FILE_CHANGES, true);
            service = new AcpSessionService(preferences, ui::add, this, this::listener, (session, listener) -> {
                FakeClient client = new FakeClient(listener);
                clients.add(client);
                return client;
            });
        }

        private AgentListener listener(ChatSessionModel session) {
            return new AcpChatSessionListener(session, service, null, ui::add, timers::add);
        }

        ChatSessionModel open(IProject project) {
            service.openSessionFor(project, null);
            client().connection.complete(null);
            drainUi();
            return service.activeSession();
        }

        FakeClient client() { return clients.getLast(); }
        void drainUi() { while (!ui.isEmpty()) ui.remove().run(); }
        @Override public void selected(ChatSessionModel session) { }
        @Override public void changed(ChatSessionModel session) { }
        @Override public void statusChanged(ChatSessionModel session) { }
        @Override public void transcriptChanged(ChatSessionModel session) { renders++; }
        @Override public void inputReady(ChatSessionModel session, String text) {
            ready = session;
            initialPrompt = text;
        }
    }

    private static final class FakeClient implements AgentClient {
        AgentListener listener;
        AgentCapabilities capabilities = AgentCapabilities.NONE;
        final CompletableFuture<Void> connection = new CompletableFuture<>();
        final CompletableFuture<Void> newSession = new CompletableFuture<>();
        final CompletableFuture<Void> prompt = new CompletableFuture<>();
        final CompletableFuture<Void> closed = new CompletableFuture<>();
        CompletableFuture<Void> config = new CompletableFuture<>();
        final Map<String, SessionPage> pages = new HashMap<>();
        List<PromptAttachment> attachments;
        ConfigValue configValue;
        String restoredId;
        int promptCount;

        FakeClient(AgentListener listener) { this.listener = listener; }
        @Override public CompletableFuture<Void> connect(Path directory) { return connection; }
        @Override public CompletableFuture<Void> restoreSession(String id, Path directory) {
            restoredId = id;
            return connection;
        }
        @Override public CompletableFuture<Void> startNewSession(Path directory, AgentListener listener) {
            this.listener = listener;
            return newSession;
        }
        @Override public CompletableFuture<Void> prompt(String text) {
            promptCount++;
            return prompt;
        }
        @Override public CompletableFuture<Void> prompt(String text, List<PromptAttachment> attachments) {
            this.attachments = attachments;
            return prompt(text);
        }
        @Override public CompletableFuture<Void> setConfigOption(String id, ConfigValue value) {
            configValue = value;
            return config;
        }
        @Override public CompletableFuture<SessionPage> listSessions(Path directory, String cursor) {
            return CompletableFuture.completedFuture(pages.getOrDefault(cursor == null ? "" : cursor,
                    new SessionPage(List.of(), null)));
        }
        @Override public void cancel() { }
        @Override public AgentCapabilities capabilities() { return capabilities; }
        @Override public void close() { closed.complete(null); }
    }
}
