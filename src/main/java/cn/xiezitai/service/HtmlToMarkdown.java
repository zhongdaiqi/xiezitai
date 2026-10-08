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
 * <p><b>转义策略：</b>文本节点里的 Markdown 元字符（反斜杠、星号、下划线、反引号、方括号、
 * 尖括号）会加反斜杠转义 —— 优先保证「渲染出来和 WordPress 一致」，而不是源码好看。
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

    /** HTML → Markdown；入参为空时返回空串 */
    public static String convert(String html) {
        if (html == null || html.isBlank()) return "";
        return new Conv().run(html);
    }

    /* ==================================================================== */

    private static final class Conv {

        /** 预抽出的「原样内容」：代码块、行内代码 */
        private final List<String> codes = new ArrayList<>();

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
            return tidy(blocks(s));
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
                        String body = inline(s.substring(after, close)).trim();
                        if (!body.isEmpty()) out.append("\n\n").append(body).append("\n\n");
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
            s = style(s, "strong|b", "**", local);
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

        private static String normInline(String t) {
            String s = decodeEntities(t).replace('\u00A0', ' ');
            s = s.replaceAll("[ \\t\\n\\u000b\\f]+", " ");
            return s.strip();
        }

        private static String esc(String t) {
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
