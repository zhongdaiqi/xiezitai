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
        public volatile int done, imported, skipped, failed;
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
     * @return result：{imported:true, articleId, slug, warnings:[...]} 或 {imported:false, reason:...}
     */
    public Map<String, Object> importSingle(WpSite site, long wpPostId) throws Exception {
        JsonNode post = client.fetchPost(site, wpPostId);
        List<String> warnings = new ArrayList<>();
        String slug = post.path("slug").asText("");
        String title = WordPressClient.unescapeEntities(post.path("title").path("rendered").asText(""));
        if (title.isBlank()) title = "(无标题)";

        Article existing = slug.isBlank() ? null : articles.findBySlug(slug).orElse(null);
        if (existing != null) {
            return Map.of("imported", false, "reason", "exists",
                    "message", "本地已有同名 slug 的文章《" + existing.getTitle() + "》，已跳过（如需覆盖请先删除或改名）",
                    "articleId", existing.getId());
        }

        Article a = buildArticle(site, post, warnings);
        if (a.getSlug().isBlank()) a.setSlug(articleService.uniqueSlug(title));
        Article saved = articles.save(a);
        site.setLastSyncAt(LocalDateTime.now());
        sites.save(site);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported", true);
        out.put("articleId", saved.getId());
        out.put("slug", saved.getSlug());
        out.put("title", saved.getTitle());
        out.put("warnings", warnings);
        return out;
    }

    /* ================= 整站导入（后台线程 + 轮询进度） ================= */

    /** 启动整站导入；同一站点已有任务在跑时返回 null（controller 回 409） */
    public WpSyncProgress startFullImport(WpSite site) {
        WpSyncProgress old = progress.get(site.getId());
        if (old != null && "RUNNING".equals(old.phase)) return null;
        WpSyncProgress p = new WpSyncProgress();
        progress.put(site.getId(), p);
        Thread t = new Thread(() -> runFullImport(site, p), "wp-import-" + site.getId());
        t.setDaemon(true);
        t.start();
        return p;
    }

    private void runFullImport(WpSite site, WpSyncProgress p) {
        try {
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
                        String slug = post.path("slug").asText("");
                        if (!slug.isBlank() && articles.findBySlug(slug).isPresent()) {
                            p.skipped++;
                            p.done++;
                            p.msg("跳过《" + title + "》：本地已存在同 slug 文章");
                            continue;
                        }
                        List<String> warnings = new ArrayList<>();
                        Article a = buildArticle(site, post, warnings);
                        if (a.getSlug().isBlank()) a.setSlug(articleService.uniqueSlug(title));
                        articles.save(a);
                        p.imported++;
                        p.done++;
                        p.msg("已导入《" + title + "》" + (warnings.isEmpty() ? "" : "（" + warnings.size() + " 条媒体警告）"));
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
            p.msg("整站导入完成：成功 " + p.imported + "，跳过 " + p.skipped + "，失败 " + p.failed);
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
     */
    Article buildArticle(WpSite site, JsonNode post, List<String> warnings) throws Exception {
        Article a = new Article();
        a.setTitle(WordPressClient.unescapeEntities(post.path("title").path("rendered").asText("(无标题)")));
        a.setSlug(post.path("slug").asText(""));

        String html = post.path("content").path("rendered").asText("");
        html = localizeMedia(site, html, warnings);
        a.setContent(html);

        String excerpt = WordPressClient.stripTags(post.path("excerpt").path("rendered").asText(""));
        if (excerpt.length() > 1000) excerpt = excerpt.substring(0, 1000);
        a.setSummary(excerpt);

        // 特色图：站点自身的图落盘换 /media/，外站图直接用外链
        String featured = featuredImageUrl(post);
        if (featured != null && !featured.isBlank()) {
            String local = downloadToLocal(site, featured, "featured", warnings);
            a.setCover(local != null ? local : featured);
        }

        // 状态：publish → 已发布（发布时间取 WP 的 date），其余（草稿/待审/私密）→ 草稿
        String wpStatus = post.path("status").asText("publish");
        if ("publish".equals(wpStatus)) {
            a.setStatus("PUBLISHED");
            String date = post.path("date_gmt").asText("");
            if (date.isBlank()) date = post.path("date").asText("");
            if (!date.isBlank()) {
                try { a.setPublishedAt(LocalDateTime.parse(date.substring(0, 19))); } catch (Exception ignore) { }
            }
        } else {
            a.setStatus("DRAFT");
        }

        // 分类 + 标签合并成本站标签（沿用「最多 10 个」规则，超量截断）
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
        if (!tagNames.isEmpty()) {
            List<String> tags = new ArrayList<>(tagNames);
            if (tags.size() > Article.MAX_TAGS) tags = tags.subList(0, Article.MAX_TAGS);
            a.setTags(String.join(",", tags));
        }
        a.setAuthor("wordpress");
        return a;
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
            if (raw == null || raw.isBlank() || raw.startsWith("/") || raw.startsWith("#")
                    || raw.startsWith("data:") || raw.startsWith("mailto:")) continue;
            // srcset 一个值里可能有多个「url 尺寸」，逐个看
            for (String part : raw.split(",")) {
                String candidate = part.trim().split("\\s+")[0];
                if (candidate.isEmpty()) continue;
                if (rewrite.containsKey(candidate)) continue;
                if (!sameHost(candidate, site.getUrl())) continue;      // 外站 → 沿用外链
                // src/poster/srcset 都落盘；href 只认 /wp-content/uploads/ 下的附件（内链页面不下载）
                if (attr.equals("href") && !candidate.contains("/wp-content/uploads/")) continue;
                String local = downloadToLocal(site, candidate, attr, warnings);
                if (local != null) rewrite.put(candidate, local);
            }
        }
        for (Map.Entry<String, String> e : rewrite.entrySet()) {
            html = html.replace(e.getKey(), e.getValue());
        }
        return html;
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
