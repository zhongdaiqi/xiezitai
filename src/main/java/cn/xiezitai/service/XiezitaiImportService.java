package cn.xiezitai.service;

import cn.xiezitai.entity.Article;
import cn.xiezitai.entity.FileEntity;
import cn.xiezitai.entity.XzSite;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.repository.XzSiteRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 写字台 ⇄ 写字台的文章导入：单篇同步导入 + 整站后台导入（带进度）。
 *
 * <p>与 WordPress 渠道的区别：对方站点导出的正文**本来就是 Markdown 原文**
 * （本站存的就是 ByteMD 产出的 Markdown），所以不需要任何 HTML → Markdown 还原，
 * 直接落库即可；也不再需要「正文格式」选项。
 *
 * <p>媒体处理规则（需求 2.3）：
 * <ul>
 *   <li>正文 / 封面里 <b>属于对方写字台站点自身</b>（主机名与接口地址一致，忽略 www.）的图片与
 *       {@code /media/} 附件 → 下载落盘到本站媒体库，URL 重写为 {@code /media/xxx}；</li>
 *   <li>外站资源（其它 CDN、图床、第三方域名）→ 一律沿用外链，不动；</li>
 *   <li>相对路径 {@code /media/xxx}（对方站点未绝对化时的兜底）→ 按对方站点自身资源处理；</li>
 *   <li>单个文件下载 / 入库失败 → 不中断导入，保留外链并记一条警告。</li>
 * </ul>
 *
 * <p>整站导入在后台线程跑，进度存在内存 {@link #progress} 里，前端轮询
 * {@code GET /api/admin/xz/sites/{id}/progress} 展示（总数/已完成/成功/更新/跳过/失败 + 当前标题 + 日志）。
 */
@Service
public class XiezitaiImportService {

    private static final Logger log = LoggerFactory.getLogger(XiezitaiImportService.class);

    private final XiezitaiClient client;
    private final ArticleRepository articles;
    private final XzSiteRepository sites;
    private final MediaStoreService media;
    private final ArticleService articleService;

    /**
     * 导入对方站点**自身文件**时的单文件上限（字节）。默认 64MB —— 站点自带的视频/附件常常
     * 十几兆，用媒体库默认的 10MB 会拒收，与需求「站点自身文件要落盘」冲突。
     * 超过此上限才降级为「保留外链 + 警告」。可通过 {@code xiezitai.xz-import.max-media-bytes} 调整。
     */
    private final long maxMediaBytes;

    /** 每个账号一份导入进度；键 = xzSite.id。同一账号同一时间只允许一个整站导入任务 */
    private final Map<Long, XzSyncProgress> progress = new ConcurrentHashMap<>();

    public XiezitaiImportService(XiezitaiClient client, ArticleRepository articles,
                                 XzSiteRepository sites, MediaStoreService media,
                                 ArticleService articleService,
                                 @Value("${xiezitai.xz-import.max-media-bytes:67108864}") long maxMediaBytes) {
        this.client = client;
        this.articles = articles;
        this.sites = sites;
        this.media = media;
        this.articleService = articleService;
        this.maxMediaBytes = maxMediaBytes;
    }

    /* ================= 进度模型 ================= */

    public static class XzSyncProgress {
        public volatile String phase = "RUNNING";        // RUNNING / DONE / FAILED
        public volatile long total = -1;                 // 对方站点文章总数（列表页拿到后回填）
        public volatile int done, imported, updated, skipped, failed;
        public volatile boolean useSrcDate = true;       // 发布时间策略：true=沿用原发布时间，false=用当前时间
        public volatile String onConflict = "skip";      // slug 冲突策略：skip=跳过，update=用对方版本覆盖
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

    public XzSyncProgress progressOf(Long siteId) {
        return progress.get(siteId);
    }

    /* ================= 单篇导入（同步，给 controller 直接调） ================= */

    /**
     * 导入一篇文章。对方文章用数字 id 定位（与 WordPress 渠道同一形态，天然 URL 安全）。
     *
     * @param useSrcDate  true=发布时间沿用对方原发布时间；false=用当前时间
     * @param onConflict  本地已有同 slug 文章时：skip=跳过；update=用对方版本覆盖更新（保留文章 id）
     * @return {imported:true, articleId, slug, title, tags, warnings:[...]} 或 {imported:false, reason:...}
     */
    public Map<String, Object> importSingle(XzSite site, long id, boolean useSrcDate, String onConflict)
            throws Exception {
        JsonNode post = client.fetchArticle(site, id);
        String title = post.path("title").asText("(无标题)");
        String slug = post.path("slug").asText("");
        Article existing = slug.isBlank() ? null : articles.findBySlugIgnoreCase(slug).orElse(null);
        if (existing != null && !"update".equals(onConflict)) {
            return Map.of("imported", false, "reason", "exists",
                    "message", "本地已有同名 slug 的文章《" + existing.getTitle() + "》，已跳过（可在导入选项里改为「更新」覆盖）",
                    "articleId", existing.getId());
        }

        boolean updated = existing != null;
        Article target = updated ? existing : new Article();
        List<String> warnings = new ArrayList<>();
        applyArticleBody(site, target, post, warnings, useSrcDate);
        if (!updated) target.setSlug(slug.isBlank() ? articleService.uniqueSlug(title) : slug);
        Article saved = articles.save(target);
        touchSync(site);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("imported", true);
        if (updated) out.put("updated", true);
        out.put("articleId", saved.getId());
        out.put("slug", saved.getSlug());
        out.put("title", saved.getTitle());
        out.put("tags", saved.getTags() == null ? "" : saved.getTags());
        out.put("warnings", warnings);
        return out;
    }

    /* ================= 整站导入（后台线程 + 轮询进度） ================= */

    /** 启动整站导入；同一账号已有任务在跑时返回 null（controller 回 409） */
    public XzSyncProgress startFullImport(XzSite site, boolean useSrcDate, String onConflict) {
        XzSyncProgress old = progress.get(site.getId());
        if (old != null && "RUNNING".equals(old.phase)) return null;
        XzSyncProgress p = new XzSyncProgress();
        p.useSrcDate = useSrcDate;
        p.onConflict = "update".equals(onConflict) ? "update" : "skip";
        progress.put(site.getId(), p);
        Thread t = new Thread(() -> runFullImport(site, p), "xz-import-" + site.getId());
        t.setDaemon(true);
        t.start();
        return p;
    }

    private void runFullImport(XzSite site, XzSyncProgress p) {
        try {
            p.msg("对方站点：" + site.getApiUrl());
            p.msg("发布时间策略：" + (p.useSrcDate ? "沿用对方原发布时间" : "使用当前时间"));
            p.msg("同 slug 冲突策略：" + ("update".equals(p.onConflict) ? "用对方版本覆盖更新" : "跳过"));
            int page = 1;
            while (true) {
                XiezitaiClient.ArticlesPage pp = client.listArticles(site, page, 100, null);
                if (p.total < 0) p.total = pp.total();
                if (pp.items().isEmpty()) break;
                for (Map<String, Object> meta : pp.items()) {
                    long postId = ((Number) meta.get("id")).longValue();
                    String title = String.valueOf(meta.get("title"));
                    p.current = title;
                    try {
                        JsonNode post = client.fetchArticle(site, postId);
                        String slug = post.path("slug").asText("");
                        Article existing = slug.isBlank() ? null : articles.findBySlugIgnoreCase(slug).orElse(null);
                        if (existing != null && !"update".equals(p.onConflict)) {
                            p.skipped++;
                            p.done++;
                            p.msg("跳过《" + title + "》：本地已存在同 slug 文章");
                            continue;
                        }
                        List<String> warnings = new ArrayList<>();
                        if (existing != null) {
                            applyArticleBody(site, existing, post, warnings, p.useSrcDate);
                            articles.save(existing);
                            p.updated++;
                            p.msg("已更新《" + title + "》" + warnNote(warnings));
                        } else {
                            Article a = new Article();
                            applyArticleBody(site, a, post, warnings, p.useSrcDate);
                            a.setSlug(slug.isBlank() ? articleService.uniqueSlug(title) : slug);
                            articles.save(a);
                            p.imported++;
                            p.msg("已导入《" + title + "》" + warnNote(warnings));
                        }
                        p.done++;
                        warnings.forEach(w -> p.msg("  ⚠ " + w));
                    } catch (Exception e) {
                        p.failed++;
                        p.done++;
                        p.msg("失败《" + title + "》：" + e.getMessage());
                        log.warn("写字台整站导入单篇失败 id={} : {}", postId, e.getMessage());
                    }
                }
                if (page >= pp.totalPages()) break;
                page++;
            }
            touchSync(site);
            p.phase = "DONE";
            p.msg("整站导入完成：成功 " + p.imported + "，更新 " + p.updated + "，跳过 " + p.skipped + "，失败 " + p.failed);
        } catch (Exception e) {
            p.phase = "FAILED";
            p.error = e.getMessage();
            p.msg("整站导入中断：" + e.getMessage());
            log.warn("写字台整站导入中断 site={}: {}", site.getId(), e.getMessage());
        } finally {
            p.finishedAt = LocalDateTime.now();
            p.current = "";
        }
    }

    private static String warnNote(List<String> warnings) {
        return warnings.isEmpty() ? "" : "（" + warnings.size() + " 条媒体警告）";
    }

    private void touchSync(XzSite site) {
        site.setLastSyncAt(LocalDateTime.now());
        sites.save(site);
    }

    /* ================= 组装文章 ================= */

    /** 把对方文章 JSON 的字段落到本站 Article（title / 正文 / 摘要 / 封面 / 状态与发布时间 / 标签 / 发布人） */
    private void applyArticleBody(XzSite site, Article a, JsonNode post,
                                  List<String> warnings, boolean useSrcDate) {
        // 发布人 = 关联账号时填写的写字台账号，而不是写死
        a.setAuthor(site.getUsername() == null || site.getUsername().isBlank()
                ? "xiezitai" : site.getUsername().trim());
        a.setTitle(post.path("title").asText("(无标题)"));

        // 对方导出的正文已是 Markdown 原文；这里只做媒体本地化
        a.setContent(localizeMediaMarkdown(site, post.path("content").asText(""), warnings));

        String summary = post.path("summary").asText("");
        if (summary.length() > 1000) summary = summary.substring(0, 1000);
        a.setSummary(summary);

        // 封面：对方站点自身的图落盘换 /media/，外站图直接用外链
        String cover = post.path("cover").asText("").trim();
        if (!cover.isBlank()) {
            String local = shouldLocalize(site, cover, "img")
                    ? downloadToLocal(site, cover, "cover", warnings) : null;
            a.setCover(local != null ? local : cover);
        }

        // 状态：PUBLISHED → 已发布；其余（草稿）→ 草稿。
        // 发布时间由用户选择：沿用对方原发布时间，或使用导入当时的当前时间
        if ("PUBLISHED".equalsIgnoreCase(post.path("status").asText("PUBLISHED"))) {
            a.setStatus("PUBLISHED");
            if (useSrcDate) {
                String date = post.path("publishedAt").asText("");
                if (!date.isBlank()) {
                    try {
                        a.setPublishedAt(LocalDateTime.parse(date.length() > 19 ? date.substring(0, 19) : date));
                    } catch (Exception ignore) { /* 时间格式异常就退回当前时间 */ }
                }
            }
            if (a.getPublishedAt() == null) a.setPublishedAt(LocalDateTime.now());
        } else {
            a.setStatus("DRAFT");
        }

        // 标签沿用「最多 10 个」规则，超量截断
        String tags = post.path("tags").asText("");
        if (!tags.isBlank()) {
            List<String> list = Article.parseTags(tags);
            if (list.size() > Article.MAX_TAGS) list = list.subList(0, Article.MAX_TAGS);
            if (!list.isEmpty()) a.setTags(String.join(",", list));
        }
    }

    /* ================= 媒体本地化 ================= */

    private static final Pattern ATTR_URL = Pattern.compile(
            "(?i)\\b(src|poster|href|data-src|srcset)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");

    /** Markdown 正文里的链接/图片（{@code ![alt](url)}、{@code [text](url)}）以及混写的裸 HTML 标签 */
    private static final Pattern MD_LINK = Pattern.compile(
            "(!?)\\[([^\\]]*)\\]\\(\\s*<?([^)\\s>]+)>?\\s*(?:\"[^\"]*\"\\s*)?\\)");

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
            return name.isBlank() ? "xz-file" : name;
        } catch (Exception e) {
            return "xz-file";
        }
    }

    /**
     * 媒体本地化：把正文里**对方站点自身**的图片/附件下载落盘、链接改写成本站 {@code /media/xxx}；
     * 外站资源原样保留。
     *
     * <p>正文是 Markdown，但常混着裸 HTML 标签（{@code <img src>}、{@code <video poster>}），
     * 所以两类链接都要扫：{@code ![alt](url)} / {@code [text](url)} 与 HTML 属性。
     *
     * <p><b>必须先扫完整篇、最后统一替换</b>，不能「扫一段替换一段」：替换出来的
     * {@code /media/xxx} 是**本站**地址，若再被扫一遍，会被当成对方站点的相对路径又下载一次
     * （必然 404 —— 正文内容无害，但会刷一堆假警告）。
     */
    String localizeMediaMarkdown(XzSite site, String md, List<String> warnings) {
        if (md == null || md.isBlank()) return md;
        Map<String, String> rewrite = new LinkedHashMap<>();

        // ① Markdown 链接 / 图片
        Matcher mm = MD_LINK.matcher(md);
        while (mm.find()) {
            String bang = mm.group(1);
            String url = decodeHtmlInUrl(mm.group(3));
            collectRewrite(site, url, "!".equals(bang) ? "img" : "href", rewrite, warnings);
        }
        // ② 混写的裸 HTML 属性（src / poster / href / data-src / srcset）
        Matcher am = ATTR_URL.matcher(md);
        while (am.find()) {
            String attr = am.group(1).toLowerCase();
            String raw = am.group(3) != null ? am.group(3) : am.group(4);
            if (raw == null || raw.isBlank()) continue;
            for (String part : raw.split(",")) {          // srcset 一个值里可能有多段「url 尺寸」
                String candidate = part.trim().split("\\s+")[0];
                if (candidate.isEmpty()) continue;
                collectRewrite(site, candidate, attr.equals("href") ? "href" : attr, rewrite, warnings);
            }
        }

        String out = md;
        for (Map.Entry<String, String> e : rewrite.entrySet()) out = out.replace(e.getKey(), e.getValue());
        return out;
    }

    /** 收集一条待替换的媒体：该落盘就下载落盘，把「原 URL → 本站 /media/xxx」记进 rewrite */
    private void collectRewrite(XzSite site, String url, String kind,
                                Map<String, String> rewrite, List<String> warnings) {
        if (url == null || url.isBlank() || rewrite.containsKey(url)) return;
        if (!shouldLocalize(site, url, kind)) return;
        String local = downloadToLocal(site, url, kind, warnings);
        if (local != null) rewrite.put(url, local);
    }

    /**
     * 这个 URL 该不该落盘。
     *
     * <ul>
     *   <li><b>绝对地址</b>：必须是对方写字台站点自身（同主机，忽略 www.）；普通链接（href）
     *       只认 {@code /media/} 下的附件，站内页面互链不下载；</li>
     *   <li><b>相对路径</b>：写字台的媒体路径就是 {@code /media/xxx}，按对方站点自身资源处理
     *       （对方导出版本较旧、没做绝对化时的兜底）；锚点 / data: / mailto: 跳过。</li>
     * </ul>
     */
    private boolean shouldLocalize(XzSite site, String url, String kind) {
        if (url == null) return false;
        String u = decodeHtmlInUrl(url.trim());
        if (u.isEmpty() || u.startsWith("#") || u.startsWith("data:") || u.startsWith("mailto:")) return false;
        if (u.startsWith("/")) return u.startsWith("/media/");   // 相对路径兜底
        if (!WordPressImportService.sameHost(u, site.getApiUrl())) return false;   // 外站 → 沿用外链
        if ("href".equals(kind) && !u.contains("/media/")) return false;
        return true;
    }

    /**
     * 下载一个对方站点自身的媒体文件并落盘到本站媒体库。
     *
     * @return 本地 URL（/media/xxx）；失败返回 null（调用方保留外链 + 记警告）
     */
    private String downloadToLocal(XzSite site, String url, String kind, List<String> warnings) {
        String abs = url.trim().startsWith("/")
                ? XiezitaiClient.origin(site.getApiUrl()) + url.trim() : url.trim();
        if (abs.isBlank()) {
            warnings.add(kind + " 媒体地址无法解析，保留外链：" + url);
            return null;
        }
        byte[] data = client.downloadQuietly(site, abs);
        if (data == null) {
            warnings.add(kind + " 媒体下载失败，保留外链：" + url);
            return null;
        }
        String name = fileNameOf(url);
        try {
            // 对方站点自身的文件用更大的上限（默认 64MB）：视频/附件常超过媒体库默认的 10MB
            FileEntity fe = media.storeImage(data, name, "xiezitai", maxMediaBytes);
            return "/media/" + fe.getStoredName();
        } catch (MediaStoreService.NotAnImageException e) {
            // 不是图片（PDF/zip/mp3/mp4…）：按普通附件入库，Content-Type 按扩展名推断
            // （否则 mp4 会落成 octet-stream，<video> 播不出来）
            try {
                FileEntity fe = media.store(data, name, MediaStoreService.guessContentType(name),
                        "xiezitai", maxMediaBytes);
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
