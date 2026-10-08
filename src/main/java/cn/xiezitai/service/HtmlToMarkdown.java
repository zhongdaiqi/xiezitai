package cn.xiezitai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTML → Markdown 转换器（零依赖，只用 JDK 标准库）。
 *
 * <p><b>为什么需要它：</b>WordPress 站点若装了 Markdown 类插件（WP Githuber MD / Jetpack /
 * Markdown Editor 等），后台编辑器里写的是 Markdown，但 WordPress REST API 的
 * {@code content.rendered} 是插件<b>渲染之后的 HTML</b>。直接入库会让本站（用 ByteMD 渲染
 * Markdown）把 HTML 源码当普通文本显示，文章看起来是坏掉的。
 *
 * <p><b>覆盖范围：</b>标题、段落、加粗/斜体/删除线、行内代码、代码块（含语言）、
 * 多级有序/无序列表、引用（可嵌套）、链接、图片（含懒加载 data-src）、水平线、表格、图片说明。
 * 无法识别的标签一律剥离但保留文字；{@code iframe/video/audio} 等富媒体原样保留
 * （Markdown 允许内联 HTML）。Gutenberg 的 {@code <!-- wp:xxx -->} 块注释会被清理。
 *
 * <p><b>两种转换模式：</b>
 * <ul>
 *   <li>{@link #convert(String)} —— <b>普通式</b>：正文是真 HTML，文本节点里的 Markdown 元字符
 *       （反斜杠、星号、下划线、反引号、方括号、尖括号）一律加反斜杠转义，优先保证
 *       「渲染出来和源站一致」，而不是源码好看。</li>
 *   <li>{@link #convertMarkdownWrapped(String)} —— <b>还原式</b>：正文本身就是 Markdown，
 *       只是被编辑器逐行包进了 HTML（详见 {@link #looksLikeMarkdownInHtml(String)}）。
 *       此时不转义元字符，反而要把被编辑器弄坏的语法还原回来。</li>
 * </ul>
 */
public final class HtmlToMarkdown {

    private HtmlToMarkdown() { }

    /**
     * 出现这些块级标签就认为正文是 HTML。
     *
     * <p>刻意<b>不</b>包含 {@code iframe/video/audio/img/a} —— Markdown 原文里内嵌这些标签太常见了，
     * 把它们当判据会让「本来就是 Markdown」的正文被误转换。
     */
    private static final Pattern BLOCK_TAG = Pattern.compile(
            "(?i)<(p|div|section|article|header|footer|h[1-6]|ul|ol|li|blockquote|pre|table"
                    + "|thead|tbody|tfoot|tr|td|th|figure|figcaption|hr|dl|dd|dt)\\b");

    /** 正文是不是 HTML（决定要不要走转换） */
    public static boolean looksLikeHtml(String s) {
        return s != null && BLOCK_TAG.matcher(s).find();
    }

    /**
     * 「Markdown 被 HTML 包裹」的强特征 —— 用于挑转换模式。
     *
     * <p>典型来源：作者把 Markdown 粘进 WordPress 古腾堡（或某些富文本编辑器）后，编辑器
     * 逐行把文本包成 {@code <p>}、把行首 {@code #} 变成 {@code <strong>}，而
     * {@code **}、<code>```</code>、{@code ![..](..)} 这些标记<b>原样留成了字面文本</b>。
     *
     * <p>这种正文如果按普通式转换（元字符全部转义），会把本来就是正确的 Markdown 语法一起
     * 转义掉，页面依然是坏的（{@code **# 标题**} 渲染出来还是字面量）。
     *
     * <p>四个判据都是「正常 HTML 正文里几乎不可能出现」的组合，误判概率很低。
     */
    private static final Pattern[] MD_IN_HTML_SIGNALS = {
            Pattern.compile("(?is)<(?:strong|b)\\b[^>]*>\\s*#{1,6}\\s"),                    // <strong># 标题</strong>
            Pattern.compile("(?is)<(?:strong|b)\\b[^>]*>\\s*\\*\\*[^<]"),                  // <strong>**粗体**</strong>
            Pattern.compile("(?is)<(?:p|div|li)\\b[^>]*>\\s*`{3,}"),                        // <p>```python
            Pattern.compile("(?is)<(?:p|div|li)\\b[^>]*>\\s*!\\[[^\\]]*\\]\\([^)]{1,500}\\)"), // <p>![图](url)
    };

    /** 正文是不是「HTML 里裹着 Markdown 字面文本」（决定用还原式还是普通式转换） */
    public static boolean looksLikeMarkdownInHtml(String s) {
        if (s == null || s.isBlank()) return false;
        for (Pattern p : MD_IN_HTML_SIGNALS) {
            if (p.matcher(s).find()) return true;
        }
        return false;
    }

    /** HTML → Markdown（普通式：文本里的 Markdown 元字符一律转义）；入参为空时返回空串 */
    public static String convert(String html) {
        if (html == null || html.isBlank()) return "";
        return new Conv(false).run(html);
    }

    /**
     * HTML → Markdown（还原式）：正文本身是 Markdown，只是被 HTML 逐行包住了。
     *
     * <p>与普通式的差别：<b>不</b>转义元字符，并额外做这些「抢救」：
     * <ul>
     *   <li>{@code <strong># 标题</strong>} → {@code # 标题}（行首 {@code #} 被编辑器吃成了加粗）</li>
     *   <li>{@code <strong>**粗体**</strong>} → {@code **粗体**}（去掉重复的外层加粗，保留字面标记）</li>
     *   <li>{@code <p>```python</p>} 这类孤立围栏行 → 真正的代码块，块内内容原样保留（含缩进、{@code #} 注释）</li>
     *   <li>被「智能标点」弄坏的 {@code ———} 表格分隔行 / {@code –} 列表符号 → 还原成 {@code ---} / {@code -}</li>
     *   <li>{@code ![图](url)}、{@code 1. 项}、{@code |表格|} 等字面 Markdown 原样输出，交给渲染器正常解析</li>
     * </ul>
     */
    public static String convertMarkdownWrapped(String html) {
        if (html == null || html.isBlank()) return "";
        return new Conv(true).run(html);
    }

    /* ==================================================================== */

    private static final class Conv {

        /** 还原式：正文本身就是 Markdown（不转义元字符，见 convertMarkdownWrapped） */
        private final boolean preserve;

        /** 还原式专用：当前是否处于「被 HTML 包住的代码围栏」内部 */
        private boolean inFence;

        /** 预抽出的「原样内容」：代码块、行内代码 */
        private final List<String> codes = new ArrayList<>();

        Conv(boolean preserve) {
            this.preserve = preserve;
        }

        private static final String PH_OPEN = "\u0001";
        private static final String PH_CLOSE = "\u0002";
        private static final Pattern PH = Pattern.compile(PH_OPEN + "(\\d+)" + PH_CLOSE);

        /** 标签：group1 = /，group2 = 名字，group3 = 属性，group4 = 自闭合斜杠 */
        private static final Pattern TAG = Pattern.compile(
                "<(/?)([a-zA-Z][a-zA-Z0-9-]*)((?:\"[^\"]*\"|'[^']*'|[^>\"'/])*)(/?)>");

        /** 块级容器：内容按块递归处理 */
        private static final Set<String> CONTAINERS = Set.of(
                "div", "section", "article", "header", "footer", "main", "aside", "nav", "form",
                "details", "summary", "center", "dl");

        String run(String html) {
            String s = stripNoise(html);
            s = hoistPre(s);
            s = hoistInlineCode(s);
            String md = tidy(blocks(s));
            return preserve ? tighten(md) : md;
        }

        /**
         * 还原式收尾：每个 Markdown 行都被包成了独立的 {@code <p>}，转换出来行与行之间全是空行。
         * 空行会让 <b>GFM 表格</b>（要求表头/分隔/数据行连续）和 <b>紧凑列表</b> 失效，所以把
         * 连续的表格行、连续的列表项之间的空行去掉。
         */
        private static String tighten(String md) {
            String prev;
            String s = md;
            do {
                prev = s;
                s = s.replaceAll("(?m)^(\\s*\\|.*\\|)\\n\\n(?=\\s*\\|)", "$1\n");
                s = s.replaceAll("(?m)^(\\s*(?:\\d+[.)]|[-*+]) \\S.*)\\n\\n(?=\\s*(?:\\d+[.)]|[-*+]) \\S)", "$1\n");
            } while (!s.equals(prev));
            return s;
        }

        /* ---------------- 预处理 ---------------- */

        private String stripNoise(String h) {
            String s = h.replace("\r\n", "\n").replace('\r', '\n');
            s = s.replaceAll("(?is)<!--.*?-->", "");
            s = s.replaceAll("(?is)<script\\b.*?</script\\s*>", "");
            s = s.replaceAll("(?is)<style\\b.*?</style\\s*>", "");
            s = s.replaceAll("(?is)<!\\[CDATA\\[.*?\\]\\]>", "");
            s = s.replaceAll("(?is)<!doctype[^>]*>", "");
            return s;
        }

        /** 代码块先抽出来做占位（内容不能被后面的转义/空白压缩破坏） */
        private String hoistPre(String s) {
            return replaceAll(s, Pattern.compile("(?is)<pre\\b([^>]*)>(.*?)</pre\\s*>"), m -> {
                String attrs = m.group(1) == null ? "" : m.group(1);
                String inner = m.group(2) == null ? "" : m.group(2);
                String lang = langOf(attrs + " " + inner);
                String code = decodeEntities(inner
                        .replaceAll("(?is)<br\\s*/?>", "\n")
                        .replaceAll("(?is)</?code\\b[^>]*>", "")
                        .replaceAll("(?is)<[^>]+>", ""));
                code = trimBlankEdges(code);
                String fence = code.contains("```") ? "````" : "```";
                return addCode("\n\n" + fence + lang + "\n" + code + "\n" + fence + "\n\n");
            });
        }

        /** 行内代码同样抽出来做占位 */
        private String hoistInlineCode(String s) {
            return replaceAll(s, Pattern.compile("(?is)<code\\b[^>]*>(.*?)</code\\s*>"), m -> {
                String code = decodeEntities(m.group(1).replaceAll("(?is)<[^>]+>", ""))
                        .replaceAll("[\\r\\n]+", " ");
                String ticks = code.contains("`") ? "``" : "`";
                return addCode(ticks + code + ticks);
            });
        }

        private String addCode(String md) {
            codes.add(md);
            return PH_OPEN + "c" + (codes.size() - 1) + PH_CLOSE;
        }

        private static String langOf(String s) {
            Matcher m = Pattern.compile("(?i)(?:language|lang|brush|lang-)([-:]?\\s*)([a-z0-9+#._-]+)").matcher(s);
            while (m.find()) {
                String lang = m.group(2).toLowerCase(Locale.ROOT);
                if (!lang.isBlank() && !lang.equals("language") && !lang.equals("lang")) return lang;
            }
            return "";
        }

        /* ---------------- 块级 ---------------- */

        private String blocks(String s) {
            StringBuilder out = new StringBuilder(s.length() + 16);
            Matcher m = TAG.matcher(s);
            int i = 0;
            while (i < s.length()) {
                m.region(i, s.length());
                if (!m.find()) {
                    out.append(inline(s.substring(i)));
                    break;
                }
                out.append(inline(s.substring(i, m.start())));
                String name = m.group(2).toLowerCase(Locale.ROOT);
                boolean closing = !m.group(1).isEmpty();
                boolean selfClose = !m.group(4).isEmpty();
                String attrs = m.group(3) == null ? "" : m.group(3);
                int after = m.end();

                if (closing) { i = after; continue; }   // 孤立闭合标签：丢掉

                switch (name) {
                    case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                        int close = findClose(s, after, name);
                        int level = name.charAt(1) - '0';
                        out.append("\n\n").append("#".repeat(level)).append(' ')
                                .append(inline(s.substring(after, close)).trim()).append("\n\n");
                        i = afterTag(s, close);
                    }
                    case "p" -> {
                        int close = findClose(s, after, name);
                        String raw = s.substring(after, close);
                        boolean handled = false;
                        if (preserve) {
                            String line = plainText(raw);
                            String lang = fenceLine(line.strip());
                            if (lang != null) {                       // 整行就是 ```lang / ```
                                if (!inFence) {
                                    inFence = true;
                                    out.append("\n\n```").append(lang).append('\n');
                                } else {
                                    inFence = false;
                                    out.append("```\n\n");
                                }
                                handled = true;
                            } else if (inFence) {                     // 围栏内部：原样保留（缩进、# 注释都不能动）
                                out.append(repairLine(rstrip(line))).append('\n');
                                handled = true;
                            } else {
                                String head = headingInBold(raw);
                                if (head != null) {
                                    out.append("\n\n").append(head).append("\n\n");
                                    handled = true;
                                }
                            }
                        }
                        if (!handled) {
                            String body = inline(raw).trim();
                            if (!body.isEmpty()) out.append("\n\n").append(preserve ? repairLine(body) : body).append("\n\n");
                        }
                        i = afterTag(s, close);
                    }
                    case "ul", "ol" -> {
                        int close = findClose(s, after, name);
                        out.append('\n').append(listBlock(s.substring(after, close), name, 0)).append('\n');
                        i = afterTag(s, close);
                    }
                    case "blockquote" -> {
                        int close = findClose(s, after, name);
                        String inner = blocks(s.substring(after, close)).trim();
                        if (!inner.isEmpty()) out.append("\n\n").append(quote(inner)).append("\n\n");
                        i = afterTag(s, close);
                    }
                    case "table" -> {
                        int close = findClose(s, after, name);
                        String t = tableBlock(s.substring(after, close));
                        if (!t.isBlank()) out.append("\n\n").append(t).append("\n\n");
                        i = afterTag(s, close);
                    }
                    case "figure" -> {
                        int close = findClose(s, after, name);
                        String f = figureBlock(s.substring(after, close));
                        if (!f.isBlank()) out.append("\n\n").append(f).append("\n\n");
                        i = afterTag(s, close);
                    }
                    case "hr" -> {
                        out.append("\n\n---\n\n");
                        i = after;
                    }
                    case "br" -> {
                        out.append("  \n");
                        i = after;
                    }
                    case "img" -> {
                        out.append(imgTag(attrs));
                        i = after;
                    }
                    case "pre", "code" -> i = after;     // 已被 hoist，兜底丢弃
                    default -> {
                        if (selfClose) {
                            out.append(inline(m.group()));
                            i = after;
                        } else {
                            int close = findClose(s, after, name);
                            int end = afterTag(s, close);
                            if (CONTAINERS.contains(name)) {
                                String inner = blocks(s.substring(after, close)).trim();
                                if (!inner.isEmpty()) out.append("\n\n").append(inner).append("\n\n");
                            } else {
                                // 行内元素/未知块：整段交给 inline（保留文字、剥离标签）
                                out.append(inline(s.substring(m.start(), end)));
                            }
                            i = end;
                        }
                    }
                }
            }
            return out.toString();
        }

        /** 列表（可多级）：每一项单独解析，嵌套列表挂在项后面 */
        private String listBlock(String inner, String type, int depth) {
            StringBuilder out = new StringBuilder();
            boolean ordered = "ol".equals(type);
            int idx = 0;
            Matcher li = Pattern.compile("(?is)<li\\b[^>]*>").matcher(inner);
            int pos = 0;
            while (li.find(pos)) {
                int start = li.end();
                int close = findClose(inner, start, "li");
                String item = inner.substring(start, close);
                String marker = ordered ? (++idx) + ". " : "- ";
                out.append('\n').append("  ".repeat(depth)).append(marker)
                        .append(itemContent(item, depth + 1));
                pos = afterTag(inner, close);
            }
            return out.toString();
        }

        /** 列表项内容：先摘出嵌套列表，剩余部分按行内处理 */
        private String itemContent(String item, int depth) {
            StringBuilder tail = new StringBuilder();
            StringBuilder keep = new StringBuilder();
            Matcher n = Pattern.compile("(?is)<(ul|ol)\\b[^>]*>").matcher(item);
            int pos = 0;
            while (n.find(pos)) {
                String t = n.group(1).toLowerCase(Locale.ROOT);
                int close = findClose(item, n.end(), t);
                keep.append(item, pos, n.start());
                tail.append(listBlock(item.substring(n.end(), close), t, depth));
                pos = afterTag(item, close);
            }
            keep.append(item.substring(Math.min(pos, item.length())));
            String head = inline(keep.toString()).trim();
            return (head + tail).isBlank() ? "" : head + tail;
        }

        private static String quote(String md) {
            StringBuilder sb = new StringBuilder();
            for (String line : md.split("\n", -1)) {
                sb.append(line.isBlank() ? ">" : "> " + line).append('\n');
            }
            return sb.toString().stripTrailing();
        }

        /** GFM 表格 */
        private String tableBlock(String inner) {
            List<List<String>> rows = new ArrayList<>();
            Matcher tr = Pattern.compile("(?is)<tr\\b[^>]*>").matcher(inner);
            int pos = 0;
            while (tr.find(pos)) {
                int close = findClose(inner, tr.end(), "tr");
                String rowHtml = inner.substring(tr.end(), close);
                List<String> cells = new ArrayList<>();
                Matcher c = Pattern.compile("(?is)<(t[dh])\\b[^>]*>").matcher(rowHtml);
                int cp = 0;
                while (c.find(cp)) {
                    int cc = findClose(rowHtml, c.end(), c.group(1).toLowerCase(Locale.ROOT));
                    String cell = inline(rowHtml.substring(c.end(), cc))
                            .replaceAll("\\s+", " ").trim().replace("|", "\\|");
                    cells.add(cell);
                    cp = afterTag(rowHtml, cc);
                }
                if (!cells.isEmpty()) rows.add(cells);
                pos = afterTag(inner, close);
            }
            if (rows.isEmpty()) return "";
            int cols = rows.stream().mapToInt(List::size).max().orElse(0);
            if (cols == 0) return "";
            StringBuilder sb = new StringBuilder();
            sb.append(row(rows.get(0), cols));
            sb.append('\n').append('|');
            for (int i = 0; i < cols; i++) sb.append(" --- |");
            for (int r = 1; r < rows.size(); r++) sb.append('\n').append(row(rows.get(r), cols));
            return sb.toString();
        }

        private static String row(List<String> cells, int cols) {
            StringBuilder sb = new StringBuilder("|");
            for (int i = 0; i < cols; i++) {
                sb.append(' ').append(i < cells.size() ? cells.get(i) : "").append(" |");
            }
            return sb.toString();
        }

        /** figure：图片 + 斜体图片说明 */
        private String figureBlock(String inner) {
            String img = null;
            String cap = null;
            Matcher i = Pattern.compile("(?is)<img\\b([^>]*)>").matcher(inner);
            if (i.find()) img = imgTag(i.group(1));
            Matcher c = Pattern.compile("(?is)<figcaption\\b[^>]*>(.*?)</figcaption\\s*>").matcher(inner);
            if (c.find()) cap = inline(c.group(1)).trim();
            if (img == null && (cap == null || cap.isBlank())) return blocks(inner).trim();
            StringBuilder sb = new StringBuilder();
            if (img != null && !img.isBlank()) sb.append(img);
            if (cap != null && !cap.isBlank()) {
                if (!sb.isEmpty()) sb.append("\n\n");
                sb.append('*').append(cap).append('*');
            }
            return sb.toString();
        }

        /* ---------------- 行内 ---------------- */

        /** 富媒体：原样保留（Markdown 允许内联 HTML），否则会被当未知标签剥掉 */
        private static final Pattern RICH = Pattern.compile(
                "(?is)<(iframe|video|audio|source|track|embed|object|param|canvas|map|area|svg)\\b[^>]*"
                        + "(?:/>|>.*?</\\1\\s*>)");

        private String inline(String chunk) {
            if (chunk == null || chunk.isEmpty()) return "";
            List<String> local = new ArrayList<>();
            String s = chunk.replaceAll("(?is)<!--.*?-->", "");

            // 富媒体先保护起来
            s = sub(s, RICH, Matcher::group, local);

            // 链接
            s = sub(s, Pattern.compile("(?is)<a\\b([^>]*)>(.*?)</a\\s*>"), m -> {
                String href = attr(m.group(1), "href");
                String text = inline(m.group(2)).trim();
                if (href == null || href.isBlank() || href.trim().toLowerCase(Locale.ROOT).startsWith("javascript:")) {
                    return text;
                }
                if (text.isEmpty()) text = mdUrl(href);
                return "[" + text + "](" + mdUrl(href) + ")";
            }, local);

            // 图片
            s = sub(s, Pattern.compile("(?is)<img\\b([^>]*?)/?>"), m -> imgTag(m.group(1)), local);

            // 预抽出的代码
            s = sub(s, Pattern.compile(PH_OPEN + "c(\\d+)" + PH_CLOSE), m -> {
                int idx = Integer.parseInt(m.group(1));
                return idx >= 0 && idx < codes.size() ? codes.get(idx) : "";
            }, local);

            // 换行
            s = sub(s, Pattern.compile("(?is)<br\\s*/?>"), m -> "  \n", local);

            // 成对行内样式
            if (preserve) {
                s = strongStyle(s, local);                 // 还原式：要额外修「外层 strong + 内层字面 **」
            } else {
                s = style(s, "strong|b", "**", local);
            }
            s = style(s, "em|i|cite|var", "*", local);
            s = style(s, "del|s|strike", "~~", local);

            s = s.replaceAll("(?is)<[^>]*>", "");     // 其余标签一律剥离
            s = normInline(s);                       // 实体解码 + 空白压缩
            s = esc(s);                              // Markdown 元字符转义
            return restore(s, local);
        }

        private String style(String s, String names, String mark, List<String> local) {
            Pattern p = Pattern.compile("(?is)<(" + names + ")\\b[^>]*>(.*?)</\\1\\s*>");
            return sub(s, p, m -> mark + inline(m.group(2)).trim() + mark, local);
        }

        /** 已经是 Markdown 粗体：<strong>**X**</strong> */
        private static final Pattern MD_BOLD = Pattern.compile("(?s)^\\*\\*.*\\*\\*$");
        /** 已经是 Markdown 标题：<strong>## X</strong> */
        private static final Pattern MD_ATX = Pattern.compile("(?s)^#{1,6}\\s.*$");

        /**
         * 还原式的 {@code <strong>} 处理。
         *
         * <p>编辑器把 {@code **X**} 转成了 {@code <strong>X</strong>}，但如果原文是
         * {@code # 标题} 或 {@code **X**} 本身，转换后就变成 {@code <strong># 标题</strong>} /
         * {@code <strong>**X**</strong>} —— 里面那层标记是「本来就对的 Markdown」，必须原样放行，
         * 不能再包一层 {@code **}（否则变成 {@code **\*\*X\*\***}，渲染出来还是字面星号）。
         */
        private String strongStyle(String s, List<String> local) {
            return sub(s, Pattern.compile("(?is)<(strong|b)\\b[^>]*>(.*?)</\\1\\s*>"), m -> {
                String inner = inline(m.group(2)).trim();
                if (inner.isEmpty()) return "";
                if (MD_BOLD.matcher(inner).matches() || MD_ATX.matcher(inner).matches()) return inner;
                return "**" + inner + "**";
            }, local);
        }

        private static String normInline(String t) {
            String s = decodeEntities(t).replace('\u00A0', ' ');
            s = s.replaceAll("[ \\t\\n\\u000b\\f]+", " ");
            return s.strip();
        }

        private String esc(String t) {
            if (preserve) {
                // 还原式：正文本身是 Markdown，星号/井号/方括号/反引号都是语法，不能转义；
                // 只挡一下 < > ，避免文本里的尖括号被当成内联 HTML。
                return t.replace("<", "\\<").replace(">", "\\>");
            }
            StringBuilder sb = new StringBuilder(t.length() + 8);
            for (int i = 0; i < t.length(); i++) {
                char c = t.charAt(i);
                switch (c) {
                    case '\\', '*', '_', '`', '[', ']', '<' -> sb.append('\\');
                    default -> { }
                }
                sb.append(c);
            }
            return sb.toString();
        }

        private String restore(String s, List<String> local) {
            Matcher m = PH.matcher(s);
            if (!m.find()) return s;
            StringBuilder sb = new StringBuilder();
            do {
                int idx = Integer.parseInt(m.group(1));
                String v = idx >= 0 && idx < local.size() ? local.get(idx) : "";
                m.appendReplacement(sb, Matcher.quoteReplacement(v));
            } while (m.find());
            m.appendTail(sb);
            return sb.toString();
        }

        /* ---------------- 还原式专用：抢救被编辑器弄坏的 Markdown ---------------- */

        /** 整行就是一个代码围栏（```lang）；语言可省略 */
        private static final Pattern FENCE_PLAIN = Pattern.compile("^`{3,}\\s*([A-Za-z0-9+#._-]*)$");
        /** wptexturize（智能标点）会把 ``` 变成 “`，这里一并认出来 */
        private static final Pattern FENCE_TEXTURIZED =
                Pattern.compile("^[\u201C\u201D\u2018\u2019]`{1,4}\\s*([A-Za-z0-9+#._-]*)$");
        /** 整段只有一个 <strong>/<b> 包裹 */
        private static final Pattern BOLD_ONLY =
                Pattern.compile("(?is)^\\s*<(strong|b)\\b[^>]*>(.*?)</\\1\\s*>\\s*$");

        /** 纯文本：解码实体、{@code <br>} 转换行、去掉标签；<b>不</b>压缩空白（代码行要留缩进） */
        private static String plainText(String h) {
            String t = h.replaceAll("(?is)<br\\s*/?>", "\n").replaceAll("(?is)<[^>]+>", "");
            return decodeEntities(t).replace('\u00A0', ' ');
        }

        private static String rstrip(String s) {
            int b = s.length();
            while (b > 0 && (s.charAt(b - 1) == ' ' || s.charAt(b - 1) == '\t')) b--;
            return s.substring(0, b);
        }

        /** 整行是不是围栏标记 → 返回语言（可能为空串）；不是则 null */
        private static String fenceLine(String t) {
            Matcher m = FENCE_PLAIN.matcher(t);
            if (!m.matches()) m = FENCE_TEXTURIZED.matcher(t);
            return m.matches() ? (m.group(1) == null ? "" : m.group(1)) : null;
        }

        /** 整段是 {@code <strong># 标题</strong>} 这种「行首 # 被吃成加粗」的写法 → 还原成 ATX 标题 */
        private String headingInBold(String raw) {
            Matcher m = BOLD_ONLY.matcher(raw);
            if (!m.matches()) return null;
            String plain = plainText(m.group(2)).trim();
            if (!MD_ATX.matcher(plain).matches()) return null;
            int level = 0;
            while (level < plain.length() && plain.charAt(level) == '#') level++;
            return "#".repeat(Math.min(level, 6)) + " " + plain.substring(level).trim();
        }

        /**
         * 还原被「智能标点」弄坏的 Markdown：{@code ———} 表格分隔行 → {@code ---}，
         * 行首的 {@code –}/{@code —} 列表符号 → {@code -}。（post_content 原文一般没这问题，
         * 但只拿得到 content.rendered 时就是这个样子。）
         */
        private static String repairLine(String t) {
            if (t.indexOf('\u2014') < 0 && t.indexOf('\u2013') < 0) return t;
            String s = t;
            if (s.matches("^\\s*\\|[-|:\\s\u2013\u2014]+\\|\\s*$")) {
                s = s.replace('\u2014', '-').replace('\u2013', '-');
            }
            s = s.replaceFirst("^(\\s*)[\u2013\u2014]([ \\t]+)(?=\\S)", "$1-$2");
            return s;
        }

        /* ---------------- 标签属性 / 工具 ---------------- */

        private String imgTag(String attrs) {
            // 懒加载优先：WP 主题普遍用 data-src 存真实地址、src 放占位图
            String src = firstNonBlank(attr(attrs, "data-src"), attr(attrs, "data-lazy-src"),
                    attr(attrs, "data-original"), attr(attrs, "src"));
            if (src == null || src.isBlank()) {
                // 部分主题把真实地址塞在 srcset 里
                String srcset = attr(attrs, "srcset");
                if (srcset != null && !srcset.isBlank()) src = srcset.split(",")[0].trim().split("\\s+")[0];
            }
            if (src == null || src.isBlank()) return "";
            String alt = attr(attrs, "alt");
            if (alt == null) alt = "";
            alt = esc(alt.replaceAll("\\s+", " ")).replace("!", "\\!");
            String title = attr(attrs, "title");
            StringBuilder sb = new StringBuilder("![").append(alt).append("](").append(mdUrl(src));
            if (title != null && !title.isBlank()) {
                sb.append(" \"").append(title.replace("\"", "\\\"")).append('"');
            }
            return sb.append(')').toString();
        }

        private static String firstNonBlank(String... vals) {
            for (String v : vals) {
                if (v != null && !v.isBlank()) return v;
            }
            return null;
        }

        private static String attr(String attrs, String name) {
            if (attrs == null || attrs.isEmpty()) return null;
            Matcher m = Pattern.compile("(?i)(?:^|[\\s\"'])" + Pattern.quote(name)
                    + "\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))").matcher(attrs);
            if (!m.find()) return null;
            String v = m.group(2) != null ? m.group(2) : (m.group(3) != null ? m.group(3) : m.group(4));
            return v == null ? null : decodeEntities(v).trim();
        }

        /** Markdown 链接地址：含空格/尖括号/括号时用尖括号包裹 */
        private static String mdUrl(String raw) {
            String u = (raw == null ? "" : raw).trim()
                    .replace("&#038;", "&").replace("&amp;", "&");
            if (u.isEmpty()) return "";
            if (u.matches(".*[\\s<>()\\\"].*")) {
                return "<" + u.replace("<", "%3C").replace(">", "%3E")
                        .replace("\"", "%22").replace(" ", "%20") + ">";
            }
            return u;
        }

        /** 找配对的结束标签起点；找不到返回字符串末尾 */
        private static int findClose(String s, int from, String name) {
            Matcher m = Pattern.compile("(?i)</?" + Pattern.quote(name) + "\\b[^>]*>").matcher(s);
            m.region(from, s.length());
            int depth = 1;
            while (m.find()) {
                String t = m.group();
                if (t.startsWith("</")) {
                    if (--depth == 0) return m.start();
                } else if (!t.endsWith("/>")) {
                    depth++;
                }
            }
            return s.length();
        }

        /** 标签（或标签起点位置）之后的第一个字符下标 */
        private static int afterTag(String s, int tagStart) {
            if (tagStart >= s.length()) return s.length();
            int i = s.indexOf('>', tagStart);
            return i < 0 ? s.length() : i + 1;
        }

        private static String sub(String s, Pattern p, Function<Matcher, String> f, List<String> local) {
            return replaceAll(s, p, m -> {
                String v = f.apply(m);
                local.add(v);
                return PH_OPEN + (local.size() - 1) + PH_CLOSE;
            });
        }

        /** 逐个匹配替换（替换值原样写入，不做二次占位） */
        private static String replaceAll(String s, Pattern p, Function<Matcher, String> f) {
            Matcher m = p.matcher(s);
            if (!m.find()) return s;
            StringBuilder sb = new StringBuilder(s.length() + 32);
            do {
                m.appendReplacement(sb, Matcher.quoteReplacement(f.apply(m)));
            } while (m.find());
            m.appendTail(sb);
            return sb.toString();
        }

        private static String tidy(String s) {
            String out = s.replaceAll("\\n{3,}", "\n\n");
            return trimBlankEdges(out);
        }

        private static String trimBlankEdges(String s) {
            int a = 0, b = s.length();
            while (a < b && Character.isWhitespace(s.charAt(a))) a++;
            while (b > a && Character.isWhitespace(s.charAt(b - 1))) b--;
            return s.substring(a, b);
        }

        /* ---------------- 实体解码 ---------------- */

        private static final Pattern ENT = Pattern.compile("&(#x?[0-9a-fA-F]+|[a-zA-Z][a-zA-Z0-9]{1,31});");

        static String decodeEntities(String s) {
            if (s == null || s.isEmpty() || s.indexOf('&') < 0) return s == null ? "" : s;
            Matcher m = ENT.matcher(s);
            StringBuilder sb = new StringBuilder(s.length());
            while (m.find()) {
                String e = m.group(1);
                String rep = entityText(e);
                m.appendReplacement(sb, Matcher.quoteReplacement(rep == null ? m.group() : rep));
            }
            m.appendTail(sb);
            return sb.toString();
        }

        private static String entityText(String e) {
            try {
                if (e.startsWith("#x") || e.startsWith("#X")) {
                    return new String(Character.toChars(Integer.parseInt(e.substring(2), 16)));
                }
                if (e.startsWith("#")) {
                    return new String(Character.toChars(Integer.parseInt(e.substring(1))));
                }
            } catch (Exception ignore) {
                return null;
            }
            return switch (e) {
                case "amp" -> "&";
                case "lt" -> "<";
                case "gt" -> ">";
                case "quot" -> "\"";
                case "apos" -> "'";
                case "nbsp" -> " ";
                case "hellip" -> "…";
                case "ldquo" -> "“";
                case "rdquo" -> "”";
                case "lsquo" -> "‘";
                case "rsquo" -> "’";
                case "mdash" -> "—";
                case "ndash" -> "–";
                case "middot" -> "·";
                case "bull" -> "•";
                case "copy" -> "©";
                case "reg" -> "®";
                case "trade" -> "™";
                case "times" -> "×";
                case "divide" -> "÷";
                case "deg" -> "°";
                case "laquo" -> "«";
                case "raquo" -> "»";
                case "permil" -> "‰";
                case "prime" -> "′";
                case "Prime" -> "″";
                case "minus" -> "−";
                case "sup2" -> "²";
                case "sup3" -> "³";
                case "frac12" -> "½";
                case "frac14" -> "¼";
                case "frac34" -> "¾";
                case "plusmn" -> "±";
                case "larr" -> "←";
                case "rarr" -> "→";
                case "uarr" -> "↑";
                case "darr" -> "↓";
                case "harr" -> "↔";
                case "ne" -> "≠";
                case "le" -> "≤";
                case "ge" -> "≥";
                default -> null;
            };
        }
    }
}
