package dev.eclipseacp.client.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.junit.Test;
import org.eclipse.swt.graphics.ImageData;
import org.eclipse.swt.graphics.PaletteData;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/** Smoke tests that must run in the real Equinox and workspace runtime. */
public class RuntimeSmokeTest {
    @Test
    public void clipboardImagePreservesPixelsAndTransparencyInTemporaryPng() throws Exception {
        ImageData original = new ImageData(2, 1, 24, new PaletteData(0xff0000, 0xff00, 0xff));
        original.setPixel(0, 0, 0x123456);
        original.setAlpha(0, 0, 128);
        original.setPixel(1, 0, 0xabcdef);
        original.setAlpha(1, 0, 255);
        var attachment = ChatComposer.createClipboardImage(original);
        try {
            assertEquals("image/png", attachment.mimeType());
            assertTrue(attachment.temporary());
            ImageData decoded = new ImageData(attachment.path().toString());
            assertEquals(original.width, decoded.width);
            assertEquals(original.height, decoded.height);
            assertEquals(original.palette.getRGB(original.getPixel(0, 0)),
                    decoded.palette.getRGB(decoded.getPixel(0, 0)));
            assertEquals(128, decoded.getAlpha(0, 0));
            assertEquals(255, decoded.getAlpha(1, 0));
        } finally {
            attachment.deleteTemporaryFile();
        }
        assertFalse(java.nio.file.Files.exists(attachment.path()));
    }

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

    @Test
    public void clipboardWorkspaceFileResolvesToTheSameSourceFileUsedByAtMention() throws Exception {
        IProject project = ResourcesPlugin.getWorkspace().getRoot()
                .getProject("eclipse-acp-clipboard-test-" + System.nanoTime());
        try {
            project.create(null);
            project.open(null);
            IFile file = project.getFile("copied-source.java");
            file.create(new ByteArrayInputStream("class CopiedSource {}".getBytes(StandardCharsets.UTF_8)), true, null);

            assertEquals(file, ChatComposer.workspaceFileFor(file.getLocation().toFile().toPath()));
        } finally {
            if (project.exists()) project.delete(true, true, null);
        }
    }
}
