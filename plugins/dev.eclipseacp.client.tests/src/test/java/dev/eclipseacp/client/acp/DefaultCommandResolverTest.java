package dev.eclipseacp.client.acp;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import org.junit.Test;

public class DefaultCommandResolverTest {
    private final DefaultCommandResolver resolver = new DefaultCommandResolver();

    @Test
    public void leavesExplicitPathsUnchanged() throws IOException {
        assertEquals("relative/path/to/kilo", resolver.resolve("relative/path/to/kilo"));
        assertEquals("C:\\tools\\kilo.cmd", resolver.resolve("C:\\tools\\kilo.cmd"));
        assertEquals("/usr/local/bin/kilo", resolver.resolve("/usr/local/bin/kilo"));
    }

    @Test
    public void resolvesBareCommandsAccordingToHostPlatform() throws IOException {
        if (DefaultCommandResolver.isWindows()) {
            String resolved = resolver.resolve("cmd");
            assertTrue(resolved.toLowerCase().endsWith("cmd.exe"));
        } else {
            assertEquals("sh", resolver.resolve("sh"));
        }
    }
}
