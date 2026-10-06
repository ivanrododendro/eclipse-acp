package dev.eclipseacp.client.acp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNull;

import java.util.List;
import org.junit.Test;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.eclipseacp.client.agent.ConfigValue;

/** Pure message conversion tests: no client, process, logging or Eclipse runtime. */
public class AcpMessageCodecTest {
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

        var actual = AcpMessageCodec.capabilities(result);

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
        var actual = AcpMessageCodec.capabilities(new JsonObject());

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

        var actual = AcpMessageCodec.authenticationMethods(result);

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

        var page = AcpMessageCodec.sessionPage(result);

        assertEquals("opaque-next-page", page.nextCursor());
        assertEquals(1, page.sessions().size());
        assertEquals("Implement ACP", page.sessions().get(0).title());
        assertEquals("/shared", page.sessions().get(0).additionalDirectories().get(0));
    }


    @Test
    public void decodesBothCommandAndChoiceRepresentationsAndPreservesBooleanValues() {
        JsonObject update = JsonParser.parseString("""
                {"commands":[{"command":"help","description":"Help"},{}]}
                """).getAsJsonObject();
        assertEquals("help", AcpMessageCodec.commands(update).get(0).name());
        assertEquals(1, AcpMessageCodec.commands(update).size());
        var options = JsonParser.parseString("""
                [{"configId":"enabled","currentValue":false,"options":["auto",
                  {"value":"manual","label":"Manual","description":"Choose manually"}]},{}]
                """).getAsJsonArray();
        var decoded = AcpMessageCodec.configOptions(options);
        assertEquals(1, decoded.size());
        assertEquals(false, decoded.get(0).value().value());
        assertEquals("enabled", decoded.get(0).name());
        assertEquals("auto", decoded.get(0).choices().get(0).label());
        assertEquals("Manual", decoded.get(0).choices().get(1).label());
    }

    @Test
    public void keepsTaggedToolPreviewsOutOfTextAndSupportsLegacyTextArrays() {
        assertEquals("", AcpMessageCodec.textFrom(JsonParser.parseString("""
                {"type":"tool_result","text":"internal preview"}
                """)));
        assertEquals("hello legacy", AcpMessageCodec.textFrom(JsonParser.parseString("""
                [{"type":"text","text":"hello"},{"text":" legacy"},false]
                """)));
    }

    @Test
    public void keepsAbsentUsageMetricsDistinctFromZero() {
        var usage = AcpMessageCodec.usage(JsonParser.parseString("""
                {"inputTokens":0,"used":42,"size":100,"cost":"0.01"}
                """).getAsJsonObject());
        assertEquals(Long.valueOf(0), usage.inputTokens());
        assertNull(usage.outputTokens());
        assertEquals(Long.valueOf(42), usage.used());
        assertEquals(Long.valueOf(100), usage.size());
    }

    @Test
    public void encodesTypedAndNullConfigurationWithoutCoercion() {
        JsonObject typed = AcpMessageCodec.configOptionParameters("session", "enabled", new ConfigValue("boolean", false));
        assertEquals("boolean", typed.get("type").getAsString());
        assertTrue(typed.get("value").getAsJsonPrimitive().isBoolean());
        assertFalse(typed.get("value").getAsBoolean());
        JsonObject cleared = AcpMessageCodec.configOptionParameters("session", "enabled", null);
        assertFalse(cleared.has("type"));
        assertTrue(cleared.get("value").isJsonNull());
    }

    @Test
    public void encodesPermissionAndElicitationCancellationSeparatelyFromSelection() {
        assertEquals(JsonParser.parseString("""
                {"outcome":{"outcome":"selected","optionId":"allow"}}
                """), AcpMessageCodec.permissionResult("allow"));
        assertEquals(JsonParser.parseString("""
                {"outcome":{"outcome":"cancelled"}}
                """), AcpMessageCodec.cancelledPermissionResult());
        assertEquals(JsonParser.parseString("""
                {"action":"cancel"}
                """), AcpMessageCodec.cancelledElicitationResult());
        assertEquals(AcpMessageCodec.cancelledElicitationResult(), AcpMessageCodec.elicitationResult(null));
        assertEquals(JsonParser.parseString("""
                {"action":"accept","content":""}
                """), AcpMessageCodec.elicitationResult(""));
    }
}
