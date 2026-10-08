package cn.xiezitai.service;

import cn.xiezitai.entity.Article;
import cn.xiezitai.entity.CnBlogSite;
import cn.xiezitai.entity.FileEntity;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.repository.CnBlogSiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 博客园文章导入：单篇同步导入 + 整站后台线程导入（进度轮询）。
 *
 * <p>镜像 {@link WordPressImportService} 的约定：
 * <ul>
 *   <li>发布时间可选：沿用博客园原发布时间（默认）或当前时间；</li>
 *   <li>slug 冲突可选：跳过（默认）或用博客园版本覆盖更新（保留 id/浏览数/评论）；</li>
 *   <li>媒体规则：博客园自身域名（*.cnblogs.com）的图片/附件下载落盘到 /media/，
 *       外站资源沿用外链；落盘走 {@link MediaStoreService}（SHA-256 内容去重）；</li>
 *   <li>发布人 = 关联账号时填写的博客园用户名。</li>
 * </ul>
 */
@Service
public class CnBlogImportService {

    private static final Logger log = LoggerFactory.getLogger(CnBlogImportService.class);

    private final MetaWeblogClient client;
    private final ArticleRepository articles;
    private final CnBlogSiteRepository sites;
    private final MediaStoreService media;

    /** 额外放行的媒体主机（测试/本地 mock 用，生产默认空：只认 *.cnblogs.com） */
    private final java.util.Set<String> extraMediaHosts;

    private final Map<Long, CnSyncProgress> progress = new ConcurrentHashMap<>();

    public CnBlogImportService(MetaWeblogClient client, ArticleRepository articles,
                               CnBlogSiteRepository sites, MediaStoreService media,
                               @org.springframework.beans.factory.annotation.Value(
                                       "${xiezitai.cn-media-hosts:}") String extraHosts) {
        this.client = client;
        this.articles = articles;
        this.sites = sites;
        this.media = media;
        java.util.Set<String> hosts = new java.util.HashSet<>();
        if (extraHosts != null) {
            for (String h : extraHosts.split(",")) {
                if (!h.isBlank()) hosts.add(h.trim().toLowerCase());
            }
        }
        this.extraMediaHosts = hosts;
    }

    /**
     * 博客园文章的稳定 slug（冲突检测的 key，重导必须落回同一个 slug）：
     * ASCII 标题用 slugify 结果；纯中文标题 slugify 会落「post-时间戳」且每次都不同，
     * 改用 cnblog-{postid} —— 稳定且唯一。
     */
    public static String baseSlugOf(String title, long postId) {
        String s = ArticleService.slugify(title);
        if (s.isBlank() || s.matches("post-\\d{13}")) return "cnblog-" + postId;
        return s;
    }

    /* ================= 进度模型 ================= */

    public static class CnSyncProgress {
        public volatile String phase = "RUNNING";        // RUNNING / DONE / FAILED
        public volatile long total = -1;                 // 账号文章总数
        public volatile int done, imported, updated, skipped, failed;
        public volatile boolean useWpDate = true;        // true=博客园原发布时间，false=当前时间
        public volatile String onConflict = "skip";      // skip / update
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

    public CnSyncProgress progressOf(Long siteId) {
        return progress.get(siteId);
    }

    /* ================= 单篇导入（同步，给 controller 直接调） ================= */

    /**
     * 导入一篇文章。
     *
     * @param useWpDate  true=发布时间用博客园原发布时间；false=用当前时间
     * @param onConflict 本地已有同 slug 文章时：skip=跳过；update=用博客园版本覆盖更新（保留文章 id）
     * @return result：{imported:true, articleId, slug, warnings:[...]} 或 {imported:false, reason:...}
     */
    public Map<String, Object> importSingle(CnBlogSite site, long postId, boolean useWpDate, String onConflict) throws Exception {
        MetaWeblogClient.CnPost post = client.getPost(site, postId);
        List<String> warnings = new ArrayList<>();
        String title = post.title().isBlank() ? "(无标题)" : post.title();

        String slug = baseSlugOf(title, postId);
        Article existing = articles.findBySlug(slug).orElse(null);
        if (existing != null) {
            if (!"update".equals(onConflict)) {
                return Map.of("imported", false, "reason", "exists",
                        "message", "本地已有同 slug 的文章《" + existing.getTitle() + "》，已跳过（可在导入选项里改为「更新」覆盖）",
                        "articleId", existing.getId());
            }
            applyPostBody(site, existing, post, warnings, useWpDate, title, slug);
            Article saved = articles.save(existing);
            touchSite(site);
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

        Article a = new Article();
        a.setSlug(slug);
        applyPostBody(site, a, post, warnings, useWpDate, title, slug);
        Article saved = articles.save(a);
        touchSite(site);
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

    /** 启动整站导入；同一账号已有任务在跑时返回 null（controller 回 409） */
    public CnSyncProgress startFullImport(CnBlogSite site, boolean useWpDate, String onConflict) {
        CnSyncProgress old = progress.get(site.getId());
        if (old != null && "RUNNING".equals(old.phase)) return null;
        CnSyncProgress p = new CnSyncProgress();
        p.useWpDate = useWpDate;
        p.onConflict = "update".equals(onConflict) ? "update" : "skip";
        progress.put(site.getId(), p);
        Thread t = new Thread(() -> runFullImport(site, p), "cnblog-import-" + site.getId());
        t.setDaemon(true);
        t.start();
        return p;
    }

    private void runFullImport(CnBlogSite site, CnSyncProgress p) {
        try {
            p.msg("发布时间策略：" + (p.useWpDate ? "沿用博客园原发布时间" : "使用当前时间"));
            p.msg("同 slug 冲突策略：" + ("update".equals(p.onConflict) ? "用博客园版本覆盖更新" : "跳过"));
            p.msg("正在拉取账号文章列表…");
            List<MetaWeblogClient.CnPost> posts = client.fetchAllPosts(site);
            p.total = posts.size();
            p.msg("账号共 " + posts.size() + " 篇文章，开始导入");
            for (MetaWeblogClient.CnPost post : posts) {
                String title = post.title().isBlank() ? "(无标题)" : post.title();
                p.current = title;
                try {
                    List<String> warnings = new ArrayList<>();
                    String slug = baseSlugOf(title, post.postId());
                    Article existing = articles.findBySlug(slug).orElse(null);
                    if (existing != null && !"update".equals(p.onConflict)) {
                        p.skipped++;
                        p.done++;
                        p.msg("跳过《" + title + "》：本地已存在同 slug 文章");
                        continue;
                    }
                    if (existing != null) {
                        applyPostBody(site, existing, post, warnings, p.useWpDate, title, slug);
                        articles.save(existing);
                        p.updated++;
                        p.done++;
                        p.msg("已更新《" + title + "》" + (warnings.isEmpty() ? "" : "（" + warnings.size() + " 条媒体警告）"));
                    } else {
                        Article a = new Article();
                        a.setSlug(slug);
                        applyPostBody(site, a, post, warnings, p.useWpDate, title, slug);
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
                    log.warn("博客园整站导入单篇失败 postid={} : {}", post.postId(), e.getMessage());
                }
            }
            touchSite(site);
            p.phase = "DONE";
            p.msg("整站导入完成：成功 " + p.imported + "，更新 " + p.updated + "，跳过 " + p.skipped + "，失败 " + p.failed);
        } catch (Exception e) {
            p.phase = "FAILED";
            p.error = e.getMessage();
            p.msg("整站导入中断：" + e.getMessage());
            log.warn("博客园整站导入中断 site={}: {}", site.getId(), e.getMessage());
        } finally {
            p.finishedAt = LocalDateTime.now();
            p.current = "";
        }
    }

    /* ================= 组装文章 ================= */

    /** 把博客园 post 的内容字段落到文章实体（title/正文/摘要/状态与发布时间/标签/发布人） */
    private void applyPostBody(CnBlogSite site, Article a, MetaWeblogClient.CnPost post,
                               List<String> warnings, boolean useWpDate, String title, String slug) {
        // 发布人 = 关联账号时填写的博客园用户名（新建导入与「更新」模式重导都会归一）
        a.setAuthor(site.getUsername() == null || site.getUsername().isBlank()
                ? "cnblogs" : site.getUsername().trim());
        a.setTitle(title);
        a.setSlug(slug);

        String html = post.fullContent();
        html = localizeMedia(html, warnings);
        a.setContent(html);

        String excerpt = post.excerpt() == null ? "" : post.excerpt();
        if (excerpt.length() > 1000) excerpt = excerpt.substring(0, 1000);
        a.setSummary(excerpt);

        a.setStatus("PUBLISHED");
        if (useWpDate && post.dateCreated() != null) {
            a.setPublishedAt(post.dateCreated());
        } else {
            a.setPublishedAt(LocalDateTime.now());
        }

        // 分类即标签（沿用「最多 10 个」规则，超量截断，去重保序）
        if (!post.categories().isEmpty()) {
            List<String> tags = new ArrayList<>(post.categories());
            if (tags.size() > Article.MAX_TAGS) tags = tags.subList(0, Article.MAX_TAGS);
            a.setTags(String.join(",", tags));
        }
    }

    private void touchSite(CnBlogSite site) {
        try {
            site.setLastSyncAt(LocalDateTime.now());
            sites.save(site);
        } catch (Exception ignore) { }
    }

    /* ================= 媒体本地化 ================= */

    private static final Pattern ATTR_URL = Pattern.compile(
            "(?i)\\b(src|poster|href|srcset)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");

    /** 判断 URL 是否博客园自身资源（cnblogs.com 及其子域，如 img2024.cnblogs.com / i.cnblogs.com） */
    public boolean isCnblogsHost(String url) {
        try {
            String host = java.net.URI.create(url.trim()).getHost();
            if (host == null) return false;
            host = host.toLowerCase();
            return host.equals("cnblogs.com") || host.endsWith(".cnblogs.com")
                    || extraMediaHosts.contains(host);
        } catch (Exception e) {
            return false;
        }
    }

    /** 路径是否指向一个文件（有扩展名）——博客园正文页 /xxx/p/123 没有，附件 /Files/xx.png 有 */
    private static boolean looksLikeFile(String url) {
        try {
            String path = java.net.URI.create(url.trim()).getPath();
            return path.matches(".*\\.[A-Za-z0-9]{2,5}$");
        } catch (Exception e) {
            return false;
        }
    }

    /** URL 末段当原始文件名（只留安全字符） */
    private static String fileNameOf(String url) {
        try {
            String path = java.net.URI.create(url.trim()).getPath();
            String name = path.substring(path.lastIndexOf('/') + 1);
            name = java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8);
            name = name.replaceAll("[^\\w.\\-\\u4e00-\\u9fa5]", "_");
            return name.isBlank() ? "cnblog-file" : name;
        } catch (Exception e) {
            return "cnblog-file";
        }
    }

    /**
     * 把正文里博客园自身的媒体下载落盘并重写 URL；外站资源原样保留。
     * 返回处理后的 HTML。
     */
    String localizeMedia(String html, List<String> warnings) {
        if (html == null || html.isBlank()) return html;
        Map<String, String> rewrite = new HashMap<>();
        Matcher m = ATTR_URL.matcher(html);
        while (m.find()) {
            String attr = m.group(1).toLowerCase();
            String raw = m.group(3) != null ? m.group(3) : m.group(4);
            if (raw == null || raw.isBlank() || raw.startsWith("/") || raw.startsWith("#")
                    || raw.startsWith("data:") || raw.startsWith("mailto:")) continue;
            for (String part : raw.split(",")) {
                String candidate = part.trim().split("\\s+")[0];
                if (candidate.isEmpty() || rewrite.containsKey(candidate)) continue;
                if (!isCnblogsHost(candidate)) continue;                  // 外站 → 沿用外链
                // src/poster/srcset 落盘；href 只认指向文件的链接（内链文章页不下载）
                if (attr.equals("href") && !looksLikeFile(candidate)) continue;
                String local = downloadToLocal(candidate, attr, warnings);
                if (local != null) rewrite.put(candidate, local);
            }
        }
        for (Map.Entry<String, String> e : rewrite.entrySet()) {
            html = html.replace(e.getKey(), e.getValue());
        }
        return html;
    }

    /**
     * 下载一个博客园自身的媒体文件并落盘到本站媒体库。
     *
     * @return 本地 URL（/media/xxx）；失败返回 null（调用方保留外链 + 记警告）
     */
    private String downloadToLocal(String url, String kind, List<String> warnings) {
        byte[] data = client.downloadQuietly(url);
        if (data == null) {
            warnings.add(kind + " 媒体下载失败，保留外链：" + url);
            return null;
        }
        String name = fileNameOf(url);
        try {
            FileEntity fe = media.storeImage(data, name, "cnblogs");
            return "/media/" + fe.getStoredName();
        } catch (MediaStoreService.NotAnImageException e) {
            try {
                FileEntity fe = media.store(data, name, MediaStoreService.guessContentType(name), "cnblogs");
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
