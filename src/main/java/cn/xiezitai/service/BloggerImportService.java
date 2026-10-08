package cn.xiezitai.service;

import cn.xiezitai.entity.Article;
import cn.xiezitai.entity.BloggerSite;
import cn.xiezitai.entity.FileEntity;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.repository.BloggerSiteRepository;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 谷歌 Blogger → 本站的文章导入：单篇同步导入 + 整站后台导入（带进度）。
 *
 * <p>与写字台渠道相比，这里多了一次 <b>HTML → Markdown 的还原</b>：Blogger 的文章正文是 HTML，
 * 而本站（ByteMD）渲染的是 Markdown，直接入库会满屏标签。转换复用
 * {@link HtmlToMarkdown}（WordPress 渠道已经在用同一套，含代码块 / 表格 / 嵌套列表 / 图片说明）。
 *
 * <p>媒体处理规则（需求 2.3）：
 * <ul>
 *   <li>正文 / 封面里 <b>属于 Blogger 来源站点自身</b>的图片与附件 ——
 *       主机名命中 {@code blogger-media-hosts} 后缀表（默认 {@code blogspot.com} /
 *       {@code googleusercontent.com} / {@code ggpht.com}），或与该博客自己的域名同主机 ——
 *       一律下载落盘进本站媒体库，链接改写成 {@code /media/xxx}；</li>
 *   <li><b>其它第三方图床</b>（imgur、其它 CDN 等）→ 沿用外链，不动；</li>
 *   <li>{@code href} 指向的是「文章互链」而不是媒体时（没有媒体后缀）也不下载，
 *       否则会把整站 HTML 页面当附件存进媒体库；</li>
 *   <li>单个文件下载 / 入库失败 → 不中断导入，保留外链并记一条警告。</li>
 * </ul>
 *
 * <p>整站导入在后台线程跑，进度存在内存 {@link #progress} 里，前端轮询
 * {@code GET /api/admin/blogger/sites/{id}/progress} 展示（总数/已完成/成功/更新/跳过/失败 + 当前标题 + 日志）。
 */
@Service
public class BloggerImportService {

    private static final Logger log = LoggerFactory.getLogger(BloggerImportService.class);

    private final BloggerClient client;
    private final ArticleRepository articles;
    private final BloggerSiteRepository sites;
    private final MediaStoreService media;

    /** 导入 Blogger 来源站点**自身文件**时的单文件上限（字节），默认 64MB */
    private final long maxMediaBytes;

    /** 视为「来源站点自身」的主机后缀表（可配）：这些域名下的媒体会被下载落盘 */
    private final Set<String> mediaHostSuffixes;

    /** 每个博客一份导入进度；键 = bloggerSite.id。同一博客同一时间只允许一个整站导入任务 */
    private final Map<Long, BloggerSyncProgress> progress = new ConcurrentHashMap<>();

    public BloggerImportService(BloggerClient client, ArticleRepository articles,
                                BloggerSiteRepository sites, MediaStoreService media,
                                @Value("${xiezitai.blogger-import.max-media-bytes:67108864}") long maxMediaBytes,
                                @Value("${xiezitai.blogger-media-hosts:blogspot.com,googleusercontent.com,ggpht.com}")
                                String mediaHosts) {
        this.client = client;
        this.articles = articles;
        this.sites = sites;
        this.media = media;
        this.maxMediaBytes = maxMediaBytes;
        this.mediaHostSuffixes = new HashSet<>();
        if (mediaHosts != null) {
            for (String h : mediaHosts.split(",")) {
                if (!h.isBlank()) this.mediaHostSuffixes.add(h.trim().toLowerCase());
            }
        }
    }

    /* ================= 进度模型 ================= */

    public static class BloggerSyncProgress {
        public volatile String phase = "RUNNING";        // RUNNING / DONE / FAILED
        public volatile long total = -1;                 // 该博客文章总数（取到后回填）
        public volatile int done, imported, updated, skipped, failed;
        public volatile boolean useSrcDate = true;       // 发布时间策略
        public volatile String onConflict = "skip";      // slug 冲突策略：skip / update
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

    public BloggerSyncProgress progressOf(Long siteId) {
        return progress.get(siteId);
    }

    /* ================= 单篇导入 ================= */

    /**
     * 导入一篇文章。
     *
     * @param useSrcDate true=发布时间沿用 Blogger 上的原发布时间；false=用当前时间
     * @param onConflict 本地已有同 slug 文章时：skip=跳过；update=用 Blogger 版本覆盖更新（保留文章 id）
     * @return {imported:true, articleId, slug, title, tags, warnings:[...]} 或 {imported:false, reason:...}
     */
    public Map<String, Object> importSingle(BloggerSite site, String postId, boolean useSrcDate,
                                            String onConflict) throws Exception {
        JsonNode post = client.fetchPost(site, postId);
        String title = post.path("title").asText("(无标题)");
        String slug = baseSlugOf(post);
        Article existing = articles.findBySlugIgnoreCase(slug).orElse(null);
        if (existing != null && !"update".equals(onConflict)) {
            return Map.of("imported", false, "reason", "exists",
                    "message", "本地已有同名 slug 的文章《" + existing.getTitle() + "》，已跳过（可在导入选项里改为「更新」覆盖）",
                    "articleId", existing.getId());
        }
        boolean updated = existing != null;
        Article target = updated ? existing : new Article();
        List<String> warnings = new ArrayList<>();
        applyArticleBody(site, target, post, warnings, useSrcDate);
        if (!updated) target.setSlug(slug);
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

    /** 启动整站导入；同一博客已有任务在跑时返回 null（controller 回 409） */
    public BloggerSyncProgress startFullImport(BloggerSite site, boolean useSrcDate, String onConflict) {
        BloggerSyncProgress old = progress.get(site.getId());
        if (old != null && "RUNNING".equals(old.phase)) return null;
        BloggerSyncProgress p = new BloggerSyncProgress();
        p.useSrcDate = useSrcDate;
        p.onConflict = "update".equals(onConflict) ? "update" : "skip";
        progress.put(site.getId(), p);
        Thread t = new Thread(() -> runFullImport(site, p), "blogger-import-" + site.getId());
        t.setDaemon(true);
        t.start();
        return p;
    }

    private void runFullImport(BloggerSite site, BloggerSyncProgress p) {
        try {
            p.msg("博客：" + nz(site.getName()) + "（" + nz(site.getUrl()) + "）");
            p.msg("发布时间策略：" + (p.useSrcDate ? "沿用 Blogger 上的原发布时间" : "使用当前时间"));
            p.msg("同 slug 冲突策略：" + ("update".equals(p.onConflict) ? "用 Blogger 版本覆盖更新" : "跳过"));

            // 先取文章总数，进度条才有分母（Blogger 的列表接口只给 pageToken，不给总数）
            p.total = client.blogInfo(site).totalPosts();
            p.msg("该博客共 " + p.total + " 篇文章，开始逐篇导入…");

            String pageToken = "";
            while (true) {
                BloggerClient.PostPage page = client.listPosts(site, pageToken, false);
                if (page.items().isEmpty()) break;
                for (BloggerClient.PostBrief brief : page.items()) {
                    p.current = brief.title();
                    try {
                        JsonNode post = client.fetchPost(site, brief.id());
                        String slug = baseSlugOf(post);
                        Article existing = articles.findBySlugIgnoreCase(slug).orElse(null);
                        if (existing != null && !"update".equals(p.onConflict)) {
                            p.skipped++;
                            p.done++;
                            p.msg("跳过《" + brief.title() + "》：本地已存在同 slug 文章");
                            continue;
                        }
                        List<String> warnings = new ArrayList<>();
                        if (existing != null) {
                            applyArticleBody(site, existing, post, warnings, p.useSrcDate);
                            articles.save(existing);
                            p.updated++;
                            p.msg("已更新《" + brief.title() + "》" + warnNote(warnings));
                        } else {
                            Article a = new Article();
                            applyArticleBody(site, a, post, warnings, p.useSrcDate);
                            a.setSlug(slug);
                            articles.save(a);
                            p.imported++;
                            p.msg("已导入《" + brief.title() + "》" + warnNote(warnings));
                        }
                        p.done++;
                        warnings.forEach(w -> p.msg("  ⚠ " + w));
                    } catch (Exception e) {
                        p.failed++;
                        p.done++;
                        p.msg("失败《" + brief.title() + "》：" + e.getMessage());
                        log.warn("Blogger 整站导入单篇失败 post={} : {}", brief.id(), e.getMessage());
                    }
                }
                pageToken = page.nextPageToken();
                if (pageToken == null || pageToken.isBlank()) break;
            }
            touchSync(site);
            p.phase = "DONE";
            p.msg("整站导入完成：成功 " + p.imported + "，更新 " + p.updated + "，跳过 " + p.skipped + "，失败 " + p.failed);
        } catch (Exception e) {
            p.phase = "FAILED";
            p.error = e.getMessage();
            p.msg("整站导入中断：" + e.getMessage());
            log.warn("Blogger 整站导入中断 site={}: {}", site.getId(), e.getMessage());
        } finally {
            p.finishedAt = LocalDateTime.now();
            p.current = "";
        }
    }

    private static String warnNote(List<String> warnings) {
        return warnings.isEmpty() ? "" : "（" + warnings.size() + " 条媒体警告）";
    }

    private void touchSync(BloggerSite site) {
        site.setLastSyncAt(LocalDateTime.now());
        sites.save(site);
    }

    /* ================= 组装文章 ================= */

    /**
     * Blogger 文章的稳定 slug（冲突检测的 key，重导必须落回同一个 slug）。
     *
     * <p>优先取文章 URL 的末段 —— Blogger 的永久链接形如
     * {@code https://blog.blogspot.com/2026/01/why-self-host.html}，末段去掉 {@code .html}
     * 就是作者当初起的那串英文（比标题转写更贴合原文）。
     * 末段取不到（{@code blog-post.html} 这类通用名）时退回标题转写；
     * 纯中文标题转写会落成 {@code post-时间戳}（每次都不同，会导致重复导入），
     * 这时改用 {@code blogger-<postId>} —— 稳定且唯一。
     */
    static String baseSlugOf(JsonNode post) {
        String id = post.path("id").asText("");
        String seg = lastSegmentOfUrl(post.path("url").asText(""));
        if (isUsableSlug(seg)) return seg;
        String s = ArticleService.slugify(post.path("title").asText(""));
        if (s.isBlank() || s.matches("post-\\d{13}")) return "blogger-" + id;
        return s;
    }

    /** Blogger 的通用末段（所有没起过自定义链接的文章都长这样），不能当 slug 用 */
    private static boolean isUsableSlug(String seg) {
        if (seg == null || seg.isBlank()) return false;
        if (!seg.matches("[A-Za-z0-9\\-_]+")) return false;
        return !seg.matches("blog-post(_\\d+)?") && !seg.matches("post-\\d{13}");
    }

    /** 取 URL 末段并去掉 Blogger 的 {@code .html} 后缀 */
    static String lastSegmentOfUrl(String url) {
        try {
            String path = URI.create(url.trim()).getPath();
            if (path == null) return "";
            String name = path.substring(path.lastIndexOf('/') + 1);
            name = URLDecoder.decode(name, StandardCharsets.UTF_8);
            name = name.replaceAll("(?i)\\.html?$", "");
            return name.trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** 把 Blogger 文章 JSON 的字段落到本站 Article */
    private void applyArticleBody(BloggerSite site, Article a, JsonNode post,
                                  List<String> warnings, boolean useSrcDate) {
        // 发布人用博客名而不是 Google 邮箱 —— 邮箱是账号身份，不该出现在前台页面上
        a.setAuthor(nz(site.getName()).isEmpty() ? "blogger" : site.getName());
        a.setTitle(post.path("title").asText("(无标题)"));

        // ① HTML → Markdown（Blogger 编辑器里粘过 Markdown 的话，用「还原式」避免把语法转义掉）
        String html = post.path("content").asText("");
        String md = HtmlToMarkdown.looksLikeMarkdownInHtml(html)
                ? HtmlToMarkdown.convertMarkdownWrapped(html)
                : HtmlToMarkdown.convert(html);
        // ② 媒体本地化：来源站点自身的图落盘，第三方图床留外链
        a.setContent(localizeMedia(site, md, warnings));

        a.setSummary(summaryOf(md));

        // 封面：Blogger 接口不提供封面，从正文第一张图推（本地化的图直接用本站地址）
        String first = firstImageUrl(a.getContent());
        if (!first.isBlank() && (a.getCover() == null || a.getCover().isBlank())) a.setCover(first);

        a.setStatus("PUBLISHED");
        LocalDateTime at = useSrcDate ? parseTime(post.path("published").asText("")) : null;
        a.setPublishedAt(at != null ? at : LocalDateTime.now());

        List<String> labels = new ArrayList<>();
        for (JsonNode l : post.path("labels")) {
            String v = l.asText("").trim();
            if (!v.isEmpty() && !labels.contains(v)) labels.add(v);
        }
        if (labels.size() > Article.MAX_TAGS) labels = labels.subList(0, Article.MAX_TAGS);
        if (!labels.isEmpty()) a.setTags(String.join(",", labels));
    }

    /** 摘要：取正文第一段非标题文字，剥掉 Markdown 记号后截断 */
    static String summaryOf(String md) {
        if (md == null) return "";
        for (String line : md.split("\n")) {
            String t = line.trim();
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!") || t.startsWith("```")
                    || t.startsWith("|") || t.startsWith(">")) continue;
            t = t.replaceAll("!?\\[([^\\]]*)\\]\\([^)]*\\)", "$1")   // 链接/图片 → 只留文字
                    .replaceAll("[*_`~]", "")
                    .replaceAll("<[^>]*>", "")
                    .trim();
            if (!t.isEmpty()) return t.length() > 200 ? t.substring(0, 200) : t;
        }
        return "";
    }

    /** 正文里第一张图的地址（Markdown 形态），没有则空串 */
    static String firstImageUrl(String md) {
        if (md == null) return "";
        Matcher m = Pattern.compile("!\\[[^\\]]*\\]\\(\\s*<?([^)\\s>]+)>?[^)]*\\)").matcher(md);
        return m.find() ? m.group(1) : "";
    }

    /** Blogger 用 RFC3339（带时区偏移）；解析不了就退回前 19 位的本地时间形态 */
    static LocalDateTime parseTime(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return OffsetDateTime.parse(s).atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime();
        } catch (Exception ignore) { /* 退回落格式 */ }
        try {
            return LocalDateTime.parse(s.length() > 19 ? s.substring(0, 19) : s);
        } catch (Exception ignore) {
            return null;
        }
    }

    /* ================= 媒体本地化 ================= */

    private static final Pattern ATTR_URL = Pattern.compile(
            "(?i)\\b(src|poster|href|data-src|srcset)\\s*=\\s*(\"([^\"]*)\"|'([^']*)')");

    /** Markdown 的链接 / 图片，以及混写的裸 HTML 标签 */
    private static final Pattern MD_LINK = Pattern.compile(
            "(!?)\\[([^\\]]*)\\]\\(\\s*<?([^)\\s>]+)>?\\s*(?:\"[^\"]*\"\\s*)?\\)");

    /** 有这些后缀的 href 才当「媒体」下载；否则是文章互链 */
    private static final Pattern MEDIA_EXT = Pattern.compile(
            "(?i)\\.(jpe?g|png|gif|webp|bmp|svg|avif|ico|mp4|webm|mov|m4v|mp3|ogg|wav|pdf|zip|7z)$");

    /**
     * 媒体本地化：把正文里**来源站点自身**的图片/附件下载落盘、链接改写成本站 {@code /media/xxx}；
     * 第三方图床原样保留。
     *
     * <p><b>必须先扫完整篇、最后统一替换</b>：替换出来的 {@code /media/xxx} 是**本站**地址，
     * 若再被扫一遍会被当成来源站点的资源又下载一次。
     */
    String localizeMedia(BloggerSite site, String md, List<String> warnings) {
        if (md == null || md.isBlank()) return md;
        Map<String, String> rewrite = new LinkedHashMap<>();

        Matcher mm = MD_LINK.matcher(md);
        while (mm.find()) {
            String url = decodeHtmlInUrl(mm.group(3));
            collectRewrite(site, url, "!".equals(mm.group(1)) ? "img" : "href", rewrite, warnings);
        }
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

    private void collectRewrite(BloggerSite site, String url, String kind,
                                Map<String, String> rewrite, List<String> warnings) {
        if (url == null || url.isBlank() || rewrite.containsKey(url)) return;
        if (!shouldLocalize(site, url, kind)) return;
        String local = downloadToLocal(url, kind, warnings);
        if (local != null) rewrite.put(url, local);
    }

    /**
     * 这个 URL 该不该落盘。
     *
     * <ul>
     *   <li>锚点 / {@code data:} / {@code mailto:} → 跳过；</li>
     *   <li>相对路径 → 跳过（Blogger 正文里的图基本都是绝对地址；真出现相对路径时无从判断是不是来源站点的，
     *       宁可不下载也不要误抓本站资源）；</li>
     *   <li>主机不属于 Blogger 自家 CDN、也不是该博客自己的域名 → <b>第三方外链，保留</b>；</li>
     *   <li>普通链接（href）只认带媒体后缀的，其它是文章互链，不下载。</li>
     * </ul>
     */
    private boolean shouldLocalize(BloggerSite site, String url, String kind) {
        if (url == null) return false;
        String u = decodeHtmlInUrl(url.trim());
        if (u.isEmpty() || u.startsWith("#") || u.startsWith("data:") || u.startsWith("mailto:")) return false;
        if (u.startsWith("//")) u = "https:" + u;              // 协议相对地址
        if (u.startsWith("/")) return false;
        String host = hostOf(u);
        if (host.isEmpty()) return false;
        if (!isSourceHost(site, host)) return false;
        return !"href".equals(kind) || MEDIA_EXT.matcher(pathOf(u)).find();
    }

    /** 是不是「来源站点自身」的主机：Blogger 自家 CDN 后缀表，或该博客自己的域名 */
    private boolean isSourceHost(BloggerSite site, String host) {
        for (String suffix : mediaHostSuffixes) {
            if (host.equals(suffix) || host.endsWith("." + suffix)) return true;
        }
        String own = hostOf(nz(site.getUrl()));
        return !own.isEmpty() && own.equals(host);
    }

    /**
     * 下载一个来源站点自身的媒体文件并落盘到本站媒体库。
     *
     * @return 本地 URL（{@code /media/xxx}）；失败返回 null（调用方保留外链 + 记警告）
     */
    private String downloadToLocal(String url, String kind, List<String> warnings) {
        byte[] data = client.downloadQuietly(url);
        if (data == null) {
            warnings.add(kind + " 媒体下载失败，保留外链：" + url);
            return null;
        }
        String name = fileNameOf(url);
        try {
            FileEntity fe = media.storeImage(data, name, "blogger", maxMediaBytes);
            return "/media/" + fe.getStoredName();
        } catch (MediaStoreService.NotAnImageException e) {
            // 不是图片（PDF/zip/mp3/mp4…）：按普通附件入库，Content-Type 按扩展名推断
            try {
                FileEntity fe = media.store(data, name, MediaStoreService.guessContentType(name),
                        "blogger", maxMediaBytes);
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

    /* ================= 小工具 ================= */

    static String hostOf(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            String u = url.trim();
            if (u.startsWith("//")) u = "https:" + u;
            String host = URI.create(u).getHost();
            if (host == null) return "";
            host = host.toLowerCase();
            return host.startsWith("www.") ? host.substring(4) : host;
        } catch (Exception e) {
            return "";
        }
    }

    /** URL 的路径部分（不含 query），用来判断媒体后缀 */
    static String pathOf(String url) {
        try {
            String p = URI.create(url.trim()).getPath();
            return p == null ? "" : p;
        } catch (Exception e) {
            return url.replaceAll("[?#].*$", "");
        }
    }

    private static String decodeHtmlInUrl(String raw) {
        return raw.replace("&#038;", "&").replace("&amp;", "&").replace("&#8211;", "-");
    }

    /** URL 末段当原始文件名（只留安全字符） */
    private static String fileNameOf(String url) {
        try {
            String name = pathOf(url);
            name = name.substring(name.lastIndexOf('/') + 1);
            name = URLDecoder.decode(name, StandardCharsets.UTF_8);
            name = name.replaceAll("[^\\w.\\-\\u4e00-\\u9fa5]", "_");
            return name.isBlank() ? "blogger-file" : name;
        } catch (Exception e) {
            return "blogger-file";
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
