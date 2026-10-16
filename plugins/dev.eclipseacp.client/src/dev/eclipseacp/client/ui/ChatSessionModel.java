package dev.eclipseacp.client.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IFile;

import dev.eclipseacp.client.agent.AgentClient;
import dev.eclipseacp.client.agent.AgentProvider;
import dev.eclipseacp.client.agent.ConfigOption;
import dev.eclipseacp.client.agent.PromptAttachment;
import dev.eclipseacp.client.agent.SessionInfo;
import dev.eclipseacp.client.agent.ToolCall;
import dev.eclipseacp.client.agent.Usage;

/** Mutable state and transcript transitions for one project chat, independent of SWT widgets. */
final class ChatSessionModel {
    final IProject project;
    final String label;
    final AgentProvider provider;
    final boolean hideAgentCommands;
    final StringBuilder transcriptMarkdown = new StringBuilder();
    /** Start of the message that may still change while agent text is streaming. */
    int liveMessageStart;
    final StringBuilder pendingAgentText = new StringBuilder();
    boolean agentRenderScheduled;
    long firstAgentChunkSentAtNanos;
    long firstAgentChunkReceivedAtNanos;
    volatile AgentClient client;
    boolean agentMessageOpen;
    boolean sessionTransitioning;
    /** The first prompt needs a complete Browser render after this session is selected. */
    boolean firstPromptNeedsTranscriptReload = true;
    boolean loadsSessionTranscript;
    boolean acceptingRestoredTranscript;
    boolean restoredAgentMessageOpen;
    final Map<String, ToolCall> toolCalls = new LinkedHashMap<>();
    private final Map<String, TranscriptFragment> renderedToolCalls = new LinkedHashMap<>();
    final WorkspaceFileLinks fileLinks;
    List<SessionInfo> savedSessions = List.of();
    final List<PromptAttachment> attachments = new ArrayList<>();
    /** Workspace source files explicitly included with the next prompt. */
    final List<IFile> sourceFiles = new ArrayList<>();
    final List<java.nio.file.Path> externalSourceFiles = new ArrayList<>();
    final Map<String, String> commands = new LinkedHashMap<>();
    final Map<String, ConfigOption> configOptions = new LinkedHashMap<>();
    String sessionId;
    String sessionName = "New session";
    /** Prompt text owned by this chat, rather than by the shared SWT editor. */
    String promptDraft = "";
    String pendingInputText;
    String initialPrompt;
    String statusText = "Not connected";
    Usage usage;

    ChatSessionModel(IProject project, String label, AgentProvider provider, boolean hideAgentCommands) {
        this.project = project;
        this.label = label;
        this.provider = provider;
        this.hideAgentCommands = hideAgentCommands;
        this.fileLinks = new WorkspaceFileLinks(project);
    }

    void append(String text) {
        transcriptMarkdown.append(text);
    }

    /** Replaces status updates for one tool call in place, keeping a single transcript component. */
    void upsertToolCall(String id, String markdown) {
        TranscriptFragment previous = renderedToolCalls.get(id);
        if (previous == null) {
            int start = transcriptMarkdown.length();
            append(markdown);
            renderedToolCalls.put(id, new TranscriptFragment(start, transcriptMarkdown.length()));
            return;
        }
        int oldEnd = previous.end;
        int delta = markdown.length() - (oldEnd - previous.start);
        transcriptMarkdown.replace(previous.start, oldEnd, markdown);
        previous.end = previous.start + markdown.length();
        if (delta == 0) return;
        for (TranscriptFragment fragment : renderedToolCalls.values()) {
            if (fragment != previous && fragment.start >= oldEnd) {
                fragment.start += delta;
                fragment.end += delta;
            }
        }
        if (liveMessageStart >= oldEnd) liveMessageStart += delta;
    }

    void appendRestoredUserText(String text) {
        if (!acceptingRestoredTranscript || text.isEmpty()) return;
        liveMessageStart = transcriptMarkdown.length();
        append("## You\n\n" + text + "\n\n");
        restoredAgentMessageOpen = false;
    }

    void appendAgentText(String text) {
        if (text.isEmpty()) return;
        if (acceptingRestoredTranscript && !restoredAgentMessageOpen) {
            liveMessageStart = transcriptMarkdown.length();
            append("## Agent\n\n");
            restoredAgentMessageOpen = true;
        }
        append(text);
    }

    void beginPrompt(String text) {
        beginPrompt(text, "");
    }

    void beginPrompt(String text, String userContextMarkdown) {
        acceptingRestoredTranscript = false;
        restoredAgentMessageOpen = false;
        append("## You\n\n" + text + "\n\n");
        if (userContextMarkdown != null && !userContextMarkdown.isBlank()) {
            append(userContextMarkdown);
            if (!userContextMarkdown.endsWith("\n\n")) append("\n\n");
        }
        liveMessageStart = transcriptMarkdown.length();
        append("## Agent\n\n");
        agentMessageOpen = true;
    }

    boolean canListSessions() {
        return client != null && client.capabilities().sessionList()
                && (client.capabilities().sessionResume() || client.capabilities().loadSession());
    }

    boolean canRestoreSavedSessions() {
        return !savedSessions.isEmpty() && (!isConnected() || canListSessions());
    }

    boolean isConnected() { return client != null; }
    boolean isBusy() { return agentMessageOpen || sessionTransitioning; }

    private static final class TranscriptFragment {
        private int start;
        private int end;

        private TranscriptFragment(int start, int end) {
            this.start = start;
            this.end = end;
        }
    }
}
