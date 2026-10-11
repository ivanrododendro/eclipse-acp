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
import java.nio.file.Files;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.junit.Test;

import com.google.gson.JsonObject;
import dev.eclipseacp.client.agent.AgentListener;
import dev.eclipseacp.client.agent.PermissionOption;
import dev.eclipseacp.client.agent.ToolCall;
import dev.eclipseacp.client.agent.PromptAttachment;

public class AcpClientProtocolTest {
    @Test
    public void usesInjectedDiagnosticsThroughoutTheClientLifecycle() throws Exception {
        FakeTransport transport = new FakeTransport();
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AcpClient client = new AcpClient("agent", "", new CapturingListener(), List.of(),
                (command, arguments, workingDirectory, diagnosticConsumer, diagnosticErrorConsumer) -> new FakeProcess(),
                (reader, writer, handler, errorHandler) -> transport, diagnostics);
        try {
            client.connect(Path.of("/workspace/project")).join();
            client.prompt("private prompt content").join();
            client.onNotification("session/update", FakeTransport.agentTextUpdate("session-1", "private reply"));
            client.cancel();
        } finally {
            client.close();
        }

        assertTrue(diagnostics.messages.stream().anyMatch(message -> message.contains("connection established")));
        assertTrue(diagnostics.messages.stream().anyMatch(message -> message.contains("session/prompt")));
        assertTrue(diagnostics.messages.stream().anyMatch(message -> message.contains("session/cancel")));
        assertTrue(diagnostics.messages.stream().anyMatch(message -> message.contains("Closing ACP connection")));
        assertFalse(diagnostics.messages.stream().anyMatch(message -> message.contains("private")));
        assertEquals(1, diagnostics.traces.size());
        assertTrue(diagnostics.traces.get(0).get().contains("session/update"));
    }

    @Test
    public void routesLaunchErrorsAndCleanupWarningsToTheInjectedSink() {
        RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        AcpClient client = new AcpClient("agent", "", new CapturingListener(), List.of(),
                (command, arguments, workingDirectory, diagnosticConsumer, diagnosticErrorConsumer) -> {
                    throw new IOException("launch failed");
                }, (reader, writer, handler, errorHandler) -> new FakeTransport(), diagnostics);
        try {
            assertTrue(client.connect(Path.of("project")).isCompletedExceptionally());
            assertEquals("launch failed", diagnostics.errors.get(0).getMessage());
        } finally {
            client.close();
        }

        AgentProcess process = new AgentProcess() {
            @Override public Reader standardOutput() { return new StringReader(""); }
            @Override public Writer standardInput() { return new StringWriter(); }
            @Override public void close() throws IOException { throw new IOException("cleanup failed"); }
        };
        AcpClient cleanupClient = new AcpClient("agent", "", new CapturingListener(), List.of(),
                (command, arguments, workingDirectory, diagnosticConsumer, diagnosticErrorConsumer) -> process,
                (reader, writer, handler, errorHandler) -> new FakeTransport(), diagnostics);
        try {
            cleanupClient.connect(Path.of("project")).join();
        } finally {
            cleanupClient.close();
        }
        assertEquals("cleanup failed", diagnostics.warnings.get(0).getMessage());
    }

    @Test
    public void replacesSessionWithoutReinitializingTheConnection() {
        FakeTransport transport = new FakeTransport();
        CapturingListener first = new CapturingListener();
        CapturingListener second = new CapturingListener();
        AcpClient client = new AcpClient("agent", "", first, List.of(),
                (command, arguments, workingDirectory, diagnosticConsumer, diagnosticErrorConsumer) ->
                        new FakeProcess(),
                (reader, writer, handler, errorHandler) -> transport);

        try {
            client.connect(Path.of("/workspace/project")).join();
            client.startNewSession(Path.of("/workspace/project"), second).join();

            assertEquals(List.of("initialize", "session/new", "session/close", "session/new"), transport.methods);
            assertEquals(1, transport.count("initialize"));
            assertEquals("session-2", client.sessionId());
            client.onNotification("session/update", FakeTransport.agentTextUpdate("session-1", "retired"));
            assertNull(second.text);
            client.onNotification("session/update", FakeTransport.agentTextUpdate("session-2", "new session"));
            assertEquals("new session", second.text);
            assertNull(first.text);
        } finally {
            client.close();
        }
    }

    @Test
    public void initializesForSessionListingWithoutCreatingASession() {
        FakeTransport transport = new FakeTransport();
        AcpClient client = new AcpClient("agent", "", new CapturingListener(), List.of(),
                (command, arguments, workingDirectory, diagnosticConsumer, diagnosticErrorConsumer) ->
                        new FakeProcess(),
                (reader, writer, handler, errorHandler) -> transport);

        try {
            client.connectForSessionList(Path.of("/workspace/project")).join();

            assertEquals(List.of("initialize"), transport.methods);
            assertNull(client.sessionId());
        } finally {
            client.close();
        }
    }

    @Test
    public void attachmentPromptIncludesEveryAttachmentAndWaitsForTheAgentResponse() throws Exception {
        FakeTransport transport = new FakeTransport();
        transport.promptResponse = new CompletableFuture<>();
        Path image = Files.createTempFile("acp-contract-", ".png");
        Path audio = Files.createTempFile("acp-contract-", ".wav");
        byte[] imageBytes = { 1, 2, 3 };
        byte[] audioBytes = { 4, 5, 6 };
        AcpClient client = new AcpClient("agent", "", new CapturingListener(), List.of(),
                (command, arguments, workingDirectory, diagnosticConsumer, diagnosticErrorConsumer) -> new FakeProcess(),
                (reader, writer, handler, errorHandler) -> transport);
        try {
            Files.write(image, imageBytes);
            Files.write(audio, audioBytes);
            client.connect(Path.of("/workspace/project")).join();
            CompletableFuture<Void> prompt = client.prompt("describe", List.of(
                    new PromptAttachment(image, "image/png"), new PromptAttachment(audio, "audio/wav")));

            var content = transport.promptParams.getAsJsonArray("prompt");
            assertEquals(3, content.size());
            assertEquals("describe", content.get(0).getAsJsonObject().get("text").getAsString());
            assertEquals("image", content.get(1).getAsJsonObject().get("type").getAsString());
            assertEquals("image/png", content.get(1).getAsJsonObject().get("mimeType").getAsString());
            assertEquals(Base64.getEncoder().encodeToString(imageBytes), content.get(1).getAsJsonObject().get("data").getAsString());
            assertEquals("audio", content.get(2).getAsJsonObject().get("type").getAsString());
            assertEquals("audio/wav", content.get(2).getAsJsonObject().get("mimeType").getAsString());
            assertEquals(Base64.getEncoder().encodeToString(audioBytes), content.get(2).getAsJsonObject().get("data").getAsString());
            assertFalse(prompt.isDone());
            transport.promptResponse.complete(new JsonObject());
            prompt.join();
        } finally {
            client.close();
            Files.deleteIfExists(image);
            Files.deleteIfExists(audio);
        }
    }

    @Test
    public void deliversUpdateReceivedBeforeNewSessionResponse() {
        FakeTransport transport = new FakeTransport();
        CapturingListener listener = new CapturingListener();
        AcpClient client = new AcpClient("agent", "", listener, List.of(),
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
        AcpClient client = new AcpClient("agent", "", listener, List.of(),
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
    public void doesNotAdvertiseClientMediatedFileAccess() {
        FakeTransport transport = new FakeTransport();
        AcpClient client = new AcpClient("agent", "", new CapturingListener(), List.of(),
                (command, arguments, workingDirectory, diagnosticConsumer, diagnosticErrorConsumer) -> new FakeProcess(),
                (reader, writer, handler, errorHandler) -> transport);

        client.connect(Path.of("/workspace/project")).join();

        assertFalse(transport.initializeParams.getAsJsonObject("clientCapabilities").has("fs"));
        client.close();
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

    @Test
    public void stopCompletesAPendingPermissionAsCancelled() throws Exception {
        PendingPermissionListener listener = new PendingPermissionListener();
        AcpClient client = new AcpClient("unused", "", listener);
        JsonObject params = new JsonObject();
        JsonObject option = new JsonObject();
        option.addProperty("optionId", "allow");
        option.addProperty("name", "Allow");
        option.addProperty("kind", "allow_once");
        var options = new com.google.gson.JsonArray();
        options.add(option);
        params.add("options", options);

        CompletableFuture<com.google.gson.JsonElement> response = client.onRequest("session/request_permission", params);
        assertFalse(response.isDone());
        client.cancel();

        JsonObject result = response.get().getAsJsonObject();
        assertEquals("cancelled", result.getAsJsonObject("outcome").get("outcome").getAsString());
        assertTrue(listener.permission.isDone());
    }

    @Test
    public void closeDuringInitializeReleasesProcessAndTransportAndFailsConnect() {
        FakeProcess process = new FakeProcess();
        PendingInitializeTransport transport = new PendingInitializeTransport();
        AcpClient client = new AcpClient("agent", "", new CapturingListener(), List.of(),
                (command, arguments, workingDirectory, diagnosticConsumer, diagnosticErrorConsumer) -> process,
                (reader, writer, handler, errorHandler) -> transport);

        CompletableFuture<Void> connecting = client.connect(Path.of("/workspace/project"));
        client.close();
        client.close();

        assertTrue(process.closed);
        assertTrue(transport.closed);
        assertTrue(connecting.isCompletedExceptionally());
    }

    @Test
    public void closingCancelsElicitationAndRejectsLaterDialogs() {
        CompletableFuture<String> answer = new CompletableFuture<>();
        int[] requests = { 0 };
        CapturingListener listener = new CapturingListener() {
            @Override public CompletableFuture<String> requestElicitation(
                    dev.eclipseacp.client.agent.ElicitationRequest request) {
                requests[0]++;
                return answer;
            }
        };
        AcpClient client = new AcpClient("unused", "", listener);
        CompletableFuture<com.google.gson.JsonElement> response = client.onRequest("elicitation/create", new JsonObject());
        assertFalse(response.isDone());

        client.close();
        client.close();

        assertEquals("cancel", response.join().getAsJsonObject().get("action").getAsString());
        assertTrue(answer.isDone());
        assertNull(answer.join());
        assertEquals("cancel", client.onRequest("elicitation/create", new JsonObject())
                .join().getAsJsonObject().get("action").getAsString());
        assertEquals(1, requests[0]);
    }

    @Test
    public void transportLossCancelsPendingPermissionAndItsSource() {
        PendingPermissionListener listener = new PendingPermissionListener();
        FakeTransport transport = new FakeTransport();
        var terminal = new java.util.concurrent.atomic.AtomicReference<java.util.function.Consumer<Throwable>>();
        AcpClient client = new AcpClient("agent", "", listener, List.of(),
                (command, arguments, directory, diagnosticConsumer, diagnosticErrorConsumer) -> new FakeProcess(),
                (reader, writer, handler, errorHandler) -> {
                    terminal.set(errorHandler);
                    return transport;
                });
        try {
            client.connect(Path.of("/workspace/project")).join();
            JsonObject params = new JsonObject();
            params.addProperty("sessionId", client.sessionId());
            CompletableFuture<com.google.gson.JsonElement> response = client.onRequest("session/request_permission", params);
            assertFalse(response.isDone());

            terminal.get().accept(new IOException("transport lost"));

            assertEquals("cancelled", response.join().getAsJsonObject().getAsJsonObject("outcome")
                    .get("outcome").getAsString());
            assertTrue(listener.permission.isDone());
            assertNull(listener.permission.join());
        } finally {
            client.close();
        }
    }

    private static JsonObject diff(String path, String oldText, String newText) {
        JsonObject diff = new JsonObject();
        diff.addProperty("type", "diff");
        diff.addProperty("path", path);
        diff.addProperty("oldText", oldText);
        diff.addProperty("newText", newText);
        return diff;
    }

    private static class CapturingListener implements AgentListener {
        private String text;

        @Override public void onAgentText(String value) { text = value; }
        @Override public void onStatus(String status) { }
        @Override public void onError(String message, Throwable error) { }
        @Override public CompletableFuture<String> requestPermission(String title, List<PermissionOption> options) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static final class RecordingDiagnostics implements DiagnosticSink {
        final List<String> messages = new ArrayList<>();
        final List<Supplier<String>> traces = new ArrayList<>();
        final List<Throwable> errors = new ArrayList<>();
        final List<Throwable> warnings = new ArrayList<>();
        @Override public void info(String message) { messages.add(message); }
        @Override public void trace(Supplier<String> message) { traces.add(message); }
        @Override public void warn(String message, Throwable error) { messages.add(message); warnings.add(error); }
        @Override public void error(String message, Throwable error) { messages.add(message); errors.add(error); }
    }

    private static final class PendingPermissionListener extends CapturingListener {
        final CompletableFuture<String> permission = new CompletableFuture<>();
        @Override public CompletableFuture<String> requestPermission(String title, List<PermissionOption> options) {
            return permission;
        }
    }

    private static final class FakeProcess implements AgentProcess {
        private boolean closed;
        @Override public Reader standardOutput() { return new StringReader(""); }
        @Override public Writer standardInput() { return new StringWriter(); }
        @Override public void close() throws IOException { closed = true; }
    }

    private static final class PendingInitializeTransport implements JsonRpcTransport {
        private final CompletableFuture<JsonObject> initialize = new CompletableFuture<>();
        private boolean closed;

        @Override public void start() { }
        @Override public CompletableFuture<JsonObject> request(String method, JsonObject params) { return initialize; }
        @Override public void notification(String method, JsonObject params) { }
        @Override public void close() {
            closed = true;
            initialize.completeExceptionally(new IOException("closed"));
        }
    }

    private static final class FakeTransport implements JsonRpcTransport {
        private final List<String> methods = new ArrayList<>();
        private int sessionNumber;
        private JsonRpcHandler handler;
        private JsonObject initializeParams;
        private JsonObject promptParams;
        private CompletableFuture<JsonObject> promptResponse = CompletableFuture.completedFuture(new JsonObject());
        private boolean sendUpdateBeforeSecondSessionResponse;
        private boolean sendOldUpdateBeforeSecondSessionResponse;

        @Override public void start() { }

        @Override public CompletableFuture<JsonObject> request(String method, JsonObject params) {
            methods.add(method);
            JsonObject result = new JsonObject();
            if ("initialize".equals(method)) {
                initializeParams = params;
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
            } else if ("session/prompt".equals(method)) {
                promptParams = params.deepCopy();
                return promptResponse;
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
