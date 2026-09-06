package dev.eclipseacp.client.ui;

import java.util.List;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

/** Safe HTML rendering for the CommonMark core and GFM table extension. */
final class GfmRenderer {
    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().extensions(EXTENSIONS).escapeHtml(true).build();

    private GfmRenderer() { }

    static String document(String markdown) {
        return document(markdown, "sans-serif", 10);
    }

    static String document(String markdown, String fontFamily, int fontSizePoints) {
        String cssFontFamily = fontFamily == null ? "sans-serif" : fontFamily.replace("\\", "\\\\").replace("'", "\\'");
        String content = markdown.isBlank()
                ? "<section class='welcome'><div class='mark'>✦</div><h1>Build something great</h1>"
                    + "<p>Explore your code, solve a problem, or plan your next change.</p>"
                    + "<div class='hint'>To start, right-click a project and open an ACP session.</div>"
                    + "<div class='examples'><span>@file · Current file</span><span>@selection · Selected code</span>"
                    + "<span>@problems · Workspace diagnostics</span></div></section>"
                : RENDERER.render(PARSER.parse(markdown));
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name='viewport' content='width=device-width,initial-scale=1'><style>"
                + ":root{color-scheme:light dark;--bg:#ffffff;--fg:#24292f;--muted:#626b78;--surface:#f5f6f8;--line:#dce1e8;--accent:#6254c7;}"
                + "@media(prefers-color-scheme:dark){:root{--bg:#1e1f22;--fg:#e1e4ea;--muted:#a4adba;--surface:#292b30;--line:#414550;--accent:#b1a5ff;}}"
                + "*{box-sizing:border-box}body{background:var(--bg);color:var(--fg);font-family:'" + cssFontFamily
                + "',sans-serif;font-size:" + fontSizePoints + "pt;margin:0;padding:20px;line-height:1.65;overflow-wrap:anywhere;}"
                + "main{max-width:900px;margin:auto}h1{font-size:1.5em;letter-spacing:-.03em;line-height:1.3}"
                + "h2{font-size:.85em;letter-spacing:.04em;color:var(--accent);border-top:1px solid var(--line);padding-top:20px;margin-top:28px}"
                + "p{margin:10px 0}pre{background:var(--surface);border:1px solid var(--line);border-radius:10px;padding:14px;overflow:auto;overflow-wrap:normal;}"
                + "code{font-family:ui-monospace,SFMono-Regular,Consolas,monospace;font-size:.92em;background:var(--surface);padding:2px 5px;border-radius:4px}pre code{padding:0}"
                + "table{display:block;max-width:100%;overflow:auto;border-collapse:collapse;}th,td{border:1px solid var(--line);padding:8px 12px;}th{background:var(--surface)}"
                + "blockquote{border-left:3px solid var(--accent);border-radius:0 8px 8px 0;margin:14px 0;padding:6px 12px;color:var(--muted);background:var(--surface);}"
                + "a{color:var(--accent)}a:focus-visible{outline:2px solid var(--accent);outline-offset:3px}"
                + ".welcome{padding:clamp(24px,10vh,100px) 4px 24px;max-width:430px;margin:auto}.mark{font-size:32px;color:var(--accent)}"
                + ".welcome p,.hint{color:var(--muted)}.hint{font-size:.9em;margin-top:24px}.examples{display:flex;flex-wrap:wrap;gap:8px;margin-top:20px}"
                + ".examples span{font-size:.85em;background:var(--surface);border:1px solid var(--line);padding:6px 10px;border-radius:8px}"
                + "@media(max-width:360px){body{padding:12px}}"
                + "</style></head><body><main>" + content + "</main></body></html>";
    }
}
