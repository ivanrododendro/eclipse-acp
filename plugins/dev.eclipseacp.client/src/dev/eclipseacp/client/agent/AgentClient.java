package dev.eclipseacp.client.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** UI-facing agent contract; protocol versions must not leak beyond this package. */
public interface AgentClient extends AutoCloseable {
    CompletableFuture<Void> connect(Path workingDirectory);
    CompletableFuture<Void> prompt(String text);
    void cancel() throws IOException;
    AgentCapabilities capabilities();
    @Override void close();
}
