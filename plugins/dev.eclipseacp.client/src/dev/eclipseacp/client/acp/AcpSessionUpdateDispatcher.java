package dev.eclipseacp.client.acp;

import static dev.eclipseacp.client.acp.AcpMessageCodec.commands;
import static dev.eclipseacp.client.acp.AcpMessageCodec.configOptions;
import static dev.eclipseacp.client.acp.AcpMessageCodec.nonBlank;
import static dev.eclipseacp.client.acp.AcpMessageCodec.object;
import static dev.eclipseacp.client.acp.AcpMessageCodec.string;
import static dev.eclipseacp.client.acp.AcpMessageCodec.textFrom;
import static dev.eclipseacp.client.acp.AcpMessageCodec.usage;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import dev.eclipseacp.client.agent.AgentListener;
import dev.eclipseacp.client.agent.ToolCall;

/** Translates accepted ACP updates; session ownership and filtering belong to AcpClient. */
final class AcpSessionUpdateDispatcher {
    /**
     * Codex emits these control messages when an automation deliberately has no user-facing
     * result. They are part of the restored agent context, not conversation content.
     */
    private static final Pattern SILENT_HEARTBEAT = Pattern.compile(
            "\\A\\s*<heartbeat>\\s*<automation_id>.*?</automation_id>\\s*"
                    + "<decision>\\s*DONT_NOTIFY\\s*</decision>\\s*"
                    + "<message>.*?</message>\\s*</heartbeat>\\s*\\z",
            Pattern.DOTALL);
    private final ToolCallTracker toolCalls;
    private final DiagnosticSink diagnostics;
    private volatile long promptSentAtNanos;
    private final AtomicBoolean firstAgentChunkReceived = new AtomicBoolean();

    AcpSessionUpdateDispatcher(ToolCallTracker toolCalls, DiagnosticSink diagnostics) {
        this.toolCalls = toolCalls;
        this.diagnostics = diagnostics;
    }

    void promptStarted(long sentAtNanos) {
        promptSentAtNanos = sentAtNanos;
        firstAgentChunkReceived.set(false);
    }

    void dispatch(JsonObject params, AgentListener listener) {
        JsonObject update = object(params.get("update"));
        String updateSessionId = string(params, "sessionId");
        String kind = string(update, "sessionUpdate");

        if ("available_commands_update".equals(kind)) {
            listener.onAvailableCommands(commands(update));
        } else if ("config_option_update".equals(kind)) {
            JsonElement options = update.has("configOptions") ? update.get("configOptions") : update;
            listener.onConfigOptions(options != null && options.isJsonArray() ? configOptions(options.getAsJsonArray())
                    : options != null && options.isJsonObject() ? configOptions(options.getAsJsonObject()) : List.of());
        } else if ("usage_update".equals(kind)) {
            listener.onUsage(usage(update));
        } else if ("terminal_output".equals(kind) || "terminal_output_update".equals(kind)) {
            listener.onTerminalOutput(nonBlank(string(update, "output"), string(update, "text")));
        }

        if ("user_message_chunk".equals(kind)) {
            String text = textFrom(update.get("content"));
            if (!text.isEmpty()) listener.onUserText(text);
        } else if ("agent_message_chunk".equals(kind)) {
            String text = textFrom(update.get("content"));
            if (!text.isEmpty()) {
                if (SILENT_HEARTBEAT.matcher(text).matches()) {
                    diagnostics.info("Ignoring non-user-facing DONT_NOTIFY heartbeat: sessionId='" + updateSessionId + "'");
                    return;
                }
                long receivedAt = System.nanoTime();
                long sentAt = promptSentAtNanos;
                if (sentAt != 0 && firstAgentChunkReceived.compareAndSet(false, true)) {
                    diagnostics.info("ACP first agent_message_chunk received: sessionId='" + updateSessionId
                            + "', afterSendMs=" + TimeUnit.NANOSECONDS.toMillis(receivedAt - sentAt));
                    listener.onPromptFirstAgentChunk(sentAt, receivedAt);
                }
                listener.onAgentText(text);
            }
        } else if ("agent_thought_chunk".equals(kind)) {
            listener.onStatus("Agent reasoning…");
        } else if ("tool_call".equals(kind) || "tool_call_update".equals(kind)) {
            ToolCall toolCall = toolCalls.accept(update);
            if (toolCall != null) {
                listener.onToolCall(toolCall);
                listener.onStatus(toolCall.title() + " (" + toolCall.status() + ")");
            }
        } else if ("plan".equals(kind) || "plan_update".equals(kind)) {
            listener.onStatus("Plan updated");
        }
    }
}
