package dev.eclipseacp.client.ui;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.BlockQuote;
import org.commonmark.node.FencedCodeBlock;
import org.commonmark.node.Code;
import org.commonmark.node.Heading;
import org.commonmark.node.Image;
import org.commonmark.node.Link;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.node.StrongEmphasis;
import org.commonmark.node.Text;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
        return document(markdown, fontFamily, fontSizePoints, fileLinkResolver, null);
    }

    static String document(String markdown, String fontFamily, int fontSizePoints,
            Function<String, String> fileLinkResolver, String backgroundColor) {
        return document("", markdown, fontFamily, fontSizePoints, fileLinkResolver, backgroundColor);
    }

    static String document(String stableMarkdown, String liveMarkdown, String fontFamily, int fontSizePoints,
            Function<String, String> fileLinkResolver, String backgroundColor) {
        String cssFontFamily = fontFamily == null ? "sans-serif" : fontFamily.replace("\\", "\\\\").replace("'", "\\'");
        String systemBackground = backgroundColor != null && backgroundColor.matches("#[0-9a-fA-F]{6}")
                ? backgroundColor : null;
        String lightBackground = systemBackground == null ? "#dce1e8" : systemBackground;
        String darkBackground = systemBackground == null ? "#414550" : systemBackground;
        String content = stableMarkdown.isBlank() && liveMarkdown.isBlank()
                ? "<section class='welcome'><div class='mark'>✦</div><h1>Build something great</h1>"
                    + "<p>Explore your code, solve a problem, or plan your next change.</p>"
                    + "<div class='hint'>To start, right-click a project and open it in the ACP view.</div></section>"
                : "<section class='conversation'><div id='transcript-stable' class='transcript-parts'>"
                    + conversationPart(stableMarkdown, fileLinkResolver)
                    + "</div><div id='transcript-live' class='transcript-part'>"
                    + conversationPart(liveMarkdown, fileLinkResolver) + "</div></section>";
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name='viewport' content='width=device-width,initial-scale=1'><style>"
                + ":root{color-scheme:light dark;--bg:" + lightBackground + ";--fg:#20242c;--muted:#667080;--surface:#f7f8fa;"
                + "--surface-raised:#fff;--surface-soft:rgba(255,255,255,.56);--line:rgba(20,28,44,.13);--accent:#6254c7;"
                + "--accent-soft:#ece9ff;--user:#ebe8ff;--code:#17191f;--code-fg:#eef0f5;--success:#287a4b;}"
                + "@media(prefers-color-scheme:dark){:root{--bg:" + darkBackground
                + ";--fg:#e8eaf0;--muted:#a9b0bd;--surface:#292c33;--surface-raised:#24262c;"
                + "--surface-soft:rgba(22,24,29,.46);--line:rgba(255,255,255,.12);--accent:#b7adff;"
                + "--accent-soft:#3b365b;--user:#383451;--code:#15161a;--code-fg:#f1f2f6;--success:#75cf98;}}"
                + "*{box-sizing:border-box}body{background:var(--bg);color:var(--fg);font-family:'" + cssFontFamily
                + "',sans-serif;font-size:" + fontSizePoints + "pt;margin:0;padding:24px 22px 36px;line-height:1.62;overflow-wrap:anywhere;}"
                + "main{max-width:860px;margin:auto}h1{font-size:1.5em;letter-spacing:-.03em;line-height:1.3}"
                + "h2{font-size:1.15em;color:var(--fg);margin:18px 0 10px}"
                + ".conversation{display:flex;flex-direction:column;gap:20px}.transcript-parts,.transcript-part{display:contents}.conversation-preamble{align-self:flex-start;width:100%}"
                + ".message{min-width:0}.message-user{align-self:flex-end;width:fit-content;max-width:min(82%,680px);text-align:left;"
                + "background:var(--user);border:1px solid color-mix(in srgb,var(--accent) 18%,transparent);border-radius:18px 18px 5px 18px;padding:9px 14px;box-shadow:0 1px 2px rgba(0,0,0,.04)}"
                + ".message-user> :first-child{margin-top:0}.message-user> :last-child{margin-bottom:0}"
                + ".message-agent{align-self:flex-start;width:100%;text-align:left}"
                + ".message-agent> :first-child{margin-top:0}.message-agent> :last-child{margin-bottom:0}"
                + "ul,ol{margin:10px 0;padding-inline-start:1.5em}"
                + "p{margin:10px 0}code{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:.91em;background:var(--surface);border:1px solid var(--line);padding:1px 5px;border-radius:5px}"
                + ".code-block{position:relative;margin:14px 0;border:1px solid var(--line);border-radius:12px;overflow:hidden;background:var(--code);box-shadow:0 2px 8px rgba(0,0,0,.08)}"
                + ".code-toolbar{height:34px;display:flex;align-items:center;justify-content:space-between;padding:0 7px 0 12px;background:color-mix(in srgb,var(--code) 88%,white);border-bottom:1px solid rgba(255,255,255,.1)}"
                + ".code-language{color:#aeb5c2;font-size:.72em;font-weight:650;letter-spacing:.04em;text-transform:uppercase}"
                + ".copy-code{appearance:none;border:0;border-radius:6px;background:transparent;color:#bfc5d0;font:inherit;font-size:.72em;padding:4px 8px;cursor:pointer}"
                + ".copy-code:hover{background:rgba(255,255,255,.1);color:#fff}.copy-code:focus-visible{outline:2px solid var(--accent);outline-offset:1px}"
                + ".copy-code.copied{color:#8fe0ab}.code-block pre{margin:0;padding:14px 16px;max-height:34rem;overflow:auto;overflow-wrap:normal;line-height:1.58;color:var(--code-fg);background:transparent;border:0;border-radius:0}"
                + ".code-block pre code{display:block;padding:0;border:0;border-radius:0;background:transparent;color:inherit;font-size:.9em}"
                + ".code-block.diff pre{padding:8px 0}.diff-line{display:block;padding:0 16px;min-height:1.58em}.diff-remove{background:#4c292d;color:#ffc5c0}.diff-add{background:#203f2d;color:#b9f3c7}"
                + "@media(prefers-color-scheme:dark){.diff-remove{background:#552c31;color:#ffbbb6}.diff-add{background:#1f4a32;color:#b9f3c7}}"
                + "table{display:block;max-width:100%;overflow:auto;border-collapse:collapse;}th,td{border:1px solid var(--line);padding:8px 12px;}th{background:var(--surface)}"
                + "blockquote{border-left:3px solid var(--accent);border-radius:0 8px 8px 0;margin:14px 0;padding:6px 12px;color:var(--muted);background:var(--surface);}"
                + ".agent-event{margin:10px 0;padding:8px 11px;border:1px solid var(--line);border-left:3px solid var(--accent);border-radius:8px;background:var(--surface-soft);color:var(--muted);font-size:.88em}"
                + ".agent-event p{margin:0}.event-changes{border-left-color:var(--success)}.event-terminal{border-left-color:#718096}.event-meta{border-left-color:transparent;background:transparent;padding-block:3px}"
                + ".event-context{border-left-color:var(--accent);background:var(--accent-soft)}.agent-event+ .code-block{margin-top:-4px}"
                + ".agent-command{margin:10px 0;border:1px solid var(--line);border-radius:9px;background:var(--surface-soft);overflow:hidden;color:var(--muted);font-size:.86em}"
                + ".agent-command>summary{display:flex;align-items:center;gap:7px;min-width:0;padding:7px 10px;cursor:pointer;list-style:none;user-select:none;white-space:nowrap}"
                + ".agent-command>summary::-webkit-details-marker{display:none}.agent-command>summary:focus-visible{outline:2px solid var(--accent);outline-offset:-2px}"
                + ".tool-chevron{flex:none;color:var(--accent);font-size:1.15em;line-height:1;transition:transform .14s ease}.agent-command[open] .tool-chevron{transform:rotate(90deg)}"
                + ".tool-name{flex:none;font-weight:650;color:var(--fg)}.tool-preview{min-width:0;flex:1;overflow:hidden;text-overflow:ellipsis;font-family:ui-monospace,SFMono-Regular,Consolas,monospace;color:var(--muted)}"
                + ".tool-status{flex:none;font-size:.78em;color:var(--muted)}.tool-status.completed{color:var(--success)}.tool-status.failed,.tool-status.error{color:#c44d56}"
                + ".tool-expanded{padding:0 10px 10px;border-top:1px solid var(--line)}.tool-meta{margin:8px 2px 0;font-size:.82em;color:var(--muted)}.tool-expanded .code-block{margin:9px 0 0;box-shadow:none}"
                + ".tool-file{margin:10px 2px 0;font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:.84em;color:var(--fg)}"
                + "a{color:var(--accent)}a:focus-visible{outline:2px solid var(--accent);outline-offset:3px}"
                + ".image-frame{position:relative;display:inline-block;max-width:100%;margin:6px 8px 6px 0;vertical-align:top;line-height:0}"
                + ".transcript-image{display:block;max-width:100%;max-height:34rem;border:1px solid var(--line);border-radius:12px;background:var(--surface);box-shadow:0 3px 12px rgba(0,0,0,.1);cursor:zoom-in;transition:transform .16s ease,box-shadow .16s ease}"
                + ".message-user .transcript-image{max-width:min(320px,70vw);max-height:260px}.image-frame:hover .transcript-image{transform:translateY(-1px);box-shadow:0 7px 20px rgba(0,0,0,.16)}"
                + ".transcript-image:focus-visible{outline:3px solid var(--accent);outline-offset:3px}.image-hint{position:absolute;right:8px;bottom:8px;padding:8px;border-radius:999px;background:rgba(14,16,20,.72);color:#fff;font-size:12px;line-height:1;opacity:0;transform:translateY(2px);transition:.16s ease;pointer-events:none}"
                + ".image-frame:hover .image-hint,.image-frame:focus-within .image-hint{opacity:1;transform:none}"
                + ".image-lightbox{position:fixed;inset:0;z-index:1000;display:none;place-items:center;padding:40px;background:rgba(10,12,16,.88);backdrop-filter:blur(10px)}.image-lightbox.open{display:grid}"
                + ".image-lightbox img{max-width:96vw;max-height:88vh;object-fit:contain;border-radius:10px;box-shadow:0 20px 70px rgba(0,0,0,.45)}.image-lightbox button{position:absolute;right:18px;top:18px;width:38px;height:38px;border:1px solid rgba(255,255,255,.25);border-radius:50%;background:rgba(20,22,27,.72);color:#fff;font-size:22px;cursor:pointer}"
                + ".welcome{padding:clamp(24px,10vh,100px) 4px 24px;max-width:430px;margin:auto}.mark{font-size:32px;color:var(--accent)}"
                + ".welcome p,.hint{color:var(--muted)}.hint{font-size:.9em;margin-top:24px}"
                + "@media(prefers-reduced-motion:reduce){.transcript-image,.image-hint{transition:none}}"
                + "@media(max-width:480px){body{padding:14px 12px 28px}.message-user{max-width:90%}.image-lightbox{padding:18px}}"
                + "</style></head><body><main>" + content + "</main>"
                + "<div id='image-lightbox' class='image-lightbox' role='dialog' aria-modal='true' aria-label='Image preview'><button type='button' aria-label='Close image preview'>×</button><img alt=''></div>"
                + "<script>(()=>{const copy=async text=>{if(navigator.clipboard&&window.isSecureContext){await navigator.clipboard.writeText(text);return;}const area=document.createElement('textarea');area.value=text;area.style.position='fixed';area.style.opacity='0';document.body.appendChild(area);area.select();document.execCommand('copy');area.remove();};"
                + "const lightbox=document.getElementById('image-lightbox'),full=lightbox.querySelector('img'),close=lightbox.querySelector('button');const openImage=image=>{full.src=image.src;full.alt=image.alt||'';lightbox.classList.add('open');close.focus();};const closeImage=()=>{lightbox.classList.remove('open');full.removeAttribute('src');};"
                + "document.addEventListener('click',event=>{const button=event.target.closest('.copy-code');if(button){const code=button.closest('.code-block').querySelector('code');copy(code.innerText).then(()=>{button.textContent='Copied';button.classList.add('copied');setTimeout(()=>{button.textContent='Copy';button.classList.remove('copied');},1400);}).catch(()=>{});return;}const image=event.target.closest('.transcript-image');if(image){event.preventDefault();openImage(image);return;}if(event.target===lightbox||event.target===close)closeImage();});"
                + "document.addEventListener('keydown',event=>{if(event.key==='Escape'&&lightbox.classList.contains('open'))closeImage();const image=event.target.closest&&event.target.closest('.transcript-image');if(image&&(event.key==='Enter'||event.key===' ')){event.preventDefault();openImage(image);}});})();</script>"
                + "</body></html>";
    }

    static String conversationPart(String markdown, Function<String, String> fileLinkResolver) {
        if (markdown == null || markdown.isBlank()) return "";
        HtmlRenderer activeRenderer = fileLinkResolver == null ? RENDERER : renderer(fileLinkResolver);
        return "<section class='conversation-preamble'>" + activeRenderer.render(PARSER.parse(markdown)) + "</section>";
    }

    private static HtmlRenderer renderer(Function<String, String> fileLinkResolver) {
        HtmlRenderer.Builder builder = HtmlRenderer.builder().extensions(EXTENSIONS).escapeHtml(true)
                .nodeRendererFactory(DiffCodeBlockRenderer::new)
                .nodeRendererFactory(AgentEventRenderer::new)
                .nodeRendererFactory(TranscriptImageRenderer::new)
                .nodeRendererFactory(ConversationHeadingRenderer::new);
        if (fileLinkResolver != null) builder.nodeRendererFactory(context -> new FileReferenceRenderer(context, fileLinkResolver));
        return builder.build();
    }

    /** Gives compact protocol notices a distinct visual hierarchy without exposing raw HTML in Markdown. */
    private static final class AgentEventRenderer implements NodeRenderer {
        private final HtmlNodeRendererContext context;
        private final HtmlWriter writer;

        private AgentEventRenderer(HtmlNodeRendererContext context) {
            this.context = context;
            writer = context.getWriter();
        }

        @Override public Set<Class<? extends Node>> getNodeTypes() { return Set.of(BlockQuote.class); }

        @Override public void render(Node node) {
            String kind = eventKind((BlockQuote) node);
            if (kind == null) {
                writer.line();
                writer.tag("blockquote");
                renderChildren(node);
                writer.tag("/blockquote");
                writer.line();
                return;
            }
            writer.line();
            writer.tag("aside", Map.of("class", "agent-event " + kind));
            renderChildren(node);
            writer.tag("/aside");
            writer.line();
        }

        private static String eventKind(BlockQuote quote) {
            if (!(quote.getFirstChild() instanceof Paragraph paragraph)
                    || !(paragraph.getFirstChild() instanceof StrongEmphasis strong)) return null;
            String label = textContent(strong);
            if (label.startsWith("Tool ")) return "event-tool";
            if (label.equals("File changes:")) return "event-changes";
            if (label.equals("Terminal output")) return "event-terminal";
            if (label.equals("Usage:") || label.equals("Timing:")) return "event-meta";
            if (label.equals("Attachments:") || label.equals("Source files:")) return "event-context";
            return null;
        }

        private void renderChildren(Node parent) {
            for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) context.render(child);
        }
    }

    /** Renders Markdown images as keyboard-accessible thumbnails handled by the document lightbox. */
    private static final class TranscriptImageRenderer implements NodeRenderer {
        private final HtmlNodeRendererContext context;
        private final HtmlWriter writer;

        private TranscriptImageRenderer(HtmlNodeRendererContext context) {
            this.context = context;
            writer = context.getWriter();
        }

        @Override public Set<Class<? extends Node>> getNodeTypes() { return Set.of(Image.class); }

        @Override public void render(Node node) {
            Image image = (Image) node;
            String alt = textContent(image);
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put("src", context.encodeUrl(image.getDestination()));
            attributes.put("alt", alt);
            attributes.put("class", "transcript-image");
            attributes.put("loading", "lazy");
            attributes.put("decoding", "async");
            attributes.put("tabindex", "0");
            attributes.put("role", "button");
            attributes.put("aria-label", alt.isBlank() ? "Open image preview" : "Open image preview: " + alt);
            if (image.getTitle() != null && !image.getTitle().isBlank()) attributes.put("title", image.getTitle());
            writer.tag("span", Map.of("class", "image-frame"));
            writer.tag("img", context.extendAttributes(image, "img", attributes), true);
            writer.tag("span", Map.of("class", "image-hint", "aria-hidden", "true"));
            writer.text("↗");
            writer.tag("/span");
            writer.tag("/span");
        }
    }

    private static String textContent(Node parent) {
        StringBuilder text = new StringBuilder();
        appendText(parent, text);
        return text.toString();
    }

    private static void appendText(Node parent, StringBuilder text) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (child instanceof Text literal) text.append(literal.getLiteral());
            else if (child instanceof Code code) text.append(code.getLiteral());
            else appendText(child, text);
        }
    }

    /** Turns the internal You/Agent headings into aligned, unlabeled message containers. */
    private static final class ConversationHeadingRenderer implements NodeRenderer {
        private final HtmlNodeRendererContext context;
        private final HtmlWriter writer;

        private ConversationHeadingRenderer(HtmlNodeRendererContext context) {
            this.context = context;
            this.writer = context.getWriter();
        }

        @Override public Set<Class<? extends Node>> getNodeTypes() { return Set.of(Heading.class); }

        @Override public void render(Node node) {
            Heading heading = (Heading) node;
            String role = conversationRole(heading);
            if (role != null) {
                writer.raw("</section><section class=\"message message-" + role + "\">");
                return;
            }
            String tag = "h" + heading.getLevel();
            writer.line();
            writer.tag(tag);
            for (Node child = heading.getFirstChild(); child != null; child = child.getNext()) context.render(child);
            writer.tag("/" + tag);
            writer.line();
        }

        private static String conversationRole(Heading heading) {
            if (heading.getLevel() != 2 || !(heading.getFirstChild() instanceof Text text)
                    || text.getNext() != null) return null;
            return switch (text.getLiteral()) {
                case "You" -> "user";
                case "Agent" -> "agent";
                default -> null;
            };
        }
    }

    /** Links only plain text and inline code; fenced code remains literal source text. */
    private static final class FileReferenceRenderer implements NodeRenderer {
        // The resolver performs the authoritative workspace validation. Keeping this lexical
        // match permissive also accommodates paths with Unicode or generated-file characters.
        private static final Pattern FILE_REFERENCE = Pattern.compile(
                "(?<!\\S)([^\\s]+?\\.[A-Za-z0-9]{1,12}(?::[1-9][0-9]*(?:-[1-9][0-9]*)?)?)");
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
        private static final int MAX_DIFF_LINES = 40;
        private final HtmlWriter writer;

        private DiffCodeBlockRenderer(HtmlNodeRendererContext context) {
            writer = context.getWriter();
        }

        @Override public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(FencedCodeBlock.class);
        }

        @Override public void render(Node node) {
            FencedCodeBlock block = (FencedCodeBlock) node;
            String language = language(block.getInfo());
            if ("acp-tool".equalsIgnoreCase(language) && renderToolCall(block.getLiteral())) return;
            boolean diff = "diff".equalsIgnoreCase(language);
            writer.line();
            renderCodeBlock(languageLabel(language), language, block.getLiteral(), diff);
            writer.line();
        }

        private boolean renderToolCall(String encoded) {
            JsonObject tool;
            try {
                String json = new String(Base64.getUrlDecoder().decode(encoded.strip()), StandardCharsets.UTF_8);
                tool = JsonParser.parseString(json).getAsJsonObject();
            } catch (RuntimeException exception) {
                return false;
            }
            String kind = value(tool, "kind");
            String title = value(tool, "title");
            String command = value(tool, "command");
            String status = value(tool, "status");
            String preview = command.isBlank() ? title : firstLine(command);
            if (preview.isBlank()) preview = "Tool call";

            writer.line();
            writer.tag("details", Map.of("class", "agent-command"));
            writer.tag("summary");
            writer.tag("span", Map.of("class", "tool-chevron", "aria-hidden", "true"));
            writer.text("›");
            writer.tag("/span");
            writer.tag("span", Map.of("class", "tool-name"));
            writer.text("Tool " + (kind.isBlank() ? "command" : kind) + ":");
            writer.tag("/span");
            writer.tag("span", Map.of("class", "tool-preview", "title", preview));
            writer.text(preview);
            writer.tag("/span");
            if (!status.isBlank()) {
                writer.tag("span", Map.of("class", "tool-status " + statusClass(status)));
                writer.text(status);
                writer.tag("/span");
            }
            writer.tag("/summary");
            writer.tag("div", Map.of("class", "tool-expanded"));
            if (!command.isBlank()) renderCodeBlock("Command", "command", command, false);
            String workingDirectory = value(tool, "workingDirectory");
            if (!workingDirectory.isBlank()) renderMeta("Working directory: " + workingDirectory);
            String path = value(tool, "path");
            if (!path.isBlank()) renderMeta("Path: " + path);
            String output = value(tool, "terminalOutput");
            if (!output.isBlank()) renderCodeBlock("Terminal output", "terminal", output, false);
            renderDiffs(tool.get("diffs"));
            if (command.isBlank() && output.isBlank() && !title.isBlank()) renderMeta(title);
            writer.tag("/div");
            writer.tag("/details");
            writer.line();
            return true;
        }

        private void renderMeta(String text) {
            writer.tag("div", Map.of("class", "tool-meta"));
            writer.text(text);
            writer.tag("/div");
        }

        private void renderDiffs(JsonElement diffs) {
            if (diffs == null || !diffs.isJsonArray()) return;
            for (JsonElement item : diffs.getAsJsonArray()) {
                if (!item.isJsonObject()) continue;
                JsonObject diff = item.getAsJsonObject();
                String path = value(diff, "path");
                writer.tag("div", Map.of("class", "tool-file"));
                writer.text(path.isBlank() ? "File changes" : path);
                writer.tag("/div");
                renderCodeBlock("Changes", "diff", diffLiteral(path,
                        nullableValue(diff, "oldText"), nullableValue(diff, "newText")), true);
            }
        }

        private void renderCodeBlock(String label, String language, String literal, boolean diff) {
            writer.tag("div", Map.of("class", diff ? "code-block diff" : "code-block"));
            writer.tag("div", Map.of("class", "code-toolbar"));
            writer.tag("span", Map.of("class", "code-language"));
            writer.text(label);
            writer.tag("/span");
            writer.tag("button", Map.of("class", "copy-code", "type", "button", "aria-label", "Copy code"));
            writer.text("Copy");
            writer.tag("/button");
            writer.tag("/div");
            writer.tag("pre");
            writer.tag("code", languageClass(language));
            if (diff) renderDiff(literal); else writer.text(literal);
            writer.tag("/code");
            writer.tag("/pre");
            writer.tag("/div");
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

        private static String value(JsonObject object, String name) {
            JsonElement value = object.get(name);
            return value != null && !value.isJsonNull() && value.isJsonPrimitive() ? value.getAsString() : "";
        }

        private static String nullableValue(JsonObject object, String name) {
            JsonElement value = object.get(name);
            return value == null || value.isJsonNull() ? null : value.getAsString();
        }

        private static String firstLine(String value) {
            int newline = value.indexOf('\n');
            return (newline < 0 ? value : value.substring(0, newline)).strip();
        }

        private static String statusClass(String status) {
            return status.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        }

        private static String diffLiteral(String path, String oldText, String newText) {
            StringBuilder result = new StringBuilder("--- ").append(path).append("\n+++ ").append(path).append('\n');
            appendDiffSide(result, '-', oldText);
            appendDiffSide(result, '+', newText);
            return result.toString();
        }

        private static void appendDiffSide(StringBuilder target, char prefix, String text) {
            if (text == null) return;
            String[] lines = text.split("\\R", -1);
            for (int index = 0; index < Math.min(lines.length, MAX_DIFF_LINES); index++) {
                target.append(prefix).append(lines[index]).append('\n');
            }
            if (lines.length > MAX_DIFF_LINES) {
                target.append(prefix).append("… ").append(lines.length - MAX_DIFF_LINES).append(" more lines\n");
            }
        }

        private static Map<String, String> languageClass(String info) {
            String language = language(info);
            return language.isEmpty() ? Map.of() : Map.of("class", "language-" + language);
        }

        private static String languageLabel(String language) {
            return switch (language.toLowerCase(java.util.Locale.ROOT)) {
                case "" -> "Code";
                case "js", "javascript" -> "JavaScript";
                case "ts", "typescript" -> "TypeScript";
                case "sh", "bash", "shell" -> "Shell";
                case "terminal" -> "Terminal output";
                case "command" -> "Command";
                case "diff" -> "Changes";
                case "md", "markdown" -> "Markdown";
                case "yml", "yaml" -> "YAML";
                case "json" -> "JSON";
                case "xml" -> "XML";
                case "html" -> "HTML";
                case "css" -> "CSS";
                case "java" -> "Java";
                case "python", "py" -> "Python";
                case "text", "txt" -> "Text";
                default -> language;
            };
        }
    }
}
