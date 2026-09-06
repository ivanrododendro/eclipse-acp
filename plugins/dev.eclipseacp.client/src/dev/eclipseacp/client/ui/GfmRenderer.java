package dev.eclipseacp.client.ui;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Code;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;

/** Safe HTML rendering for the CommonMark core and GFM table extension. */
final class GfmRenderer {
    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = renderer(null);

    private GfmRenderer() { }

    static String document(String markdown) {
        return document(markdown, "sans-serif", 10);
    }

    static String document(String markdown, String fontFamily, int fontSizePoints) {
        return document(markdown, fontFamily, fontSizePoints, null);
    }

    /**
     * Renders project file references as internal links. The resolver must return {@code null}
     * for a reference that is not an unambiguous file in the active project.
     */
    static String document(String markdown, String fontFamily, int fontSizePoints, Function<String, String> fileLinkResolver) {
        String cssFontFamily = fontFamily == null ? "sans-serif" : fontFamily.replace("\\", "\\\\").replace("'", "\\'");
        String content = markdown.isBlank()
                ? "<section class='welcome'><div class='mark'>✦</div><h1>Build something great</h1>"
                    + "<p>Explore your code, solve a problem, or plan your next change.</p>"
                    + "<div class='hint'>To start, right-click a project and open an ACP session.</div>"
                    + "<div class='examples'><span>@file · Current file</span><span>@selection · Selected code</span>"
                    + "<span>@problems · Workspace diagnostics</span></div></section>"
                : (fileLinkResolver == null ? RENDERER : renderer(fileLinkResolver)).render(PARSER.parse(markdown));
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name='viewport' content='width=device-width,initial-scale=1'><style>"
                + ":root{color-scheme:light dark;--bg:#ffffff;--fg:#24292f;--muted:#626b78;--surface:#f5f6f8;--line:#dce1e8;--accent:#6254c7;}"
                + "@media(prefers-color-scheme:dark){:root{--bg:#1e1f22;--fg:#e1e4ea;--muted:#a4adba;--surface:#292b30;--line:#414550;--accent:#b1a5ff;}}"
                + "*{box-sizing:border-box}body{background:var(--bg);color:var(--fg);font-family:'" + cssFontFamily
                + "',sans-serif;font-size:" + fontSizePoints + "pt;margin:0;padding:20px;line-height:1.65;overflow-wrap:anywhere;}"
                + "main{max-width:900px;margin:auto}h1{font-size:1.5em;letter-spacing:-.03em;line-height:1.3}"
                + "h2{font-size:.85em;letter-spacing:.04em;color:var(--accent);border-top:1px solid var(--line);padding-top:20px;margin-top:28px}"
                + "p{margin:10px 0}pre{background:var(--surface);border:1px solid var(--line);border-radius:10px;padding:14px;overflow:auto;overflow-wrap:normal;}"
                + "code{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:.92em;background:var(--surface);padding:2px 5px;border-radius:4px}pre code{padding:0}"
                + "pre.diff{padding:8px 0}.diff code{display:block}.diff-line{display:block;padding:0 14px;min-height:1.65em}.diff-remove{background:#fde2e1;color:#852d2b}.diff-add{background:#dff3e4;color:#1f6b3b}"
                + "@media(prefers-color-scheme:dark){.diff-remove{background:#552c31;color:#ffbbb6}.diff-add{background:#1f4a32;color:#b9f3c7}}"
                + "table{display:block;max-width:100%;overflow:auto;border-collapse:collapse;}th,td{border:1px solid var(--line);padding:8px 12px;}th{background:var(--surface)}"
                + "blockquote{border-left:3px solid var(--accent);border-radius:0 8px 8px 0;margin:14px 0;padding:6px 12px;color:var(--muted);background:var(--surface);}"
                + "a{color:var(--accent)}a:focus-visible{outline:2px solid var(--accent);outline-offset:3px}"
                + ".welcome{padding:clamp(24px,10vh,100px) 4px 24px;max-width:430px;margin:auto}.mark{font-size:32px;color:var(--accent)}"
                + ".welcome p,.hint{color:var(--muted)}.hint{font-size:.9em;margin-top:24px}.examples{display:flex;flex-wrap:wrap;gap:8px;margin-top:20px}"
                + ".examples span{font-size:.85em;background:var(--surface);border:1px solid var(--line);padding:6px 10px;border-radius:8px}"
                + "@media(max-width:360px){body{padding:12px}}"
                + "</style></head><body><main>" + content + "</main></body></html>";
    }

    private static HtmlRenderer renderer(Function<String, String> fileLinkResolver) {
        HtmlRenderer.Builder builder = HtmlRenderer.builder().extensions(EXTENSIONS).escapeHtml(true)
                .nodeRendererFactory(DiffCodeBlockRenderer::new);
        if (fileLinkResolver != null) builder.nodeRendererFactory(context -> new FileReferenceRenderer(context, fileLinkResolver));
        return builder.build();
    }

    /** Links only plain text and inline code; fenced code remains literal source text. */
    private static final class FileReferenceRenderer implements NodeRenderer {
        // The resolver performs the authoritative workspace validation. Keeping this lexical
        // match permissive also accommodates paths with Unicode or generated-file characters.
        private static final Pattern FILE_REFERENCE = Pattern.compile(
                "(?<!\\S)([^\\s]+?\\.[A-Za-z0-9]{1,12}(?::[1-9][0-9]*)?)");
        private final HtmlNodeRendererContext context;
        private final HtmlWriter writer;
        private final Function<String, String> resolver;

        private FileReferenceRenderer(HtmlNodeRendererContext context, Function<String, String> resolver) {
            this.context = context;
            this.writer = context.getWriter();
            this.resolver = resolver;
        }

        @Override public Set<Class<? extends Node>> getNodeTypes() { return Set.of(Link.class, Text.class, Code.class); }

        @Override public void render(Node node) {
            if (node instanceof Link link) {
                // Agents commonly provide source locations as Markdown links, e.g.
                // [McpServerRegistry.java:14](src/.../McpServerRegistry.java:14).
                // Without this conversion SWT resolves the relative href against the local
                // document and attempts to load file:///.../McpServerRegistry.java:14.
                String href = resolver.apply(link.getDestination());
                String destination = context.encodeUrl(href == null ? link.getDestination() : href);
                writer.tag("a", href == null ? Map.of("href", destination)
                        : Map.of("href", destination, "class", "workspace-file"));
                renderChildren(link);
                writer.tag("/a");
                return;
            }
            if (node.getParent() instanceof Link) { renderLiteral(node); return; }
            if (node instanceof Code code) {
                String literal = code.getLiteral();
                String href = resolver.apply(literal);
                if (href == null) { renderLiteral(node); return; }
                writer.tag("a", Map.of("href", href, "class", "workspace-file"));
                writer.tag("code");
                writer.text(literal);
                writer.tag("/code");
                writer.tag("/a");
                return;
            }
            String literal = ((Text) node).getLiteral();
            Matcher matcher = FILE_REFERENCE.matcher(literal);
            int end = 0;
            while (matcher.find()) {
                String reference = matcher.group();
                String href = resolver.apply(reference);
                if (href == null) continue;
                writer.text(literal.substring(end, matcher.start()));
                writer.tag("a", Map.of("href", href, "class", "workspace-file"));
                writer.text(reference);
                writer.tag("/a");
                end = matcher.end();
            }
            if (end == 0) writer.text(literal); else writer.text(literal.substring(end));
        }

        private void renderLiteral(Node node) {
            if (node instanceof Code code) {
                writer.tag("code");
                writer.text(code.getLiteral());
                writer.tag("/code");
            } else {
                writer.text(((Text) node).getLiteral());
            }
        }

        private void renderChildren(Node parent) {
            for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) context.render(child);
        }
    }

    /** Renders unified-diff lines individually, while keeping all source text HTML-escaped. */
    private static final class DiffCodeBlockRenderer implements NodeRenderer {
        private final HtmlWriter writer;

        private DiffCodeBlockRenderer(HtmlNodeRendererContext context) {
            writer = context.getWriter();
        }

        @Override public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(FencedCodeBlock.class);
        }

        @Override public void render(Node node) {
            FencedCodeBlock block = (FencedCodeBlock) node;
            boolean diff = "diff".equalsIgnoreCase(language(block.getInfo()));
            writer.line();
            writer.tag("pre", diff ? Map.of("class", "diff") : Map.of());
            writer.tag("code", languageClass(block.getInfo()));
            if (diff) renderDiff(block.getLiteral()); else writer.text(block.getLiteral());
            writer.tag("/code");
            writer.tag("/pre");
            writer.line();
        }

        private void renderDiff(String literal) {
            for (String line : literal.split("\\R", -1)) {
                String style = line.startsWith("-") && !line.startsWith("---") ? "diff-line diff-remove"
                        : line.startsWith("+") && !line.startsWith("+++") ? "diff-line diff-add" : "diff-line";
                writer.tag("span", Map.of("class", style));
                writer.text(line);
                writer.tag("/span");
                writer.line();
            }
        }

        private static String language(String info) {
            if (info == null || info.isBlank()) return "";
            int separator = info.indexOf(' ');
            return separator < 0 ? info : info.substring(0, separator);
        }

        private static Map<String, String> languageClass(String info) {
            String language = language(info);
            return language.isEmpty() ? Map.of() : Map.of("class", "language-" + language);
        }
    }
}
