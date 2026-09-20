package dev.eclipseacp.client.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import dev.eclipseacp.client.agent.AgentClient;
import dev.eclipseacp.client.agent.AgentProvider;
import dev.eclipseacp.client.agent.ConfigOption;
import dev.eclipseacp.client.agent.PromptAttachment;
import dev.eclipseacp.client.agent.SessionInfo;
import dev.eclipseacp.client.agent.ToolCall;

/** Mutable state and transcript transitions for one project chat, independent of SWT widgets. */
final class ChatSessionModel {
    final IProject project;
    final String label;
    final AgentProvider provider;
    final boolean reviewFileChanges;
    final boolean hideAgentCommands;
    final StringBuilder transcriptMarkdown = new StringBuilder();
    final StringBuilder pendingAgentText = new StringBuilder();
    boolean agentRenderScheduled;
    long firstAgentChunkSentAtNanos;
    long firstAgentChunkReceivedAtNanos;
    AgentClient client;
    boolean agentMessageOpen;
    boolean sessionTransitioning;
    boolean acceptingRestoredTranscript;
    boolean restoredAgentMessageOpen;
    final Map<String, ToolCall> toolCalls = new LinkedHashMap<>();
    final Map<String, List<dev.eclipseacp.client.agent.FileDiff>> renderedToolDiffs = new LinkedHashMap<>();
    final WorkspaceFileLinks fileLinks;
    final ChangeReviewService changes;
    List<SessionInfo> savedSessions = List.of();
    final List<PromptAttachment> attachments = new ArrayList<>();
    final Map<String, String> commands = new LinkedHashMap<>();
    final Map<String, ConfigOption> configOptions = new LinkedHashMap<>();
    String sessionName = "New session";
    String pendingInputText;
    String initialPrompt;
    String statusText = "Not connected";

    ChatSessionModel(IProject project, String label, AgentProvider provider, boolean reviewFileChanges,
            boolean hideAgentCommands) {
        this.project = project;
        this.label = label;
        this.provider = provider;
        this.reviewFileChanges = reviewFileChanges;
        this.hideAgentCommands = hideAgentCommands;
        this.fileLinks = new WorkspaceFileLinks(project);
        this.changes = new ChangeReviewService(project);
    }

    void append(String text) {
        transcriptMarkdown.append(text);
    }

    void appendRestoredUserText(String text) {
        if (!acceptingRestoredTranscript || text.isEmpty()) return;
        append("## You\n\n" + text + "\n\n");
        restoredAgentMessageOpen = false;
    }

    void appendAgentText(String text) {
        if (text.isEmpty()) return;
        if (acceptingRestoredTranscript && !restoredAgentMessageOpen) {
            append("## Agent\n\n");
            restoredAgentMessageOpen = true;
        }
        append(text);
    }

    void beginPrompt(String text) {
        acceptingRestoredTranscript = false;
        restoredAgentMessageOpen = false;
        append("## You\n\n" + text + "\n\n## Agent\n\n");
        agentMessageOpen = true;
    }

    boolean canListSessions() {
        return client != null && client.capabilities().sessionList()
                && (client.capabilities().sessionResume() || client.capabilities().loadSession());
    }

    boolean isConnected() { return client != null; }
    boolean isBusy() { return isConnected() && (agentMessageOpen || sessionTransitioning); }
}
