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

    @Test
    public void linksResolvedFileReferencesButLeavesCodeBlocksAndUnknownNamesAlone() {
        String html = GfmRenderer.document("See src/Foo.java:7 and `pom.xml`.\n\n```java\nsrc/Foo.java:7\n```\n\nUnknown.java",
                "sans-serif", 10, reference -> switch (reference) {
                case "src/Foo.java:7", "src/Foo.java" -> "eclipse-acp://open?path=src%2FFoo.java&line=7";
                case "pom.xml" -> "eclipse-acp://open?path=pom.xml";
                default -> null;
                });

        assertTrue(html, html.contains("href=\"eclipse-acp://open?path=src%2FFoo.java&amp;line=7\""));
        assertTrue(html.contains("href=\"eclipse-acp://open?path=pom.xml\""));
        assertTrue(html.contains("<pre><code class=\"language-java\">src/Foo.java:7"));
        assertFalse(html.contains("href=\"eclipse-acp://open?path=Unknown.java\""));
    }

    @Test
    public void convertsResolvedMarkdownLinkDestinationsToInternalWorkspaceLinks() {
        String location = "/Users/example/eclipse-acp/plugins/dev.eclipseacp.client/src/dev/eclipseacp/client/mcp/McpServerRegistry.java:14";
        String html = GfmRenderer.document("[McpServerRegistry.java:14](" + location + ")",
                "sans-serif", 10, reference -> location.equals(reference)
                        ? "eclipse-acp://open?path=src%2Fdev%2Feclipseacp%2Fclient%2Fmcp%2FMcpServerRegistry.java&line=14"
                        : null);

        assertTrue(html, html.contains("href=\"eclipse-acp://open?path=src%2Fdev%2Feclipseacp%2Fclient%2Fmcp%2FMcpServerRegistry.java&amp;line=14\""));
        assertFalse(html.contains("href=\"" + location + "\""));
    }

    @Test
    public void alignsConversationMessagesWithoutRoleLabelsOrSeparators() {
        String html = GfmRenderer.document("## You\n\nA question\n\n## Agent\n\nAn answer");

        assertTrue(html.contains("class=\"message message-user\""));
        assertTrue(html.contains("class=\"message message-agent\""));
        assertFalse(html.contains("<h2>You</h2>"));
        assertFalse(html.contains("<h2>Agent</h2>"));
        assertFalse(html.contains("border-top:1px"));
        assertTrue(html.contains("body{background:var(--bg)"));
    }

    @Test
    public void usesTheSuppliedSwtViewColorAsTheChatBackground() {
        String html = GfmRenderer.document("## Agent\n\nAnswer", "sans-serif", 10, null, "#a1b2c3");

        assertTrue(html.contains("--bg:#a1b2c3"));
        assertTrue(html.contains("--line:#a1b2c3"));
    }

}
