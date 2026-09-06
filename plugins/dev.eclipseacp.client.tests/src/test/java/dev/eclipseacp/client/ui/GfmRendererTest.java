package dev.eclipseacp.client.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GfmRendererTest {
    @Test
    public void rendersUnifiedDiffChangesWithPastelLineClassesAndEscapesContent() {
        String html = GfmRenderer.document("```diff\n--- old.txt\n+++ new.txt\n-<removed>\n+<added>\n context\n```");

        assertTrue(html.contains("class=\"diff-line diff-remove\""));
        assertTrue(html.contains("class=\"diff-line diff-add\""));
        assertTrue(html.contains("&lt;removed&gt;"));
        assertTrue(html.contains("&lt;added&gt;"));
        assertFalse(html.contains("diff-remove\">--- old.txt"));
        assertFalse(html.contains("diff-add\">+++ new.txt"));
    }
}
