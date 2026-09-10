package dev.eclipseacp.client.acp;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/** Launches an ACP agent and owns its process-specific diagnostics and shutdown. */
interface AgentProcessLauncher {
    AgentProcess launch(String command, String arguments, Path workingDirectory,
            Consumer<String> diagnosticConsumer, Consumer<Throwable> diagnosticErrorConsumer) throws IOException;
}
