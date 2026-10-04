package dev.eclipseacp.client.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.junit.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/** Smoke tests that must run in the real Equinox and workspace runtime. */
public class RuntimeSmokeTest {
    @Test
    public void hostBundleAndPlatformDependenciesResolveInEquinox() {
        Bundle host = FrameworkUtil.getBundle(RuntimeSmokeTest.class);

        assertNotNull(host);
        assertEquals("dev.eclipseacp.client", host.getSymbolicName());
        assertTrue(host.getState() == Bundle.ACTIVE || host.getState() == Bundle.RESOLVED
                || host.getState() == Bundle.STARTING);
        assertNotNull(Platform.getBundle("org.eclipse.core.resources"));
        assertTrue(GfmRenderer.document("## Agent\n\nRuntime ready").contains("Runtime ready"));
    }

    @Test
    public void workspaceCanCreateReadAndDeleteAProjectFile() throws Exception {
        IProject project = ResourcesPlugin.getWorkspace().getRoot()
                .getProject("eclipse-acp-runtime-test-" + System.nanoTime());
        try {
            project.create(null);
            project.open(null);
            IFile file = project.getFile("smoke.txt");
            file.create(new ByteArrayInputStream("runtime".getBytes(StandardCharsets.UTF_8)), true, null);

            try (var contents = file.getContents()) {
                assertEquals("runtime", new String(contents.readAllBytes(), StandardCharsets.UTF_8));
            }
        } finally {
            if (project.exists()) project.delete(true, true, null);
        }
    }
}
