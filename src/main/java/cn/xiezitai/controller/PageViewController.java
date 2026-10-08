package cn.xiezitai.controller;

import cn.xiezitai.entity.Article;
import cn.xiezitai.entity.PageEntity;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.repository.CommentRepository;
import cn.xiezitai.repository.PageRepository;
import cn.xiezitai.service.ArticleService;
import cn.xiezitai.service.MarkdownService;
import cn.xiezitai.service.NotifyService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.util.List;

/** SEO 友好的服务端渲染页面 */
@Controller
public class PageViewController {

    private final ArticleRepository articles;
    private final PageRepository pages;
    private final CommentRepository comments;
    private final MarkdownService md;
    private final ArticleService articleService;
    private final NotifyService notify;

    public PageViewController(ArticleRepository articles, PageRepository pages,
                              CommentRepository comments, MarkdownService md,
                              ArticleService articleService, NotifyService notify) {
        this.articles = articles;
        this.pages = pages;
        this.comments = comments;
        this.md = md;
        this.articleService = articleService;
        this.notify = notify;
    }

    /** 首页每页文章数（SEO：翻页用真实链接 + rel prev/next，不用 JS 拼） */
    private static final int PAGE_SIZE = 10;

    /** 搜索关键词上限：超长关键词直接截断，别把一整段话喂进 like */
    private static final int MAX_KEYWORD = 60;

    /** 搜索结果的排序：发布时间倒序，同刻按 id 倒序 —— 与首页默认列表同口径，翻页才稳定 */
    private static final Sort LIST_SORT = Sort.by(Sort.Order.desc("publishedAt"), Sort.Order.desc("id"));

    /** 站点根地址：把 /media/xxx 这类相对封面拼成绝对 URL（OG 标签按规范要求绝对地址） */
    @Value("${xiezitai.site-url:https://xiezitai.cn}")
    private String siteUrl;

    /**
     * 首页：默认列已发布文章；带 {@code ?q=} 时变成站内搜索（标题 / 摘要 / 正文 / 标签，只搜已发布）。
     *
     * <p>搜索与翻页是叠加的：关键词会一路带进翻页链接、canonical、rel prev/next，
     * 否则用户点「下一页」搜索条件就丢了。搜索页用 {@code noindex} —— 站内搜索结果页
     * 属于典型的「低质重复页」，收录了反而稀释首页权重（SEO 常规做法）。
     */
    @GetMapping("/")
    public String home(@RequestParam(name = "page", defaultValue = "1") int page,
                       @RequestParam(name = "q", required = false) String q, Model model) {
        String kw = normalizeKeyword(q);
        boolean searching = !kw.isEmpty();

        org.springframework.data.domain.Page<Article> result = searching
                ? articles.searchPublished(kw, PageRequest.of(0, PAGE_SIZE, LIST_SORT))
                : articles.findByStatusOrderByPublishedAtDesc("PUBLISHED", PageRequest.of(0, PAGE_SIZE));
        int totalPages = Math.max(1, result.getTotalPages());
        int pageNo = Math.min(Math.max(page, 1), totalPages);
        // 页码越界（含 ?page=999）时按钳制后的页码重新取一页，而不是给空列表
        if (pageNo - 1 != 0) {
            result = searching
                    ? articles.searchPublished(kw, PageRequest.of(pageNo - 1, PAGE_SIZE, LIST_SORT))
                    : articles.findByStatusOrderByPublishedAtDesc("PUBLISHED", PageRequest.of(pageNo - 1, PAGE_SIZE));
        }
        List<Article> list = result.getContent();
        model.addAttribute("articles", list);
        model.addAttribute("navPages", navPages());
        model.addAttribute("htmls", list.stream().collect(java.util.stream.Collectors.toMap(
                Article::getId, a -> md.toHtml(abbrev(a.getContent(), 300)))));
        model.addAttribute("pageNo", pageNo);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalArticles", result.getTotalElements());
        model.addAttribute("q", kw);
        model.addAttribute("searching", searching);
        // 首页 / 翻页链接交给模板拼：模板里没法把「?q=」有条件地拼在 @{...} 前面，
        // 索性在这里把两种前缀算好，模板只用 ${} 拼接（关键词已经编码过，直接进 href 是安全的）
        String enc = kw.isEmpty() ? "" : java.net.URLEncoder.encode(kw, java.nio.charset.StandardCharsets.UTF_8);
        model.addAttribute("homeUrl", kw.isEmpty() ? "/" : "/?q=" + enc);
        model.addAttribute("pageUrlPrefix", kw.isEmpty() ? "/?page=" : "/?q=" + enc + "&page=");
        // 页码窗口：最多 5 个，围绕当前页，避免文章多了以后页码条铺满一行
        int winStart = Math.max(1, Math.min(pageNo - 2, totalPages - 4));
        int winEnd = Math.min(totalPages, winStart + 4);
        model.addAttribute("pageNumbers", java.util.stream.IntStream.rangeClosed(winStart, winEnd).boxed().toList());
        model.addAttribute("siteHost", siteHost());
        return "index";
    }

    /**
     * 搜索关键词归一：去首尾空白、把换行/制表压成空格（GET 参数里塞换行只会污染 like）、超长截断。
     * 返回空串表示「不搜索」，模板与仓库层都用「空串 = 不过滤」这一个口径。
     */
    private String normalizeKeyword(String q) {
        if (q == null) return "";
        String s = q.replaceAll("[\\r\\n\\t]+", " ").trim();
        return s.length() > MAX_KEYWORD ? s.substring(0, MAX_KEYWORD) : s;
    }

    /**
     * 文章与页面的**规范地址**：直接挂在根级（{@code /why-self-host}、{@code /links}）。
     *
     * <p>这条兜底路由有两个坑，改之前务必先看清楚：
     * <ol>
     *   <li><b>必须显式放过根级静态文件</b>。{@code @RequestMapping} 的优先级（order 0）高于静态资源
     *       处理器（{@code LOWEST_PRECEDENCE - 1}），少了负向断言的话 {@code /admin.html}、
     *       {@code /favicon.svg} 会被当成 slug 吃掉 —— 后台直接打不开。
     *       <b>以后往 {@code static/} 根目录加文件，记得把文件名补进下面这串断言。</b></li>
     *   <li><b>变量正则里不能写 {@code /}</b>。PathPattern 会把正则里的 {@code /} 当路径分隔符，
     *       直接抛 {@code PatternParseException}；好在 PathPattern 本身就保证变量只吃**一个**路径段，
     *       所以 {@code .+} 已经够用（{@code /a/b} 这种多段路径压根不会命中这条路由）。</li>
     * </ol>
     */
    @GetMapping("/{slug:(?!admin\\.html$|index\\.html$|favicon\\.svg$|favicon\\.ico$|robots\\.txt$|sitemap\\.xml$|error$).+}")
    public String rootSlug(@PathVariable String slug, Model model, jakarta.servlet.http.HttpServletRequest request) {
        String s = slug.replaceAll("/+$", "");
        Article a = findArticleFlexible(s).filter(ArticleService::isPublished).orElse(null);
        if (a != null) return renderArticle(a, model, request);

        PageEntity p = findPageFlexible(s).filter(PageEntity::isPublished).orElse(null);
        if (p != null) return renderPage(p, model);

        // 未命中的根级路径：与历史行为保持一致，回首页而不是抛 404（老站有很多 /xxx 的旧引用）
        return "redirect:/";
    }

    /**
     * 历史地址 {@code /article/{slug}} → <b>301</b> 到根级规范地址。
     *
     * <p>用 301（永久搬家）而不是 302：老链接、搜索引擎里已有的收录、外部转载过的引用都能平滑过渡，
     * 权重跟着走到新地址。找不到就回首页 —— 与改造前的行为完全一致。
     */
    @GetMapping("/article/{slug}")
    public org.springframework.http.ResponseEntity<?> legacyArticle(@PathVariable String slug) {
        Article a = findArticleFlexible(slug).filter(ArticleService::isPublished).orElse(null);
        return movedPermanently(a == null ? "/" : publicPath(a.getSlug()));
    }

    /** 历史地址 {@code /page/{slug}} → 301 到根级规范地址，语义同 {@link #legacyArticle} */
    @GetMapping("/page/{slug}")
    public org.springframework.http.ResponseEntity<?> legacyPage(@PathVariable String slug) {
        PageEntity p = findPageFlexible(slug).filter(PageEntity::isPublished).orElse(null);
        return movedPermanently(p == null ? "/" : publicPath(p.getSlug()));
    }

    /**
     * 301 + {@code Location}。
     *
     * <p>Location 用 {@link java.net.URI} 构造（而不是交给 RedirectView 去拼）：路径里如果已经是
     * {@code %e4%bd%a0...} 形态的老 slug，被容器再编一层会变成 {@code %25e4...}，
     * 直接被 Spring 的 StrictHttpFirewall 拦成 400。
     */
    private org.springframework.http.ResponseEntity<?> movedPermanently(String location) {
        return org.springframework.http.ResponseEntity
                .status(org.springframework.http.HttpStatus.MOVED_PERMANENTLY)
                .location(java.net.URI.create(location))
                .build();
    }

    /** 文章详情渲染（根级规范地址与其它入口共用一份逻辑） */
    private String renderArticle(Article a, Model model, jakarta.servlet.http.HttpServletRequest request) {
        articleService.increaseView(a);
        notifyVisit(a, request);
        model.addAttribute("article", a);
        // 标签（逗号分隔串）拆成列表给模板 —— Thymeleaf 对单个字符串没有 split 表达式，别在模板里硬拆
        model.addAttribute("tagList", cn.xiezitai.entity.Article.parseTags(a.getTags()));
        model.addAttribute("navPages", navPages());
        model.addAttribute("contentHtml", md.toHtml(a.getContent()));
        List<cn.xiezitai.dto.CommentNode> threads = cn.xiezitai.dto.CommentNode.tree(
                comments.findByArticleIdAndStatusOrderByCreatedAtAsc(a.getId(), "APPROVED"));
        model.addAttribute("comments", threads);
        model.addAttribute("commentCount", cn.xiezitai.dto.CommentNode.count(threads));
        // 封面同时作为社交分享图（og:image）
        model.addAttribute("ogImage", absoluteUrl(a.getCover()));
        model.addAttribute("canonicalUrl", siteRoot() + publicPath(a.getSlug()));
        model.addAttribute("siteHost", siteHost());
        return "article";
    }

    /** 自定义页面渲染 */
    private String renderPage(PageEntity p, Model model) {
        model.addAttribute("page", p);
        model.addAttribute("navPages", navPages());
        model.addAttribute("contentHtml", md.toHtml(p.getContent()));
        model.addAttribute("canonicalUrl", siteRoot() + publicPath(p.getSlug()));
        return "page";
    }

    /**
     * slug → 可安全放进 {@code Location} 头 / sitemap 的站内路径。
     * 编码规则的实现与理由集中在 {@link cn.xiezitai.service.SlugUtil#publicPath(String)}，
     * 分发尾注、开放 API、sitemap 三处共用同一套，避免各编各的。
     */
    public static String publicPath(String slug) {
        return cn.xiezitai.service.SlugUtil.publicPath(slug);
    }

    /** 封面可能是外部地址（AI 生成）或站内相对地址（/media/xxx）；后者补上站点根，拼成绝对 URL */
    private String absoluteUrl(String url) {
        if (url == null || url.isBlank()) return "";
        if (url.startsWith("http://") || url.startsWith("https://") || url.startsWith("//")) return url;
        return siteRoot() + (url.startsWith("/") ? url : "/" + url);
    }

    /**
     * 站点根地址（去掉结尾斜杠）。
     * robots / sitemap / og:image 三处共用同一个出处，改域名只需改配置 xiezitai.site-url，
     * 不再散落硬编码。
     */
    private String siteRoot() {
        String base = siteUrl == null ? "" : siteUrl.trim();
        return base.replaceAll("/+$", "");
    }

    /** 站点域名（只取主机名，用于页脚/副标题这类展示文案，不显示 https:// 前缀） */
    private String siteHost() {
        String root = siteRoot();
        try {
            String host = java.net.URI.create(root).getHost();
            return host == null ? root.replaceAll("^https?://", "") : host;
        } catch (RuntimeException e) {
            return root.replaceAll("^https?://", "");
        }
    }

    /** 文章访问来源通知（可通过后台 notify.visit 开关关闭） */
    private void notifyVisit(Article a, jakarta.servlet.http.HttpServletRequest request) {
        String referer = request.getHeader("Referer");
        String source = referer == null || referer.isBlank() ? "直接访问" : referer;
        String ua = request.getHeader("User-Agent");
        if (ua != null && ua.length() > 120) ua = ua.substring(0, 120);
        notify.notifyEvent("visit", "**写字台文章访问**\n> 文章: " + a.getTitle()
                + "\n> 来源: " + source
                + "\n> IP: " + clientIp(request)
                + "\n> UA: " + (ua == null ? "-" : ua));
    }

    private String clientIp(jakarta.servlet.http.HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return request.getRemoteAddr();
    }

    /**
     * slug 多候选匹配：URL 路径里的 {@code %xx} 会被容器先解码一次，于是
     * <ul>
     *   <li>库里存真中文（后台手输）→ 解码后的中文直接命中；</li>
     *   <li>库里存 WP 风格的 {@code %e4%bd%a0...} 字面串（WP 对中文标题就这么生成 slug）→
     *       解码后变成中文、查不到，文章/页面会跳回首页 —— 把原串再 URL 编码回去就能命中；</li>
     *   <li>双重编码的极端情况 → 再解一次码兜底。</li>
     * </ul>
     * 忽略大小写：Java {@link java.net.URLEncoder} 产出的 %XX 是大写，WP 存的是小写。
     */
    private java.util.Optional<Article> findArticleFlexible(String rawSlug) {
        for (String candidate : slugCandidates(rawSlug)) {
            java.util.Optional<Article> hit = articles.findBySlugIgnoreCase(candidate);
            if (hit.isPresent()) return hit;
        }
        return java.util.Optional.empty();
    }

    private java.util.Optional<PageEntity> findPageFlexible(String rawSlug) {
        for (String candidate : slugCandidates(rawSlug)) {
            java.util.Optional<PageEntity> hit = pages.findBySlugIgnoreCase(candidate);
            if (hit.isPresent()) return hit;
        }
        return java.util.Optional.empty();
    }

    /** 候选顺序：原样 → 再解码一次 → 再编码回去（含小写形态） */
    public static java.util.List<String> slugCandidates(String rawSlug) {
        java.util.LinkedHashSet<String> cands = new java.util.LinkedHashSet<>();
        if (rawSlug == null || rawSlug.isBlank()) return List.of();
        cands.add(rawSlug);
        try {
            String dec = java.net.URLDecoder.decode(rawSlug, java.nio.charset.StandardCharsets.UTF_8);
            if (!dec.equals(rawSlug)) cands.add(dec);
        } catch (Exception ignore) { /* 本来就不是合法 % 序列，跳过 */ }
        try {
            String enc = java.net.URLEncoder.encode(rawSlug, java.nio.charset.StandardCharsets.UTF_8);
            if (!enc.equals(rawSlug)) {
                cands.add(enc);
                cands.add(enc.toLowerCase(java.util.Locale.ROOT));
            }
        } catch (Exception ignore) { /* 不可能出现 */ }
        return List.copyOf(cands);
    }

    /**
     * 顶部导航里的自定义页面：只列已发布、按创建顺序。
     * 不加上这个的话，初始化生成的「关于 / 友链」页面在站内没有任何入口。
     */
    private List<PageEntity> navPages() {
        return pages.findAll(Sort.by(Sort.Direction.ASC, "id")).stream()
                .filter(PageEntity::isPublished)
                .toList();
    }

    @GetMapping(value = "/robots.txt", produces = "text/plain")
    @ResponseBody
    public String robots() {
        return "User-agent: *\n"
                + "Allow: /\n"
                + "Disallow: /admin.html\n"
                + "Disallow: /api/\n"
                + "Sitemap: " + siteRoot() + "/sitemap.xml\n";
    }

    @GetMapping(value = "/sitemap.xml", produces = "application/xml")
    @ResponseBody
    public String sitemap() {
        String root = siteRoot();
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        sb.append("  <url><loc>").append(root).append("/</loc></url>\n");
        // 文章/页面都挂在根级；slug 里的中文走 publicPath 编码（已是 %xx 形态的老 slug 原样保留）
        articles.findByStatusOrderByPublishedAtDesc("PUBLISHED", PageRequest.of(0, 500)).forEach(a ->
                sb.append("  <url><loc>").append(root).append(publicPath(a.getSlug()))
                        .append("</loc><lastmod>").append(a.getUpdatedAt()).append("</lastmod></url>\n"));
        pages.findAll().stream().filter(PageEntity::isPublished).forEach(p ->
                sb.append("  <url><loc>").append(root).append(publicPath(p.getSlug())).append("</loc></url>\n"));
        sb.append("</urlset>");
        return sb.toString();
    }

    private String abbrev(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) + "……" : text;
    }
}
