package dev.eclipseacp.client.ui;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.util.List;

import org.junit.Test;

import dev.eclipseacp.client.agent.AgentClient;
import dev.eclipseacp.client.agent.AgentProvider;
import dev.eclipseacp.client.agent.SessionInfo;

public class RecentSessionsPanelTest {
    @Test
    public void noSessionAndUntitledNewChatProduceNoEntries() {
        assertTrue(RecentSessionsPanel.entriesFor(null).isEmpty());
        assertTrue(RecentSessionsPanel.entriesFor(session()).isEmpty());
    }

    @Test
    public void sortsSavedChatsWithoutMutatingHistoryAndNormalizesLabels() {
        ChatSessionModel session = session();
        SessionInfo old = saved("old", "", null);
        SessionInfo recent = saved("recent", "  Multi\nline\t title  ", "2026-10-05T10:00:00Z");
        session.savedSessions = List.of(old, recent);

        List<RecentSessionsPanel.RecentSession> entries = RecentSessionsPanel.entriesFor(session);

        assertEquals(List.of("Multi line title", "old"), entries.stream()
                .map(RecentSessionsPanel.RecentSession::label).toList());
        assertSame(recent, entries.get(0).saved());
        assertNull(entries.get(0).open());
        assertEquals(List.of(old, recent), session.savedSessions);
    }

    @Test
    public void connectedSavedChatIsRepresentedOnceAsOpenAndDisconnectedChatAsRestorable() {
        ChatSessionModel session = session();
        session.sessionId = "active";
        session.sessionName = "Active chat";
        SessionInfo active = saved("active", "Active chat", "2026-10-05T10:00:00Z");
        session.savedSessions = List.of(active);
        session.client = (AgentClient) Proxy.newProxyInstance(AgentClient.class.getClassLoader(),
                new Class<?>[] { AgentClient.class }, (proxy, method, arguments) -> {
                    throw new AssertionError("Snapshot construction must not call the agent");
                });

        List<RecentSessionsPanel.RecentSession> entries = RecentSessionsPanel.entriesFor(session);
        assertEquals(1, entries.size());
        assertSame(session, entries.get(0).open());
        assertNull(entries.get(0).saved());

        session.client = null;
        entries = RecentSessionsPanel.entriesFor(session);
        assertEquals(1, entries.size());
        assertNull(entries.get(0).open());
        assertSame(active, entries.get(0).saved());
    }

    @Test
    public void prependsAnUnsavedNamedChatToItsSavedHistory() {
        ChatSessionModel session = session();
        session.sessionName = "Local\nchat";
        session.savedSessions = List.of(saved("previous", "Previous", ""));

        List<RecentSessionsPanel.RecentSession> entries = RecentSessionsPanel.entriesFor(session);

        assertEquals(List.of("Local chat", "Previous"), entries.stream()
                .map(RecentSessionsPanel.RecentSession::label).toList());
        assertSame(session, entries.get(0).open());
        assertNull(entries.get(0).saved());
    }

    @Test
    public void omitsAnUntitledActiveSavedChatAndNormalizesMissingTitles() {
        ChatSessionModel session = session();
        session.sessionId = "active";
        session.savedSessions = List.of(saved("active", "", ""));

        assertTrue(RecentSessionsPanel.entriesFor(session).isEmpty());
        assertEquals("", RecentSessionsPanel.chatTitle(null));
    }

    @Test
    public void showsViewAllOnlyWhenSomeSessionsAreNotInTheRecentList() {
        assertFalse(RecentSessionsPanel.shouldShowViewAll(0, 3));
        assertFalse(RecentSessionsPanel.shouldShowViewAll(3, 3));
        assertTrue(RecentSessionsPanel.shouldShowViewAll(4, 3));
    }

    private static ChatSessionModel session() {
        return new ChatSessionModel(null, "Project", new AgentProvider("agent", "Agent", "agent", ""), false);
    }

    private static SessionInfo saved(String id, String title, String updatedAt) {
        return new SessionInfo(id, "/workspace/project", List.of(), title, updatedAt);
    }
}
