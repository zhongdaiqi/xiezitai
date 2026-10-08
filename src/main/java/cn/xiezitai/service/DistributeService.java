package cn.xiezitai.service;

import cn.xiezitai.entity.Article;
import cn.xiezitai.entity.BloggerSite;
import cn.xiezitai.entity.CnBlogSite;
import cn.xiezitai.entity.DistRecord;
import cn.xiezitai.entity.WpSite;
import cn.xiezitai.entity.XzSite;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.repository.BloggerSiteRepository;
import cn.xiezitai.repository.CnBlogSiteRepository;
import cn.xiezitai.repository.DistRecordRepository;
import cn.xiezitai.repository.WpSiteRepository;
import cn.xiezitai.repository.XzSiteRepository;
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
 * 文章分发：把本站的一篇文章推到关联的**写字台账号 / 谷歌 Blogger 博客 / WordPress 站点 / 博客园账号**。
 *
 * <p>三条约定（对应需求）：
 * <ol>
 *   <li><b>可多选目标</b>：一次请求可以同时发往多个写字台、Blogger 博客、WP 站点与博客园账号；</li>
 *   <li><b>记住发过谁</b>：每对「文章 × 目标」在 {@code dist_records} 里落一行，
 *       于是「已分发过」的目标可以由用户选「更新之前分发的文章」（用记录里的远端 id 调对方的更新接口）
 *       还是「分发一个新文章」（新建后把这行指向新文章）；</li>
 *   <li><b>原文 / 转载</b>：转载分发会在正文尾部追加「首发于写字台 + 原文链接」，
 *       原文分发则一字不改。</li>
 * </ol>
 *
 * <p>正文里的站内媒体（{@code /media/xxx}）一律**补成绝对地址**再发出去 ——
 * 相对路径到了别人的域名下必然 404。地址前缀取 {@code xiezitai.site-url}。
 *
 * <p><b>正文格式按渠道归一</b>（这是个容易踩的坑，四个渠道的「正确形态」各不相同）：
 * <ul>
 *   <li><b>写字台</b>：开放 API 收的就是 Markdown（对方前台按 Markdown 渲染）→ 无视用户的「转成 HTML」；</li>
 *   <li><b>Blogger</b>：Blogger 只认 HTML，Markdown 源码会被原样当正文贴出来 → 无视用户的「Markdown 原文」，
 *       一律先渲染成 HTML；</li>
 *   <li><b>WP / 博客园</b>：照用户的选项来。</li>
 * </ul>
 */
@Service
public class DistributeService {

    private static final Logger log = LoggerFactory.getLogger(DistributeService.class);

    /** 本站地址（补在 /media/ 与转载链接前）；去尾斜杠 */
    private final String siteUrl;

    private final ArticleRepository articles;
    private final WpSiteRepository wpSites;
    private final CnBlogSiteRepository cnSites;
    private final XzSiteRepository xzSites;
    private final BloggerSiteRepository bloggerSites;
    private final DistRecordRepository records;
    private final WordPressClient wp;
    private final MetaWeblogClient cn;
    private final XiezitaiClient xz;
    private final BloggerClient blogger;
    private final MarkdownService markdown;

    public DistributeService(ArticleRepository articles, WpSiteRepository wpSites,
                             CnBlogSiteRepository cnSites, XzSiteRepository xzSites,
                             BloggerSiteRepository bloggerSites,
                             DistRecordRepository records,
                             WordPressClient wp, MetaWeblogClient cn, XiezitaiClient xz,
                             BloggerClient blogger,
                             MarkdownService markdown,
                             @Value("${xiezitai.site-url:}") String siteUrl) {
        this.articles = articles;
        this.wpSites = wpSites;
        this.cnSites = cnSites;
        this.xzSites = xzSites;
        this.bloggerSites = bloggerSites;
        this.records = records;
        this.wp = wp;
        this.cn = cn;
        this.xz = xz;
        this.blogger = blogger;
        this.markdown = markdown;
        String u = siteUrl == null ? "" : siteUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        this.siteUrl = u;
    }

    /** 本次请求里的一个目标：发到哪、以及对已分发过的目标是「更新」还是「发新文章」 */
    public record DistTarget(String channel, Long targetId, String action) {}

    /**
     * 远端请求的结果。
     *
     * @param id    远端文章 id 的数值形态（WP / 博客园 / 写字台；解析不出来时为 0）
     * @param idStr 远端文章 id 的**权威字符串形态**（Blogger 的长数字串不会丢精度）
     */
    private record RemoteResult(long id, String idStr, String url) {}

    /* ================= 参数归一 ================= */

    public static String normalizeChannel(String c) {
        String v = String.valueOf(c).trim().toLowerCase();
        if ("cnblog".equals(v)) return DistRecord.CHANNEL_CNBLOG;
        if ("xz".equals(v) || "xiezitai".equals(v)) return DistRecord.CHANNEL_XZ;
        if ("blogger".equals(v) || "google".equals(v) || "blogspot".equals(v)) return DistRecord.CHANNEL_BLOGGER;
        return DistRecord.CHANNEL_WP;
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
        for (XzSite s : xzSites.findAll()) {
            boolean ready = notBlank(s.getApiUrl()) && notBlank(s.getApiToken());
            out.add(describe(DistRecord.CHANNEL_XZ, s.getId(), siteName(s), s.getApiUrl(), ready,
                    ready ? "" : "未配置对接密钥，只能浏览、不能分发", mine));
        }
        for (BloggerSite s : bloggerSites.findAll()) {
            boolean ready = s.isHasAuth() && notBlank(s.getBlogId());
            out.add(describe(DistRecord.CHANNEL_BLOGGER, s.getId(), bloggerName(s), s.getUrl(), ready,
                    ready ? "" : "未完成 Google 授权（缺少刷新令牌），请到「Blogger」面板重新关联", mine));
        }
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

    /** 写字台账号的展示名：优先用户自定义名，否则「账号@接口主机」 */
    private static String siteName(XzSite s) {
        if (notBlank(s.getName())) return s.getName();
        String host = XiezitaiClient.origin(s.getApiUrl()).replaceFirst("(?i)^https?://", "");
        String user = notBlank(s.getUsername()) ? s.getUsername() : "";
        if (host.isEmpty()) return user.isEmpty() ? ("写字台 #" + s.getId()) : host;
        return user.isEmpty() ? host : user + "@" + host;
    }

    /** Blogger 博客的展示名：博客名 + Google 账号（同名博客可能分属不同账号，只有名字分不清） */
    private static String bloggerName(BloggerSite s) {
        String blog = notBlank(s.getName()) ? s.getName() : s.getUrl();
        String email = notBlank(s.getGoogleEmail()) ? s.getGoogleEmail() : "";
        if (!notBlank(s.getName()) && !notBlank(s.getUrl())) blog = "Blogger #" + s.getId();
        return email.isEmpty() ? blog : blog + "（" + email + "）";
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
            d.put("remoteId", r.remoteId());
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
                    : switch (String.valueOf(r.getChannel())) {
                        case DistRecord.CHANNEL_CNBLOG -> "博客园";
                        case DistRecord.CHANNEL_XZ -> "写字台";
                        case DistRecord.CHANNEL_BLOGGER -> "Blogger";
                        default -> "WordPress";
                    };
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
        if (buildBody(a, m, f).isBlank()) throw new IllegalStateException("这篇文章正文为空，没什么可分发的");

        List<Map<String, Object>> results = new ArrayList<>();
        int ok = 0, failed = 0;
        for (DistTarget t : targets) {
            Map<String, Object> r = runOne(a, t, m, f);
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

    private Map<String, Object> runOne(Article a, DistTarget t, String mode, String format) {
        String channel = normalizeChannel(t.channel());
        String action = normalizeAction(t.action());
        List<String> warnings = new ArrayList<>();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("channel", channel);
        out.put("targetId", t.targetId());
        out.put("action", action);
        try {
            // 正文格式按渠道归一（详见类注释）：写字台只认 Markdown，Blogger 只认 HTML。
            // 一次分发里混着不同渠道时，这个归一保证「每个渠道收到的都是它真正能渲染的形态」。
            String fmt = format;
            if (DistRecord.CHANNEL_XZ.equals(channel)) {
                fmt = DistRecord.FORMAT_MARKDOWN;
                if (!fmt.equals(format)) warnings.add("写字台正文一律按 Markdown 发送，已忽略「转成 HTML」");
            } else if (DistRecord.CHANNEL_BLOGGER.equals(channel)) {
                fmt = DistRecord.FORMAT_HTML;
                if (!fmt.equals(format)) {
                    warnings.add("Blogger 不渲染 Markdown，正文一律渲染成 HTML 发送，已忽略「Markdown 原文」");
                }
            }
            String body = buildBody(a, mode, fmt);
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
                rr = distToCnBlog(a, s, action, body, fmt, warnings);
            } else if (DistRecord.CHANNEL_XZ.equals(channel)) {
                XzSite s = xzSites.findById(t.targetId())
                        .orElseThrow(() -> new IllegalStateException("写字台账号已不存在（可能已被删除）"));
                if (!notBlank(s.getApiUrl()) || !notBlank(s.getApiToken())) {
                    throw new IllegalStateException("该账号未配置对接密钥，无法分发");
                }
                name = siteName(s);
                url = s.getApiUrl();
                rr = distToXz(a, s, action, body, warnings);
            } else if (DistRecord.CHANNEL_BLOGGER.equals(channel)) {
                BloggerSite s = bloggerSites.findById(t.targetId())
                        .orElseThrow(() -> new IllegalStateException("Blogger 博客关联已不存在（可能已被解除）"));
                if (!s.isHasAuth()) {
                    throw new IllegalStateException("该博客未完成 Google 授权（缺少刷新令牌），请到「Blogger」面板重新关联");
                }
                name = bloggerName(s);
                url = s.getUrl();
                rr = distToBlogger(a, s, action, body, warnings);
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
            // 权威字符串形态（Blogger）；老渠道没有字符串 id 就退回数值形态，null 安全
            out.put("remoteId", notBlank(rr.idStr()) ? rr.idStr() : String.valueOf(rr.id()));
            out.put("remoteUrl", rr.url());
            out.put("updated", "update".equals(action));
            out.put("ok", true);
            saveRecord(a.getId(), channel, t.targetId(), name, url, rr, mode, fmt);
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
            return new RemoteResult(rp.id(), null, rp.link());
        }
        if ("update".equals(action)) {
            warnings.add("此前没有分发记录，「更新」自动改为新发一篇");
        }
        WordPressClient.RemotePost rp = wp.createPost(site, payload);
        return new RemoteResult(rp.id(), null, rp.link());
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
            return new RemoteResult(rec.getRemotePostId(), null,
                    remoteLinkQuietly(site, rec.getRemotePostId(), rec.getRemoteUrl()));
        }
        if ("update".equals(action)) {
            warnings.add("此前没有分发记录，「更新」自动改为新发一篇");
        }
        String newId = cn.newPost(site, struct, true);
        long pid = parseLong(newId, 0L);
        if (pid <= 0) {
            warnings.add("博客园未返回文章 id，已发文但本站无法再对它做「更新」");
            return new RemoteResult(0L, null, "");
        }
        return new RemoteResult(pid, null, remoteLinkQuietly(site, pid, ""));
    }

    /**
     * 发往另一台写字台（走它的开放 API）。
     *
     * <p>与 WP / 博客园不同的是：<b>不传 slug</b>。
     * <ul>
     *   <li>「分发一个新文章」时，同一篇原文的 slug 在同一台写字台上已经存在，
     *       带过去要么撞唯一索引（对方 500），要么被动改成 {@code xxx-2}（链接不直观）；</li>
     *   <li>「更新」走的是远端文章 id，跟 slug 没关系；</li>
     *   <li>让对端按标题自己生成 slug，两边各自唯一、互不干扰。</li>
     * </ul>
     * 远端链接以对方接口返回的 {@code url} 为准（已补成绝对地址）。
     */
    private RemoteResult distToXz(Article a, XzSite site, String action, String body,
                                  List<String> warnings) throws Exception {
        Map<String, String> payload = new LinkedHashMap<>();
        payload.put("title", a.getTitle() == null ? "" : a.getTitle());
        payload.put("content", body);
        if (notBlank(a.getSummary())) payload.put("summary", a.getSummary());

        DistRecord rec = records
                .findByArticleIdAndChannelAndTargetId(a.getId(), DistRecord.CHANNEL_XZ, site.getId())
                .orElse(null);
        if ("update".equals(action) && rec != null && rec.getRemotePostId() != null && rec.getRemotePostId() > 0) {
            XiezitaiClient.RemoteArticle ra = xz.updateArticle(site, rec.getRemotePostId(), payload);
            return new RemoteResult(ra.id() > 0 ? ra.id() : rec.getRemotePostId(), null, ra.url());
        }
        if ("update".equals(action)) {
            warnings.add("此前没有分发记录，「更新」自动改为新发一篇");
        }
        XiezitaiClient.RemoteArticle ra = xz.publishArticle(site, payload);
        if (ra.id() <= 0) {
            warnings.add("对方站点未返回文章 id，已发出但本站无法再对它做「更新」");
        }
        return new RemoteResult(ra.id(), null, ra.url());
    }

    /**
     * 发往谷歌 Blogger（走 Blogger API v3，OAuth Bearer 鉴权）。
     *
     * <p>两个与其它渠道不同的点：
     * <ul>
     *   <li><b>正文是 HTML</b> —— 进来之前 {@link #runOne} 已经把 format 归一到 HTML 了；
     *       标签用的是文章已有的标签。</li>
     *   <li><b>远端 id 用字符串</b> —— Blogger 的文章 id 是长数字串，
     *       {@link DistRecord#remoteId()} 取权威形态，避免数值溢出把「更新」打到别的文章上。</li>
     * </ul>
     * 「更新」走 {@code PATCH}（部分更新），没传的字段不会被清空。
     */
    private RemoteResult distToBlogger(Article a, BloggerSite site, String action, String body,
                                       List<String> warnings) throws Exception {
        String title = a.getTitle() == null ? "" : a.getTitle();
        List<String> labels = Article.parseTags(a.getTags());
        if (labels.size() > Article.MAX_TAGS) labels = labels.subList(0, Article.MAX_TAGS);

        DistRecord rec = records
                .findByArticleIdAndChannelAndTargetId(a.getId(), DistRecord.CHANNEL_BLOGGER, site.getId())
                .orElse(null);
        String remoteId = rec == null ? "" : rec.remoteId();
        if ("update".equals(action) && !remoteId.isEmpty()) {
            BloggerClient.RemotePost rp = blogger.patchPost(site, remoteId, title, body, labels);
            // Blogger 的 PATCH 偶尔只回 id 不回 url；那种情况沿用上次记录的链接
            String url = notBlank(rp.url()) ? rp.url() : (rec.getRemoteUrl() == null ? "" : rec.getRemoteUrl());
            return new RemoteResult(parseLong(rp.id(), 0L), notBlank(rp.id()) ? rp.id() : remoteId, url);
        }
        if ("update".equals(action)) {
            warnings.add("此前没有分发记录，「更新」自动改为新发一篇");
        }
        BloggerClient.RemotePost rp = blogger.createPost(site, title, body, labels);
        if (!notBlank(rp.id())) {
            warnings.add("Blogger 未返回文章 id，已发出但本站无法再对它做「更新」");
        }
        return new RemoteResult(parseLong(rp.id(), 0L), rp.id(), rp.url());
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
        if (notBlank(rr.idStr())) r.setRemotePostIdStr(rr.idStr());
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
