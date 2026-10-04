package dev.eclipseacp.client.acp;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.io.Writer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public class JsonRpcConnectionTest {
    @Test
    public void queuesRequestsInsteadOfBlockingTheCallerOnWriterBackpressure() throws Exception {
        BlockingWriter writer = new BlockingWriter();
        JsonRpcConnection connection = new JsonRpcConnection(new StringReader(""), writer, new JsonRpcHandler() {
            @Override public void onNotification(String method, JsonObject params) { }
            @Override public CompletableFuture<JsonElement> onRequest(String method, JsonObject params) {
                return CompletableFuture.completedFuture(new JsonObject());
            }
        }, error -> { });

        try {
            long started = System.nanoTime();
            connection.request("session/prompt", new JsonObject());
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue("request must return without waiting for writer.flush(), took " + elapsedMillis + " ms",
                    elapsedMillis < 100);
            assertTrue("writer task was not started", writer.writeStarted.await(2, TimeUnit.SECONDS));
        } finally {
            writer.release.countDown();
            connection.close();
        }
    }

    private static final class BlockingWriter extends Writer {
        final CountDownLatch writeStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override public void write(char[] characters, int offset, int length) throws IOException {
            writeStarted.countDown();
            try {
                release.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while simulating a blocked ACP writer", exception);
            }
        }
        @Override public void flush() { }
        @Override public void close() { }
    }
}
