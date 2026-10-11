package dev.eclipseacp.client.acp;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.junit.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.eclipseacp.client.agent.AgentCommand;
import dev.eclipseacp.client.agent.AgentListener;
import dev.eclipseacp.client.agent.ConfigOption;
import dev.eclipseacp.client.agent.PermissionOption;
import dev.eclipseacp.client.agent.ToolCall;
import dev.eclipseacp.client.agent.Usage;

public class AcpSessionUpdateDispatcherTest {
    @Test
    public void reportsTheFirstAgentChunkOncePerPromptToTheSuppliedListener() {
        AcpSessionUpdateDispatcher dispatcher = dispatcher(new ToolCallTracker());
        Listener first = new Listener();
        dispatcher.dispatch(update("agent_message_chunk", "\"content\":{\"type\":\"text\",\"text\":\"Replay\"}"), first);
        assertEquals(List.of("agent:Replay"), first.events);

        dispatcher.promptStarted(1);
        dispatcher.dispatch(update("agent_message_chunk", "\"content\":{\"type\":\"text\",\"text\":\"\"}"), first);
        dispatcher.dispatch(update("agent_message_chunk", "\"content\":{\"type\":\"tool_result\",\"text\":\"Tool\"}"), first);
        dispatcher.dispatch(update("agent_message_chunk", "\"content\":{\"type\":\"text\",\"text\":\"One\"}"), first);
        dispatcher.dispatch(update("agent_message_chunk", "\"content\":{\"type\":\"text\",\"text\":\"Two\"}"), first);
        assertEquals(List.of("agent:Replay", "first:1", "agent:One", "agent:Two"), first.events);

        Listener second = new Listener();
        dispatcher.promptStarted(2);
        dispatcher.dispatch(update("agent_message_chunk", "\"content\":{\"type\":\"text\",\"text\":\"Next\"}"), second);
        assertEquals(List.of("first:2", "agent:Next"), second.events);
        assertEquals(4, first.events.size());
    }

    @Test
    public void omitsSilentAutomationHeartbeatsFromAgentText() {
        AcpSessionUpdateDispatcher dispatcher = dispatcher(new ToolCallTracker());
        Listener listener = new Listener();

        dispatcher.dispatch(update("agent_message_chunk", """
                \"content\":{\"type\":\"text\",\"text\":\"<heartbeat>\\n<automation_id>resume-at-02-10</automation_id>\\n<decision>DONT_NOTIFY</decision>\\n<message>Already running.</message>\\n</heartbeat>\"}
                """), listener);
        dispatcher.dispatch(update("agent_message_chunk", "\"content\":{\"type\":\"text\",\"text\":\"Visible reply\"}"), listener);

        assertEquals(List.of("agent:Visible reply"), listener.events);
    }

    @Test
    public void keepsHeartbeatsThatAreNotMarkedSilent() {
        AcpSessionUpdateDispatcher dispatcher = dispatcher(new ToolCallTracker());
        Listener listener = new Listener();

        dispatcher.dispatch(update("agent_message_chunk", """
                \"content\":{\"type\":\"text\",\"text\":\"<heartbeat><automation_id>job</automation_id><decision>NOTIFY</decision><message>Done.</message></heartbeat>\"}
                """), listener);

        assertEquals(List.of("agent:<heartbeat><automation_id>job</automation_id><decision>NOTIFY</decision><message>Done.</message></heartbeat>"),
                listener.events);
    }

    @Test
    public void dispatchesTypedAndStatusEventsInArrivalOrder() {
        AcpSessionUpdateDispatcher dispatcher = dispatcher(new ToolCallTracker());
        Listener listener = new Listener();
        dispatcher.dispatch(update("available_commands_update", "\"availableCommands\":[]"), listener);
        dispatcher.dispatch(update("config_option_update", "\"configOptions\":[]"), listener);
        dispatcher.dispatch(update("usage_update", "\"used\":10,\"size\":100"), listener);
        dispatcher.dispatch(update("terminal_output_update", "\"text\":\"output\""), listener);
        dispatcher.dispatch(update("user_message_chunk", "\"content\":{\"type\":\"text\",\"text\":\"Question\"}"), listener);
        dispatcher.dispatch(update("agent_thought_chunk", ""), listener);
        dispatcher.dispatch(update("plan_update", ""), listener);
        dispatcher.dispatch(update("unknown_update", ""), listener);

        assertEquals(List.of("commands", "config", "usage", "terminal:output", "user:Question",
                "status:Agent reasoning…", "status:Plan updated"), listener.events);
    }

    @Test
    public void sharesToolStateWithPermissionRequestsAndPreservesToolBeforeStatusOrder() {
        ToolCallTracker tracker = new ToolCallTracker();
        tracker.accept(JsonParser.parseString("{\"toolCallId\":\"call\",\"title\":\"Read file\",\"status\":\"pending\"}")
                .getAsJsonObject());
        Listener listener = new Listener();

        dispatcher(tracker).dispatch(update("tool_call_update", "\"toolCallId\":\"call\",\"status\":\"completed\""), listener);

        assertEquals("Read file", listener.tool.title());
        assertEquals("completed", listener.tool.status());
        assertEquals(List.of("tool", "status:Read file (completed)"), listener.events);
    }

    private static JsonObject update(String kind, String fields) {
        return JsonParser.parseString("{\"sessionId\":\"session\",\"update\":{\"sessionUpdate\":\"" + kind + "\""
                + (fields.isEmpty() ? "" : "," + fields) + "}}").getAsJsonObject();
    }

    private static AcpSessionUpdateDispatcher dispatcher(ToolCallTracker tracker) {
        return new AcpSessionUpdateDispatcher(tracker, new DiagnosticSink() {
            @Override public void info(String message) { }
            @Override public void trace(Supplier<String> message) { }
            @Override public void warn(String message, Throwable error) { }
            @Override public void error(String message, Throwable error) { }
        });
    }

    private static final class Listener implements AgentListener {
        final List<String> events = new ArrayList<>();
        ToolCall tool;
        @Override public void onAgentText(String text) { events.add("agent:" + text); }
        @Override public void onUserText(String text) { events.add("user:" + text); }
        @Override public void onStatus(String status) { events.add("status:" + status); }
        @Override public void onError(String message, Throwable error) { throw new AssertionError(message, error); }
        @Override public void onPromptFirstAgentChunk(long sentAt, long receivedAt) {
            assertTrue(receivedAt >= sentAt);
            events.add("first:" + sentAt);
        }
        @Override public void onAvailableCommands(List<AgentCommand> commands) { events.add("commands"); }
        @Override public void onConfigOptions(List<ConfigOption> options) { events.add("config"); }
        @Override public void onUsage(Usage usage) { events.add("usage"); }
        @Override public void onTerminalOutput(String text) { events.add("terminal:" + text); }
        @Override public void onToolCall(ToolCall call) { tool = call; events.add("tool"); }
        @Override public CompletableFuture<String> requestPermission(String title, List<PermissionOption> options) {
            throw new AssertionError("Dispatch must not request permission");
        }
    }
}
