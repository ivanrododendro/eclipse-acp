package dev.eclipseacp.client.acp;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import dev.eclipseacp.client.AcpLog;

/** Default local-process implementation of {@link AgentProcessLauncher}. */
final class DefaultAgentProcessLauncher implements AgentProcessLauncher {
    private static final long TERMINATION_GRACE_MILLIS = 1_000;
    private static final long FORCED_TERMINATION_MILLIS = 1_000;
    private final CommandResolver commandResolver;

    DefaultAgentProcessLauncher() {
        this(new DefaultCommandResolver());
    }

    DefaultAgentProcessLauncher(CommandResolver commandResolver) {
        this.commandResolver = Objects.requireNonNull(commandResolver);
    }

    @Override
    public AgentProcess launch(String command, String arguments, Path workingDirectory,
            Consumer<String> diagnosticConsumer, Consumer<Throwable> diagnosticErrorConsumer) throws IOException {
        String resolvedCommand = commandResolver.resolve(command);
        List<String> agentArguments = parseArguments(arguments);
        List<String> processCommand = buildProcessCommand(resolvedCommand, agentArguments);

        ProcessBuilder builder = new ProcessBuilder(processCommand).directory(workingDirectory.toFile());
        addCommonNodeLocationsToPath(builder);
        Process process = builder.start();
        AcpLog.info("ACP agent process started: pid=" + process.pid() + ", executable='" + resolvedCommand + "'");
        Thread diagnosticThread = streamStandardError(process, diagnosticConsumer, diagnosticErrorConsumer);
        return new LocalAgentProcess(process, diagnosticThread);
    }

    /**
     * Builds the operating-system process command. Windows batch launchers cannot be passed
     * directly to CreateProcess; they must be executed through the command interpreter.
     * The command interpreter receives the resolved launcher and all user-configured arguments.
     */
    static List<String> buildProcessCommand(String resolvedCommand, List<String> agentArguments) {
        List<String> processCommand = new ArrayList<>();
        if (isWindowsScript(resolvedCommand)) {
            processCommand.add(windowsCommandInterpreter());
            processCommand.add("/d");
            processCommand.add("/c");
            processCommand.add(resolvedCommand);
        } else {
            processCommand.add(resolvedCommand);
        }
        processCommand.addAll(agentArguments);
        return processCommand;
    }

    private static boolean isWindowsScript(String command) {
        String lowerCaseCommand = command.toLowerCase(Locale.ROOT);
        return DefaultCommandResolver.isWindows()
                && (lowerCaseCommand.endsWith(".cmd") || lowerCaseCommand.endsWith(".bat"));
    }

    private static String windowsCommandInterpreter() {
        String commandInterpreter = System.getenv("ComSpec");
        return commandInterpreter == null || commandInterpreter.isBlank() ? "cmd.exe" : commandInterpreter;
    }

    /**
     * Eclipse applications started from the IDE or Finder often inherit a minimal PATH.  In
     * particular, npm-installed ACP launchers use {@code #!/usr/bin/env node}, which otherwise
     * fails even when Node is installed in one of macOS's standard locations.
     */
    private static void addCommonNodeLocationsToPath(ProcessBuilder builder) {
        String path = builder.environment().getOrDefault("PATH", "");
        String separator = System.getProperty("path.separator");
        for (String directory : List.of("/opt/homebrew/bin", "/usr/local/bin")) {
            if (!java.nio.file.Files.isExecutable(Path.of(directory, "node"))
                    || containsPathEntry(path, directory, separator)) continue;
            path = path.isBlank() ? directory : path + separator + directory;
        }
        builder.environment().put("PATH", path);
    }

    private static boolean containsPathEntry(String path, String entry, String separator) {
        for (String candidate : path.split(java.util.regex.Pattern.quote(separator))) {
            if (entry.equals(candidate)) return true;
        }
        return false;
    }

    static List<String> parseArguments(String commandLine) {
        List<String> result = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        for (int index = 0; index < commandLine.length(); index++) {
            char character = commandLine.charAt(index);
            if (character == '\'' || character == '"') {
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
        if (quoted) throw new IllegalArgumentException("Unterminated quote in ACP agent arguments");
        if (!current.isEmpty()) result.add(current.toString());
        return result;
    }

    private static Thread streamStandardError(Process process, Consumer<String> diagnosticConsumer,
            Consumer<Throwable> diagnosticErrorConsumer) {
        Thread thread = new Thread(() -> {
            try (var reader = process.errorReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        // Agent stderr remains visible to the session listener, but enters the
                        // Eclipse log only when bounded, opt-in protocol tracing is enabled.
                        String diagnostic = line;
                        AcpLog.debug(() -> "ACP agent stderr: " + DiagnosticText.stderr(diagnostic));
                        diagnosticConsumer.accept(line);
                    }
                }
            } catch (IOException exception) {
                if (process.isAlive()) {
                    AcpLog.error("Cannot read ACP agent diagnostics", exception);
                    diagnosticErrorConsumer.accept(exception);
                }
            }
        }, "eclipse-acp-stderr");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static final class LocalAgentProcess implements AgentProcess {
        private final Process process;
        private final Thread diagnosticThread;

        private LocalAgentProcess(Process process, Thread diagnosticThread) {
            this.process = Objects.requireNonNull(process);
            this.diagnosticThread = Objects.requireNonNull(diagnosticThread);
        }

        @Override public InputStreamReader standardOutput() {
            return new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8);
        }
        @Override public OutputStreamWriter standardInput() {
            return new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8);
        }
        @Override public void close() {
            terminate(process, TERMINATION_GRACE_MILLIS, FORCED_TERMINATION_MILLIS);
            diagnosticThread.interrupt();
            if (Thread.currentThread() != diagnosticThread) {
                try {
                    diagnosticThread.join(FORCED_TERMINATION_MILLIS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /** Terminates the launched process tree only, escalating after bounded grace periods. */
    static void terminate(Process process, long gracefulMillis, long forcedMillis) {
        List<ProcessHandle> descendants;
        try (Stream<ProcessHandle> owned = process.descendants()) {
            descendants = owned.toList();
        } catch (RuntimeException exception) {
            AcpLog.warn("Could not enumerate ACP agent descendants; terminating the owned parent", exception);
            descendants = List.of();
        }
        descendants.reversed().forEach(DefaultAgentProcessLauncher::destroy);
        process.destroy();
        if (awaitTermination(process, descendants, gracefulMillis)) return;

        descendants.reversed().stream().filter(ProcessHandle::isAlive)
                .forEach(DefaultAgentProcessLauncher::destroyForcibly);
        if (process.isAlive()) process.destroyForcibly();
        if (!awaitTermination(process, descendants, forcedMillis)) {
            AcpLog.warn("ACP agent process tree is still alive after forced termination: pid=" + process.pid(), null);
        }
    }

    private static void destroy(ProcessHandle process) {
        try {
            process.destroy();
        } catch (RuntimeException exception) {
            AcpLog.warn("Could not terminate owned ACP descendant: pid=" + process.pid(), exception);
        }
    }

    private static void destroyForcibly(ProcessHandle process) {
        try {
            process.destroyForcibly();
        } catch (RuntimeException exception) {
            AcpLog.warn("Could not force termination of owned ACP descendant: pid=" + process.pid(), exception);
        }
    }

    private static boolean awaitTermination(Process process, List<ProcessHandle> descendants, long timeoutMillis) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0, timeoutMillis));
        do {
            if (!process.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive)) return true;
            try {
                Thread.sleep(Math.min(20, Math.max(1, timeoutMillis)));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        } while (System.nanoTime() < deadline);
        return !process.isAlive() && descendants.stream().noneMatch(ProcessHandle::isAlive);
    }
}
