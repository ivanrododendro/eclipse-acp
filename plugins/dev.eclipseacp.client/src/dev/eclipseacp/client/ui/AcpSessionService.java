package dev.eclipseacp.client.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;

import dev.eclipseacp.client.agent.AgentClient;
import dev.eclipseacp.client.agent.AgentListener;
import dev.eclipseacp.client.agent.AgentProvider;
import dev.eclipseacp.client.agent.ConfigOption;
import dev.eclipseacp.client.agent.ConfigValue;
import dev.eclipseacp.client.agent.PromptAttachment;
import dev.eclipseacp.client.agent.SessionInfo;

/** Owns project chats and agent lifecycles. State and presentation callbacks run on the UI executor. */
final class AcpSessionService {
    record SessionConfiguration(AgentProvider provider, boolean hideAgentCommands) { }

    interface Presentation {
        void selected(ChatSessionModel session);
        void changed(ChatSessionModel session);
        void statusChanged(ChatSessionModel session);
        void transcriptChanged(ChatSessionModel session);
        void transcriptReloaded(ChatSessionModel session);
        void inputReady(ChatSessionModel session, String text);
        void draftReady(ChatSessionModel session, String text);
    }

    private final Supplier<SessionConfiguration> configurations;
    private final Consumer<Runnable> ui;
    private final Presentation presentation;
    private final Function<ChatSessionModel, AgentListener> listeners;
    private final BiFunction<ChatSessionModel, AgentListener, AgentClient> clients;
    private final SessionOperationScope operations;
    private final AgentSessionHistory history;
    private final List<ChatSessionModel> sessions = new ArrayList<>();
    private ChatSessionModel activeSession;

    AcpSessionService(Supplier<SessionConfiguration> configurations, Consumer<Runnable> ui,
            Presentation presentation, Function<ChatSessionModel, AgentListener> listeners,
            BiFunction<ChatSessionModel, AgentListener, AgentClient> clients, SessionOperationScope operations) {
        this.configurations = configurations;
        this.ui = ui;
        this.presentation = presentation;
        this.listeners = listeners;
        this.clients = clients;
        this.operations = operations;
        this.history = new AgentSessionHistory(operations);
    }

    List<ChatSessionModel> sessions() { return List.copyOf(sessions); }
    ChatSessionModel activeSession() { return activeSession; }
    boolean contains(ChatSessionModel session) { return sessions.contains(session); }

    void openSessionFor(IProject project, String initialPrompt) {
        if (!operations.isAccepting()) return;
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
        ChatSessionModel session = newSession(project, configurations.get());
        session.initialPrompt = initialPrompt;
        session.statusText = "Connecting to " + session.provider.name() + " in " + project.getLocation() + "…";
        replace(session);
        connect(session, null);
    }

    private ChatSessionModel newSession(IProject project, SessionConfiguration configuration) {
        return new ChatSessionModel(project, project.getName(), configuration.provider(),
                configuration.hideAgentCommands());
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
        CompletableFuture<Void> connection = operations.run(session, () -> isOwned(session, client), () -> restoredSessionId == null
                ? client.connect(directory(session)) : client.restoreSession(restoredSessionId, directory(session)));
        connection.whenComplete((ignored, failure) -> ui.accept(() -> {
            if (session.client != client) return;
            session.sessionTransitioning = false;
            if (failure != null) {
                retire(session);
                error(session, "Could not start " + session.provider.name(), unwrap(failure));
                return;
            }
            if (restoredSessionId != null) {
                session.statusText = "Session restored";
                // session/load replays messages while the Browser may still be installing its
                // initial document. Rebuild it once the replay response has completed.
                presentation.transcriptReloaded(session);
            }
            connected(session);
        }));
    }

    void newSession(String pendingInputText) {
        newSession(pendingInputText, null);
    }

    /** Starts a new session and sends the supplied prompt as soon as it is connected. */
    void newSessionWithInitialPrompt(String initialPrompt) {
        newSession(null, initialPrompt);
    }

    private void newSession(String pendingInputText, String initialPrompt) {
        if (!operations.isAccepting()) return;
        ChatSessionModel current = activeSession;
        if (current == null || !current.isConnected() || current.isBusy()) return;
        SessionConfiguration configuration = configurations.get();
        ChatSessionModel session = newSession(current.project, configuration);
        session.pendingInputText = pendingInputText;
        session.initialPrompt = initialPrompt;
        session.statusText = "Connecting to " + session.provider.name() + " in " + current.project.getLocation() + "…";
        boolean reusable = current.provider.equals(session.provider);
        if (reusable) session.savedSessions = current.savedSessions;
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
        operations.run(session, () -> isOwned(session, client), () -> client.startNewSession(directory(session), listeners.apply(session)))
                .whenComplete((ignored, failure) -> ui.accept(() -> {
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
        if (!operations.isAccepting()) return;
        ChatSessionModel current = activeSession;
        if (current == null || current.isBusy() || selected == null) return;
        ChatSessionModel restored = new ChatSessionModel(current.project, current.label,
                current.provider, current.hideAgentCommands);
        restored.savedSessions = current.savedSessions;
        restored.sessionId = selected.id();
        restored.sessionName = selected.title().isBlank() ? selected.id() : selected.title();
        // session/load may replay messages before its response completes.
        restored.loadsSessionTranscript = current.loadsSessionTranscript;
        restored.acceptingRestoredTranscript = current.loadsSessionTranscript;
        restored.sessionTransitioning = true;
        restored.statusText = "Restoring session with " + current.provider.name() + "…";
        AgentClient previousClient = current.client;
        current.client = null;
        replace(restored);
        if (previousClient == null) {
            connect(restored, selected.id());
        } else {
            // Do not let two agent processes own the same persisted session concurrently.
            operations.cleanup(previousClient::close)
                    .whenComplete((ignored, failure) -> ui.accept(() -> {
                        if (contains(restored)) connect(restored, selected.id());
                    }));
        }
    }

    private void connected(ChatSessionModel session) {
        String connectedSessionId = session.client.sessionId();
        if (connectedSessionId != null && !connectedSessionId.isBlank()) session.sessionId = connectedSessionId;
        session.loadsSessionTranscript = session.client.capabilities().loadSession();
        changed(session);
        if (session.canListSessions()) loadAgentSessions(session);
        presentation.inputReady(session, session.initialPrompt);
        session.initialPrompt = null;
    }

    void sendPrompt(ChatSessionModel session, String expanded) {
        sendPrompt(session, expanded, expanded);
    }

    /**
     * Sends the expanded ACP prompt while retaining the editor text for a retry if it fails.
     */
    void sendPrompt(ChatSessionModel session, String draft, String expanded) {
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
        operations.run(session, () -> isOwned(session, client), () -> client.prompt(expanded, attachments))
                .whenComplete((ignored, failure) -> ui.accept(() -> {
            if (session.client != client) return;
            if (failure != null) {
                session.attachments.addAll(attachments);
                session.promptDraft = draft == null ? "" : draft;
                error(session, "Prompt failed", unwrap(failure));
                presentation.draftReady(session, session.promptDraft);
            } else if (session.agentMessageOpen) {
                append(session, "\n\n");
            }
            session.agentMessageOpen = false;
            changed(session);
            if (failure == null && session.canListSessions()) loadAgentSessions(session);
        }));
    }

    void cancel() {
        if (!operations.isAccepting()) return;
        if (activeSession == null || !activeSession.isConnected()) return;
        ChatSessionModel session = activeSession;
        AgentClient client = session.client;
        operations.run(session, () -> isOwned(session, client), () -> {
            try {
                client.cancel();
            } catch (IOException exception) {
                return CompletableFuture.failedFuture(exception);
            }
            return CompletableFuture.<Void>completedFuture(null);
        }).whenComplete((ignored, failure) -> ui.accept(() -> {
            if (session.client == client && failure != null) {
                error(session, "Could not cancel the current prompt", unwrap(failure));
            }
        }));
    }

    void changeConfigOption(ChatSessionModel session, ConfigOption option, Object value, String success, String failure) {
        if (!operations.isAccepting()) return;
        if (session == null || !session.isConnected()) return;
        AgentClient client = session.client;
        operations.run(session, () -> isOwned(session, client), () -> client.setConfigOption(option.id(), ConfigValue.of(value)))
                .whenComplete((ignored, cause) -> ui.accept(() -> {
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
        history.load(session, client, directory(session), () -> isOwned(session, client))
                .whenComplete((available, failure) -> ui.accept(() -> {
            if (session.client != client) return;
            if (failure != null) {
                error(session, "Could not list agent sessions", unwrap(failure));
            } else {
                session.savedSessions = available;
                session.savedSessions.stream()
                        .filter(info -> info.id().equals(session.sessionId) && !info.title().isBlank())
                        .findFirst()
                        .ifPresent(info -> session.sessionName = info.title());
                changed(session);
            }
        }));
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

    /** The terminal transport callback invalidates the client so the chat can be explicitly retried. */
    void connectionClosed(ChatSessionModel session, String message, Throwable error) {
        if (session == null || session.client == null) return;
        retire(session);
        error(session, message, error);
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
    private CompletableFuture<Void> retire(ChatSessionModel session) {
        AgentClient client = session.client;
        session.client = null;
        session.sessionTransitioning = false;
        session.fileLinks.close();
        operations.cancel(session);
        return client == null ? CompletableFuture.completedFuture(null) : operations.cleanup(client::close);
    }

    void disconnect() {
        if (!operations.stopAccepting()) return;
        sessions.forEach(this::retire);
        sessions.clear();
        activeSession = null;
        operations.shutdown();
    }

    CompletableFuture<Void> shutdownCompletion() {
        return operations.shutdownCompletion();
    }

    private boolean isOwned(ChatSessionModel session, AgentClient client) {
        return operations.isAccepting() && session.client == client;
    }

    private static Path directory(ChatSessionModel session) {
        return session.project.getLocation().toFile().toPath();
    }

    private static Throwable unwrap(Throwable error) {
        return error.getCause() == null ? error : error.getCause();
    }
}
