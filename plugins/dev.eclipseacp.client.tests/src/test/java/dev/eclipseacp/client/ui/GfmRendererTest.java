package dev.eclipseacp.client.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import dev.eclipseacp.client.agent.ToolCall;

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
        String html = GfmRenderer.document("See src/Foo.java:7, src/ContratPrestataireService.java:63-70 and `pom.xml`.\n\n```java\nsrc/Foo.java:7\n```\n\nUnknown.java",
                "sans-serif", 10, reference -> switch (reference) {
                case "src/Foo.java:7", "src/Foo.java" -> "eclipse-acp://open?path=src%2FFoo.java&line=7";
                case "src/ContratPrestataireService.java:63-70" ->
                        "eclipse-acp://open?path=src%2FContratPrestataireService.java&line=63";
                case "pom.xml" -> "eclipse-acp://open?path=pom.xml";
                default -> null;
                });

        assertTrue(html, html.contains("href=\"eclipse-acp://open?path=src%2FFoo.java&amp;line=7\""));
        assertTrue(html, html.contains("href=\"eclipse-acp://open?path=src%2FContratPrestataireService.java&amp;line=63\""));
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
        assertFalse(html.contains(".message{border-top:1px"));
        assertTrue(html.contains("body{background:var(--bg)"));
        assertTrue(html.contains(".message-agent> :first-child{margin-top:0}"));
        assertTrue(html.contains("ul,ol{margin:10px 0;padding-inline-start:1.5em}"));
    }

    @Test
    public void usesTheSuppliedSwtViewColorAsTheChatBackground() {
        String html = GfmRenderer.document("## Agent\n\nAnswer", "sans-serif", 10, null, "#a1b2c3");

        assertTrue(html.contains("--bg:#a1b2c3"));
        assertTrue(html.contains("--line:rgba(20,28,44,.13)"));
    }

    @Test
    public void rendersCodeAsACopyableLanguageCard() {
        String html = GfmRenderer.document("```java\nrecord Person(String name) {}\n```");

        assertTrue(html.contains("class=\"code-block\""));
        assertTrue(html.contains("class=\"code-language\">Java</span>"));
        assertTrue(html.contains("class=\"copy-code\""));
        assertTrue(html.contains("record Person(String name) {}"));
    }

    @Test
    public void rendersAgentEventsAsCompactSemanticCards() {
        String html = GfmRenderer.document("> **Tool terminal:** Run tests — completed\n\n"
                + "> **Usage:** totalTokens=42");

        assertTrue(html.contains("class=\"agent-event event-tool\""));
        assertTrue(html.contains("class=\"agent-event event-meta\""));
    }

    @Test
    public void rendersToolCallsAsOneCollapsedAccordionWithACommandPreview() {
        ToolCall tool = new ToolCall("tool-1", "Execute command", "execute", "completed", List.of(), List.of(),
                "pwd && rg --files -g '*.class'", "/workspace", "", "ok");

        String html = GfmRenderer.document(ChatMessageFormatter.toolCall(tool));

        assertTrue(html.contains("<details class=\"agent-command\">"));
        assertFalse(html.contains("<details class=\"agent-command\" open"));
        assertTrue(html.contains("Tool execute:"));
        assertTrue(html.contains("pwd &amp;&amp; rg --files -g '*.class'"));
        assertTrue(html.contains("class=\"tool-expanded\""));
    }

    @Test
    public void rendersImagesAsAccessibleLightboxThumbnails() {
        String html = GfmRenderer.document("![Architecture](https://example.test/architecture.png)");

        assertTrue(html.contains("class=\"transcript-image\""));
        assertTrue(html.contains("alt=\"Architecture\""));
        assertTrue(html.contains("loading=\"lazy\""));
        assertTrue(html.contains("id='image-lightbox'"));
    }

    @Test
    public void rendersAnInlinePngFixtureWithoutTreatingItAsAgentContent() {
        // A valid 1×1 PNG used only to exercise the transcript renderer.
        String png = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVQIHWP4z8DwHwAFgAI/"
                + "p8uY0QAAAABJRU5ErkJggg==";
        String html = GfmRenderer.document("## You\n\n![Package Explorer](data:image/png;base64," + png
                + " \"Render-only fixture\")\n\n## Agent\n\nRendered.");

        assertTrue(html.contains("data:image/png;base64," + png));
        assertTrue(html.contains("alt=\"Package Explorer\""));
        assertTrue(html.contains("title=\"Render-only fixture\""));
        assertTrue(html.contains("class=\"message message-user\""));
        assertTrue(html.contains("class=\"message message-agent\""));
    }

    @Test
    public void separatesStableAndLiveConversationMarkupForIncrementalUpdates() {
        String html = GfmRenderer.document("## You\n\nQuestion\n\n", "## Agent\n\nPartial", "sans-serif", 10,
                null, null);

        assertTrue(html.contains("id='transcript-stable'"));
        assertTrue(html.contains("id='transcript-live'"));
        assertTrue(html.contains("Question"));
        assertTrue(html.contains("Partial"));
    }

}
