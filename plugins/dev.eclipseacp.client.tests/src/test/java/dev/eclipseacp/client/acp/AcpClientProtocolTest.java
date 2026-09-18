package dev.eclipseacp.client.acp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.ArrayList;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.google.gson.JsonObject;
import dev.eclipseacp.client.agent.AgentListener;
import dev.eclipseacp.client.agent.PermissionOption;
import dev.eclipseacp.client.agent.ToolCall;

public class AcpClientProtocolTest {
    @Test
    public void replacesSessionWithoutReinitializingTheConnection() {
        FakeTransport transport = new FakeTransport();
        AgentListener first = new CapturingListener();
        AgentListener second = new CapturingListener();
        AcpClient client = new AcpClient("agent", "", first, false, List.of(),
                (command, arguments, workingDirectory, diagnosticConsumer, diagnosticErrorConsumer) ->
                        new FakeProcess(),
                (reader, writer, handler, errorHandler) -> transport);

        client.connect(Path.of("/workspace/project")).join();
        client.startNewSession(Path.of("/workspace/project"), second).join();

        assertEquals(List.of("initialize", "session/new", "session/close", "session/new"), transport.methods);
        assertEquals(1, transport.count("initialize"));
        assertEquals("session-2", client.sessionId());
    }

    @Test
    public void deliversUpdateReceivedBeforeNewSessionResponse() {
        FakeTransport transport = new FakeTransport();
        CapturingListener listener = new CapturingListener();
        AcpClient client = new AcpClient("agent", "", listener, false, List.of(),
                (command, arguments, workingDirectory, diagnosticConsumer, diagnosticErrorConsumer) ->
                        new FakeProcess(),
                (reader, writer, handler, errorHandler) -> {
                    transport.handler = handler;
                    transport.sendUpdateBeforeSecondSessionResponse = true;
                    return transport;
                });

        client.connect(Path.of("/workspace/project")).join();
        client.startNewSession(Path.of("/workspace/project"), listener).join();

        assertEquals("session-2", client.sessionId());
        assertEquals("early update", listener.text);
    }

    @Test
    public void discardsLateUpdateFromClosedSessionWhileNewSessionIsPending() {
        FakeTransport transport = new FakeTransport();
        CapturingListener listener = new CapturingListener();
        AcpClient client = new AcpClient("agent", "", listener, false, List.of(),
                (command, arguments, workingDirectory, diagnosticConsumer, diagnosticErrorConsumer) ->
                        new FakeProcess(),
                (reader, writer, handler, errorHandler) -> {
                    transport.handler = handler;
                    transport.sendOldUpdateBeforeSecondSessionResponse = true;
                    return transport;
                });

        client.connect(Path.of("/workspace/project")).join();
        client.startNewSession(Path.of("/workspace/project"), listener).join();

        assertEquals("session-2", client.sessionId());
        assertNull(listener.text);
    }

    @Test
    public void parsesV1AgentCapabilitiesAtTheirSpecifiedLocations() {
        JsonObject caps = new JsonObject();
        caps.addProperty("loadSession", true);
        JsonObject sessions = new JsonObject();
        sessions.add("list", new JsonObject());
        sessions.add("resume", new JsonObject());
        sessions.add("close", new JsonObject());
        sessions.add("delete", new JsonObject());
        sessions.add("additionalDirectories", new JsonObject());
        caps.add("sessionCapabilities", sessions);
        JsonObject prompt = new JsonObject();
        prompt.addProperty("image", true);
        prompt.addProperty("audio", true);
        prompt.addProperty("embeddedContext", true);
        caps.add("promptCapabilities", prompt);
        JsonObject mcp = new JsonObject();
        mcp.addProperty("http", true);
        mcp.addProperty("sse", true);
        caps.add("mcpCapabilities", mcp);
        JsonObject auth = new JsonObject();
        auth.add("logout", new JsonObject());
        caps.add("auth", auth);
        JsonObject result = new JsonObject();
        result.add("agentCapabilities", caps);

        var actual = AcpClient.capabilities(result);

        assertTrue(actual.loadSession());
        assertTrue(actual.sessionList());
        assertTrue(actual.sessionResume());
        assertTrue(actual.sessionClose());
        assertTrue(actual.sessionDelete());
        assertTrue(actual.additionalDirectories());
        assertTrue(actual.promptImage());
        assertTrue(actual.promptAudio());
        assertTrue(actual.promptEmbeddedContext());
        assertTrue(actual.mcpHttp());
        assertTrue(actual.mcpSse());
        assertTrue(actual.logout());
    }

    @Test
    public void treatsOmittedCapabilitiesAsUnsupported() {
        var actual = AcpClient.capabilities(new JsonObject());

        assertFalse(actual.loadSession());
        assertFalse(actual.sessionList());
        assertFalse(actual.sessionClose());
        assertFalse(actual.promptImage());
        assertFalse(actual.logout());
    }

    @Test
    public void parsesAgentAndTerminalAuthenticationMethods() {
        JsonObject result = new JsonObject();
        var methods = new com.google.gson.JsonArray();
        JsonObject agent = new JsonObject();
        agent.addProperty("id", "web-login");
        agent.addProperty("name", "Sign in");
        methods.add(agent);
        JsonObject terminal = new JsonObject();
        terminal.addProperty("id", "terminal-login");
        terminal.addProperty("type", "terminal");
        terminal.addProperty("description", "Use browser login");
        var args = new com.google.gson.JsonArray();
        args.add("--login");
        terminal.add("args", args);
        JsonObject env = new JsonObject();
        env.addProperty("ACP_INTERACTIVE_LOGIN", "1");
        terminal.add("env", env);
        methods.add(terminal);
        result.add("authMethods", methods);

        var actual = AcpClient.authenticationMethods(result);

        assertEquals(2, actual.size());
        assertEquals("agent", actual.get(0).type());
        assertFalse(actual.get(0).isTerminal());
        assertTrue(actual.get(1).isTerminal());
        assertEquals("--login", actual.get(1).arguments().get(0));
        assertEquals("1", actual.get(1).environment().get("ACP_INTERACTIVE_LOGIN"));
    }

    @Test
    public void retainsSessionListMetadataAndPaginationCursor() {
        JsonObject session = new JsonObject();
        session.addProperty("sessionId", "session-1");
        session.addProperty("cwd", "/workspace");
        session.addProperty("title", "Implement ACP");
        session.addProperty("updatedAt", "2026-09-05T12:00:00Z");
        var additionalDirectories = new com.google.gson.JsonArray();
        additionalDirectories.add("/shared");
        session.add("additionalDirectories", additionalDirectories);
        var sessions = new com.google.gson.JsonArray();
        sessions.add(session);
        JsonObject result = new JsonObject();
        result.add("sessions", sessions);
        result.addProperty("nextCursor", "opaque-next-page");

        var page = AcpClient.sessionPage(result);

        assertEquals("opaque-next-page", page.nextCursor());
        assertEquals(1, page.sessions().size());
        assertEquals("Implement ACP", page.sessions().get(0).title());
        assertEquals("/shared", page.sessions().get(0).additionalDirectories().get(0));
    }

    @Test
    public void forwardsTypedSessionUpdatesWhileRetainingPlainTextStreaming() {
        CapturingListener listener = new CapturingListener();
        AcpClient client = new AcpClient("unused", "", listener);
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", "hello");
        JsonObject update = new JsonObject();
        update.addProperty("sessionUpdate", "agent_message_chunk");
        update.add("content", content);
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", "session-1");
        params.add("update", update);

        client.onNotification("session/update", params);

        assertEquals("hello", listener.text);
    }

    @Test
    public void doesNotForwardToolContentMislabelledAsAnAgentMessageChunk() {
        CapturingListener listener = new CapturingListener();
        AcpClient client = new AcpClient("unused", "", listener);
        JsonObject content = new JsonObject();
        content.addProperty("type", "tool_result");
        content.addProperty("text", "tool output that must stay out of the transcript");
        JsonObject update = new JsonObject();
        update.addProperty("sessionUpdate", "agent_message_chunk");
        update.add("content", content);
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", "session-1");
        params.add("update", update);

        client.onNotification("session/update", params);

        assertNull(listener.text);
    }

    @Test
    public void mergesToolCallUpdatesAndRetainsOnlyTheLatestDiffCollection() {
        ToolCallTracker tracker = new ToolCallTracker();
        JsonObject started = new JsonObject();
        started.addProperty("toolCallId", "call-1");
        started.addProperty("title", "Edit greeting");
        started.addProperty("kind", "edit");
        started.addProperty("status", "pending");
        var initialContent = new com.google.gson.JsonArray();
        initialContent.add(diff("/workspace/hello.txt", "old", "first"));
        started.add("content", initialContent);

        ToolCall initial = tracker.accept(started);

        JsonObject update = new JsonObject();
        update.addProperty("toolCallId", "call-1");
        update.addProperty("status", "completed");
        var finalContent = new com.google.gson.JsonArray();
        finalContent.add(diff("/workspace/hello.txt", "old", "final"));
        update.add("content", finalContent);
        ToolCall finalCall = tracker.accept(update);

        assertEquals("Edit greeting", finalCall.title());
        assertEquals("completed", finalCall.status());
        assertEquals(1, finalCall.diffs().size());
        assertEquals("final", finalCall.diffs().get(0).newText());
        assertEquals("first", initial.diffs().get(0).newText());
    }

    private static JsonObject diff(String path, String oldText, String newText) {
        JsonObject diff = new JsonObject();
        diff.addProperty("type", "diff");
        diff.addProperty("path", path);
        diff.addProperty("oldText", oldText);
        diff.addProperty("newText", newText);
        return diff;
    }

    private static final class CapturingListener implements AgentListener {
        private String text;

        @Override public void onAgentText(String value) { text = value; }
        @Override public void onStatus(String status) { }
        @Override public void onError(String message, Throwable error) { }
        @Override public CompletableFuture<String> requestPermission(String title, List<PermissionOption> options) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class FakeProcess implements AgentProcess {
        @Override public Reader standardOutput() { return new StringReader(""); }
        @Override public Writer standardInput() { return new StringWriter(); }
        @Override public void close() throws IOException { }
    }

    private static final class FakeTransport implements JsonRpcTransport {
        private final List<String> methods = new ArrayList<>();
        private int sessionNumber;
        private JsonRpcHandler handler;
        private boolean sendUpdateBeforeSecondSessionResponse;
        private boolean sendOldUpdateBeforeSecondSessionResponse;

        @Override public void start() { }

        @Override public CompletableFuture<JsonObject> request(String method, JsonObject params) {
            methods.add(method);
            JsonObject result = new JsonObject();
            if ("initialize".equals(method)) {
                result.addProperty("protocolVersion", 1);
                JsonObject capabilities = new JsonObject();
                JsonObject session = new JsonObject();
                session.add("close", new JsonObject());
                capabilities.add("sessionCapabilities", session);
                result.add("agentCapabilities", capabilities);
            } else if ("session/new".equals(method)) {
                String newSessionId = "session-" + (++sessionNumber);
                if (sessionNumber == 2 && sendUpdateBeforeSecondSessionResponse) {
                    handler.onNotification("session/update", agentTextUpdate(newSessionId, "early update"));
                }
                if (sessionNumber == 2 && sendOldUpdateBeforeSecondSessionResponse) {
                    handler.onNotification("session/update", agentTextUpdate("session-1", "late old update"));
                }
                result.addProperty("sessionId", newSessionId);
            }
            return CompletableFuture.completedFuture(result);
        }

        private static JsonObject agentTextUpdate(String sessionId, String text) {
            JsonObject content = new JsonObject();
            content.addProperty("type", "text");
            content.addProperty("text", text);
            JsonObject update = new JsonObject();
            update.addProperty("sessionUpdate", "agent_message_chunk");
            update.add("content", content);
            JsonObject params = new JsonObject();
            params.addProperty("sessionId", sessionId);
            params.add("update", update);
            return params;
        }

        @Override public void notification(String method, JsonObject params) { }
        @Override public void close() { }
        int count(String method) { return (int) methods.stream().filter(method::equals).count(); }
    }
}
