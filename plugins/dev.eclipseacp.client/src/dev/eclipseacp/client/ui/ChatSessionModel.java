package dev.eclipseacp.client.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;

import dev.eclipseacp.client.agent.AgentClient;
import dev.eclipseacp.client.agent.ConfigOption;
import dev.eclipseacp.client.agent.PromptAttachment;
import dev.eclipseacp.client.agent.ToolCall;

/** Mutable state owned by one project chat. SWT widgets deliberately stay in the view. */
final class ChatSessionModel {
    final IProject project;
    final String label;
    final boolean reviewFileChanges;
    final boolean hideAgentCommands;
    final StringBuilder transcriptMarkdown = new StringBuilder();
    final StringBuilder pendingAgentText = new StringBuilder();
    boolean agentRenderScheduled;
    long firstAgentChunkSentAtNanos;
    long firstAgentChunkReceivedAtNanos;
    AgentClient client;
    boolean agentMessageOpen;
    boolean acceptingRestoredTranscript;
    boolean restoredAgentMessageOpen;
    final Map<String, ToolCall> toolCalls = new LinkedHashMap<>();
    final Map<String, List<dev.eclipseacp.client.agent.FileDiff>> renderedToolDiffs = new LinkedHashMap<>();
    final WorkspaceFileLinks fileLinks;
    ChangeReviewService changes;
    final List<PromptAttachment> attachments = new ArrayList<>();
    final Map<String, String> commands = new LinkedHashMap<>();
    final Map<String, ConfigOption> configOptions = new LinkedHashMap<>();
    boolean switching;
    String initialPrompt;
    String statusText = "Not connected";

    ChatSessionModel(IProject project, String label, boolean reviewFileChanges, boolean hideAgentCommands) {
        this.project = project;
        this.label = label;
        this.reviewFileChanges = reviewFileChanges;
        this.hideAgentCommands = hideAgentCommands;
        this.fileLinks = new WorkspaceFileLinks(project);
        this.changes = new ChangeReviewService(project);
    }

    boolean isConnected() { return client != null && !switching; }
    boolean isBusy() { return isConnected() && agentMessageOpen; }

    void resetConversation() {
        transcriptMarkdown.setLength(0);
        pendingAgentText.setLength(0);
        agentRenderScheduled = false;
        firstAgentChunkSentAtNanos = 0;
        firstAgentChunkReceivedAtNanos = 0;
        agentMessageOpen = false;
        acceptingRestoredTranscript = false;
        restoredAgentMessageOpen = false;
        toolCalls.clear();
        renderedToolDiffs.clear();
        changes.clear();
        attachments.clear();
        commands.clear();
        configOptions.clear();
        changes = new ChangeReviewService(project);
    }
}
