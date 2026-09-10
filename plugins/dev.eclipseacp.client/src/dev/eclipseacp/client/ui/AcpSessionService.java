package dev.eclipseacp.client.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.eclipse.jface.preference.IPreferenceStore;

import dev.eclipseacp.client.acp.AcpClientFactory;
import dev.eclipseacp.client.agent.AgentClient;
import dev.eclipseacp.client.agent.AgentListener;
import dev.eclipseacp.client.agent.AgentProvider;
import dev.eclipseacp.client.agent.SessionInfo;
import dev.eclipseacp.client.mcp.McpServerRegistry;
import dev.eclipseacp.client.preferences.AcpPreferences;
import dev.eclipseacp.client.preferences.AgentProviderRegistry;

/**
 * Application service for selecting an agent and driving its ACP session lifecycle.
 * SWT controls, transcript rendering and dialogs deliberately remain in {@link AcpChatView}.
 */
final class AcpSessionService {
    record SessionConfiguration(AgentProvider provider, boolean reviewFileChanges, boolean hideAgentCommands) { }
    record SessionSwitch(String sessionId) { }

    private final IPreferenceStore preferences;
    private final McpServerRegistry mcpServers;

    AcpSessionService() {
        this(AcpPreferences.store());
    }

    AcpSessionService(IPreferenceStore preferences) {
        this.preferences = preferences;
        this.mcpServers = new McpServerRegistry(preferences);
    }

    SessionConfiguration newSessionConfiguration() {
        // Provider preferences can be changed while this view remains open.  Do not retain a
        // registry snapshot from view creation, otherwise every subsequent session uses the
        // provider that happened to be active when the view was opened.
        AgentProvider provider = new AgentProviderRegistry(preferences).active();
        return new SessionConfiguration(provider, preferences.getBoolean(AcpPreferences.REVIEW_FILE_CHANGES),
                preferences.getBoolean(AcpPreferences.HIDE_AGENT_COMMANDS_IN_CHAT));
    }

    AgentClient createClient(AgentProvider provider, AgentListener listener, boolean reviewFileChanges, String projectName) {
        return AcpClientFactory.create(provider, listener, reviewFileChanges,
                mcpServers.forSession(provider.id(), projectName));
    }

    CompletableFuture<Void> connect(AgentClient client, Path workingDirectory, String restoredSessionId) {
        return restoredSessionId == null ? client.connect(workingDirectory)
                : client.restoreSession(restoredSessionId, workingDirectory);
    }

    CompletableFuture<List<SessionInfo>> listSessions(AgentClient client, Path workingDirectory) {
        return listSessions(client, workingDirectory, null, new ArrayList<>());
    }

    private CompletableFuture<List<SessionInfo>> listSessions(AgentClient client, Path workingDirectory,
            String cursor, List<SessionInfo> collected) {
        return client.listSessions(workingDirectory, cursor).thenCompose(page -> {
            collected.addAll(page.sessions());
            return page.nextCursor() == null || page.nextCursor().isBlank()
                    ? CompletableFuture.completedFuture(List.copyOf(collected))
                    : listSessions(client, workingDirectory, page.nextCursor(), collected);
        });
    }

    boolean belongsToProject(SessionInfo session, Path projectDirectory) {
        try {
            return Path.of(session.cwd()).toAbsolutePath().normalize()
                    .equals(projectDirectory.toAbsolutePath().normalize());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** Closes the active ACP session before creating or restoring its replacement. */
    CompletableFuture<SessionSwitch> switchSession(AgentClient client, Path workingDirectory, String restoredSessionId,
            Consumer<Boolean> beforeOpen) {
        return client.closeSession().thenCompose(ignored -> {
            boolean replayTranscript = restoredSessionId != null && client.capabilities().loadSession();
            beforeOpen.accept(replayTranscript);
            CompletableFuture<Void> operation = restoredSessionId == null
                    ? client.startNewSession(workingDirectory)
                    : replayTranscript ? client.loadSession(restoredSessionId, workingDirectory)
                            : client.resumeSession(restoredSessionId, workingDirectory);
            return operation.thenApply(result -> new SessionSwitch(client.sessionId()));
        });
    }

}
