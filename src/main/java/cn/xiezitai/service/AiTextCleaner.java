package cn.xiezitai.service;

import java.util.LinkedHashSet;

/**
 * 大模型返回文本的规范化工具。
 *
 * <p>为什么需要它：模型（尤其是小参数模型）经常不守格式 —— 标题被包上引号、关键词写成
 * 「关键词：1. xxx、2. yyy」这样的列表、SEO 描述前面先来一句「描述：」。这些原文直接写进
 * 表单字段就是脏数据，所以统一在这里洗成可直接落库的纯文本。
 *
 * <p>另一个关键点：{@link AiService#chat} 在「未配置大模型」或「调用失败」时返回的是
 * 「（AI 功能未启用…）」这类**友好提示文案**。它必须原样透出去让前端当消息显示，绝不能被
 * 当成结果写进标题/关键词字段 —— 否则用户点一下「AI 优化标题」就把自己的标题覆盖成一句报错。
 * {@link #isUnavailable(String)} 就是给调用方做这个判断用的。
 */
public final class AiTextCleaner {

    /** {@link AiService#chat} 未配置 / 调用失败时的提示前缀 */
    private static final String UNAVAILABLE_PREFIX = "（AI ";

    private static final int MAX_TITLE = 80;        // 表单与 articles.title(200) 都放得下
    private static final int MAX_DESC = 300;        // articles.seo_description(500)
    private static final int MAX_KEYWORDS = 8;      // 关键词最多保留 8 个
    private static final int MAX_KEYWORD_LEN = 20;  // 更长的多半是整句而不是关键词

    private AiTextCleaner() {}

    /** 是否是「AI 不可用 / 调用失败」的提示文案（调用方应把它当消息展示，而不是当结果写进字段） */
    public static boolean isUnavailable(String raw) {
        return raw != null && raw.startsWith(UNAVAILABLE_PREFIX);
    }

    /** 优化标题：去引号、序号、「优化后的标题：」这类标签，压平空白并限长；洗不出东西就回退原标题 */
    public static String title(String raw, String fallback) {
        String fb = fallback == null ? "" : fallback;
        if (raw == null || raw.isBlank()) return fb;
        if (isUnavailable(raw)) return raw;
        // markdown 强调符（**粗体**）在标题里没有意义，先整体去掉
        String s = firstLine(raw).replaceAll("[*`]+", "").trim();
        // 引号与标签可能互相包裹（例如 "优化后的标题：xxx"），所以洗两遍
        for (int i = 0; i < 2; i++) {
            s = stripLeadingDecor(s);
            s = stripQuotes(s);
        }
        s = squeeze(s);
        if (s.length() > MAX_TITLE) s = s.substring(0, MAX_TITLE).trim();
        return s.isBlank() ? fb : s;
    }

    /** SEO 关键词：把「关键词：1. A、2. B / C」这类列表洗成「A, B, C」，去重并限制个数 */
    public static String keywords(String raw) {
        if (raw == null || raw.isBlank()) return "";
        if (isUnavailable(raw)) return raw;
        String s = raw.replaceAll("[\\r\\n]+", ",");                       // 换行同样是分隔符
        s = s.replaceAll("^(?:AI\\s*)?(?:SEO\\s*)?关键词\\s*[:：]\\s*", "");  // 去掉「关键词：」标签
        s = s.replaceAll("(?i)^(?:seo\\s*)?keywords?\\s*[:：]?\\s*", "");
        s = s.replaceAll("[`*#]", " ");
        s = s.replaceAll("[，、,;;；｜|/／·•・]+", ",");                     // 各种分隔符统一成英文逗号
        LinkedHashSet<String> uniq = new LinkedHashSet<>();
        for (String part : s.split(",")) {
            String k = stripLeadingDecor(part);
            k = k.replaceAll("^[「『\"'“”]+", "").replaceAll("[」』\"'“”]+$", "");
            k = squeeze(k);
            if (k.isBlank() || k.length() > MAX_KEYWORD_LEN) continue;      // 空项与整句都不要
            uniq.add(k);
            if (uniq.size() >= MAX_KEYWORDS) break;
        }
        return String.join(", ", uniq);
    }

    /** SEO 描述：去「描述：」标签与包裹引号，压平换行与空白，限长 */
    public static String seoDescription(String raw) {
        if (raw == null || raw.isBlank()) return "";
        if (isUnavailable(raw)) return raw;
        String s = raw.replaceAll("[\\r\\n]+", " ").trim();
        s = s.replaceAll("^(?:AI\\s*)?(?:SEO\\s*)?(?:元描述|描述|摘要|简介)\\s*[:：]\\s*", "");
        s = s.replaceAll("(?i)^meta\\s*description\\s*[:：]\\s*", "");
        s = s.replaceAll("[`*#]", "");
        s = squeeze(stripQuotes(s));
        if (s.length() > MAX_DESC) s = s.substring(0, MAX_DESC).trim();
        return s;
    }

    /* ---------------- 内部工具 ---------------- */

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    /** 压掉换行、制表与连续空格 */
    private static String squeeze(String s) {
        return s == null ? "" : s.replaceAll("\\s+", " ").trim();
    }

    /** 去掉行首的 markdown 装饰、列表序号与「标题：」这类标签 */
    private static String stripLeadingDecor(String s) {
        String out = s == null ? "" : s;
        out = out.replaceAll("^(?:[#>*\\-•・]|\\s)+", "");
        out = out.replaceAll("^(?:AI\\s*)?(?:优化后的|优化后|新|新的|推荐)?\\s*(?:文章)?(?:标题|题目|Title)\\s*[:：]\\s*", "");
        out = out.replaceAll("^(?:[0-9]+|[一二三四五六七八九十]+)\\s*[.、)）]\\s*", "");
        out = out.replaceAll("^(?:[#>*\\-•・]|\\s)+", "");
        return out.trim();
    }

    /** 去掉成对包裹的引号 / 书名号 / 星号（可嵌套，循环剥离） */
    private static String stripQuotes(String s) {
        String out = s == null ? "" : s.trim();
        while (out.length() >= 2) {
            char a = out.charAt(0), b = out.charAt(out.length() - 1);
            boolean pair = (a == '"' && b == '"') || (a == '\'' && b == '\'')
                    || (a == '“' && b == '”') || (a == '‘' && b == '’')
                    || (a == '「' && b == '」') || (a == '『' && b == '』')
                    || (a == '《' && b == '》') || (a == '<' && b == '>')
                    || (a == '*' && b == '*') || (a == '`' && b == '`');
            if (!pair) break;
            out = out.substring(1, out.length() - 1).trim();
        }
        return out;
    }
}
