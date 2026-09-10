package dev.eclipseacp.client.ui;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import dev.eclipseacp.client.agent.AgentCommand;
import dev.eclipseacp.client.agent.AgentListener;
import dev.eclipseacp.client.agent.AuthMethod;
import dev.eclipseacp.client.agent.ConfigOption;
import dev.eclipseacp.client.agent.ElicitationRequest;
import dev.eclipseacp.client.agent.FileReadRequest;
import dev.eclipseacp.client.agent.FileWriteRequest;
import dev.eclipseacp.client.agent.PermissionOption;
import dev.eclipseacp.client.agent.PermissionRequest;
import dev.eclipseacp.client.agent.ToolCall;
import dev.eclipseacp.client.agent.Usage;

/** Bridges protocol-neutral agent callbacks to the controller/presentation callbacks of one chat. */
final class AcpChatSessionListener implements AgentListener {
    interface Callbacks {
        void agentText(String text); void firstChunk(long sentAtNanos, long receivedAtNanos);
        void promptCompleted(long sentAtNanos, long completedAtNanos); void userText(String text);
        void status(String value); void error(String message, Throwable error); void toolCall(ToolCall toolCall);
        void commands(List<AgentCommand> commands); void configOptions(List<ConfigOption> options);
        void usage(Usage usage); void terminalOutput(String output);
        CompletableFuture<String> permission(String title, List<PermissionOption> options);
        CompletableFuture<String> permission(PermissionRequest request);
        CompletableFuture<String> authentication(List<AuthMethod> methods);
        CompletableFuture<String> elicitation(ElicitationRequest request);
        CompletableFuture<String> readFile(FileReadRequest request);
        CompletableFuture<Void> stageFileWrite(FileWriteRequest request);
    }
    private final Callbacks callbacks;
    AcpChatSessionListener(Callbacks callbacks) { this.callbacks = callbacks; }
    @Override public void onAgentText(String text) { callbacks.agentText(text); }
    @Override public void onPromptFirstAgentChunk(long sent, long received) { callbacks.firstChunk(sent, received); }
    @Override public void onPromptCompleted(long sent, long completed) { callbacks.promptCompleted(sent, completed); }
    @Override public void onUserText(String text) { callbacks.userText(text); }
    @Override public void onStatus(String status) { callbacks.status(status); }
    @Override public void onError(String message, Throwable error) { callbacks.error(message, error); }
    @Override public void onToolCall(ToolCall toolCall) { callbacks.toolCall(toolCall); }
    @Override public void onAvailableCommands(List<AgentCommand> commands) { callbacks.commands(commands); }
    @Override public void onConfigOptions(List<ConfigOption> options) { callbacks.configOptions(options); }
    @Override public void onUsage(Usage usage) { callbacks.usage(usage); }
    @Override public void onTerminalOutput(String output) { callbacks.terminalOutput(output); }
    @Override public CompletableFuture<String> requestPermission(String title, List<PermissionOption> options) { return callbacks.permission(title, options); }
    @Override public CompletableFuture<String> requestPermission(PermissionRequest request) { return callbacks.permission(request); }
    @Override public CompletableFuture<String> requestAuthentication(List<AuthMethod> methods) { return callbacks.authentication(methods); }
    @Override public CompletableFuture<String> requestElicitation(ElicitationRequest request) { return callbacks.elicitation(request); }
    @Override public CompletableFuture<String> readTextFile(FileReadRequest request) { return callbacks.readFile(request); }
    @Override public CompletableFuture<Void> stageFileWrite(FileWriteRequest request) { return callbacks.stageFileWrite(request); }
}
