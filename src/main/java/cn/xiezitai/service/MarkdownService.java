package cn.xiezitai.service;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;
import org.springframework.stereotype.Service;

import java.util.List;

/** Markdown -> HTML（公开页服务端渲染，SEO 友好） */
@Service
public class MarkdownService {

    private final Parser parser;
    private final HtmlRenderer renderer;

    public MarkdownService() {
        List<Extension> ext = List.of(TablesExtension.create());
        this.parser = Parser.builder().extensions(ext).build();
        this.renderer = HtmlRenderer.builder().extensions(ext).build();
    }

    public String toHtml(String markdown) {
        if (markdown == null) return "";
        Node doc = parser.parse(markdown);
        return renderer.render(doc);
    }
}
