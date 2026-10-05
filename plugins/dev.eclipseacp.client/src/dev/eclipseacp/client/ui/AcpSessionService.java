package dev.eclipseacp.client.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

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
    private static final int MAX_SESSION_LIST_PAGES = 100;
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

    private final IPreferenceStore preferences;
    private final Consumer<Runnable> ui;
    private final Presentation presentation;
    private final Function<ChatSessionModel, AgentListener> listeners;
    private final BiFunction<ChatSessionModel, AgentListener, AgentClient> clients;
    /** Owns calls that can start a process, access files, or enqueue ACP output. */
    private final Executor io;
    private final ExecutorService shutdownableIo;
    private final AtomicBoolean acceptingOperations = new AtomicBoolean(true);
    private final Set<CompletableFuture<?>> cleanupOperations = ConcurrentHashMap.newKeySet();
    private final Map<ChatSessionModel, Set<CompletableFuture<?>>> sessionOperations =
            new ConcurrentHashMap<>();
    private volatile CompletableFuture<Void> shutdownCompletion = CompletableFuture.completedFuture(null);
    private final List<ChatSessionModel> sessions = new ArrayList<>();
    private ChatSessionModel activeSession;

    AcpSessionService(Consumer<Runnable> ui, Presentation presentation,
            Function<ChatSessionModel, AgentListener> listeners) {
        this(AcpPreferences.store(), ui, presentation, listeners,
                (session, listener) -> AcpClientFactory.create(session.provider, listener,
                        List.of()));
    }

    AcpSessionService(IPreferenceStore preferences, Consumer<Runnable> ui, Presentation presentation,
            Function<ChatSessionModel, AgentListener> listeners,
            BiFunction<ChatSessionModel, AgentListener, AgentClient> clients) {
        this(preferences, ui, presentation, listeners, clients, newIoExecutor());
    }

    AcpSessionService(IPreferenceStore preferences, Consumer<Runnable> ui, Presentation presentation,
            Function<ChatSessionModel, AgentListener> listeners,
            BiFunction<ChatSessionModel, AgentListener, AgentClient> clients, Executor io) {
        this.preferences = preferences;
        this.ui = ui;
        this.presentation = presentation;
        this.listeners = listeners;
        this.clients = clients;
        this.io = io;
        this.shutdownableIo = io instanceof ExecutorService service ? service : null;
    }

    SessionConfiguration newSessionConfiguration() {
        // Preferences may change while the view is open; read the provider for each new session.
        AgentProvider provider = new AgentProviderRegistry(preferences).active();
        return new SessionConfiguration(provider,
                preferences.getBoolean(AcpPreferences.HIDE_AGENT_COMMANDS_IN_CHAT));
    }

    List<ChatSessionModel> sessions() { return List.copyOf(sessions); }
    ChatSessionModel activeSession() { return activeSession; }
    boolean contains(ChatSessionModel session) { return sessions.contains(session); }

    void openSessionFor(IProject project, String initialPrompt) {
        if (!acceptingOperations.get()) return;
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
        CompletableFuture<Void> connection = io(session, client, () -> restoredSessionId == null
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
        if (!acceptingOperations.get()) return;
        ChatSessionModel current = activeSession;
        if (current == null || !current.isConnected() || current.isBusy()) return;
        SessionConfiguration configuration = newSessionConfiguration();
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
        io(session, client, () -> client.startNewSession(directory(session), listeners.apply(session)))
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
        if (!acceptingOperations.get()) return;
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
            runCleanup(previousClient::close)
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
        io(session, client, () -> client.prompt(expanded, attachments)).whenComplete((ignored, failure) -> ui.accept(() -> {
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
        if (!acceptingOperations.get()) return;
        if (activeSession == null || !activeSession.isConnected()) return;
        ChatSessionModel session = activeSession;
        AgentClient client = session.client;
        runIo(session, client, () -> {
            try {
                client.cancel();
            } catch (IOException exception) {
                throw new IoOperationException(exception);
            }
        }).whenComplete((ignored, failure) -> ui.accept(() -> {
            if (session.client == client && failure != null) {
                error(session, "Could not cancel the current prompt", unwrap(failure));
            }
        }));
    }

    void changeConfigOption(ChatSessionModel session, ConfigOption option, Object value, String success, String failure) {
        if (!acceptingOperations.get()) return;
        if (session == null || !session.isConnected()) return;
        AgentClient client = session.client;
        io(session, client, () -> client.setConfigOption(option.id(), ConfigValue.of(value)))
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
        io(session, client, () -> listSessions(client, directory(session), () -> isOwned(session, client)))
                .whenComplete((available, failure) -> ui.accept(() -> {
            if (session.client != client) return;
            if (failure != null) {
                error(session, "Could not list agent sessions", unwrap(failure));
            } else {
                session.savedSessions = available.stream()
                        .filter(info -> belongsToProject(info, directory(session)))
                        .toList();
                session.savedSessions.stream()
                        .filter(info -> info.id().equals(session.sessionId) && !info.title().isBlank())
                        .findFirst()
                        .ifPresent(info -> session.sessionName = info.title());
                changed(session);
            }
        }));
    }

    CompletableFuture<List<SessionInfo>> listSessions(AgentClient client, Path workingDirectory) {
        return listSessions(client, workingDirectory, acceptingOperations::get);
    }

    private CompletableFuture<List<SessionInfo>> listSessions(AgentClient client, Path workingDirectory,
            BooleanSupplier ownership) {
        return listSessions(client, workingDirectory, null, new ArrayList<>(), new HashSet<>(), 0, ownership);
    }

    private CompletableFuture<List<SessionInfo>> listSessions(AgentClient client, Path workingDirectory,
            String cursor, List<SessionInfo> collected, Set<String> seenCursors, int pageCount,
            BooleanSupplier ownership) {
        if (pageCount >= MAX_SESSION_LIST_PAGES) {
            return CompletableFuture.failedFuture(new IllegalStateException("ACP session list exceeded "
                    + MAX_SESSION_LIST_PAGES + " pages"));
        }
        if (cursor != null && !cursor.isBlank() && !seenCursors.add(cursor)) {
            return CompletableFuture.failedFuture(new IllegalStateException("ACP session list repeated cursor: " + cursor));
        }
        return client.listSessions(workingDirectory, cursor).thenCompose(page -> {
            collected.addAll(page.sessions());
            return page.nextCursor() == null || page.nextCursor().isBlank()
                    ? CompletableFuture.completedFuture(List.copyOf(collected))
                    : io(ownership, () -> listSessions(client, workingDirectory, page.nextCursor(), collected,
                            seenCursors, pageCount + 1, ownership));
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
        Set<CompletableFuture<?>> operations = sessionOperations.remove(session);
        if (operations != null) operations.forEach(operation -> operation.cancel(true));
        return client == null ? CompletableFuture.completedFuture(null) : runCleanup(client::close);
    }

    void disconnect() {
        if (!acceptingOperations.compareAndSet(true, false)) return;
        List<CompletableFuture<Void>> cleanup = sessions.stream().map(this::retire).toList();
        sessions.clear();
        activeSession = null;
        List<CompletableFuture<?>> allCleanup = new ArrayList<>(cleanupOperations);
        allCleanup.addAll(cleanup);
        shutdownCompletion = CompletableFuture.allOf(allCleanup.toArray(CompletableFuture[]::new))
                .handle((ignored, failure) -> null);
        if (shutdownableIo != null) shutdownableIo.shutdown();
    }

    CompletableFuture<Void> shutdownCompletion() {
        return shutdownCompletion;
    }

    private boolean isOwned(ChatSessionModel session, AgentClient client) {
        return acceptingOperations.get() && session.client == client;
    }

    private <T> CompletableFuture<T> io(ChatSessionModel session, AgentClient client,
            Supplier<CompletableFuture<T>> operation) {
        CompletableFuture<T> result = io(() -> isOwned(session, client), operation);
        sessionOperations.computeIfAbsent(session, ignored -> ConcurrentHashMap.newKeySet()).add(result);
        result.whenComplete((ignored, failure) -> {
            Set<CompletableFuture<?>> operations = sessionOperations.get(session);
            if (operations != null) {
                operations.remove(result);
                if (operations.isEmpty()) sessionOperations.remove(session, operations);
            }
        });
        return result;
    }

    private <T> CompletableFuture<T> io(BooleanSupplier ownership,
            Supplier<CompletableFuture<T>> operation) {
        CompletableFuture<T> result = new CompletableFuture<>();
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
        return result;
    }

    private CompletableFuture<Void> runIo(ChatSessionModel session, AgentClient client, Runnable operation) {
        return io(session, client, () -> {
            operation.run();
            return CompletableFuture.completedFuture(null);
        });
    }

    private CompletableFuture<Void> runCleanup(Runnable operation) {
        CompletableFuture<Void> result = io(() -> true, () -> {
            operation.run();
            return CompletableFuture.completedFuture(null);
        });
        cleanupOperations.add(result);
        result.whenComplete((ignored, failure) -> cleanupOperations.remove(result));
        return result;
    }

    private static ExecutorService newIoExecutor() {
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "eclipse-acp-io");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(threads);
    }

    private static final class IoOperationException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        IoOperationException(IOException cause) { super(cause); }
    }

    private static Path directory(ChatSessionModel session) {
        return session.project.getLocation().toFile().toPath();
    }

    private static Throwable unwrap(Throwable error) {
        return error.getCause() == null ? error : error.getCause();
    }
}
