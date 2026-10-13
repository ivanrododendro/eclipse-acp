package dev.eclipseacp.client.acp;

import static dev.eclipseacp.client.acp.AcpMessageCodec.cancelledPermissionResult;
import static dev.eclipseacp.client.acp.AcpMessageCodec.permissionResult;
import static dev.eclipseacp.client.acp.AcpMessageCodec.cancelledElicitationResult;
import static dev.eclipseacp.client.acp.AcpMessageCodec.object;
import static dev.eclipseacp.client.acp.AcpMessageCodec.configOptions;
import static dev.eclipseacp.client.acp.AcpMessageCodec.nonBlank;
import static dev.eclipseacp.client.acp.AcpMessageCodec.string;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.eclipseacp.client.agent.AgentCapabilities;
import dev.eclipseacp.client.agent.AgentClient;
import dev.eclipseacp.client.agent.AgentListener;
import dev.eclipseacp.client.agent.AuthMethod;
import dev.eclipseacp.client.agent.ConfigValue;
import dev.eclipseacp.client.agent.PermissionOption;
import dev.eclipseacp.client.agent.PermissionRequest;
import dev.eclipseacp.client.agent.SessionPage;
import dev.eclipseacp.client.agent.PromptAttachment;
import dev.eclipseacp.client.agent.PromptAttachmentPolicy;
import dev.eclipseacp.client.agent.ToolCall;
import dev.eclipseacp.client.agent.ElicitationRequest;
import dev.eclipseacp.client.mcp.McpServerConfig;

/** ACP v1 adapter. The rest of the plug-in talks to AgentClient only. */
public final class AcpClient implements AgentClient, JsonRpcHandler {
    private static final int PROTOCOL_VERSION = 1;
    private static final long AUTHENTICATION_TIMEOUT_MINUTES = 15;
    private static final long PERMISSION_TIMEOUT_MINUTES = 10;
    private static final long ELICITATION_TIMEOUT_MINUTES = 15;
    private volatile AgentListener listener;
    private final String command;
    private final List<McpServerConfig> mcpServers;
    private final DiagnosticSink diagnostics;
    private final AcpConnectionLifecycle connection;
    private String sessionId;
    private volatile AgentCapabilities capabilities = AgentCapabilities.NONE;
    private volatile List<AuthMethod> authenticationMethods = List.of();
    private final ToolCallTracker toolCalls = new ToolCallTracker();
    private final Object sessionLifecycleLock = new Object();
    private final List<JsonObject> pendingNewSessionUpdates = new ArrayList<>();
    private boolean newSessionPending;
    private final PendingAgentInteractions interactions;
    private final AcpSessionUpdateDispatcher updates;

    public AcpClient(String command, String arguments, AgentListener listener) {
        this(command, arguments, listener, List.of());
    }

    public AcpClient(String command, String arguments, AgentListener listener, List<McpServerConfig> mcpServers) {
        this(command, arguments, listener, mcpServers,
                new DefaultAgentProcessLauncher(), new DefaultJsonRpcTransportFactory());
    }

    AcpClient(String command, String arguments, AgentListener listener, List<McpServerConfig> mcpServers,
            AgentProcessLauncher processLauncher, JsonRpcTransportFactory transportFactory) {
        this(command, arguments, listener, mcpServers, processLauncher, transportFactory, DiagnosticSink.eclipse());
    }

    AcpClient(String command, String arguments, AgentListener listener, List<McpServerConfig> mcpServers,
            AgentProcessLauncher processLauncher, JsonRpcTransportFactory transportFactory, DiagnosticSink diagnostics) {
        this.diagnostics = Objects.requireNonNull(diagnostics);
        this.command = Objects.requireNonNull(command).trim();
        this.listener = Objects.requireNonNull(listener);
        this.mcpServers = List.copyOf(mcpServers);
        this.connection = new AcpConnectionLifecycle(this.command, arguments == null ? "" : arguments,
                processLauncher, transportFactory, diagnostics);
        this.interactions = new PendingAgentInteractions(diagnostics);
        this.updates = new AcpSessionUpdateDispatcher(toolCalls, diagnostics);
    }

    public CompletableFuture<Void> connect(Path workingDirectory) {
        return connect(workingDirectory, null, true);
    }

    @Override
    public CompletableFuture<Void> connectForSessionList(Path workingDirectory) {
        return connect(workingDirectory, null, false);
    }

    @Override
    public CompletableFuture<Void> startNewSession(Path workingDirectory) {
        return startNewSession(workingDirectory, listener);
    }

    @Override
    public CompletableFuture<Void> startNewSession(Path workingDirectory, AgentListener newListener) {
        if (connection.isClosing() || !connection.isConnected()) {
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
        return connect(workingDirectory, restoredSessionId, true);
    }

    @Override
    public String sessionId() {
        return sessionId;
    }

    private CompletableFuture<Void> connect(Path workingDirectory, String restoredSessionId, boolean createSession) {
        diagnostics.info("ACP connection requested: command='" + command + "', workingDirectory='" + workingDirectory
                + "'");
        if (command.isBlank()) {
            diagnostics.warn("ACP connection rejected because the command is empty", null);
            return CompletableFuture.failedFuture(new IllegalArgumentException("The ACP command is empty"));
        }
        if (connection.isClosing()) {
            return CompletableFuture.failedFuture(new IllegalStateException("ACP connection is closed"));
        }

        return connection.start(workingDirectory, this,
                line -> listener.onStatus("Agent: " + line),
                error -> listener.onError("Cannot read ACP agent diagnostics", error),
                error -> {
                    interactions.cancelAll();
                    listener.onConnectionClosed("ACP connection failed", error);
                }).thenCompose(ignored -> initializeSession(workingDirectory, restoredSessionId, createSession));
    }

    private CompletableFuture<Void> initializeSession(Path workingDirectory, String restoredSessionId, boolean createSession) {
        diagnostics.info("ACP JSON-RPC reader started; sending initialize");
        listener.onStatus("Initializing " + command + "…");
        CompletableFuture<Void> connectionFuture = initialize()
                .thenCompose(ignored -> createSession
                        ? establishSession(() -> restoredSessionId == null
                                ? newSession(workingDirectory).thenAccept(result -> { })
                                : restoreAfterInitialize(restoredSessionId, workingDirectory))
                        : CompletableFuture.completedFuture(null))
                .thenAccept(ignored -> listener.onStatus("Connected"));
        CompletableFuture<Void> reportedConnectionFuture = connectionFuture.exceptionallyCompose(error ->
                CompletableFuture.failedFuture(connection.withAgentDiagnostics(unwrap(error))));
        reportedConnectionFuture.whenComplete((ignored, error) -> {
            if (error == null) {
                diagnostics.info("ACP connection established: sessionId='" + sessionId + "'");
            } else {
                // The reported exception may include raw stderr for the user's status view.
                // Keep that useful context out of ordinary logs, where it may expose secrets.
                diagnostics.error("ACP connection failed during initialization/session setup ("
                        + unwrap(error).getClass().getSimpleName() + ")", null);
            }
        });
        return reportedConnectionFuture;
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
            return interactions.request(() -> listener.requestAuthentication(authenticationMethods),
                    AUTHENTICATION_TIMEOUT_MINUTES, "authentication").thenCompose(methodId -> {
                if (methodId == null || methodId.isBlank()) {
                    return CompletableFuture.failedFuture(new IOException("Authentication was cancelled"));
                }
                diagnostics.info("Authenticating ACP agent with method='" + methodId + "'");
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
        List<AcpMessageCodec.BinaryContent> binaries = new ArrayList<>();
        try {
            for (PromptAttachment attachment : attachments) {
                if (attachment == null || attachment.path() == null) continue;
                if (!PromptAttachmentPolicy.isSupportedMimeType(attachment.mimeType())) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException(
                            "Attachments must be images or audio files"));
                }
                byte[] data = Files.readAllBytes(attachment.path());
                if (data.length > PromptAttachmentPolicy.MAX_BYTES) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException("Attachments must be at most 10 MiB"));
                }
                binaries.add(new AcpMessageCodec.BinaryContent(attachment.mimeType(), data));
            }
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        JsonObject params = AcpMessageCodec.promptParameters(sessionId, text, binaries);

        if (announce) listener.onStatus("Agent working…");
        long sentAt = System.nanoTime();
        updates.promptStarted(sentAt);
        diagnostics.info("Sending ACP request: method='session/prompt', sessionId='" + sessionId
                + "', textLength=" + text.length());
        return connection.request("session/prompt", params)
                .thenAccept(result -> {
                    long completedAt = System.nanoTime();
                    diagnostics.info("ACP request completed: method='session/prompt', sessionId='" + sessionId
                            + "', stopReason='" + stopReason(result) + "', totalMs="
                            + elapsedMillis(sentAt, completedAt));
                    listener.onPromptCompleted(sentAt, completedAt);
                    if (announce) listener.onStatus(stopReason(result));
                })
                .whenComplete((ignored, error) -> {
                    if (error != null) {
                        diagnostics.error("ACP request failed: method='session/prompt', sessionId='" + sessionId + "'",
                                unwrap(error));
                    }
                });
    }

    @Override
    public CompletableFuture<Void> setConfigOption(String configId, ConfigValue configValue) {
        if (sessionId == null) return CompletableFuture.failedFuture(new IllegalStateException("ACP session is not connected"));
        if (configId == null || configId.isBlank()) return CompletableFuture.failedFuture(new IllegalArgumentException("Configuration option ID is empty"));
        JsonObject params = AcpMessageCodec.configOptionParameters(sessionId, configId, configValue);
        return request("session/set_config_option", params).thenAccept(result -> {
            publishConfigOptions(result);
            listener.onStatus("Configuration updated");
        });
    }

    public void cancel() throws IOException {
        interactions.cancelAll();
        if (sessionId == null || !connection.isConnected()) {
            return;
        }
        JsonObject params = new JsonObject();
        params.addProperty("sessionId", sessionId);
        connection.notification("session/cancel", params);
        diagnostics.info("ACP notification sent: method='session/cancel', sessionId='" + sessionId + "'");
        listener.onStatus("Cancellation requested");
    }

    private CompletableFuture<JsonObject> initialize() {
        JsonObject params = AcpMessageCodec.initializeParameters(PROTOCOL_VERSION);

        diagnostics.info("Sending ACP request: method='initialize', protocolVersion=" + PROTOCOL_VERSION);
        return connection.request("initialize", params).thenApply(result -> {
            int negotiated = result.has("protocolVersion")
                    ? result.get("protocolVersion").getAsInt()
                    : PROTOCOL_VERSION;
            if (negotiated != PROTOCOL_VERSION) {
                throw new IllegalStateException("Unsupported ACP protocol version: " + negotiated);
            }
            capabilities = AcpMessageCodec.capabilities(result);
            authenticationMethods = AcpMessageCodec.authenticationMethods(result);
            listener.onAuthenticationMethods(authenticationMethods);
            diagnostics.info("ACP initialize completed: negotiatedProtocolVersion=" + negotiated
                    + ", capabilities=" + capabilities);
            return result;
        });
    }

    private CompletableFuture<JsonObject> newSession(Path workingDirectory) {
        JsonObject params = new JsonObject();
        params.addProperty("cwd", workingDirectory.toAbsolutePath().normalize().toString());
        params.add("mcpServers", configuredMcpServers());

        diagnostics.info("Sending ACP request: method='session/new', cwd='" + params.get("cwd").getAsString() + "'");
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
                diagnostics.info("ACP session created: sessionId='" + sessionId + "'");
                for (JsonObject pending : pendingNewSessionUpdates) {
                    String pendingSessionId = string(pending, "sessionId");
                    if (Objects.equals(sessionId, pendingSessionId)) {
                        updates.dispatch(pending, listener);
                    } else {
                        diagnostics.info("Ignoring ACP update for inactive sessionId='" + pendingSessionId + "'");
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
        return request("session/list", params).thenApply(AcpMessageCodec::sessionPage);
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
        interactions.cancelAll();
        if (sessionId == null || !connection.isConnected()) return CompletableFuture.completedFuture(null);
        String closingSessionId = sessionId;
        if (!capabilities.sessionClose()) {
            diagnostics.info("ACP agent does not advertise session/close; detaching from sessionId='" + closingSessionId + "'");
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
        diagnostics.trace(() -> "ACP notification received: method='" + method + "'");
        if (!"session/update".equals(method)) {
            return;
        }
        synchronized (sessionLifecycleLock) {
            String updateSessionId = string(params, "sessionId");
            if (connection.isConnected() && sessionId == null && newSessionPending && !updateSessionId.isBlank()) {
                pendingNewSessionUpdates.add(params.deepCopy());
                diagnostics.info("Buffering ACP update until session/new completes: sessionId='" + updateSessionId + "'");
                return;
            }
            // A live connection is attached to exactly one session in this client. Once
            // session/close completes, sessionId is null, so late updates from the retired
            // session must not leak into the conversation that is about to be loaded. Some
            // agents omit sessionId while replaying session/load, however; those updates belong
            // to the sole active session and must still reach the transcript.
            if (connection.isConnected() && (sessionId == null
                    || (!updateSessionId.isBlank() && !Objects.equals(sessionId, updateSessionId)))) {
                diagnostics.info("Ignoring ACP update for inactive sessionId='" + updateSessionId + "'");
                return;
            }
            updates.dispatch(params, listener);
        }
    }

    @Override
    public CompletableFuture<JsonElement> onRequest(String method, JsonObject params) {
        diagnostics.info("ACP server request received: method='" + method + "'");
        if (isForRetiredSession(params)) {
            diagnostics.info("Rejecting ACP request for inactive sessionId='" + string(params, "sessionId") + "'");
            if ("session/request_permission".equals(method)) {
                return CompletableFuture.completedFuture(cancelledPermissionResult());
            }
            if ("elicitation/create".equals(method)) {
                return CompletableFuture.completedFuture(cancelledElicitationResult());
            }
            return CompletableFuture.failedFuture(new IllegalStateException("The ACP session is no longer active"));
        }
        if ("elicitation/create".equals(method)) {
            ElicitationRequest request = new ElicitationRequest(nonBlank(string(params, "title"), "Agent input required"),
                    nonBlank(string(params, "message"), string(params, "title")));
            return interactions.request(() -> listener.requestElicitation(request), ELICITATION_TIMEOUT_MINUTES,
                    "elicitation").thenApply(answer -> {
                return AcpMessageCodec.elicitationResult(answer);
            });
        }
        if (!"session/request_permission".equals(method)) {
            return CompletableFuture.failedFuture(new UnsupportedOperationException("Unsupported ACP method: " + method));
        }

        List<PermissionOption> options = AcpMessageCodec.permissionOptions(params);

        JsonObject toolCall = object(params.get("toolCall"));
        ToolCall permissionToolCall = toolCalls.accept(toolCall);
        if (permissionToolCall != null) listener.onToolCall(permissionToolCall);
        String title = string(params, "title");
        if (title.isBlank()) title = permissionToolCall == null ? string(toolCall, "title") : permissionToolCall.title();
        if (title.isBlank()) {
            title = "The agent requests permission";
        }
        String permissionTitle = title;

        return interactions.request(() -> listener.requestPermission(new PermissionRequest(permissionTitle, permissionToolCall, options)),
                PERMISSION_TIMEOUT_MINUTES, "permission").thenApply(optionId -> {
            return permissionResult(optionId);
        });
    }

    private boolean isForRetiredSession(JsonObject params) {
        return connection.isConnected() && params.has("sessionId") && !Objects.equals(sessionId, string(params, "sessionId"));
    }

    private static String stopReason(JsonObject result) {
        String reason = string(result, "stopReason");
        return reason.isBlank() ? "Ready" : "Ready (" + reason + ")";
    }

    private static long elapsedMillis(long startedAt, long completedAt) {
        return TimeUnit.NANOSECONDS.toMillis(completedAt - startedAt);
    }

    private CompletableFuture<JsonObject> request(String method, JsonObject params) {
        if (!connection.isConnected()) return CompletableFuture.failedFuture(new IllegalStateException("ACP connection is not connected"));
        diagnostics.info("Sending ACP request: method='" + method + "'");
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

    @Override
    public void close() {
        if (!connection.beginClose()) return;
        diagnostics.info("Closing ACP connection: sessionId='" + sessionId + "'");
        interactions.close();
        try {
            closeSession().get(2, TimeUnit.SECONDS);
        } catch (Exception exception) {
            diagnostics.warn("ACP session could not be closed cleanly", unwrap(exception));
        }
        sessionId = null;
        capabilities = AgentCapabilities.NONE;
        authenticationMethods = List.of();

        connection.close();
    }

    private static Throwable unwrap(Throwable error) {
        return error.getCause() == null ? error : error.getCause();
    }
}
