package dev.eclipseacp.client.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.core.resources.IProject;
import org.eclipse.jface.preference.IPreferenceStore;

import dev.eclipseacp.client.acp.AcpClientFactory;
import dev.eclipseacp.client.agent.AgentClient;
import dev.eclipseacp.client.agent.AgentListener;
import dev.eclipseacp.client.agent.AgentProvider;
import dev.eclipseacp.client.agent.ConfigOption;
import dev.eclipseacp.client.agent.ConfigValue;
import dev.eclipseacp.client.agent.PromptAttachment;
import dev.eclipseacp.client.agent.SessionInfo;
import dev.eclipseacp.client.preferences.AcpPreferences;
import dev.eclipseacp.client.preferences.AgentProviderRegistry;

/** Owns project chats and agent lifecycles. State and presentation callbacks run on the UI executor. */
final class AcpSessionService {
    record SessionConfiguration(AgentProvider provider, boolean reviewFileChanges, boolean hideAgentCommands) { }

    interface Presentation {
        void selected(ChatSessionModel session);
        void changed(ChatSessionModel session);
        void statusChanged(ChatSessionModel session);
        void transcriptChanged(ChatSessionModel session);
        void inputReady(ChatSessionModel session, String text);
    }

    private final IPreferenceStore preferences;
    private final Consumer<Runnable> ui;
    private final Presentation presentation;
    private final Function<ChatSessionModel, AgentListener> listeners;
    private final BiFunction<ChatSessionModel, AgentListener, AgentClient> clients;
    private final List<ChatSessionModel> sessions = new ArrayList<>();
    private ChatSessionModel activeSession;

    AcpSessionService(Consumer<Runnable> ui, Presentation presentation,
            Function<ChatSessionModel, AgentListener> listeners) {
        this(AcpPreferences.store(), ui, presentation, listeners,
                (session, listener) -> AcpClientFactory.create(session.provider, listener,
                        session.reviewFileChanges, List.of()));
    }

    AcpSessionService(IPreferenceStore preferences, Consumer<Runnable> ui, Presentation presentation,
            Function<ChatSessionModel, AgentListener> listeners,
            BiFunction<ChatSessionModel, AgentListener, AgentClient> clients) {
        this.preferences = preferences;
        this.ui = ui;
        this.presentation = presentation;
        this.listeners = listeners;
        this.clients = clients;
    }

    SessionConfiguration newSessionConfiguration() {
        // Preferences may change while the view is open; read the provider for each new session.
        AgentProvider provider = new AgentProviderRegistry(preferences).active();
        return new SessionConfiguration(provider, preferences.getBoolean(AcpPreferences.REVIEW_FILE_CHANGES),
                preferences.getBoolean(AcpPreferences.HIDE_AGENT_COMMANDS_IN_CHAT));
    }

    List<ChatSessionModel> sessions() { return List.copyOf(sessions); }
    ChatSessionModel activeSession() { return activeSession; }
    boolean contains(ChatSessionModel session) { return sessions.contains(session); }

    void openSessionFor(IProject project, String initialPrompt) {
        if (project == null || !project.exists() || !project.isOpen() || project.getLocation() == null) {
            error(activeSession, "Cannot open ACP session",
                    new IllegalArgumentException("The selected project is not open"));
            return;
        }
        ChatSessionModel existing = sessionFor(project);
        if (existing != null) {
            if (existing.isConnected()) {
                select(existing);
                if (initialPrompt != null && !initialPrompt.isBlank()) {
                    presentation.inputReady(existing, initialPrompt);
                }
                return;
            }
            // Opening a failed session again is an explicit retry.
            close(existing);
        }
        ChatSessionModel session = newSession(project, newSessionConfiguration());
        session.initialPrompt = initialPrompt;
        session.statusText = "Connecting to " + session.provider.name() + " in " + project.getLocation() + "…"
                + (session.reviewFileChanges ? " Changes will be reviewed before applying." : " Changes apply immediately.");
        replace(session);
        connect(session, null);
    }

    private ChatSessionModel newSession(IProject project, SessionConfiguration configuration) {
        return new ChatSessionModel(project, project.getName(), configuration.provider(),
                configuration.reviewFileChanges(), configuration.hideAgentCommands());
    }

    private ChatSessionModel sessionFor(IProject project) {
        return sessions.stream().filter(session -> session.project.equals(project)).findFirst().orElse(null);
    }

    private void replace(ChatSessionModel replacement) {
        ChatSessionModel previous = sessionFor(replacement.project);
        if (previous == null) {
            sessions.add(replacement);
        } else {
            int index = sessions.indexOf(previous);
            retire(previous);
            sessions.set(index, replacement);
        }
        select(replacement);
    }

    void select(ChatSessionModel session) {
        if (session != null && !contains(session)) return;
        activeSession = session;
        presentation.selected(session);
    }

    private void connect(ChatSessionModel session, String restoredSessionId) {
        AgentClient client = clients.apply(session, listeners.apply(session));
        session.client = client;
        session.sessionTransitioning = true;
        changed(session);
        CompletableFuture<Void> connection = restoredSessionId == null
                ? client.connect(directory(session)) : client.restoreSession(restoredSessionId, directory(session));
        connection.whenComplete((ignored, failure) -> ui.accept(() -> {
            if (session.client != client) return;
            session.sessionTransitioning = false;
            if (failure != null) {
                retire(session);
                error(session, "Could not start " + session.provider.name(), unwrap(failure));
                return;
            }
            if (restoredSessionId != null) session.statusText = "Session restored";
            connected(session);
        }));
    }

    void newSession(String pendingInputText) {
        ChatSessionModel current = activeSession;
        if (current == null || !current.isConnected() || current.isBusy()) return;
        SessionConfiguration configuration = newSessionConfiguration();
        ChatSessionModel session = newSession(current.project, configuration);
        session.pendingInputText = pendingInputText;
        session.statusText = "Connecting to " + session.provider.name() + " in " + current.project.getLocation() + "…";
        boolean reusable = current.provider.equals(session.provider)
                && current.reviewFileChanges == session.reviewFileChanges;
        if (!reusable) {
            replace(session);
            connect(session, null);
            return;
        }
        AgentClient client = current.client;
        current.client = null; // Transfer ownership without closing the initialized connection.
        session.client = client;
        session.sessionTransitioning = true;
        replace(session);
        client.startNewSession(directory(session), listeners.apply(session)).whenComplete((ignored, failure) -> ui.accept(() -> {
            if (session.client != client) return;
            session.sessionTransitioning = false;
            if (failure != null) {
                retire(session);
                error(session, "Could not create a new ACP session", unwrap(failure));
            } else {
                session.statusText = "Connected";
                connected(session);
            }
        }));
    }

    void restore(SessionInfo selected) {
        ChatSessionModel current = activeSession;
        if (current == null || !current.isConnected() || current.isBusy()) return;
        ChatSessionModel restored = new ChatSessionModel(current.project, current.label,
                current.provider, current.reviewFileChanges, current.hideAgentCommands);
        restored.sessionName = selected.title().isBlank() ? selected.id() : selected.title();
        // session/load may replay messages before its response completes.
        restored.acceptingRestoredTranscript = current.client.capabilities().loadSession();
        restored.statusText = "Restoring session with " + current.provider.name() + "…";
        replace(restored);
        connect(restored, selected.id());
    }

    private void connected(ChatSessionModel session) {
        changed(session);
        if (session.canListSessions()) loadAgentSessions(session);
        presentation.inputReady(session, session.initialPrompt);
        session.initialPrompt = null;
    }

    void sendPrompt(ChatSessionModel session, String expanded) {
        if (session == null || !session.isConnected() || session.isBusy() || expanded == null || expanded.isBlank()) return;
        session.beginPrompt(expanded);
        List<PromptAttachment> attachments = List.copyOf(session.attachments);
        session.attachments.clear();
        if (!attachments.isEmpty()) {
            session.append("> Attached: " + attachments.stream().map(item -> "`" + item.path().getFileName() + "`")
                    .collect(java.util.stream.Collectors.joining(", ")) + "\n\n");
        }
        transcriptChanged(session);
        changed(session);
        AgentClient client = session.client;
        client.prompt(expanded, attachments).whenComplete((ignored, failure) -> ui.accept(() -> {
            if (session.client != client) return;
            if (failure != null) {
                session.attachments.addAll(attachments);
                error(session, "Prompt failed", unwrap(failure));
            } else if (session.agentMessageOpen) {
                append(session, "\n\n");
            }
            session.agentMessageOpen = false;
            changed(session);
        }));
    }

    void cancel() {
        if (activeSession == null || !activeSession.isConnected()) return;
        try {
            activeSession.client.cancel();
        } catch (IOException exception) {
            error(activeSession, "Could not cancel the current prompt", exception);
        }
    }

    void changeConfigOption(ChatSessionModel session, ConfigOption option, Object value, String success, String failure) {
        if (session == null || !session.isConnected()) return;
        AgentClient client = session.client;
        client.setConfigOption(option.id(), ConfigValue.of(value)).whenComplete((ignored, cause) -> ui.accept(() -> {
            if (session.client != client) return;
            if (cause != null) {
                error(session, failure, unwrap(cause));
            } else {
                session.configOptions.put(option.id(), new ConfigOption(option.id(), option.name(), option.description(),
                        option.category(), ConfigValue.of(value), option.choices()));
                setStatus(session, success);
                changed(session);
            }
        }));
    }

    private void loadAgentSessions(ChatSessionModel session) {
        AgentClient client = session.client;
        listSessions(client, directory(session)).whenComplete((available, failure) -> ui.accept(() -> {
            if (session.client != client) return;
            if (failure != null) {
                error(session, "Could not list agent sessions", unwrap(failure));
            } else {
                session.savedSessions = available.stream().filter(info -> belongsToProject(info, directory(session))).toList();
                changed(session);
            }
        }));
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
            return Path.of(session.cwd()).toAbsolutePath().normalize().equals(projectDirectory.toAbsolutePath().normalize());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    void append(ChatSessionModel session, String text) {
        session.append(text);
        transcriptChanged(session);
    }

    void transcriptChanged(ChatSessionModel session) { presentation.transcriptChanged(session); }
    void changed(ChatSessionModel session) { presentation.changed(session); }

    void setStatus(ChatSessionModel session, String value) {
        if (session == null) return;
        session.statusText = value == null || value.isBlank() ? "Not connected" : value;
        presentation.statusChanged(session);
    }

    void error(ChatSessionModel session, String message, Throwable error) {
        if (session == null) return;
        String detail = error == null || error.getMessage() == null ? "" : ": " + error.getMessage();
        append(session, "\n> **Error:** " + message + detail + "\n\n");
        setStatus(session, "Error");
        changed(session);
    }

    void close(ChatSessionModel session) {
        int index = sessions.indexOf(session);
        if (index < 0) return;
        retire(session);
        sessions.remove(index);
        if (activeSession == session) {
            select(sessions.isEmpty() ? null : sessions.get(Math.min(index, sessions.size() - 1)));
        }
    }

    /** session/close lets the agent durably store the conversation. */
    private void retire(ChatSessionModel session) {
        AgentClient client = session.client;
        session.client = null;
        session.sessionTransitioning = false;
        if (client != null) CompletableFuture.runAsync(client::close);
    }

    void disconnect() {
        sessions.forEach(this::retire);
        sessions.clear();
        activeSession = null;
    }

    private static Path directory(ChatSessionModel session) {
        return session.project.getLocation().toFile().toPath();
    }

    private static Throwable unwrap(Throwable error) {
        return error.getCause() == null ? error : error.getCause();
    }
}
