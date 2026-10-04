package dev.eclipseacp.client.ui;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import dev.eclipseacp.client.AcpLog;
import dev.eclipseacp.client.agent.AgentCommand;
import dev.eclipseacp.client.agent.AgentListener;
import dev.eclipseacp.client.agent.AuthMethod;
import dev.eclipseacp.client.agent.ConfigOption;
import dev.eclipseacp.client.agent.ElicitationRequest;
import dev.eclipseacp.client.agent.PermissionOption;
import dev.eclipseacp.client.agent.PermissionRequest;
import dev.eclipseacp.client.agent.ToolCall;
import dev.eclipseacp.client.agent.Usage;

/** Updates one chat from agent events, batching streamed text on the UI executor. */
final class AcpChatSessionListener implements AgentListener {
    private final ChatSessionModel session;
    private final AcpSessionService sessions;
    private final AcpChatDialogs dialogs;
    private final Consumer<Runnable> ui;
    private final Consumer<Runnable> delayedUi;

    AcpChatSessionListener(ChatSessionModel session, AcpSessionService sessions, AcpChatDialogs dialogs,
            Consumer<Runnable> ui, Consumer<Runnable> delayedUi) {
        this.session = session;
        this.sessions = sessions;
        this.dialogs = dialogs;
        this.ui = ui;
        this.delayedUi = delayedUi;
    }

    private void dispatch(Runnable action) {
        ui.accept(() -> {
            if (sessions.contains(session)) action.run();
        });
    }

    private void dispatchAfterQueuedAgentText(Runnable action) {
        AgentTextBatch batch = takeQueuedAgentText();
        dispatch(() -> {
            renderAgentText(batch);
            action.run();
        });
    }

    private void append(String text) { sessions.append(session, text); }

    @Override public void onAgentText(String text) {
        if (text == null || text.isEmpty()) return;
        synchronized (session) {
            session.pendingAgentText.append(text);
            if (session.agentRenderScheduled) return;
            session.agentRenderScheduled = true;
        }
        dispatch(() -> delayedUi.accept(() -> {
            if (sessions.contains(session)) flushQueuedAgentText();
        }));
    }

    @Override public void onPromptFirstAgentChunk(long sent, long received) {
        synchronized (session) {
            session.firstAgentChunkSentAtNanos = sent;
            session.firstAgentChunkReceivedAtNanos = received;
        }
    }

    /** Runs on the UI thread after the batching interval or at prompt completion. */
    private void flushQueuedAgentText() {
        renderAgentText(takeQueuedAgentText());
    }

    private AgentTextBatch takeQueuedAgentText() {
        synchronized (session) {
            AgentTextBatch batch = new AgentTextBatch(session.pendingAgentText.toString(),
                    session.firstAgentChunkSentAtNanos, session.firstAgentChunkReceivedAtNanos);
            session.pendingAgentText.setLength(0);
            session.agentRenderScheduled = false;
            session.firstAgentChunkSentAtNanos = 0;
            session.firstAgentChunkReceivedAtNanos = 0;
            return batch;
        }
    }

    private void renderAgentText(AgentTextBatch batch) {
        if (!batch.text().isEmpty()) {
            session.appendAgentText(batch.text());
            sessions.transcriptChanged(session);
        }
        if (batch.receivedAt() != 0) {
            long uiAt = System.nanoTime();
            AcpLog.info("ACP first agent chunk rendered on SWT UI thread: session='" + session.label
                    + "', receiveToUiMs=" + elapsedMillis(batch.receivedAt(), uiAt)
                    + ", sendToUiMs=" + elapsedMillis(batch.sentAt(), uiAt));
        }
    }

    @Override public void onPromptCompleted(long sent, long completed) {
        dispatchAfterQueuedAgentText(() -> {
            append("\n> **Timing:** session/prompt completed in " + elapsedMillis(sent, completed) + " ms\n\n");
            session.agentMessageOpen = false;
            sessions.changed(session);
        });
    }

    @Override public void onUserText(String text) {
        dispatchAfterQueuedAgentText(() -> {
            session.appendRestoredUserText(text);
            sessions.transcriptChanged(session);
        });
    }

    @Override public void onStatus(String value) {
        dispatch(() -> sessions.setStatus(session, value));
    }

    @Override public void onError(String message, Throwable error) {
        dispatchAfterQueuedAgentText(() -> sessions.error(session, message, error));
    }

    @Override public void onConnectionClosed(String message, Throwable error) {
        dispatchAfterQueuedAgentText(() -> sessions.connectionClosed(session, message, error));
    }

    @Override public void onToolCall(ToolCall toolCall) {
        dispatchAfterQueuedAgentText(() -> {
            session.toolCalls.put(toolCall.id(), toolCall);
            if (session.hideAgentCommands) {
                sessions.setStatus(session, ChatMessageFormatter.toolCallStatus(toolCall));
            } else {
                appendNewToolDiffs(toolCall);
                append("\n> **Tool " + toolCall.kind() + ":** " + toolCall.title() + " — " + toolCall.status()
                        + (toolCall.hasDiffs() ? " (" + toolCall.diffs().size() + " file change(s))" : "")
                        + "\n\n");
                if (toolCall.kind().toLowerCase(java.util.Locale.ROOT).contains("terminal")) {
                    append(ChatMessageFormatter.terminalOutput(toolCall.terminalOutput()));
                }
            }
            sessions.changed(session);
        });
    }

    private void appendNewToolDiffs(ToolCall toolCall) {
        if (toolCall.diffs().isEmpty() || toolCall.diffs().equals(session.renderedToolDiffs.get(toolCall.id()))) return;
        session.renderedToolDiffs.put(toolCall.id(), List.copyOf(toolCall.diffs()));
        append("\n> **File changes:** " + toolCall.diffs().size() + " file change(s)\n"
                + ChatMessageFormatter.diffPreview(toolCall.diffs()) + "\n\n");
    }

    @Override public void onAvailableCommands(List<AgentCommand> commands) {
        dispatch(() -> {
            session.commands.clear();
            commands.forEach(command -> session.commands.put(command.name(), command.description()));
            sessions.setStatus(session, session.commands.isEmpty() ? "No slash commands"
                    : session.commands.size() + " slash command(s) available");
            sessions.changed(session);
        });
    }

    @Override public void onConfigOptions(List<ConfigOption> options) {
        dispatch(() -> {
            options.forEach(option -> session.configOptions.put(option.id(), option));
            sessions.changed(session);
        });
    }

    @Override public void onUsage(Usage usage) {
        dispatchAfterQueuedAgentText(() -> {
            session.usage = usage;
            if (!session.hideAgentCommands) append("> **Usage:** " + ChatMessageFormatter.usage(usage) + "\n\n");
            sessions.changed(session);
        });
    }

    @Override public void onTerminalOutput(String output) {
        dispatchAfterQueuedAgentText(() -> {
            if (!session.hideAgentCommands) append(ChatMessageFormatter.terminalOutput(output));
        });
    }

    @Override public CompletableFuture<String> requestPermission(String title, List<PermissionOption> options) {
        return dialogs.requestPermission(session.label, title, options);
    }

    @Override public CompletableFuture<String> requestPermission(PermissionRequest request) {
        return dialogs.requestPermission(session.label, request);
    }

    @Override public CompletableFuture<String> requestAuthentication(List<AuthMethod> methods) {
        return dialogs.requestAuthentication(session.label, methods);
    }

    @Override public CompletableFuture<String> requestElicitation(ElicitationRequest request) {
        return dialogs.requestElicitation(session.label, request);
    }

    private static long elapsedMillis(long startedAt, long completedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(completedAt - startedAt);
    }

    private record AgentTextBatch(String text, long sentAt, long receivedAt) { }
}
