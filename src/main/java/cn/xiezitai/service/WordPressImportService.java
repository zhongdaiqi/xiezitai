package cn.xiezitai.service;

import cn.xiezitai.entity.Article;
import cn.xiezitai.entity.FileEntity;
import cn.xiezitai.entity.WpSite;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.repository.WpSiteRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WordPress 文章导入：单篇同步导入 + 整站后台导入（带进度）。
 *
 * <p>媒体处理规则（需求 2.3）：
 * <ul>
 *   <li>正文/特色图里 <b>属于 WP 站点自身</b>（主机名与站点一致，忽略 www.）的图片与
 *       {@code /wp-content/uploads/} 附件 → 下载落盘到本站媒体库，URL 重写为 {@code /media/xxx}；</li>
 *   <li>外站资源（CDN、图床、第三方域名）→ 一律沿用外链，不动；</li>
 *   <li>单个文件下载/入库失败 → 不中断导入，保留外链并记一条警告。</li>
 * </ul>
 *
 * <p>整站导入在后台线程跑，进度存在内存 {@link #progress} 里，前端轮询
 * {@code GET /api/admin/wp/sites/{id}/progress} 展示（总数/已完成/成功/跳过/失败 + 当前标题 + 日志）。
 */
@Service
public class WordPressImportService {

    private static final Logger log = LoggerFactory.getLogger(WordPressImportService.class);

    private final WordPressClient client;
    private final ArticleRepository articles;
    private final WpSiteRepository sites;
    private final MediaStoreService media;
    private final ArticleService articleService;

    /** 每个站点一份导入进度；键 = wpSite.id。整站导入同一站点同一时间只允许一个任务 */
    private final Map<Long, WpSyncProgress> progress = new ConcurrentHashMap<>();

    /* ================= 正文格式策略 ================= */

    /** 自动识别：优先用 WP 原文（raw），拿到 HTML 就转 Markdown（默认，推荐） */
    public static final String MODE_AUTO = "auto";
    /** 强制转换：忽略 raw，始终把渲染结果 content.rendered 转成 Markdown */
    public static final String MODE_HTML2MD = "html2md";
    /** 保持原文：原样使用 WP 的 content.raw（拿不到就退回 rendered），只改媒体链接，不做转换 */
    public static final String MODE_RAW = "raw";

    /** 归一化前端传进来的正文格式；无法识别一律按 auto */
    public static String normalizeContentMode(Object mode) {
        if (mode == null) return MODE_AUTO;
        String m = String.valueOf(mode).trim().toLowerCase(java.util.Locale.ROOT);
        return switch (m) {
            case "html2md", "markdown", "md", "convert" -> MODE_HTML2MD;
            case "raw", "none", "off", "original" -> MODE_RAW;
            default -> MODE_AUTO;
        };
    }

    /** 给进度日志用的人类可读说明 */
    private static String contentModeText(String mode) {
        return switch (normalizeContentMode(mode)) {
            case MODE_HTML2MD -> "始终把 WP 渲染结果转成 Markdown";
            case MODE_RAW -> "原样保留 WP 正文原文";
            default -> "自动识别（优先原文，HTML 自动转 Markdown）";
        };
    }

    public WordPressImportService(WordPressClient client, ArticleRepository articles,
                                  WpSiteRepository sites, MediaStoreService media,
                                  ArticleService articleService) {
        this.client = client;
        this.articles = articles;
        this.sites = sites;
        this.media = media;
        this.articleService = articleService;
    }

    /* ================= 进度模型 ================= */

    public static class WpSyncProgress {
        public volatile String phase = "RUNNING";        // RUNNING / DONE / FAILED
        public volatile long total = -1;                 // 站点文章总数（列表页拿到后回填）
        public volatile int done, imported, updated, skipped, failed;
        public volatile boolean useWpDate = true;        // 发布时间策略：true=用 WP 原发布时间，false=用当前时间
        public volatile String onConflict = "skip";      // slug 冲突策略：skip=跳过，update=用 WP 版本覆盖
        public volatile String contentMode = MODE_AUTO;  // 正文格式：auto / html2md / raw
        public volatile String current = "";             // 正在导入的标题
        public volatile String error;
        public final LocalDateTime startedAt = LocalDateTime.now();
        public volatile LocalDateTime finishedAt;
        public final List<String> messages = new ArrayList<>();

        public synchronized void msg(String m) {
            if (messages.size() >= 60) messages.remove(0);
            messages.add(m);
        }
    }

    public WpSyncProgress progressOf(Long siteId) {
        return progress.get(siteId);
    }

    /* ================= 单篇导入（同步，给 controller 直接调） ================= */

    /**
     * 导入一篇文章。
     *
     * @param useWpDate  true=发布时间用 WP 原发布时间；false=用当前时间
     * @param onConflict 本地已有同 slug 文章时：skip=跳过；update=用 WP 版本覆盖更新（保留文章 id）
     * @param contentMode 正文格式：auto=自动识别（优先原文，HTML 自动转 Markdown）/ html2md / raw
     * @return result：{imported:true, articleId, slug, warnings:[...]} 或 {imported:false, reason:...}
     */
    public Map<String, Object> importSingle(WpSite site, long wpPostId, boolean useWpDate,
                                            String onConflict, String contentMode) throws Exception {
        JsonNode post = client.fetchPost(site, wpPostId);
        List<String> warnings = new ArrayList<>();
        String mode = normalizeContentMode(contentMode);
        String slug = normalizeWpSlug(post.path("slug").asText(""));
        String title = WordPressClient.unescapeEntities(post.path("title").path("rendered").asText(""));
        if (title.isBlank()) title = "(无标题)";

        Article existing = slug.isBlank() ? null : articles.findBySlug(slug).orElse(null);
        if (existing != null) {
            if (!"update".equals(onConflict)) {
                return Map.of("imported", false, "reason", "exists",
                        "message", "本地已有同名 slug 的文章《" + existing.getTitle() + "》，已跳过（可在导入选项里改为「更新」覆盖）",
                        "articleId", existing.getId());
            }
            updateArticleFields(site, existing, post, warnings, useWpDate, mode);
            Article saved = articles.save(existing);
            site.setLastSyncAt(LocalDateTime.now());
            sites.save(site);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("imported", true);
            out.put("updated", true);
            out.put("articleId", saved.getId());
            out.put("slug", saved.getSlug());
            out.put("title", saved.getTitle());
            out.put("tags", saved.getTags() == null ? "" : saved.getTags());
            out.put("warnings", warnings);
            return out;
        }

        Article a = buildArticle(site, post, warnings, useWpDate, mode);
        if (a.getSlug().isBlank()) a.setSlug(articleService.uniqueSlug(title));
        Article saved = articles.save(a);
        site.setLastSyncAt(LocalDateTime.now());
        sites.save(site);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported", true);
        out.put("articleId", saved.getId());
        out.put("slug", saved.getSlug());
        out.put("title", saved.getTitle());
        out.put("tags", saved.getTags() == null ? "" : saved.getTags());
        out.put("warnings", warnings);
        return out;
    }

    /* ================= 整站导入（后台线程 + 轮询进度） ================= */

    /** 启动整站导入；同一站点已有任务在跑时返回 null（controller 回 409） */
    public WpSyncProgress startFullImport(WpSite site, boolean useWpDate, String onConflict, String contentMode) {
        WpSyncProgress old = progress.get(site.getId());
        if (old != null && "RUNNING".equals(old.phase)) return null;
        WpSyncProgress p = new WpSyncProgress();
        p.useWpDate = useWpDate;
        p.onConflict = "update".equals(onConflict) ? "update" : "skip";
        p.contentMode = normalizeContentMode(contentMode);
        progress.put(site.getId(), p);
        Thread t = new Thread(() -> runFullImport(site, p), "wp-import-" + site.getId());
        t.setDaemon(true);
        t.start();
        return p;
    }

    private void runFullImport(WpSite site, WpSyncProgress p) {
        try {
            p.msg("发布时间策略：" + (p.useWpDate ? "沿用 WP 原发布时间" : "使用当前时间"));
            p.msg("同 slug 冲突策略：" + ("update".equals(p.onConflict) ? "用 WP 版本覆盖更新" : "跳过"));
            p.msg("正文格式：" + contentModeText(p.contentMode));
            int page = 1;
            while (true) {
                WordPressClient.PostsPage pp = client.listPosts(site, page, 100, null);
                if (p.total < 0) p.total = pp.total();
                if (pp.posts().isEmpty()) break;
                for (Map<String, Object> meta : pp.posts()) {
                    long wpId = ((Number) meta.get("id")).longValue();
                    String title = String.valueOf(meta.get("title"));
                    p.current = title;
                    try {
                        JsonNode post = client.fetchPost(site, wpId);
                        String slug = normalizeWpSlug(post.path("slug").asText(""));
                        Article existing = slug.isBlank() ? null : articles.findBySlug(slug).orElse(null);
                        if (existing != null && !"update".equals(p.onConflict)) {
                            p.skipped++;
                            p.done++;
                            p.msg("跳过《" + title + "》：本地已存在同 slug 文章");
                            continue;
                        }
                        List<String> warnings = new ArrayList<>();
                        if (existing != null) {
                            updateArticleFields(site, existing, post, warnings, p.useWpDate, p.contentMode);
                            articles.save(existing);
                            p.updated++;
                            p.done++;
                            p.msg("已更新《" + title + "》" + (warnings.isEmpty() ? "" : "（" + warnings.size() + " 条媒体警告）"));
                        } else {
                            Article a = buildArticle(site, post, warnings, p.useWpDate, p.contentMode);
                            if (a.getSlug().isBlank()) a.setSlug(articleService.uniqueSlug(title));
                            articles.save(a);
                            p.imported++;
                            p.done++;
                            p.msg("已导入《" + title + "》" + (warnings.isEmpty() ? "" : "（" + warnings.size() + " 条媒体警告）"));
                        }
                        warnings.forEach(w -> p.msg("  ⚠ " + w));
                    } catch (Exception e) {
                        p.failed++;
                        p.done++;
                        p.msg("失败《" + title + "》：" + e.getMessage());
                        log.warn("WP 整站导入单篇失败 id={} : {}", wpId, e.getMessage());
                    }
                }
                if (page >= pp.totalPages()) break;
                page++;
            }
            site.setLastSyncAt(LocalDateTime.now());
            sites.save(site);
            p.phase = "DONE";
            p.msg("整站导入完成：成功 " + p.imported + "，更新 " + p.updated + "，跳过 " + p.skipped + "，失败 " + p.failed);
        } catch (Exception e) {
            p.phase = "FAILED";
            p.error = e.getMessage();
            p.msg("整站导入中断：" + e.getMessage());
            log.warn("WP 整站导入中断 site={}: {}", site.getId(), e.getMessage());
        } finally {
            p.finishedAt = LocalDateTime.now();
            p.current = "";
        }
    }

    /* ================= 组装文章 ================= */

    /**
     * WP post JSON → 本站 Article（不落库）。warnings 里收集「该落盘但没成功」的媒体警告。
     *
     * @param useWpDate true=发布时间沿用 WP 的 date；false=用当前时间
     * @param contentMode 正文格式（auto / html2md / raw）
     */
    Article buildArticle(WpSite site, JsonNode post, List<String> warnings, boolean useWpDate,
                         String contentMode) throws Exception {
        Article a = new Article();
        applyPostBody(site, a, post, warnings, useWpDate, contentMode);
        a.setSlug(normalizeWpSlug(post.path("slug").asText("")));
        return a;
    }

    /**
     * WP 对中文标题生成的 slug 是 {@code %e4%bd%a0...} 形态的百分号编码字面串。
     * 原样入库的话，链接里的 {@code %} 再编码成 {@code %25} 会被 Spring Security 的
     * StrictHttpFirewall 拦成 400，文章永远打不开。这里解码回真实字符（WP 内部本来就是中文），
     * 得到正常的中英混合 slug，前台路由/SEO/分享都干净。
     */
    public static String normalizeWpSlug(String slug) {
        if (slug == null || slug.isBlank() || !slug.contains("%")) return slug;
        try {
            String dec = java.net.URLDecoder.decode(slug, java.nio.charset.StandardCharsets.UTF_8);
            if (!dec.equals(slug) && !dec.contains("%")
                    && dec.matches("[\\p{L}\\p{N}\\-_]+")) {
                return dec;
            }
        } catch (Exception ignore) { /* 非法 % 序列，保持原样 */ }
        return slug;
    }

    /**
     * 更新已有文章：保留 id / slug / 浏览数 / 评论，正文相关字段与发布人以 WP 站点版本为准。
     */
    private void updateArticleFields(WpSite site, Article target, JsonNode post,
                                     List<String> warnings, boolean useWpDate, String contentMode) throws Exception {
        applyPostBody(site, target, post, warnings, useWpDate, contentMode);
    }

    /** 把 WP post 的内容字段落到文章实体（title/正文/摘要/封面/状态与发布时间/标签/发布人） */
    private void applyPostBody(WpSite site, Article a, JsonNode post,
                               List<String> warnings, boolean useWpDate, String contentMode) throws Exception {
        // 发布人 = 关联站点时填写的 WP 用户名（如 zhongdaiqi），而不是固定写死。
        // 放在 applyPostBody 里：新建导入与「更新」模式重导都会归一，旧文章（author=wordpress）可自愈
        a.setAuthor(site.getUsername() == null || site.getUsername().isBlank()
                ? "wordpress" : site.getUsername().trim());
        a.setTitle(WordPressClient.unescapeEntities(post.path("title").path("rendered").asText("(无标题)")));

        a.setContent(resolveBody(site, post, contentMode, warnings));

        String excerpt = WordPressClient.stripTags(post.path("excerpt").path("rendered").asText(""));
        if (excerpt.length() > 1000) excerpt = excerpt.substring(0, 1000);
        a.setSummary(excerpt);

        // 特色图：站点自身的图落盘换 /media/，外站图直接用外链
        String featured = featuredImageUrl(post);
        if (featured != null && !featured.isBlank()) {
            String local = downloadToLocal(site, featured, "featured", warnings);
            a.setCover(local != null ? local : featured);
        }

        // 状态：publish → 已发布；其余（草稿/待审/私密）→ 草稿。
        // 发布时间由用户选择：沿用 WP 原发布时间，或使用导入当时的当前时间
        String wpStatus = post.path("status").asText("publish");
        if ("publish".equals(wpStatus)) {
            a.setStatus("PUBLISHED");
            if (useWpDate) {
                String date = post.path("date_gmt").asText("");
                if (date.isBlank()) date = post.path("date").asText("");
                if (!date.isBlank()) {
                    try { a.setPublishedAt(LocalDateTime.parse(date.substring(0, 19))); } catch (Exception ignore) { }
                }
            } else {
                a.setPublishedAt(LocalDateTime.now());
            }
        } else {
            a.setStatus("DRAFT");
        }

        // 分类 + 标签合并成本站标签（沿用「最多 10 个」规则，超量截断）。
        // 优先用 _embed 返回的 _embedded.wp:term；缺失时按 tags/categories 的 id 数组显式查名称兜底。
        Set<String> tagNames = new LinkedHashSet<>();
        JsonNode termGroups = post.path("_embedded").path("wp:term");
        if (termGroups.isArray()) {
            for (JsonNode group : termGroups) {
                if (!group.isArray()) continue;
                for (JsonNode term : group) {
                    String name = WordPressClient.unescapeEntities(term.path("name").asText(""));
                    if (!name.isBlank()) tagNames.add(name);
                }
            }
        }
        if (tagNames.isEmpty()) {
            fetchTermNamesInto(site, post, "categories", tagNames);
            fetchTermNamesInto(site, post, "tags", tagNames);
        }
        if (!tagNames.isEmpty()) {
            List<String> tags = new ArrayList<>(tagNames);
            if (tags.size() > Article.MAX_TAGS) tags = tags.subList(0, Article.MAX_TAGS);
            a.setTags(String.join(",", tags));
        }
    }

    /**
     * 解析出「Markdown 正文」。
     *
     * <p>装上 Markdown 类插件的 WP 站点，REST 的 {@code content.rendered} 是插件把 Markdown
     * 渲染之后的 HTML；直接入库会让本站把 HTML 源码当 Markdown 文本展示，文章看起来是坏的。
     * 这里按用户选择的策略取正文：
     * <ul>
     *   <li><b>auto</b>（默认）：优先用 {@code content.raw}（编辑器里真正的 Markdown 原文，
     *       需要 context=edit 与编辑权限）；raw 不可用、或 raw 本身就是 HTML（老式 HTML 编辑器）时，
     *       用 {@code content.rendered} 并自动转成 Markdown；</li>
     *   <li><b>html2md</b>：忽略 raw，始终把渲染结果转成 Markdown；</li>
     *   <li><b>raw</b>：原样使用 WP 原文（拿不到就退回渲染结果），只做媒体链接本地化，不转换。</li>
     * </ul>
     *
     * <p>注意处理顺序：<b>先</b>做媒体本地化（HTML 属性替换），<b>再</b>转 Markdown ——
     * 这样转换出来的图片链接直接就是本站 {@code /media/xxx}，不会漏改。
     */
    String resolveBody(WpSite site, JsonNode post, String contentMode, List<String> warnings) {
        String raw = post.path("content").path("raw").asText("");
        String rendered = post.path("content").path("rendered").asText("");
        String mode = normalizeContentMode(contentMode);

        String source;
        boolean isHtml;
        if (MODE_HTML2MD.equals(mode)) {
            source = rendered;
            isHtml = true;
        } else if (MODE_RAW.equals(mode)) {
            source = raw.isBlank() ? rendered : raw;
            isHtml = false;
        } else {                                          // auto
            if (!raw.isBlank()) {
                source = raw;
                isHtml = HtmlToMarkdown.looksLikeHtml(raw);
            } else {
                source = rendered;
                isHtml = true;
            }
        }

        if (source.isBlank()) return "";
        if (isHtml) {
            String localized = localizeMedia(site, source, warnings);
            // 有的站点是「Markdown 粘进古腾堡」的正文：HTML 只是外壳，里面全是字面 Markdown。
            // 这种情况必须走还原式转换，否则会把本来就对的 Markdown 语法一起转义掉。
            String md = HtmlToMarkdown.looksLikeMarkdownInHtml(localized)
                    ? HtmlToMarkdown.convertMarkdownWrapped(localized)
                    : HtmlToMarkdown.convert(localized);
            // 还原式转换会把「本来就写在 Markdown 里的图片/附件链接」原样带出来，
            // 这里再过一遍 Markdown 媒体本地化：指向本站资源的照样落盘，已本地化的 /media/ 会自动跳过。
            return localizeMediaMarkdown(site, md, warnings);
        }
        return localizeMediaMarkdown(site, source, warnings);
    }

    /**
     * 标签/分类名兜底：WP 返回里没带 _embedded 时，按 post 的 {taxonomy} id 数组
     * 调 /wp/v2/{taxonomy}?include=… 批量换名称，塞进 tagNames。
     */
    private void fetchTermNamesInto(WpSite site, JsonNode post, String taxonomy, Set<String> tagNames) {
        JsonNode idsNode = post.path(taxonomy);
        if (!idsNode.isArray() || idsNode.isEmpty()) return;
        StringBuilder inc = new StringBuilder();
        for (JsonNode n : idsNode) inc.append(n.asLong()).append(',');
        String query = "/wp-json/wp/v2/" + taxonomy + "?include=" + inc.substring(0, inc.length() - 1)
                + "&per_page=100&_fields=id,name";
        try {
            JsonNode terms = client.getJson(site, query);
            if (terms.isArray()) {
                for (JsonNode t : terms) {
                    String name = WordPressClient.unescapeEntities(t.path("name").asText(""));
                    if (!name.isBlank()) tagNames.add(name);
                }
            }
        } catch (Exception e) {
            log.warn("WP 标签/分类名称获取失败（{}，不影响文章导入）: {}", taxonomy, e.getMessage());
        }
    }

    private String featuredImageUrl(JsonNode post) {
        JsonNode media = post.path("_embedded").path("wp:featuredmedia");
        if (media.isArray() && !media.isEmpty()) {
            String src = media.get(0).path("source_url").asText("");
            if (!src.isBlank()) return src;
        }
        return null;
    }

    /* ================= 媒体本地化 ================= */

    private static final Pattern ATTR_URL = Pattern.compile(
            "(?i)\\b(src|poster|href|srcset)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");

    /** 判断 URL 与站点是否同一主机（忽略大小写与 www. 前缀） */
    public static boolean sameHost(String url, String siteUrl) {
        String h1 = hostOf(url), h2 = hostOf(siteUrl);
        return !h1.isEmpty() && h1.equals(h2);
    }

    static String hostOf(String url) {
        try {
            String host = java.net.URI.create(url.trim()).getHost();
            if (host == null) return "";
            host = host.toLowerCase();
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "";
        }
    }

    private static String decodeHtmlInUrl(String raw) {
        return raw.replace("&#038;", "&").replace("&amp;", "&").replace("&#8211;", "-");
    }

    /** URL 末段当原始文件名（只留安全字符） */
    private static String fileNameOf(String url) {
        try {
            String path = java.net.URI.create(url.trim()).getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            name = java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8);
            name = name.replaceAll("[^\\w.\\-\\u4e00-\\u9fa5]", "_");
            return name.isBlank() ? "wp-file" : name;
        } catch (Exception e) {
            return "wp-file";
        }
    }

    /**
     * 把正文里 WP 站点自身的媒体下载落盘并重写 URL；外站资源原样保留。
     * 返回处理后的 HTML。
     */
    String localizeMedia(WpSite site, String html, List<String> warnings) {
        if (html == null || html.isBlank()) return html;
        // raw(HTML 里的原文) -> 本地 /media/xxx；一次收集，统一替换（src/srcset/href 里的同一 URL 只下一次）
        Map<String, String> rewrite = new LinkedHashMap<>();
        Matcher m = ATTR_URL.matcher(html);
        while (m.find()) {
            String attr = m.group(1).toLowerCase();
            String raw = m.group(3) != null ? m.group(3) : m.group(4);
            if (raw == null || raw.isBlank()) continue;
            // srcset 一个值里可能有多个「url 尺寸」，逐个看
            for (String part : raw.split(",")) {
                String candidate = part.trim().split("\\s+")[0];
                if (candidate.isEmpty() || rewrite.containsKey(candidate)) continue;
                String kind = attr.equals("href") ? "href" : attr;
                if (!shouldLocalize(site, candidate, kind)) continue;   // 外站/内链 → 沿用原样
                String local = downloadToLocal(site, candidate, attr, warnings);
                if (local != null) rewrite.put(candidate, local);
            }
        }
        for (Map.Entry<String, String> e : rewrite.entrySet()) {
            html = html.replace(e.getKey(), e.getValue());
        }
        return html;
    }

    /** Markdown 正文里的链接/图片（{@code ![alt](url)}、{@code [text](url)}）以及混写的裸 HTML 标签 */
    private static final Pattern MD_LINK = Pattern.compile(
            "(!?)\\[([^\\]]*)\\]\\(\\s*<?([^)\\s>]+)>?\\s*(?:\"[^\"]*\"\\s*)?\\)");

    /**
     * 媒体本地化（Markdown 版）：正文本来就是 Markdown 时，把 WP 站点自身的图片下载落盘，
     * 链接改写成 {@code /media/xxx}；外站资源保持原外链。
     */
    String localizeMediaMarkdown(WpSite site, String md, List<String> warnings) {
        if (md == null || md.isBlank()) return md;
        // 先过一遍 HTML 规则：Markdown 里常混着 <img src="...">、<a href="...">
        String out = localizeMedia(site, md, warnings);
        Map<String, String> rewrite = new LinkedHashMap<>();
        Matcher m = MD_LINK.matcher(out);
        while (m.find()) {
            String bang = m.group(1);
            String url = decodeHtmlInUrl(m.group(3));
            if (url.isBlank() || rewrite.containsKey(url)) continue;
            String kind = "!".equals(bang) ? "img" : "href";
            if (!shouldLocalize(site, url, kind)) continue;
            String local = downloadToLocal(site, url, kind, warnings);
            if (local != null) rewrite.put(url, local);
        }
        for (Map.Entry<String, String> e : rewrite.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }

    /**
     * 这个 URL 该不该落盘：必须是 WP 站点自身的资源（同主机，忽略 www.）；
     * 相对路径 / 锚点 / data: / mailto: 跳过；普通链接（href）只认 /wp-content/uploads/ 下的附件，
     * 站内页面互链不下载。
     */
    private boolean shouldLocalize(WpSite site, String url, String kind) {
        if (url == null) return false;
        String u = decodeHtmlInUrl(url.trim());
        if (u.isEmpty() || u.startsWith("/") || u.startsWith("#")
                || u.startsWith("data:") || u.startsWith("mailto:")) return false;
        if (!sameHost(u, site.getUrl())) return false;          // 外站 → 沿用外链
        if ("href".equals(kind) && !u.contains("/wp-content/uploads/")) return false;
        return true;
    }

    /**
     * 下载一个站点自身的媒体文件并落盘到本站媒体库。
     *
     * @return 本地 URL（/media/xxx）；失败返回 null（调用方保留外链 + 记警告）
     */
    private String downloadToLocal(WpSite site, String url, String kind, List<String> warnings) {
        byte[] data = client.downloadQuietly(site, url);
        if (data == null) {
            warnings.add(kind + " 媒体下载失败，保留外链：" + url);
            return null;
        }
        String name = fileNameOf(url);
        try {
            FileEntity fe = media.storeImage(data, name, "wordpress");
            return "/media/" + fe.getStoredName();
        } catch (MediaStoreService.NotAnImageException e) {
            // 不是图片（PDF/zip/mp3…）：按普通附件入库
            try {
                FileEntity fe = media.store(data, name, "application/octet-stream", "wordpress");
                return "/media/" + fe.getStoredName();
            } catch (Exception e2) {
                warnings.add(kind + " 附件入库失败，保留外链：" + url + "（" + e2.getMessage() + "）");
                return null;
            }
        } catch (Exception e) {
            warnings.add(kind + " 媒体入库失败，保留外链：" + url + "（" + e.getMessage() + "）");
            return null;
        }
    }
}
