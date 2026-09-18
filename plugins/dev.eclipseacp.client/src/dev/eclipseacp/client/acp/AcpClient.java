package dev.eclipseacp.client.acp;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.eclipseacp.client.AcpLog;
import dev.eclipseacp.client.agent.AgentCapabilities;
import dev.eclipseacp.client.agent.AgentClient;
import dev.eclipseacp.client.agent.AgentListener;
import dev.eclipseacp.client.agent.AuthMethod;
import dev.eclipseacp.client.agent.ConfigValue;
import dev.eclipseacp.client.agent.FileReadRequest;
import dev.eclipseacp.client.agent.FileWriteRequest;
import dev.eclipseacp.client.agent.PermissionOption;
import dev.eclipseacp.client.agent.PermissionRequest;
import dev.eclipseacp.client.agent.SessionInfo;
import dev.eclipseacp.client.agent.SessionPage;
import dev.eclipseacp.client.agent.PromptAttachment;
import dev.eclipseacp.client.agent.ToolCall;
import dev.eclipseacp.client.agent.AgentCommand;
import dev.eclipseacp.client.agent.ConfigOption;
import dev.eclipseacp.client.agent.ElicitationRequest;
import dev.eclipseacp.client.agent.Usage;
import dev.eclipseacp.client.mcp.McpServerConfig;

/** ACP v1 adapter. The rest of the plug-in talks to AgentClient only. */
public final class AcpClient implements AgentClient, JsonRpcHandler {
    private static final int PROTOCOL_VERSION = 1;
    private volatile AgentListener listener;
    private final String command;
    private final String arguments;
    private final boolean reviewFileChanges;
    private final List<McpServerConfig> mcpServers;
    private final AgentProcessLauncher processLauncher;
    private final JsonRpcTransportFactory transportFactory;
    private AgentProcess process;
    private JsonRpcTransport connection;
    private String sessionId;
    private volatile AgentCapabilities capabilities = AgentCapabilities.NONE;
    private volatile List<AuthMethod> authenticationMethods = List.of();
    private final ToolCallTracker toolCalls = new ToolCallTracker();
    private final Object sessionLifecycleLock = new Object();
    private final List<JsonObject> pendingNewSessionUpdates = new ArrayList<>();
    private boolean newSessionPending;
    private final List<String> recentAgentDiagnostics = new ArrayList<>();
    private volatile long promptSentAtNanos;
    private final AtomicBoolean firstAgentChunkReceived = new AtomicBoolean();

    public AcpClient(String command, String arguments, AgentListener listener) {
        this(command, arguments, listener, false);
    }

    public AcpClient(String command, String arguments, AgentListener listener, boolean reviewFileChanges) {
        this(command, arguments, listener, reviewFileChanges, List.of());
    }
    public AcpClient(String command, String arguments, AgentListener listener, boolean reviewFileChanges, List<McpServerConfig> mcpServers) {
        this(command, arguments, listener, reviewFileChanges, mcpServers,
                new DefaultAgentProcessLauncher(), new DefaultJsonRpcTransportFactory());
    }

    AcpClient(String command, String arguments, AgentListener listener, boolean reviewFileChanges, List<McpServerConfig> mcpServers,
            AgentProcessLauncher processLauncher, JsonRpcTransportFactory transportFactory) {
        this.command = Objects.requireNonNull(command).trim();
        this.arguments = arguments == null ? "" : arguments;
        this.listener = Objects.requireNonNull(listener);
        this.reviewFileChanges = reviewFileChanges;
        this.mcpServers = List.copyOf(mcpServers);
        this.processLauncher = Objects.requireNonNull(processLauncher);
        this.transportFactory = Objects.requireNonNull(transportFactory);
    }

    public CompletableFuture<Void> connect(Path workingDirectory) {
        return connect(workingDirectory, null);
    }

    @Override
    public CompletableFuture<Void> startNewSession(Path workingDirectory) {
        return startNewSession(workingDirectory, listener);
    }

    @Override
    public CompletableFuture<Void> startNewSession(Path workingDirectory, AgentListener newListener) {
        if (connection == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("ACP connection is not connected"));
        }
        Objects.requireNonNull(newListener);
        return closeSession().thenCompose(ignored -> {
            listener = newListener;
            return establishSession(() -> newSession(workingDirectory).thenAccept(result -> { }));
        });
    }

    @Override
    public CompletableFuture<Void> restoreSession(String restoredSessionId, Path workingDirectory) {
        if (restoredSessionId == null || restoredSessionId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("The ACP session ID is empty"));
        }
        return connect(workingDirectory, restoredSessionId);
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    private CompletableFuture<Void> connect(Path workingDirectory, String restoredSessionId) {
        AcpLog.info("ACP connection requested: command='" + command + "', workingDirectory='" + workingDirectory
                + "', reviewFileChanges=" + reviewFileChanges);
        if (command.isBlank()) {
            AcpLog.warn("ACP connection rejected because the command is empty", null);
            return CompletableFuture.failedFuture(new IllegalArgumentException("The ACP command is empty"));
        }

        synchronized (recentAgentDiagnostics) {
            recentAgentDiagnostics.clear();
        }
        try {
            process = processLauncher.launch(command, arguments, workingDirectory,
                    line -> {
                        rememberAgentDiagnostic(line);
                        listener.onStatus("Agent: " + line);
                    },
                    error -> listener.onError("Cannot read ACP agent diagnostics", error));
            connection = transportFactory.create(process.standardOutput(), process.standardInput(),
                    this,
                    error -> listener.onError("ACP connection failed", error));
            connection.start();
        } catch (IOException exception) {
            AcpLog.error("Could not start ACP agent process", exception);
            return CompletableFuture.failedFuture(exception);
        }

        AcpLog.info("ACP JSON-RPC reader started; sending initialize");
        listener.onStatus("Initializing " + command + "…");
        CompletableFuture<Void> connectionFuture = initialize()
                .thenCompose(ignored -> establishSession(() -> restoredSessionId == null
                        ? newSession(workingDirectory).thenAccept(result -> { })
                        : restoreAfterInitialize(restoredSessionId, workingDirectory)))
                .thenAccept(ignored -> listener.onStatus("Connected"));
        CompletableFuture<Void> reportedConnectionFuture = connectionFuture.exceptionallyCompose(error ->
                CompletableFuture.failedFuture(withAgentDiagnostics(unwrap(error))));
        reportedConnectionFuture.whenComplete((ignored, error) -> {
            if (error == null) {
                AcpLog.info("ACP connection established: sessionId='" + sessionId + "'");
            } else {
                AcpLog.error("ACP connection failed during initialization/session setup", unwrap(error));
            }
        });
        return reportedConnectionFuture;
    }

    private void rememberAgentDiagnostic(String line) {
        synchronized (recentAgentDiagnostics) {
            recentAgentDiagnostics.add(line);
            if (recentAgentDiagnostics.size() > 8) recentAgentDiagnostics.remove(0);
        }
    }

    private IOException withAgentDiagnostics(Throwable error) {
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        List<String> diagnostics;
        synchronized (recentAgentDiagnostics) {
            diagnostics = List.copyOf(recentAgentDiagnostics);
        }
        if (diagnostics.isEmpty()) return new IOException(message, error);
        return new IOException(message + "\n\nAgent diagnostics:\n" + String.join("\n", diagnostics), error);
    }

    /**
     * Some agents advertise login methods but require authentication only when a session is
     * actually opened. Retry that failed operation after the user selects a method, keeping
     * the existing ACP process and JSON-RPC connection alive.
     */
    private CompletableFuture<Void> establishSession(Supplier<CompletableFuture<Void>> operation) {
        return operation.get().exceptionallyCompose(error -> {
            if (!authenticationRequired(error)) return CompletableFuture.failedFuture(error);
            if (authenticationMethods.isEmpty()) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "The ACP agent requires authentication but did not advertise any authentication methods", error));
            }
            listener.onStatus("Authentication required");
            return listener.requestAuthentication(authenticationMethods).thenCompose(methodId -> {
                if (methodId == null || methodId.isBlank()) {
                    return CompletableFuture.failedFuture(new IOException("Authentication was cancelled"));
                }
                AcpLog.info("Authenticating ACP agent with method='" + methodId + "'");
                return authenticate(methodId).thenCompose(ignored -> operation.get());
            });
        });
    }

    private static boolean authenticationRequired(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            String message = current.getMessage();
            if (message != null && message.contains("Authentication required")) return true;
        }
        return false;
    }

    private CompletableFuture<Void> restoreAfterInitialize(String restoredSessionId, Path workingDirectory) {
        // session/load replays the conversation through user/agent message updates.
        // Prefer it for History so the newly selected chat is rendered, even when
        // the agent also offers session/resume (which restores context only).
        if (capabilities.loadSession()) return loadSession(restoredSessionId, workingDirectory);
        if (capabilities.sessionResume()) return resumeSession(restoredSessionId, workingDirectory);
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "The ACP agent does not support session/resume or session/load"));
    }

    @Override
    public AgentCapabilities capabilities() { return capabilities; }

    @Override
    public List<AuthMethod> authenticationMethods() { return authenticationMethods; }

    @Override
    public CompletableFuture<Void> authenticate(String methodId) {
        AuthMethod method = authenticationMethods.stream().filter(candidate -> candidate.id().equals(methodId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown ACP authentication method: " + methodId));
        if (method.isTerminal()) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException(
                    "Terminal authentication requires an Eclipse terminal integration"));
        }
        JsonObject params = new JsonObject();
        params.addProperty("methodId", method.id());
        return request("authenticate", params).thenAccept(ignored -> listener.onStatus("Authenticated"));
    }

    @Override
    public CompletableFuture<Void> logout() {
        if (!capabilities.logout()) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("The ACP agent does not support logout"));
        }
        return request("logout", new JsonObject()).thenAccept(ignored -> listener.onStatus("Logged out"));
    }

    public CompletableFuture<Void> prompt(String text) {
        if (sessionId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("ACP session is not connected"));
        }

        return sendPrompt(text, List.of(), true);
    }

    @Override
    public CompletableFuture<Void> prompt(String text, List<PromptAttachment> attachments) {
        if (sessionId == null) return CompletableFuture.failedFuture(new IllegalStateException("ACP session is not connected"));
        return sendPrompt(text, attachments == null ? List.of() : attachments, true);
    }

    private CompletableFuture<Void> sendPrompt(String text, List<PromptAttachment> attachments, boolean announce) {
        if (sessionId == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("ACP session is not connected"));
        }
        JsonObject content = new JsonObject();
        content.addProperty("type", "text");
        content.addProperty("text", text);
        JsonArray prompt = new JsonArray();
        prompt.add(content);
        try {
            for (PromptAttachment attachment : attachments) {
                if (attachment == null || attachment.path() == null) continue;
                byte[] data = Files.readAllBytes(attachment.path());
                if (data.length > 10 * 1024 * 1024) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException("Attachments must be at most 10 MiB"));
                }
                JsonObject binary = new JsonObject();
                binary.addProperty("type", attachment.mimeType().startsWith("audio/") ? "audio" : "image");
                binary.addProperty("mimeType", attachment.mimeType());
                binary.addProperty("data", java.util.Base64.getEncoder().encodeToString(data));
                prompt.add(binary);
            }
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        params.add("prompt", prompt);

        if (announce) listener.onStatus("Agent working…");
        long sentAt = System.nanoTime();
        promptSentAtNanos = sentAt;
        firstAgentChunkReceived.set(false);
        AcpLog.info("Sending ACP request: method='session/prompt', sessionId='" + sessionId
                + "', textLength=" + text.length());
        return connection.request("session/prompt", params)
                .thenAccept(result -> {
                    long completedAt = System.nanoTime();
                    AcpLog.info("ACP request completed: method='session/prompt', sessionId='" + sessionId
                            + "', stopReason='" + stopReason(result) + "', totalMs="
                            + elapsedMillis(sentAt, completedAt));
                    listener.onPromptCompleted(sentAt, completedAt);
                    if (announce) listener.onStatus(stopReason(result));
                })
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        AcpLog.error("ACP request failed: method='session/prompt', sessionId='" + sessionId + "'",
                                unwrap(error));
                    }
                });
    }

    @Override
    public CompletableFuture<Void> setConfigOption(String configId, ConfigValue configValue) {
        if (sessionId == null) return CompletableFuture.failedFuture(new IllegalStateException("ACP session is not connected"));
        if (configId == null || configId.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("Configuration option ID is empty"));
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        params.addProperty("configId", configId);
        if (configValue != null && configValue.type() != null && !configValue.type().isBlank()) {
            params.addProperty("type", configValue.type());
        }
        params.add("value", configValue == null ? com.google.gson.JsonNull.INSTANCE
                : new com.google.gson.Gson().toJsonTree(configValue.value()));
        return request("session/set_config_option", params).thenAccept(result -> {
            publishConfigOptions(result);
            listener.onStatus("Configuration updated");
        });
    }

    public void cancel() throws IOException {
        if (sessionId == null || connection == null) {
            return;
        }
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        connection.notification("session/cancel", params);
        AcpLog.info("ACP notification sent: method='session/cancel', sessionId='" + sessionId + "'");
        listener.onStatus("Cancellation requested");
    }

    private CompletableFuture<JsonObject> initialize() {
        JsonObject clientInfo = new JsonObject();
        clientInfo.addProperty("name", "Eclipse ACP");
        clientInfo.addProperty("version", "0.1.0");

        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL_VERSION);
        // Files are always mediated by Eclipse; review is a client-side policy selected for this session.
        JsonObject fileSystem = new JsonObject();
        fileSystem.addProperty("readTextFile", true);
        fileSystem.addProperty("writeTextFile", true);
        JsonObject clientCapabilities = new JsonObject();
        clientCapabilities.add("fs", fileSystem);
        JsonObject configOptions = new JsonObject();
        configOptions.add("boolean", new JsonObject());
        JsonObject session = new JsonObject();
        session.add("configOptions", configOptions);
        clientCapabilities.add("session", session);
        params.add("clientCapabilities", clientCapabilities);
        params.add("clientInfo", clientInfo);

        AcpLog.info("Sending ACP request: method='initialize', protocolVersion=" + PROTOCOL_VERSION);
        return connection.request("initialize", params).thenApply(result -> {
            int negotiated = result.has("protocolVersion")
                    ? result.get("protocolVersion").getAsInt()
                    : PROTOCOL_VERSION;
            if (negotiated != PROTOCOL_VERSION) {
                throw new IllegalStateException("Unsupported ACP protocol version: " + negotiated);
            }
            capabilities = capabilities(result);
            authenticationMethods = authenticationMethods(result);
            listener.onAuthenticationMethods(authenticationMethods);
            AcpLog.info("ACP initialize completed: negotiatedProtocolVersion=" + negotiated
                    + ", capabilities=" + capabilities);
            return result;
        });
    }

    static AgentCapabilities capabilities(JsonObject result) {
        JsonObject caps = result.has("agentCapabilities") && result.get("agentCapabilities").isJsonObject()
                ? result.getAsJsonObject("agentCapabilities") : new JsonObject();
        JsonObject session = object(caps.get("sessionCapabilities"));
        JsonObject prompt = object(caps.get("promptCapabilities"));
        JsonObject mcp = object(caps.get("mcpCapabilities"));
        JsonObject auth = object(caps.get("auth"));
        return new AgentCapabilities(
                bool(caps, "loadSession"),
                present(session, "list"),
                present(session, "resume"),
                present(session, "close"),
                present(session, "delete"),
                present(session, "additionalDirectories"),
                bool(prompt, "image"),
                bool(prompt, "audio"),
                bool(prompt, "embeddedContext"),
                bool(mcp, "http"),
                bool(mcp, "sse"),
                present(auth, "logout"));
    }

    static List<AuthMethod> authenticationMethods(JsonObject result) {
        if (!result.has("authMethods") || !result.get("authMethods").isJsonArray()) return List.of();
        List<AuthMethod> methods = new ArrayList<>();
        for (JsonElement element : result.getAsJsonArray("authMethods")) {
            JsonObject raw = object(element);
            String id = string(raw, "id");
            if (id.isBlank()) continue;
            List<String> args = new ArrayList<>();
            if (raw.has("args") && raw.get("args").isJsonArray()) {
                for (JsonElement argument : raw.getAsJsonArray("args")) if (argument.isJsonPrimitive()) args.add(argument.getAsString());
            }
            Map<String, String> environment = new LinkedHashMap<>();
            JsonObject rawEnvironment = object(raw.get("env"));
            for (String name : rawEnvironment.keySet()) {
                if (rawEnvironment.get(name).isJsonPrimitive()) environment.put(name, rawEnvironment.get(name).getAsString());
            }
            String type = string(raw, "type");
            methods.add(new AuthMethod(id, string(raw, "name"), string(raw, "description"),
                    type.isBlank() ? "agent" : type, List.copyOf(args), Map.copyOf(environment)));
        }
        return List.copyOf(methods);
    }

    private CompletableFuture<JsonObject> newSession(Path workingDirectory) {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", workingDirectory.toAbsolutePath().normalize().toString());
        params.add("mcpServers", configuredMcpServers());

        AcpLog.info("Sending ACP request: method='session/new', cwd='" + params.get("cwd").getAsString() + "'");
        synchronized (sessionLifecycleLock) {
            newSessionPending = true;
            pendingNewSessionUpdates.clear();
        }
        CompletableFuture<JsonObject> request;
        try {
            request = connection.request("session/new", params);
        } catch (RuntimeException error) {
            clearPendingNewSessionUpdates();
            throw error;
        }
        return request.thenApply(result -> {
            synchronized (sessionLifecycleLock) {
                if (!result.has("sessionId")) {
                    throw new IllegalStateException("ACP agent did not return a sessionId");
                }
                sessionId = result.get("sessionId").getAsString();
                newSessionPending = false;
                publishConfigOptions(result);
                AcpLog.info("ACP session created: sessionId='" + sessionId + "'");
                for (JsonObject pending : pendingNewSessionUpdates) {
                    String pendingSessionId = string(pending, "sessionId");
                    if (Objects.equals(sessionId, pendingSessionId)) {
                        deliverSessionUpdate(pending);
                    } else {
                        AcpLog.info("Ignoring ACP update for inactive sessionId='" + pendingSessionId + "'");
                    }
                }
                pendingNewSessionUpdates.clear();
            }
            return result;
        }).whenComplete((result, error) -> {
            if (error != null) clearPendingNewSessionUpdates();
        });
    }

    private void clearPendingNewSessionUpdates() {
        synchronized (sessionLifecycleLock) {
            newSessionPending = false;
            pendingNewSessionUpdates.clear();
        }
    }

    /** Delivers option state returned by session/new and session/set_config_option. */
    private void publishConfigOptions(JsonObject result) {
        if (result != null && result.has("configOptions") && result.get("configOptions").isJsonArray()) {
            listener.onConfigOptions(configOptions(result.getAsJsonArray("configOptions")));
        }
    }

    @Override
    public CompletableFuture<SessionPage> listSessions(Path workingDirectory, String cursor) {
        if (!capabilities.sessionList()) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("The ACP agent does not support session/list"));
        }
        JsonObject params = new JsonObject();
        if (workingDirectory != null) params.addProperty("cwd", workingDirectory.toAbsolutePath().normalize().toString());
        if (cursor != null && !cursor.isBlank()) params.addProperty("cursor", cursor);
        return request("session/list", params).thenApply(AcpClient::sessionPage);
    }

    @Override
    public CompletableFuture<Void> loadSession(String loadedSessionId, Path workingDirectory) {
        if (!capabilities.loadSession()) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("The ACP agent does not support session/load"));
        }
        if (sessionId != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Close the active ACP session before loading another one"));
        }
        return establishSession(() -> attachSession("session/load", loadedSessionId, workingDirectory));
    }

    @Override
    public CompletableFuture<Void> resumeSession(String resumedSessionId, Path workingDirectory) {
        if (!capabilities.sessionResume()) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("The ACP agent does not support session/resume"));
        }
        if (sessionId != null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Close the active ACP session before resuming another one"));
        }
        return establishSession(() -> attachSession("session/resume", resumedSessionId, workingDirectory));
    }

    private CompletableFuture<Void> attachSession(String method, String restoredSessionId, Path workingDirectory) {
        if (restoredSessionId == null || restoredSessionId.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("The ACP session ID is empty"));
        }
        String previousSessionId = sessionId;
        sessionId = restoredSessionId; // Required before load replays session/update notifications.
        JsonObject params = sessionParameters(workingDirectory);
        params.addProperty("sessionId", restoredSessionId);
        return request(method, params).thenAccept(result -> {
            publishConfigOptions(result);
            listener.onStatus("Session restored");
        })
                .whenComplete((ignored, error) -> { if (error != null) sessionId = previousSessionId; });
    }

    @Override
    public CompletableFuture<Void> closeSession() {
        if (sessionId == null || connection == null) return CompletableFuture.completedFuture(null);
        String closingSessionId = sessionId;
        if (!capabilities.sessionClose()) {
            AcpLog.info("ACP agent does not advertise session/close; detaching from sessionId='" + closingSessionId + "'");
            sessionId = null;
            toolCalls.clear();
            return CompletableFuture.completedFuture(null);
        }
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", closingSessionId);
        return connection.request("session/close", params).thenAccept(ignored -> {
            if (Objects.equals(sessionId, closingSessionId)) {
                sessionId = null;
                toolCalls.clear();
            }
            listener.onStatus("Session closed");
        });
    }

    @Override
    public CompletableFuture<Void> deleteSession(String deletedSessionId) {
        if (!capabilities.sessionDelete()) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("The ACP agent does not support session/delete"));
        }
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", Objects.requireNonNull(deletedSessionId).trim());
        return request("session/delete", params).thenAccept(ignored -> { });
    }

    @Override
    public void onNotification(String method, JsonObject params) {
        AcpLog.info("ACP notification received: method='" + method + "'");
        if (!"session/update".equals(method)) {
            return;
        }
        synchronized (sessionLifecycleLock) {
            String updateSessionId = string(params, "sessionId");
            if (connection != null && sessionId == null && newSessionPending && !updateSessionId.isBlank()) {
                pendingNewSessionUpdates.add(params.deepCopy());
                AcpLog.info("Buffering ACP update until session/new completes: sessionId='" + updateSessionId + "'");
                return;
            }
            // A live connection is attached to exactly one session in this client. Once
            // session/close completes, sessionId is null, so late updates from the retired
            // session must not leak into the conversation that is about to be loaded. Some
            // agents omit sessionId while replaying session/load, however; those updates belong
            // to the sole active session and must still reach the transcript.
            if (connection != null && (sessionId == null
                    || (!updateSessionId.isBlank() && !Objects.equals(sessionId, updateSessionId)))) {
                AcpLog.info("Ignoring ACP update for inactive sessionId='" + updateSessionId + "'");
                return;
            }
            deliverSessionUpdate(params);
        }
    }

    private void deliverSessionUpdate(JsonObject params) {
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
                long receivedAt = System.nanoTime();
                long sentAt = promptSentAtNanos;
                if (sentAt != 0 && firstAgentChunkReceived.compareAndSet(false, true)) {
                    AcpLog.info("ACP first agent_message_chunk received: sessionId='" + updateSessionId
                            + "', afterSendMs=" + elapsedMillis(sentAt, receivedAt));
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

    @Override
    public CompletableFuture<JsonElement> onRequest(String method, JsonObject params) {
        AcpLog.info("ACP server request received: method='" + method + "'");
        if (isForRetiredSession(params)) {
            AcpLog.info("Rejecting ACP request for inactive sessionId='" + string(params, "sessionId") + "'");
            if ("session/request_permission".equals(method)) {
                return CompletableFuture.completedFuture(cancelledPermissionResult());
            }
            if ("elicitation/create".equals(method)) {
                return CompletableFuture.completedFuture(cancelledElicitationResult());
            }
            return CompletableFuture.failedFuture(new IllegalStateException("The ACP session is no longer active"));
        }
        if ("fs/read_text_file".equals(method)) return readTextFile(params);
        if ("fs/write_text_file".equals(method)) return stageFileWrite(params);
        if ("elicitation/create".equals(method)) {
            ElicitationRequest request = new ElicitationRequest(nonBlank(string(params, "title"), "Agent input required"),
                    nonBlank(string(params, "message"), string(params, "title")));
            return listener.requestElicitation(request).thenApply(answer -> {
                JsonObject result = new JsonObject();
                if (answer == null) result.addProperty("action", "cancel");
                else { result.addProperty("action", "accept"); result.addProperty("content", answer); }
                return result;
            });
        }
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
        ToolCall permissionToolCall = toolCalls.accept(toolCall);
        if (permissionToolCall != null) listener.onToolCall(permissionToolCall);
        String title = string(params, "title");
        if (title.isBlank()) title = permissionToolCall == null ? string(toolCall, "title") : permissionToolCall.title();
        if (title.isBlank()) {
            title = "The agent requests permission";
        }

        return listener.requestPermission(new PermissionRequest(title, permissionToolCall, options)).thenApply(optionId -> {
            return permissionResult(optionId);
        });
    }

    private boolean isForRetiredSession(JsonObject params) {
        return connection != null && params.has("sessionId") && !Objects.equals(sessionId, string(params, "sessionId"));
    }

    private static JsonObject cancelledPermissionResult() {
        return permissionResult(null);
    }

    private static JsonObject permissionResult(String optionId) {
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
    }

    private static JsonObject cancelledElicitationResult() {
        JsonObject result = new JsonObject();
        result.addProperty("action", "cancel");
        return result;
    }

    private CompletableFuture<JsonElement> readTextFile(JsonObject params) {
        Integer line = integer(params, "line");
        Integer limit = integer(params, "limit");
        return listener.readTextFile(new FileReadRequest(string(params, "sessionId"), string(params, "path"), line, limit))
                .thenApply(content -> {
                    JsonObject result = new JsonObject();
                    result.addProperty("content", content);
                    return result;
                });
    }

    private CompletableFuture<JsonElement> stageFileWrite(JsonObject params) {
        return listener.stageFileWrite(new FileWriteRequest(string(params, "sessionId"), string(params, "path"), string(params, "content")))
                .thenApply(ignored -> new JsonObject());
    }

    private static String stopReason(JsonObject result) {
        String reason = string(result, "stopReason");
        return reason.isBlank() ? "Ready" : "Ready (" + reason + ")";
    }

    private static long elapsedMillis(long startedAt, long completedAt) {
        return TimeUnit.NANOSECONDS.toMillis(completedAt - startedAt);
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
            // ACP content is a tagged union.  In particular, some agents serialize
            // tool calls/results as chunks containing a textual preview.  Treating
            // every object with a `text` member as an agent message leaks those
            // previews into the transcript and bypasses the hide-agent-commands
            // preference.  Only an actual text content block belongs in the chat.
            if ("text".equals(string(object, "type"))
                    && object.has("text") && object.get("text").isJsonPrimitive()) {
                return object.get("text").getAsString();
            }
            // Older ACP implementations sometimes omit the tag for an otherwise
            // normal text block. Keep that compatibility, but never unwrap a
            // declared non-text block (such as tool_call or tool_result).
            if (!object.has("type") && object.has("text") && object.get("text").isJsonPrimitive()) {
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

    private static List<AgentCommand> commands(JsonObject update) {
        JsonElement entries = update.has("availableCommands") ? update.get("availableCommands") : update.get("commands");
        if (entries == null || !entries.isJsonArray()) return List.of();
        List<AgentCommand> commands = new ArrayList<>();
        for (JsonElement entry : entries.getAsJsonArray()) {
            JsonObject command = object(entry);
            String name = nonBlank(string(command, "name"), string(command, "command"));
            if (!name.isBlank()) commands.add(new AgentCommand(name, string(command, "description")));
        }
        return List.copyOf(commands);
    }

    private static List<ConfigOption> configOptions(JsonArray options) {
        List<ConfigOption> converted = new ArrayList<>();
        for (JsonElement option : options) converted.addAll(configOptions(object(option)));
        return List.copyOf(converted);
    }

    private static List<ConfigOption> configOptions(JsonObject option) {
        String id = nonBlank(string(option, "configId"), string(option, "id"));
        if (id.isBlank()) return List.of();
        JsonElement value = option.has("currentValue") ? option.get("currentValue") : option.get("value");
        List<ConfigOption.Choice> choices = new ArrayList<>();
        if (option.has("options") && option.get("options").isJsonArray()) for (JsonElement raw : option.getAsJsonArray("options")) {
            if (raw.isJsonPrimitive()) {
                String choice = raw.getAsString(); choices.add(new ConfigOption.Choice(choice, choice, ""));
            } else {
                JsonObject choice = object(raw);
                String choiceValue = string(choice, "value");
                if (!choiceValue.isBlank()) choices.add(new ConfigOption.Choice(choiceValue,
                        nonBlank(string(choice, "label"), nonBlank(string(choice, "name"), choiceValue)), string(choice, "description")));
            }
        }
        return List.of(new ConfigOption(id, nonBlank(string(option, "name"), id), string(option, "description"),
                string(option, "category"), new ConfigValue(null, javaValue(value)), List.copyOf(choices)));
    }

    private static Usage usage(JsonObject update) {
        return new Usage(longValue(update, "inputTokens"), longValue(update, "outputTokens"),
                longValue(update, "totalTokens"), string(update, "cost"));
    }

    private static Long longValue(JsonObject object, String name) {
        return object.has(name) && object.get(name).isJsonPrimitive() && object.get(name).getAsJsonPrimitive().isNumber()
                ? object.get(name).getAsLong() : null;
    }

    private static Object javaValue(JsonElement value) {
        return value == null || value.isJsonNull() ? null : new com.google.gson.Gson().fromJson(value, Object.class);
    }

    private static String nonBlank(String first, String fallback) {
        return first == null || first.isBlank() ? fallback : first;
    }

    private static String string(JsonObject object, String member) {
        return object.has(member) && object.get(member).isJsonPrimitive()
                ? object.get(member).getAsString()
                : "";
    }

    private static Integer integer(JsonObject object, String member) {
        return object.has(member) && object.get(member).isJsonPrimitive() ? object.get(member).getAsInt() : null;
    }

    private CompletableFuture<JsonObject> request(String method, JsonObject params) {
        if (connection == null) return CompletableFuture.failedFuture(new IllegalStateException("ACP connection is not connected"));
        AcpLog.info("Sending ACP request: method='" + method + "'");
        return connection.request(method, params);
    }

    private JsonObject sessionParameters(Path workingDirectory) {
        if (workingDirectory == null) throw new IllegalArgumentException("The ACP working directory is required");
        JsonObject params = new JsonObject();
        params.addProperty("cwd", workingDirectory.toAbsolutePath().normalize().toString());
        params.add("mcpServers", configuredMcpServers());
        return params;
    }
    private JsonArray configuredMcpServers() {
        JsonArray result = new JsonArray();
        for (McpServerConfig server : mcpServers) result.add(server.toAcp(capabilities.mcpHttp(), capabilities.mcpSse()));
        return result;
    }

    static SessionPage sessionPage(JsonObject result) {
        return new SessionPage(sessionInfos(result.get("sessions")), string(result, "nextCursor"));
    }

    private static List<SessionInfo> sessionInfos(JsonElement element) {
        if (element == null || !element.isJsonArray()) return List.of();
        List<SessionInfo> sessions = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            JsonObject raw = object(item);
            String id = string(raw, "sessionId");
            String cwd = string(raw, "cwd");
            if (id.isBlank() || cwd.isBlank()) continue;
            List<String> directories = new ArrayList<>();
            if (raw.has("additionalDirectories") && raw.get("additionalDirectories").isJsonArray()) {
                for (JsonElement directory : raw.getAsJsonArray("additionalDirectories")) {
                    if (directory.isJsonPrimitive()) directories.add(directory.getAsString());
                }
            }
            sessions.add(new SessionInfo(id, cwd, List.copyOf(directories), string(raw, "title"), string(raw, "updatedAt")));
        }
        return List.copyOf(sessions);
    }

    private static boolean bool(JsonObject object, String member) {
        return object.has(member) && object.get(member).isJsonPrimitive() && object.get(member).getAsBoolean();
    }

    private static boolean present(JsonObject object, String member) {
        return object.has(member) && !object.get(member).isJsonNull();
    }

    @Override
    public void close() {
        AcpLog.info("Closing ACP connection: sessionId='" + sessionId + "'");
        try {
            closeSession().get(2, TimeUnit.SECONDS);
        } catch (Exception exception) {
            AcpLog.warn("ACP session could not be closed cleanly", unwrap(exception));
        }
        sessionId = null;
        capabilities = AgentCapabilities.NONE;
        authenticationMethods = List.of();

        AgentProcess child = process;
        process = null;
        JsonRpcTransport activeConnection = connection;
        connection = null;

        // Terminate the child first: this unblocks the JSON-RPC reader before its streams are closed.
        if (child != null) {
            try {
                child.close();
            } catch (IOException exception) {
                AcpLog.warn("ACP agent process could not be closed cleanly", exception);
            }
        }
        if (activeConnection != null) {
            try {
                activeConnection.close();
            } catch (IOException ignored) {
                AcpLog.warn("ACP connection streams were already closed", ignored);
                // The child process may already have closed the streams.
            }
        }
    }

    private static Throwable unwrap(Throwable error) {
        return error.getCause() == null ? error : error.getCause();
    }
}
