package dev.eclipseacp.client.acp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Default cross-platform command resolver used before starting an ACP agent process. */
final class DefaultCommandResolver implements CommandResolver {
    @Override
    public String resolve(String command) throws IOException {
        Objects.requireNonNull(command, "command");
        if (command.isBlank() || containsPathSeparator(command)) {
            return command;
        }
        if (isWindows()) {
            return resolveWindowsCommand(command);
        }
        return command;
    }

    private static String resolveWindowsCommand(String command) throws IOException {
        Process resolver = new ProcessBuilder("where.exe", command).redirectErrorStream(true).start();
        try {
            String output = new String(resolver.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exitCode = resolver.waitFor();
            if (exitCode != 0) {
                throw new IOException("Cannot resolve ACP agent command '" + command
                        + "' through PATH/PATHEXT (where.exe exit code " + exitCode + ")");
            }

            List<String> candidates = output.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .toList();

            return candidates.stream()
                    .filter(DefaultCommandResolver::isWindowsLaunchableCandidate)
                    .findFirst()
                    .orElseThrow(() -> new IOException(
                            "Cannot resolve ACP agent command '" + command
                                    + "' to a Win32 executable or command script through PATH/PATHEXT. "
                                    + "Candidates returned by where.exe: " + candidates));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while resolving ACP agent command '" + command + "'", exception);
        } finally {
            resolver.destroy();
        }
    }

    private static boolean isWindowsLaunchableCandidate(String candidate) {
        String lowerCaseCandidate = candidate.toLowerCase(Locale.ROOT);
        return lowerCaseCandidate.endsWith(".exe")
                || lowerCaseCandidate.endsWith(".com")
                || lowerCaseCandidate.endsWith(".cmd")
                || lowerCaseCandidate.endsWith(".bat");
    }

    static boolean containsPathSeparator(String command) {
        return command.indexOf('/') >= 0 || command.indexOf('\\') >= 0;
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
