package dev.eclipseacp.client.acp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Function;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

final class JsonRpcConnection implements JsonRpcTransport {
    private static final int MAX_FRAME_CHARACTERS = 1024 * 1024;
    private static final int MAX_PENDING_REQUESTS = 128;
    private final Gson gson = new Gson();
    private final BufferedReader reader;
    private final BufferedWriter writer;
    private final JsonRpcHandler handler;
    private final Consumer<Throwable> errorHandler;
    private final Function<String, Long> requestTimeoutMillis;
    private final DiagnosticSink diagnostics;
    private final AtomicLong nextId = new AtomicLong();
    private final AtomicBoolean started = new AtomicBoolean();
    private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    /** Serializes adding pending requests with the terminal state transition. */
    private final Object stateLock = new Object();
    /** A bounded, single-writer queue keeps pipe backpressure away from SWT and the reader. */
    private final ExecutorService writerExecutor = newWriterExecutor();
    private final ScheduledExecutorService timeoutExecutor = newTimeoutExecutor();
    private volatile boolean closed;
    private volatile Thread readerThread;

    JsonRpcConnection(Reader reader, Writer writer, JsonRpcHandler handler, Consumer<Throwable> errorHandler) {
        this(reader, writer, handler, errorHandler, JsonRpcConnection::requestTimeoutMillis);
    }

    JsonRpcConnection(Reader reader, Writer writer, JsonRpcHandler handler, Consumer<Throwable> errorHandler,
            Function<String, Long> requestTimeoutMillis) {
        this(reader, writer, handler, errorHandler, requestTimeoutMillis, DiagnosticSink.eclipse());
    }

    JsonRpcConnection(Reader reader, Writer writer, JsonRpcHandler handler, Consumer<Throwable> errorHandler,
            Function<String, Long> requestTimeoutMillis, DiagnosticSink diagnostics) {
        this.reader = new BufferedReader(Objects.requireNonNull(reader));
        this.writer = new BufferedWriter(Objects.requireNonNull(writer));
        this.handler = Objects.requireNonNull(handler);
        this.errorHandler = Objects.requireNonNull(errorHandler);
        this.requestTimeoutMillis = Objects.requireNonNull(requestTimeoutMillis);
        this.diagnostics = Objects.requireNonNull(diagnostics);
    }

    @Override
    public void start() {
        if (!started.compareAndSet(false, true)) return;
        diagnostics.info("Starting ACP JSON-RPC reader thread");
        Thread thread = new Thread(this::readLoop, "eclipse-acp-jsonrpc");
        thread.setDaemon(true);
        readerThread = thread;
        thread.start();
    }

    @Override
    public CompletableFuture<JsonObject> request(String method, JsonObject params) {
        long startedAt = System.nanoTime();
        long id = nextId.getAndIncrement();
        diagnostics.trace(() -> "JSON-RPC request created: id=" + id + ", method='" + method + "'");
        JsonObject message = envelope(method, params);
        message.addProperty("id", id);

        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        String requestKey = Long.toString(id);
        synchronized (stateLock) {
            if (closed) return CompletableFuture.failedFuture(new IOException("ACP connection is closed"));
            if (pending.size() >= MAX_PENDING_REQUESTS) {
                return CompletableFuture.failedFuture(new IOException("Too many pending ACP requests"));
            }
            pending.put(requestKey, future);
        }
        long timeoutMillis = Math.max(1L, requestTimeoutMillis.apply(method));
        ScheduledFuture<?> timeout;
        try {
            timeout = timeoutExecutor.schedule(() -> {
                if (pending.remove(requestKey, future)) {
                    future.completeExceptionally(new TimeoutException("ACP request timed out after " + timeoutMillis
                            + " ms: " + method));
                }
            }, timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException exception) {
            pending.remove(requestKey, future);
            future.completeExceptionally(new IOException("ACP connection is closed", exception));
            return future;
        }
        future.whenComplete((result, error) -> {
            pending.remove(requestKey, future);
            timeout.cancel(false);
        });
        try {
            enqueue(message);
            diagnostics.trace(() -> "JSON-RPC request queued: id=" + id + ", method='" + method
                    + "', queueMs=" + elapsedMillis(startedAt, System.nanoTime()));
        } catch (IOException exception) {
            diagnostics.error("JSON-RPC request could not be queued: id=" + id + ", method='" + method + "'", exception);
            pending.remove(requestKey, future);
            future.completeExceptionally(exception);
        }
        return future;
    }

    @Override
    public void notification(String method, JsonObject params) throws IOException {
        diagnostics.trace(() -> "JSON-RPC notification queued: method='" + method + "'");
        enqueue(envelope(method, params));
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
            while (!closed && (line = readFrame()) != null) {
                if (!line.isBlank()) {
                    JsonObject message = JsonParser.parseString(line).getAsJsonObject();
                    diagnostics.trace(() -> "ACP JSON-RPC <- agent: " + DiagnosticText.json(message));
                    dispatch(message);
                }
            }
            if (!closed) {
                diagnostics.warn("ACP agent closed its output stream", null);
                failTransport(new IOException("ACP agent closed its output stream"));
            }
        } catch (Exception exception) {
            if (!closed) {
                diagnostics.error("ACP JSON-RPC reader failed", exception);
                failTransport(exception);
            }
        }
    }

    /** Reads one newline-delimited JSON-RPC frame without allowing an agent to allocate unbounded memory. */
    private String readFrame() throws IOException {
        StringBuilder frame = new StringBuilder();
        int character;
        while ((character = reader.read()) != -1) {
            if (character == '\n') return frame.toString();
            if (character != '\r') frame.append((char) character);
            if (frame.length() > MAX_FRAME_CHARACTERS) {
                throw new IOException("ACP JSON-RPC frame exceeds " + MAX_FRAME_CHARACTERS + " characters");
            }
        }
        return frame.isEmpty() ? null : frame.toString();
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
                String code = errorCode(message.get("error"));
                diagnostics.error("JSON-RPC error response received: id=" + key(message.get("id"))
                        + ", code=" + code, null);
                future.completeExceptionally(new IOException(safeRemoteError(message.get("error"), code)));
            } else {
                diagnostics.trace(() -> "JSON-RPC response received: id=" + key(message.get("id")));
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
                enqueue(message);
                diagnostics.trace(() -> "JSON-RPC response queued: id=" + id);
            } catch (IOException exception) {
                diagnostics.error("Could not send JSON-RPC response: id=" + id, exception);
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
            enqueue(message);
        } catch (IOException exception) {
            diagnostics.error("Could not send JSON-RPC error response: id=" + id, exception);
            errorHandler.accept(exception);
        }
    }

    private void send(JsonObject message) throws IOException {
        synchronized (writeLock) {
            if (closed) {
                throw new IOException("ACP connection is closed");
            }
            String payload = gson.toJson(message);
            diagnostics.trace(() -> "ACP JSON-RPC -> agent: " + DiagnosticText.json(message));
            writer.write(payload);
            writer.newLine();
            writer.flush();
        }
    }

    private void enqueue(JsonObject message) throws IOException {
        if (closed) throw new IOException("ACP connection is closed");
        try {
            writerExecutor.execute(() -> {
                try {
                    send(message);
                } catch (IOException exception) {
                    diagnostics.error("Could not write ACP JSON-RPC message", exception);
                    failTransport(exception);
                }
            });
        } catch (RejectedExecutionException exception) {
            throw new IOException("ACP JSON-RPC writer queue is full or closed", exception);
        }
    }

    private void failTransport(Throwable exception) {
        if (!transitionToClosed(exception)) return;
        writerExecutor.shutdownNow();
        timeoutExecutor.shutdownNow();
        errorHandler.accept(exception);
    }

    /**
     * Moves the connection to its terminal state exactly once.  In particular, no request can be
     * added to {@code pending} after the existing requests have been failed.
     */
    private boolean transitionToClosed(Throwable error) {
        synchronized (stateLock) {
            if (closed) return false;
            closed = true;
            failPending(error);
            return true;
        }
    }

    private static ExecutorService newWriterExecutor() {
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "eclipse-acp-jsonrpc-writer");
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(128), threads, new ThreadPoolExecutor.AbortPolicy());
    }

    private static ScheduledExecutorService newTimeoutExecutor() {
        ThreadFactory threads = runnable -> {
            Thread thread = new Thread(runnable, "eclipse-acp-jsonrpc-timeout");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(threads);
    }

    /** Short protocol operations fail promptly; a prompt may legitimately take much longer. */
    private static long requestTimeoutMillis(String method) {
        return switch (method) {
            case "session/prompt" -> TimeUnit.MINUTES.toMillis(15);
            case "authenticate" -> TimeUnit.MINUTES.toMillis(5);
            default -> TimeUnit.SECONDS.toMillis(30);
        };
    }

    private static JsonObject objectOrEmpty(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : new JsonObject();
    }

    private static String key(JsonElement id) {
        return id.isJsonPrimitive() && id.getAsJsonPrimitive().isString()
                ? id.getAsString()
                : id.toString();
    }

    private static String errorCode(JsonElement error) {
        if (error != null && error.isJsonObject() && error.getAsJsonObject().has("code")) {
            return error.getAsJsonObject().get("code").getAsString();
        }
        return "unknown";
    }

    private static String safeRemoteError(JsonElement error, String code) {
        if (error != null && error.isJsonObject() && error.getAsJsonObject().has("message")) {
            String message = error.getAsJsonObject().get("message").getAsString();
            if (message.toLowerCase(java.util.Locale.ROOT).contains("authentication required")) {
                return "Authentication required (ACP error " + code + ")";
            }
        }
        return "ACP error " + code;
    }

    private static long elapsedMillis(long startedAt, long completedAt) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(completedAt - startedAt);
    }

    private void failPending(Throwable error) {
        pending.values().forEach(future -> future.completeExceptionally(error));
        pending.clear();
    }

    int pendingRequestCount() { return pending.size(); }

    @Override
    public void close() throws IOException {
        diagnostics.info("Closing ACP JSON-RPC connection");
        transitionToClosed(new IOException("ACP connection closed"));
        writerExecutor.shutdownNow();
        timeoutExecutor.shutdownNow();
        IOException failure = null;
        try {
            reader.close();
        } catch (IOException exception) {
            failure = exception;
        }
        try {
            writer.close();
        } catch (IOException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        Thread activeReader = readerThread;
        if (activeReader != null && activeReader != Thread.currentThread()) {
            activeReader.interrupt();
            try {
                activeReader.join(1_000);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        if (failure != null) throw failure;
    }
}
