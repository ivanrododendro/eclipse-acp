package dev.eclipseacp.client.acp;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.eclipseacp.client.agent.AgentCapabilities;
import dev.eclipseacp.client.agent.AgentClient;

/** ACP v1 adapter. The rest of the plug-in talks to AgentClient only. */
public final class AcpClient implements AgentClient, JsonRpcHandler {
    private static final int PROTOCOL_VERSION = 1;
    private static final String GFM_SYSTEM_INSTRUCTION = "System instruction: Format every response using GitHub Flavored Markdown (GFM). Use headings, lists, tables, links, and fenced code blocks when they improve clarity. Do not use raw HTML unless explicitly requested.";

    private final AcpListener listener;
    private final String command;
    private final String arguments;
    private Process process;
    private JsonRpcConnection connection;
    private String sessionId;
    private volatile boolean applyingSystemInstruction;
    private volatile AgentCapabilities capabilities = AgentCapabilities.NONE;

    public AcpClient(String command, String arguments, AcpListener listener) {
        this.command = Objects.requireNonNull(command).trim();
        this.arguments = arguments == null ? "" : arguments;
        this.listener = Objects.requireNonNull(listener);
    }

    public CompletableFuture<Void> connect(Path workingDirectory) {
        if (command.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("The ACP command is empty"));
        }

        try {
            List<String> processCommand = new ArrayList<>();
            processCommand.add(command);
            processCommand.addAll(parseArguments(arguments));

            ProcessBuilder builder = new ProcessBuilder(processCommand);
            builder.directory(workingDirectory.toFile());
            process = builder.start();

            connection = new JsonRpcConnection(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8),
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8),
                    this,
                    error -> listener.onError("ACP connection failed", error));
            connection.start();
            streamStandardError(process);
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        listener.onStatus("Initializing " + command + "…");
        return initialize()
                .thenCompose(ignored -> newSession(workingDirectory))
                .thenCompose(ignored -> applySystemInstruction())
                .thenAccept(ignored -> listener.onStatus("Connected"));
    }

    /** ACP v1 has no native system role; this establishes an invisible session bootstrap instruction. */
    private CompletableFuture<Void> applySystemInstruction() {
        applyingSystemInstruction = true;
        return sendPrompt(GFM_SYSTEM_INSTRUCTION, false)
                .whenComplete((ignored, error) -> applyingSystemInstruction = false);
    }

    @Override
    public AgentCapabilities capabilities() { return capabilities; }

    public CompletableFuture<Void> prompt(String text) {
        if (sessionId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("ACP session is not connected"));
        }

        return sendPrompt(text, true);
    }

    private CompletableFuture<Void> sendPrompt(String text, boolean announce) {
        if (sessionId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("ACP session is not connected"));
        }
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);
        JsonArray prompt = new JsonArray();
        prompt.add(content);

        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        params.add("prompt", prompt);

        if (announce) listener.onStatus("Agent working…");
        return connection.request("session/prompt", params)
                .thenAccept(result -> { if (announce) listener.onStatus(stopReason(result)); });
    }

    public void cancel() throws IOException {
        if (sessionId == null || connection == null) {
            return;
        }
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        connection.notification("session/cancel", params);
        listener.onStatus("Cancellation requested");
    }

    private CompletableFuture<JsonObject> initialize() {
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "Eclipse ACP");
        clientInfo.addProperty("version", "0.1.0");

        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION);
        params.add("clientCapabilities", new JsonObject());
        params.add("clientInfo", clientInfo);

        return connection.request("initialize", params).thenApply(result -> {
            int negotiated = result.has("protocolVersion")
                    ? result.get("protocolVersion").getAsInt()
                    : PROTOCOL_VERSION;
            if (negotiated != PROTOCOL_VERSION) {
                throw new IllegalStateException("Unsupported ACP protocol version: " + negotiated);
            }
            capabilities = capabilities(result);
            return result;
        });
    }

    private static AgentCapabilities capabilities(JsonObject result) {
        JsonObject caps = result.has("agentCapabilities") && result.get("agentCapabilities").isJsonObject()
                ? result.getAsJsonObject("agentCapabilities") : new JsonObject();
        return new AgentCapabilities(caps.has("sessionRequestPermission") || caps.has("permissions"),
                caps.has("modes"), caps.has("fileSystem") || caps.has("fs"), caps.has("terminal"));
    }

    private CompletableFuture<JsonObject> newSession(Path workingDirectory) {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", workingDirectory.toAbsolutePath().normalize().toString());
        params.add("mcpServers", new JsonArray());

        return connection.request("session/new", params).thenApply(result -> {
            if (!result.has("sessionId")) {
                throw new IllegalStateException("ACP agent did not return a sessionId");
            }
            sessionId = result.get("sessionId").getAsString();
            return result;
        });
    }

    @Override
    public void onNotification(String method, JsonObject params) {
        if (!"session/update".equals(method)) {
            return;
        }
        JsonObject update = object(params.get("update"));
        String kind = string(update, "sessionUpdate");

        if ("agent_message_chunk".equals(kind)) {
            String text = textFrom(update.get("content"));
            if (!applyingSystemInstruction && !text.isEmpty()) {
                listener.onAgentText(text);
            }
        } else if ("agent_thought_chunk".equals(kind)) {
            listener.onStatus("Agent reasoning…");
        } else if ("tool_call".equals(kind) || "tool_call_update".equals(kind)) {
            String title = string(update, "title");
            if (!title.isBlank()) {
                listener.onStatus(title);
            }
        } else if ("plan".equals(kind) || "plan_update".equals(kind)) {
            listener.onStatus("Plan updated");
        }
    }

    @Override
    public CompletableFuture<JsonElement> onRequest(String method, JsonObject params) {
        if (!"session/request_permission".equals(method)) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("Unsupported ACP method: " + method));
        }

        List<PermissionOption> options = new ArrayList<>();
        JsonArray rawOptions = params.has("options") && params.get("options").isJsonArray()
                ? params.getAsJsonArray("options")
                : new JsonArray();
        for (JsonElement element : rawOptions) {
            JsonObject option = object(element);
            options.add(new PermissionOption(
                    string(option, "optionId"),
                    string(option, "name"),
                    string(option, "kind")));
        }

        JsonObject toolCall = object(params.get("toolCall"));
        String title = string(toolCall, "title");
        if (title.isBlank()) {
            title = "The agent requests permission";
        }

        return listener.requestPermission(title, options).thenApply(optionId -> {
            JsonObject outcome = new JsonObject();
            if (optionId == null) {
                outcome.addProperty("outcome", "cancelled");
            } else {
                outcome.addProperty("outcome", "selected");
                outcome.addProperty("optionId", optionId);
            }
            JsonObject result = new JsonObject();
            result.add("outcome", outcome);
            return result;
        });
    }

    private void streamStandardError(Process child) {
        Thread thread = new Thread(() -> {
            try (var reader = child.errorReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        listener.onStatus("Agent: " + line);
                    }
                }
            } catch (IOException exception) {
                if (child.isAlive()) {
                    listener.onError("Cannot read ACP agent diagnostics", exception);
                }
            }
        }, "eclipse-acp-stderr");
        thread.setDaemon(true);
        thread.start();
    }

    private static String stopReason(JsonObject result) {
        String reason = string(result, "stopReason");
        return reason.isBlank() ? "Ready" : "Ready (" + reason + ")";
    }

    private static String textFrom(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return "";
        }
        if (element.isJsonPrimitive()) {
            JsonPrimitive value = element.getAsJsonPrimitive();
            return value.isString() ? value.getAsString() : "";
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            if (object.has("text") && object.get("text").isJsonPrimitive()) {
                return object.get("text").getAsString();
            }
            return textFrom(object.get("content"));
        }
        StringBuilder text = new StringBuilder();
        for (JsonElement child : element.getAsJsonArray()) {
            text.append(textFrom(child));
        }
        return text.toString();
    }

    private static JsonObject object(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static String string(JsonObject object, String member) {
        return object.has(member) && object.get(member).isJsonPrimitive()
                ? object.get(member).getAsString()
                : "";
    }

    static List<String> parseArguments(String commandLine) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char quote = 0;

        for (int index = 0; index < commandLine.length(); index++) {
            char character = commandLine.charAt(index);
            if ((character == '\'' || character == '"')) {
                if (!quoted) {
                    quoted = true;
                    quote = character;
                } else if (quote == character) {
                    quoted = false;
                } else {
                    current.append(character);
                }
            } else if (Character.isWhitespace(character) && !quoted) {
                if (!current.isEmpty()) {
                    result.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Unterminated quote in ACP agent arguments");
        }
        if (!current.isEmpty()) {
            result.add(current.toString());
        }
        return result;
    }

    @Override
    public void close() {
        sessionId = null;
        capabilities = AgentCapabilities.NONE;

        Process child = process;
        process = null;
        JsonRpcConnection activeConnection = connection;
        connection = null;

        // Terminate the child first: this unblocks the JSON-RPC reader before its streams are closed.
        if (child != null) {
            child.destroy();
        }
        if (activeConnection != null) {
            try {
                activeConnection.close();
            } catch (IOException ignored) {
                // The child process may already have closed the streams.
            }
        }
    }
}
