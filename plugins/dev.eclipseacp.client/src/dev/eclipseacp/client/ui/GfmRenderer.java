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
        return "<!doctype html><html><head><meta charset=\"utf-8\"><style>"
                + "body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;margin:12px;line-height:1.45;}"
                + "pre{background:#f6f8fa;padding:10px;overflow:auto;}code{background:#f6f8fa;padding:1px 3px;}"
                + "table{border-collapse:collapse;}th,td{border:1px solid #d0d7de;padding:6px 10px;}blockquote{border-left:3px solid #d0d7de;margin-left:0;padding-left:10px;color:#57606a;}"
                + "</style></head><body>" + RENDERER.render(PARSER.parse(markdown)) + "</body></html>";
    }
}
