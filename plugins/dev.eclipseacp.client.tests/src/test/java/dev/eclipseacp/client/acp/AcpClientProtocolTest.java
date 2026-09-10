package dev.eclipseacp.client.acp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.Test;

import com.google.gson.JsonObject;
import dev.eclipseacp.client.agent.AgentListener;
import dev.eclipseacp.client.agent.PermissionOption;
import dev.eclipseacp.client.agent.ToolCall;

public class AcpClientProtocolTest {
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
        assertEquals("hello", listener.text);
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
}
