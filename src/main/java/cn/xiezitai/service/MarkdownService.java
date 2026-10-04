package cn.xiezitai.service;

import org.commonmark.Extension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
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
        // 表格 + 任务列表：ByteMD 预览走 GFM，服务端渲染必须对齐，否则 `- [x]` 会原样显示成字面量
        // 注意类名是 TaskListItemsExtension（不是 TaskListExtension），且它不给节点加 class，
        // 只输出 `<input type="checkbox" checked="" disabled="">` —— 样式需靠 CSS :has() 命中
        List<Extension> ext = List.of(TablesExtension.create(), TaskListItemsExtension.create());
        this.parser = Parser.builder().extensions(ext).build();
        this.renderer = HtmlRenderer.builder().extensions(ext).build();
    }

    public String toHtml(String markdown) {
        if (markdown == null) return "";
        Node doc = parser.parse(markdown);
        return renderer.render(doc);
    }
}
