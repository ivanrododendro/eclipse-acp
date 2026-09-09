package dev.eclipseacp.client.agent;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonElement;

/**
 * Decorates an agent client by advertising the Eclipse JDT CLI Bridge through the first
 * user prompt sent in a session. The instruction is intentionally injected only on the
 * wire, so the Eclipse chat transcript keeps showing exactly what the user typed.
 */
public final class JdtCliAwareAgentClient implements AgentClient {
    private static final String JDT_CLI_INSTRUCTION = """
            <eclipse-jdt-cli>
            This Eclipse workspace exposes the JDT CLI Bridge through the `jdt` shell command.
            When Java semantic information is needed, prefer `jdt` over grep/text search where applicable
            (for example references, type hierarchy, implementations, source lookup, diagnostics,
            refactoring or tests). Use your existing shell/terminal tool to invoke `jdt`.
            If needed, run `jdt --help` to discover the commands supported by the installed bridge version.
            </eclipse-jdt-cli>

            """;

    private final AgentClient delegate;
    private final AtomicBoolean instructionPending = new AtomicBoolean(true);

    public JdtCliAwareAgentClient(AgentClient delegate) {
        this.delegate = delegate;
    }

    private String augmentFirstPrompt(String text) {
        if (instructionPending.compareAndSet(true, false)) {
            return JDT_CLI_INSTRUCTION + text;
        }
        return text;
    }

    @Override public CompletableFuture<Void> connect(Path workingDirectory) { return delegate.connect(workingDirectory); }
    @Override public CompletableFuture<Void> restoreSession(String sessionId, Path workingDirectory) { return delegate.restoreSession(sessionId, workingDirectory); }
    @Override public String sessionId() { return delegate.sessionId(); }
    @Override public CompletableFuture<Void> prompt(String text) { return delegate.prompt(augmentFirstPrompt(text)); }
    @Override public CompletableFuture<Void> prompt(String text, List<PromptAttachment> attachments) {
        return delegate.prompt(augmentFirstPrompt(text), attachments);
    }
    @Override public CompletableFuture<Void> setConfigOption(String configId, JsonElement value) { return delegate.setConfigOption(configId, value); }
    @Override public CompletableFuture<Void> setConfigOption(String configId, String valueType, JsonElement value) { return delegate.setConfigOption(configId, valueType, value); }
    @Override public void cancel() throws IOException { delegate.cancel(); }
    @Override public AgentCapabilities capabilities() { return delegate.capabilities(); }
    @Override public List<AuthMethod> authenticationMethods() { return delegate.authenticationMethods(); }
    @Override public CompletableFuture<Void> authenticate(String methodId) { return delegate.authenticate(methodId); }
    @Override public CompletableFuture<Void> logout() { return delegate.logout(); }
    @Override public CompletableFuture<SessionPage> listSessions(Path workingDirectory, String cursor) { return delegate.listSessions(workingDirectory, cursor); }
    @Override public CompletableFuture<Void> loadSession(String sessionId, Path workingDirectory) { return delegate.loadSession(sessionId, workingDirectory); }
    @Override public CompletableFuture<Void> resumeSession(String sessionId, Path workingDirectory) { return delegate.resumeSession(sessionId, workingDirectory); }
    @Override public CompletableFuture<Void> closeSession() { return delegate.closeSession(); }
    @Override public CompletableFuture<Void> deleteSession(String sessionId) { return delegate.deleteSession(sessionId); }
    @Override public void close() { delegate.close(); }
}
