package dev.eclipseacp.client.acp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.Test;

public class DefaultAgentProcessLauncherTest {
    @Test
    public void preservesEmptyTokensAndConcatenatesQuotedFragments() {
        assertEquals(List.of("--name", "", "next", "", "prefix suffix"),
                DefaultAgentProcessLauncher.parseArguments("--name \"\" next '' prefix\" suffix\""));
        assertEquals(List.of(), DefaultAgentProcessLauncher.parseArguments("  \t "));
        assertThrows(IllegalArgumentException.class,
                () -> DefaultAgentProcessLauncher.parseArguments("--name 'unfinished"));
    }

    @Test
    public void preservesUnicodeBackslashesAndLiteralQuotes() {
        assertEquals(List.of("città 日本語", "C:\\Program Files\\agent\\", "a\"b", "it's"),
                DefaultAgentProcessLauncher.parseArguments(
                        "'città 日本語' 'C:\\Program Files\\agent\\' 'a\"b' \"it's\""));
    }

    @Test
    public void nativeProcessReceivesExactParsedArguments() throws Exception {
        List<String> arguments = DefaultAgentProcessLauncher.parseArguments(
                "--name \"\" 'città 日本語' 'C:\\Program Files\\agent\\' 'a\"b' \"it's\"");
        List<String> javaArguments = new ArrayList<>(List.of("-cp",
                Path.of(ArgumentEcho.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(),
                ArgumentEcho.class.getName()));
        javaArguments.addAll(arguments);
        String executable = Path.of(System.getProperty("java.home"), "bin",
                DefaultCommandResolver.isWindows() ? "java.exe" : "java").toString();
        Process process = new ProcessBuilder(DefaultAgentProcessLauncher.buildProcessCommand(executable, javaArguments))
                .redirectErrorStream(true).start();
        try {
            assertTrue("Argument fixture timed out", process.waitFor(10, TimeUnit.SECONDS));
            List<String> output = process.inputReader(StandardCharsets.UTF_8).lines().toList();
            assertEquals(output.toString(), 0, process.exitValue());
            assertEquals(arguments, output.stream().map(line -> {
                assertTrue(line, line.startsWith("ARG:"));
                return new String(Base64.getDecoder().decode(line.substring(4)), StandardCharsets.UTF_8);
            }).toList());
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    public void windowsBatchLauncherReceivesEmptySpacedAndUnicodeArguments() throws Exception {
        org.junit.Assume.assumeTrue(DefaultCommandResolver.isWindows());
        Path fixture = Path.of(getClass().getResource("/argument-echo.cmd").toURI());
        List<String> arguments = List.of("--name", "", "two words", "città 日本語", "C:\\tools\\agent");
        ProcessBuilder builder = new ProcessBuilder(
                DefaultAgentProcessLauncher.buildProcessCommand(fixture.toString(), arguments));
        builder.environment().put("ACP_TEST_JAVA", Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
        builder.environment().put("ACP_TEST_CLASSES",
                Path.of(ArgumentEcho.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString());
        Process process = builder.redirectErrorStream(true).start();
        try {
            assertTrue("Batch argument fixture timed out", process.waitFor(10, TimeUnit.SECONDS));
            List<String> output = process.inputReader(StandardCharsets.UTF_8).lines().toList();
            assertEquals(output.toString(), 0, process.exitValue());
            assertEquals(arguments, output.stream().map(line -> {
                assertTrue(line, line.startsWith("ARG:"));
                return new String(Base64.getDecoder().decode(line.substring(4)), StandardCharsets.UTF_8);
            }).toList());
        } finally {
            process.destroyForcibly();
        }
    }

    @Test
    public void escalatesWhenOwnedProcessIgnoresGracefulTermination() {
        StubbornProcess process = new StubbornProcess();

        DefaultAgentProcessLauncher.terminate(process, 0, 0);

        assertEquals(1, process.destroyCount);
        assertEquals(1, process.destroyForciblyCount);
        assertFalse(process.isAlive());
    }

    private static final class StubbornProcess extends Process {
        private boolean alive = true;
        private int destroyCount;
        private int destroyForciblyCount;

        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { alive = false; return 0; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return !alive; }
        @Override public int exitValue() {
            if (alive) throw new IllegalThreadStateException();
            return 0;
        }
        @Override public void destroy() { destroyCount++; }
        @Override public Process destroyForcibly() { destroyForciblyCount++; alive = false; return this; }
        @Override public boolean isAlive() { return alive; }
        @Override public Stream<ProcessHandle> descendants() { return Stream.empty(); }
    }
}
