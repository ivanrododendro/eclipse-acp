package dev.eclipseacp.client.acp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.Test;

public class DefaultAgentProcessLauncherTest {
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
