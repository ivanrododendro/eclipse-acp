package dev.eclipseacp.client.acp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.eclipseacp.client.AcpLog;

final class JsonRpcConnection implements JsonRpcTransport {
    private final Gson gson = new Gson();
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final JsonRpcHandler handler;
    private final Consumer<Throwable> errorHandler;
    private final AtomicLong nextId = new AtomicLong();
    private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private volatile boolean closed;

    JsonRpcConnection(Reader reader, Writer writer, JsonRpcHandler handler, Consumer<Throwable> errorHandler) {
        this.reader = new BufferedReader(Objects.requireNonNull(reader));
        this.writer = new BufferedWriter(Objects.requireNonNull(writer));
        this.handler = Objects.requireNonNull(handler);
        this.errorHandler = Objects.requireNonNull(errorHandler);
    }

    @Override
    public void start() {
        AcpLog.info("Starting ACP JSON-RPC reader thread");
        Thread thread = new Thread(this::readLoop, "eclipse-acp-jsonrpc");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public CompletableFuture<JsonObject> request(String method, JsonObject params) {
        long startedAt = System.nanoTime();
        long id = nextId.getAndIncrement();
        AcpLog.info("JSON-RPC request sent: id=" + id + ", method='" + method + "'");
        JsonObject message = envelope(method, params);
        message.addProperty("id", id);

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        pending.put(Long.toString(id), future);
        try {
            send(message);
            AcpLog.info("JSON-RPC request flushed: id=" + id + ", method='" + method
                    + "', writeMs=" + elapsedMillis(startedAt, System.nanoTime()));
        } catch (IOException exception) {
            AcpLog.error("JSON-RPC request could not be written: id=" + id + ", method='" + method + "'", exception);
            pending.remove(Long.toString(id));
            future.completeExceptionally(exception);
        }
        return future;
    }

    @Override
    public void notification(String method, JsonObject params) throws IOException {
        AcpLog.info("JSON-RPC notification sent: method='" + method + "'");
        send(envelope(method, params));
    }

    private JsonObject envelope(String method, JsonObject params) {
        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.addProperty("method", method);
        message.add("params", params == null ? new JsonObject() : params);
        return message;
    }

    private void readLoop() {
        try {
            String line;
            while (!closed && (line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    AcpLog.info("JSON-RPC message received: " + summarize(line));
                    dispatch(JsonParser.parseString(line).getAsJsonObject());
                }
            }
            if (!closed) {
                AcpLog.warn("ACP agent closed its output stream", null);
                failPending(new IOException("ACP agent closed its output stream"));
            }
        } catch (Exception exception) {
            if (!closed) {
                AcpLog.error("ACP JSON-RPC reader failed", exception);
                failPending(exception);
                errorHandler.accept(exception);
            }
        }
    }

    private void dispatch(JsonObject message) {
        if (message.has("method")) {
            String method = message.get("method").getAsString();
            JsonObject params = objectOrEmpty(message.get("params"));
            if (message.has("id")) {
                handleIncomingRequest(message.get("id"), method, params);
            } else {
                handler.onNotification(method, params);
            }
            return;
        }

        if (message.has("id")) {
            CompletableFuture<JsonObject> future = pending.remove(key(message.get("id")));
            if (future == null) {
                return;
            }
            if (message.has("error")) {
                AcpLog.error("JSON-RPC error response received: id=" + key(message.get("id")),
                        new IOException(message.get("error").toString()));
                future.completeExceptionally(new IOException("ACP error: " + message.get("error")));
            } else {
                AcpLog.info("JSON-RPC response received: id=" + key(message.get("id")));
                future.complete(objectOrEmpty(message.get("result")));
            }
        }
    }

    private void handleIncomingRequest(JsonElement id, String method, JsonObject params) {
        CompletableFuture<JsonElement> response;
        try {
            response = handler.onRequest(method, params);
        } catch (RuntimeException exception) {
            sendError(id, -32603, exception.getMessage());
            return;
        }

        response.whenComplete((result, error) -> {
            if (error != null) {
                sendError(id, -32603, error.getMessage());
                return;
            }
            JsonObject message = new JsonObject();
            message.addProperty("jsonrpc", "2.0");
            message.add("id", id);
            message.add("result", result == null ? new JsonObject() : result);
            try {
                send(message);
                AcpLog.info("JSON-RPC response sent: id=" + id);
            } catch (IOException exception) {
                AcpLog.error("Could not send JSON-RPC response: id=" + id, exception);
                errorHandler.accept(exception);
            }
        });
    }

    private void sendError(JsonElement id, int code, String detail) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", detail == null || detail.isBlank() ? "Internal error" : detail);

        JsonObject message = new JsonObject();
        message.addProperty("jsonrpc", "2.0");
        message.add("id", id);
        message.add("error", error);
        try {
            send(message);
        } catch (IOException exception) {
            AcpLog.error("Could not send JSON-RPC error response: id=" + id, exception);
            errorHandler.accept(exception);
        }
    }

    private void send(JsonObject message) throws IOException {
        synchronized (writeLock) {
            if (closed) {
                throw new IOException("ACP connection is closed");
            }
            writer.write(gson.toJson(message));
            writer.newLine();
            writer.flush();
        }
    }

    private static JsonObject objectOrEmpty(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static String key(JsonElement id) {
        return id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()
                ? id.getAsString()
                : id.toString();
    }

    private static String summarize(String line) {
        JsonObject message = JsonParser.parseString(line).getAsJsonObject();
        String method = message.has("method") ? message.get("method").getAsString() : "<response>";
        String id = message.has("id") ? ", id=" + key(message.get("id")) : "";
        return "method='" + method + "'" + id;
    }

    private static long elapsedMillis(long startedAt, long completedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(completedAt - startedAt);
    }

    private void failPending(Throwable error) {
        pending.values().forEach(future -> future.completeExceptionally(error));
        pending.clear();
    }

    @Override
    public void close() throws IOException {
        AcpLog.info("Closing ACP JSON-RPC connection");
        closed = true;
        failPending(new IOException("ACP connection closed"));
        reader.close();
        writer.close();
    }
}
