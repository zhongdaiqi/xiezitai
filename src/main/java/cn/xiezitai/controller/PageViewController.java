package cn.xiezitai.controller;

import cn.xiezitai.entity.Article;
import cn.xiezitai.entity.PageEntity;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.repository.CommentRepository;
import cn.xiezitai.repository.PageRepository;
import cn.xiezitai.service.ArticleService;
import cn.xiezitai.service.MarkdownService;
import cn.xiezitai.service.NotifyService;
import org.commonmark.node.Node;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.text.TextContentRenderer;
import org.springframework.data.domain.PageRequest;
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

    @GetMapping("/")
    public String home(@RequestParam(name = "page", defaultValue = "1") int page, Model model) {
        org.springframework.data.domain.Page<Article> result =
                articles.findByStatusOrderByPublishedAtDesc("PUBLISHED", PageRequest.of(0, PAGE_SIZE));
        int totalPages = Math.max(1, result.getTotalPages());
        int pageNo = Math.min(Math.max(page, 1), totalPages);
        // 页码越界（含 ?page=999）时按钳制后的页码重新取一页，而不是给空列表
        if (pageNo - 1 != 0) {
            result = articles.findByStatusOrderByPublishedAtDesc("PUBLISHED", PageRequest.of(pageNo - 1, PAGE_SIZE));
        }
        List<Article> list = result.getContent();
        model.addAttribute("articles", list);
        model.addAttribute("htmls", list.stream().collect(java.util.stream.Collectors.toMap(
                Article::getId, a -> md.toHtml(abbrev(a.getContent(), 300)))));
        model.addAttribute("pageNo", pageNo);
        model.addAttribute("totalPages", totalPages);
        model.addAttribute("totalArticles", result.getTotalElements());
        // 页码窗口：最多 5 个，围绕当前页，避免文章多了以后页码条铺满一行
        int winStart = Math.max(1, Math.min(pageNo - 2, totalPages - 4));
        int winEnd = Math.min(totalPages, winStart + 4);
        model.addAttribute("pageNumbers", java.util.stream.IntStream.rangeClosed(winStart, winEnd).boxed().toList());
        return "index";
    }

    @GetMapping("/article/{slug}")
    public String article(@PathVariable String slug, Model model, jakarta.servlet.http.HttpServletRequest request) {
        Article a = articles.findBySlug(slug).orElse(null);
        if (a == null || !ArticleService.isPublished(a)) return "redirect:/";
        articleService.increaseView(a);
        notifyVisit(a, request);
        model.addAttribute("article", a);
        model.addAttribute("contentHtml", md.toHtml(a.getContent()));
        List<cn.xiezitai.dto.CommentNode> threads = cn.xiezitai.dto.CommentNode.tree(
                comments.findByArticleIdAndStatusOrderByCreatedAtAsc(a.getId(), "APPROVED"));
        model.addAttribute("comments", threads);
        model.addAttribute("commentCount", cn.xiezitai.dto.CommentNode.count(threads));
        return "article";
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

    @GetMapping("/page/{slug}")
    public String page(@PathVariable String slug, Model model) {
        PageEntity p = pages.findBySlug(slug).filter(PageEntity::isPublished).orElse(null);
        if (p == null) return "redirect:/";
        model.addAttribute("page", p);
        model.addAttribute("contentHtml", md.toHtml(p.getContent()));
        return "page";
    }

    @GetMapping(value = "/robots.txt", produces = "text/plain")
    @ResponseBody
    public String robots() {
        return """
                User-agent: *
                Allow: /
                Disallow: /admin.html
                Disallow: /api/
                Sitemap: https://xiezitai.cn/sitemap.xml
                """;
    }

    @GetMapping(value = "/sitemap.xml", produces = "application/xml")
    @ResponseBody
    public String sitemap() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n");
        sb.append("  <url><loc>https://xiezitai.cn/</loc></url>\n");
        articles.findByStatusOrderByPublishedAtDesc("PUBLISHED", PageRequest.of(0, 500)).forEach(a ->
                sb.append("  <url><loc>https://xiezitai.cn/article/").append(a.getSlug())
                        .append("</loc><lastmod>").append(a.getUpdatedAt()).append("</lastmod></url>\n"));
        pages.findAll().stream().filter(PageEntity::isPublished).forEach(p ->
                sb.append("  <url><loc>https://xiezitai.cn/page/").append(p.getSlug()).append("</loc></url>\n"));
        sb.append("</urlset>");
        return sb.toString();
    }

    private String abbrev(String text, int max) {
        if (text == null) return "";
        return text.length() > max ? text.substring(0, max) + "……" : text;
    }
}
