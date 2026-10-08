package cn.xiezitai.service;

import cn.xiezitai.entity.Article;
import cn.xiezitai.entity.CnBlogSite;
import cn.xiezitai.entity.DistRecord;
import cn.xiezitai.entity.WpSite;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.repository.CnBlogSiteRepository;
import cn.xiezitai.repository.DistRecordRepository;
import cn.xiezitai.repository.WpSiteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文章分发：把本站的一篇文章推到关联的 WordPress 站点 / 博客园账号。
 *
 * <p>三条约定（对应需求）：
 * <ol>
 *   <li><b>可多选目标</b>：一次请求可以同时发往多个 WP 站点与多个博客园账号；</li>
 *   <li><b>记住发过谁</b>：每对「文章 × 目标」在 {@code dist_records} 里落一行，
 *       于是「已分发过」的目标可以由用户选「更新之前分发的文章」（用记录里的远端 id 调对方的更新接口）
 *       还是「分发一个新文章」（新建后把这行指向新文章）；</li>
 *   <li><b>原文 / 转载</b>：转载分发会在正文尾部追加「首发于写字台 + 原文链接」，
 *       原文分发则一字不改。</li>
 * </ol>
 *
 * <p>正文里的站内媒体（{@code /media/xxx}）一律**补成绝对地址**再发出去 ——
 * 相对路径到了别人的域名下必然 404。地址前缀取 {@code xiezitai.site-url}。
 */
@Service
public class DistributeService {

    private static final Logger log = LoggerFactory.getLogger(DistributeService.class);

    /** 本站地址（补在 /media/ 与转载链接前）；去尾斜杠 */
    private final String siteUrl;

    private final ArticleRepository articles;
    private final WpSiteRepository wpSites;
    private final CnBlogSiteRepository cnSites;
    private final DistRecordRepository records;
    private final WordPressClient wp;
    private final MetaWeblogClient cn;
    private final MarkdownService markdown;

    public DistributeService(ArticleRepository articles, WpSiteRepository wpSites,
                             CnBlogSiteRepository cnSites, DistRecordRepository records,
                             WordPressClient wp, MetaWeblogClient cn, MarkdownService markdown,
                             @Value("${xiezitai.site-url:}") String siteUrl) {
        this.articles = articles;
        this.wpSites = wpSites;
        this.cnSites = cnSites;
        this.records = records;
        this.wp = wp;
        this.cn = cn;
        this.markdown = markdown;
        String u = siteUrl == null ? "" : siteUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        this.siteUrl = u;
    }

    /** 本次请求里的一个目标：发到哪、以及对已分发过的目标是「更新」还是「发新文章」 */
    public record DistTarget(String channel, Long targetId, String action) {}

    /** 远端请求的结果 */
    private record RemoteResult(long id, String url) {}

    /* ================= 参数归一 ================= */

    public static String normalizeChannel(String c) {
        return "cnblog".equalsIgnoreCase(String.valueOf(c).trim())
                ? DistRecord.CHANNEL_CNBLOG : DistRecord.CHANNEL_WP;
    }

    public static String normalizeMode(String m) {
        return "repost".equalsIgnoreCase(String.valueOf(m).trim())
                ? DistRecord.MODE_REPOST : DistRecord.MODE_ORIGINAL;
    }

    public static String normalizeFormat(String f) {
        return "html".equalsIgnoreCase(String.valueOf(f).trim())
                ? DistRecord.FORMAT_HTML : DistRecord.FORMAT_MARKDOWN;
    }

    /** 前端传的 action：update=更新记录里那篇，create（默认）=发新文章 */
    public static String normalizeAction(String a) {
        return "update".equalsIgnoreCase(String.valueOf(a).trim()) ? "update" : "create";
    }

    public String siteUrl() { return siteUrl; }

    /** 文章在本站的公开链接（根级 slug；中文/编码形态的处理统一走 {@link SlugUtil}） */
    public String articleUrl(String slug) {
        return SlugUtil.publicUrl(siteUrl, slug);
    }

    /* ================= 目标清单（含已分发状态） ================= */

    /** 所有可选目标 + 该文章在每个目标上的分发状态；前端据此渲染勾选框 */
    public List<Map<String, Object>> targetsFor(Long articleId) {
        Map<String, DistRecord> mine = new HashMap<>();
        for (DistRecord r : records.findByArticleIdOrderByIdAsc(articleId)) {
            mine.put(key(r.getChannel(), r.getTargetId()), r);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (WpSite s : wpSites.findAll()) {
            String name = s.getName() == null || s.getName().isBlank() ? s.getUrl() : s.getName();
            boolean ready = notBlank(s.getUsername()) && notBlank(s.getApiToken());
            out.add(describe(DistRecord.CHANNEL_WP, s.getId(), name, s.getUrl(), ready,
                    ready ? "" : "未配置用户名 / 应用密码，只能读公开文章、不能发文", mine));
        }
        for (CnBlogSite s : cnSites.findAll()) {
            String name = s.getName() == null || s.getName().isBlank() ? s.getUrl() : s.getName();
            boolean ready = notBlank(s.getUsername()) && notBlank(s.getAppKey());
            out.add(describe(DistRecord.CHANNEL_CNBLOG, s.getId(), name, s.getUrl(), ready,
                    ready ? "" : "未配置账号名 / 对接密钥，无法发文", mine));
        }
        return out;
    }

    private Map<String, Object> describe(String channel, Long id, String name, String url,
                                         boolean ready, String hint, Map<String, DistRecord> mine) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("channel", channel);
        m.put("targetId", id);
        m.put("name", name);
        m.put("url", url);
        m.put("ready", ready);
        m.put("readyHint", hint);
        DistRecord r = mine.get(key(channel, id));
        if (r == null) {
            m.put("dist", null);
        } else {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("remotePostId", r.getRemotePostId());
            d.put("remoteUrl", r.getRemoteUrl());
            d.put("mode", r.getMode());
            d.put("format", r.getFormat());
            d.put("distCount", r.getDistCount());
            d.put("firstDistAt", r.getFirstDistAt());
            d.put("lastDistAt", r.getLastDistAt());
            m.put("dist", d);
        }
        return m;
    }

    /** 批量取「文章 → 已分发目标名」映射（后台文章列表的徽标用） */
    public Map<Long, List<String>> badgeMap(List<Long> articleIds) {
        Map<Long, List<String>> out = new HashMap<>();
        if (articleIds == null || articleIds.isEmpty()) return out;
        for (DistRecord r : records.findByArticleIdIn(articleIds)) {
            String label = notBlank(r.getTargetName()) ? r.getTargetName()
                    : (DistRecord.CHANNEL_CNBLOG.equals(r.getChannel()) ? "博客园" : "WordPress");
            out.computeIfAbsent(r.getArticleId(), k -> new ArrayList<>()).add(label);
        }
        return out;
    }

    /* ================= 正文组装 ================= */

    /**
     * 组装要发出去的正文。
     *
     * @param mode   original=原文 / repost=尾部加转载出处
     * @param format markdown=原样发 Markdown（WP 的 Markdown 插件、博客园 [Markdown] 分类都认）/ html=先渲染成 HTML
     */
    public String buildBody(Article a, String mode, String format) {
        String m = normalizeMode(mode);
        String f = normalizeFormat(format);
        String body = a.getContent() == null ? "" : a.getContent();
        if (DistRecord.FORMAT_HTML.equals(f)) body = markdown.toHtml(body);
        body = absolutizeMedia(body, siteUrl);
        if (DistRecord.MODE_REPOST.equals(m)) body = body + repostFooter(a, f);
        return body;
    }

    /** 转载尾注：说明首发于本站并给出原文链接（Markdown / HTML 两版） */
    String repostFooter(Article a, String format) {
        String title = a.getTitle() == null ? "" : a.getTitle();
        String url = articleUrl(a.getSlug());
        String home = siteUrl.isEmpty() ? "/" : siteUrl;
        if (DistRecord.FORMAT_HTML.equals(format)) {
            return "\n<hr>\n<blockquote><p>本文由 <a href=\"" + home + "\">写字台</a> 首发，原文链接：<a href=\""
                    + url + "\">" + escapeHtml(title) + "</a><br>转载请注明出处。</p></blockquote>\n";
        }
        return "\n\n---\n\n> 本文由 [写字台](" + home + ") 首发，原文链接：[" + title + "](" + url + ")\n"
                + "> 转载请注明出处。\n";
    }

    /**
     * 把正文里的站内资源改成绝对地址。
     *
     * <p>只认「紧跟在 {@code ](} / {@code src="} / {@code href="} / {@code poster="} 后面的 /media/」，
     * 所以外链、锚点、以及本来就已经是绝对地址的链接都不会被误伤。
     */
    public static String absolutizeMedia(String body, String base) {
        if (body == null || body.isEmpty() || base == null || base.isBlank()) return body;
        String[][] pairs = {
                {"](/media/", "](" + base + "/media/"},
                {"src=\"/media/", "src=\"" + base + "/media/"},
                {"src='/media/", "src='" + base + "/media/"},
                {"href=\"/media/", "href=\"" + base + "/media/"},
                {"href='/media/", "href='" + base + "/media/"},
                {"poster=\"/media/", "poster=\"" + base + "/media/"},
                {"data-src=\"/media/", "data-src=\"" + base + "/media/"},
        };
        String out = body;
        for (String[] p : pairs) out = out.replace(p[0], p[1]);
        return out;
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /* ================= 执行分发 ================= */

    /**
     * 把一篇文章发往若干目标。
     *
     * @return {ok, failed, results:[{channel,targetId,targetName,action,ok,remotePostId,remoteUrl,message,warnings}]}
     */
    public Map<String, Object> distribute(Long articleId, String mode, String format,
                                          List<DistTarget> targets, String actor) {
        Article a = articles.findById(articleId)
                .orElseThrow(() -> new IllegalStateException("文章不存在（可能已被删除）"));
        String m = normalizeMode(mode);
        String f = normalizeFormat(format);
        String body = buildBody(a, m, f);
        if (body.isBlank()) throw new IllegalStateException("这篇文章正文为空，没什么可分发的");

        List<Map<String, Object>> results = new ArrayList<>();
        int ok = 0, failed = 0;
        for (DistTarget t : targets) {
            Map<String, Object> r = runOne(a, t, m, f, body);
            results.add(r);
            if (Boolean.TRUE.equals(r.get("ok"))) ok++; else failed++;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("failed", failed);
        out.put("mode", m);
        out.put("format", f);
        out.put("results", results);
        log.info("分发完成 article={} by={} mode={} format={} 成功{} 失败{}", articleId, actor, m, f, ok, failed);
        return out;
    }

    private Map<String, Object> runOne(Article a, DistTarget t, String mode, String format, String body) {
        String channel = normalizeChannel(t.channel());
        String action = normalizeAction(t.action());
        List<String> warnings = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channel", channel);
        out.put("targetId", t.targetId());
        out.put("action", action);
        try {
            RemoteResult rr;
            String name, url;
            if (DistRecord.CHANNEL_CNBLOG.equals(channel)) {
                CnBlogSite s = cnSites.findById(t.targetId())
                        .orElseThrow(() -> new IllegalStateException("博客园账号已不存在（可能已被删除）"));
                if (!notBlank(s.getUsername()) || !notBlank(s.getAppKey())) {
                    throw new IllegalStateException("该账号未配置账号名 / 对接密钥，无法发文");
                }
                name = notBlank(s.getName()) ? s.getName() : s.getUrl();
                url = s.getUrl();
                rr = distToCnBlog(a, s, action, body, format, warnings);
            } else {
                WpSite s = wpSites.findById(t.targetId())
                        .orElseThrow(() -> new IllegalStateException("WordPress 站点已不存在（可能已被删除）"));
                if (!notBlank(s.getUsername()) || !notBlank(s.getApiToken())) {
                    throw new IllegalStateException("该站点未配置用户名 / 应用密码，无法发文");
                }
                name = notBlank(s.getName()) ? s.getName() : s.getUrl();
                url = s.getUrl();
                rr = distToWp(a, s, action, body, warnings);
            }
            out.put("targetName", name);
            out.put("remotePostId", rr.id());
            out.put("remoteUrl", rr.url());
            out.put("updated", "update".equals(action));
            out.put("ok", true);
            saveRecord(a.getId(), channel, t.targetId(), name, url, rr, mode, format);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", friendly(e));
            log.warn("分发失败 article={} channel={} target={}: {}", a.getId(), channel, t.targetId(), e.getMessage());
        }
        out.put("warnings", warnings);
        return out;
    }

    private RemoteResult distToWp(Article a, WpSite site, String action, String body, List<String> warnings)
            throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("title", a.getTitle() == null ? "" : a.getTitle());
        payload.put("content", body);
        payload.put("status", "publish");
        if (notBlank(a.getSummary())) payload.put("excerpt", a.getSummary());
        // 只在 slug 是纯 ASCII 时才带过去：WP 对非 ASCII slug 的处理是百分号编码入库，
        // 那种形态再导回来还得归一一次（详见 WordPressImportService.normalizeWpSlug），
        // 与其埋这个雷，不如让 WP 自己按标题生成。
        if (a.getSlug() != null && a.getSlug().matches("[A-Za-z0-9\\-_]+")) payload.put("slug", a.getSlug());
        List<Long> tagIds = wp.ensureTagIds(site, Article.parseTags(a.getTags()), warnings);
        if (!tagIds.isEmpty()) payload.put("tags", tagIds);

        DistRecord rec = records
                .findByArticleIdAndChannelAndTargetId(a.getId(), DistRecord.CHANNEL_WP, site.getId())
                .orElse(null);
        if ("update".equals(action) && rec != null && rec.getRemotePostId() != null) {
            WordPressClient.RemotePost rp = wp.updatePost(site, rec.getRemotePostId(), payload);
            return new RemoteResult(rp.id(), rp.link());
        }
        if ("update".equals(action)) {
            warnings.add("此前没有分发记录，「更新」自动改为新发一篇");
        }
        WordPressClient.RemotePost rp = wp.createPost(site, payload);
        return new RemoteResult(rp.id(), rp.link());
    }

    private RemoteResult distToCnBlog(Article a, CnBlogSite site, String action, String body,
                                      String format, List<String> warnings) throws Exception {
        Map<String, Object> struct = new LinkedHashMap<>();
        struct.put("title", a.getTitle() == null ? "" : a.getTitle());
        struct.put("description", body);
        // 博客园靠分类里的 [Markdown] 判断「正文是 Markdown、请按 Markdown 渲染」，
        // 少了它会把 Markdown 源码当 HTML 原样贴出来（代码块、表格全废）。
        List<String> categories = new ArrayList<>();
        if (DistRecord.FORMAT_MARKDOWN.equals(format)) categories.add("[Markdown]");
        struct.put("categories", categories);
        if (notBlank(a.getTags())) struct.put("mt_keywords", a.getTags());
        if (notBlank(a.getSummary())) struct.put("mt_excerpt", a.getSummary());
        struct.put("post_type", DistRecord.FORMAT_MARKDOWN.equals(format) ? "markdown" : "post");

        DistRecord rec = records
                .findByArticleIdAndChannelAndTargetId(a.getId(), DistRecord.CHANNEL_CNBLOG, site.getId())
                .orElse(null);
        if ("update".equals(action) && rec != null && rec.getRemotePostId() != null) {
            cn.editPost(site, rec.getRemotePostId(), struct, true);
            return new RemoteResult(rec.getRemotePostId(), remoteLinkQuietly(site, rec.getRemotePostId(), rec.getRemoteUrl()));
        }
        if ("update".equals(action)) {
            warnings.add("此前没有分发记录，「更新」自动改为新发一篇");
        }
        String newId = cn.newPost(site, struct, true);
        long pid = parseLong(newId, 0L);
        if (pid <= 0) {
            warnings.add("博客园未返回文章 id，已发文但本站无法再对它做「更新」");
            return new RemoteResult(0L, "");
        }
        return new RemoteResult(pid, remoteLinkQuietly(site, pid, ""));
    }

    /** 取远端链接失败不算分发失败（文章其实已经发出去了），退回旧值或空 */
    private String remoteLinkQuietly(CnBlogSite site, long postId, String fallback) {
        try {
            String link = cn.getPost(site, postId).link();
            return notBlank(link) ? link : (fallback == null ? "" : fallback);
        } catch (Exception e) {
            log.info("博客园取远端链接失败 postId={}: {}", postId, e.getMessage());
            return fallback == null ? "" : fallback;
        }
    }

    /** 落记录：文章 × 目标 唯一，已存在就更新（远端 id 可能因为「发新文章」而被换掉） */
    private void saveRecord(Long articleId, String channel, Long targetId, String targetName, String targetUrl,
                            RemoteResult rr, String mode, String format) {
        DistRecord r = records.findByArticleIdAndChannelAndTargetId(articleId, channel, targetId)
                .orElseGet(DistRecord::new);
        boolean fresh = r.getId() == null;
        LocalDateTime now = LocalDateTime.now();
        r.setArticleId(articleId);
        r.setChannel(channel);
        r.setTargetId(targetId);
        r.setTargetName(targetName);
        r.setTargetUrl(targetUrl);
        if (rr.id() > 0) r.setRemotePostId(rr.id());
        if (notBlank(rr.url())) r.setRemoteUrl(rr.url());
        r.setMode(mode);
        r.setFormat(format);
        r.setDistCount(fresh || r.getDistCount() == null ? 1 : r.getDistCount() + 1);
        if (fresh) r.setFirstDistAt(now);
        r.setLastDistAt(now);
        records.save(r);
    }

    /* ================= 小工具 ================= */

    private static String key(String channel, Long targetId) { return channel + ":" + targetId; }

    private static boolean notBlank(String s) { return s != null && !s.isBlank(); }

    private static long parseLong(String s, long def) {
        try { return Long.parseLong(String.valueOf(s).trim()); } catch (Exception e) { return def; }
    }

    /** 把底层异常翻成用户能看懂的一句话（去掉 Java 类名前缀之类的噪音） */
    private static String friendly(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) return e.getClass().getSimpleName();
        return msg.length() > 300 ? msg.substring(0, 300) : msg;
    }
}
