package cn.xiezitai.controller;

import cn.xiezitai.entity.Article;
import cn.xiezitai.entity.User;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.repository.UserRepository;
import cn.xiezitai.service.ArticleService;
import cn.xiezitai.service.NotifyService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 作者自己的文章接口（/api/my/**，SecurityConfig 里整体要求登录）。
 *
 * <p>权限模型（2026-10 定稿）：<b>管理员发布的直接公开；普通登录用户提交的进入待审核（PENDING），
 * 审核通过前只有作者本人（和管理员）可见，驳回后同样只有本人可见，改完可再提交。</b>
 * 管理员对全部文章的增删改仍走 /api/admin/articles，两边互不干扰。
 */
@RestController
@RequestMapping("/api/my")
public class MyArticleController {

    private final ArticleRepository articles;
    private final ArticleService articleService;
    private final UserRepository users;
    private final NotifyService notify;

    public MyArticleController(ArticleRepository articles, ArticleService articleService,
                               UserRepository users, NotifyService notify) {
        this.articles = articles;
        this.articleService = articleService;
        this.users = users;
        this.notify = notify;
    }

    /** 我自己的文章（含待审/驳回），按更新时间倒序 */
    @GetMapping("/articles")
    public Page<Article> mine(@RequestParam(defaultValue = "0") int page,
                              @RequestParam(defaultValue = "20") int size,
                              Authentication auth) {
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 100);
        return articles.findByAuthorOrderByUpdatedAtDesc(auth.getName(), PageRequest.of(p, s,
                Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"))));
    }

    /**
     * 投稿：普通用户 → PENDING（通知管理员审核）；管理员 → PUBLISHED 直接公开。
     * slug 可省略（按标题自动生成）；标签沿用后台的「≤10 个」规则。
     */
    @PostMapping("/articles")
    public ResponseEntity<?> create(@RequestBody Article body, Authentication auth) {
        if (body.getTitle() == null || body.getTitle().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "标题不能为空"));
        }
        String tagErr = tagsViolation(body.getTags());
        if (tagErr != null) return ResponseEntity.badRequest().body(Map.of("error", tagErr));
        if (body.getSlug() != null && !body.getSlug().isBlank() && articles.existsBySlug(body.getSlug())) {
            return ResponseEntity.status(409).body(Map.of("error", "slug 已被其他文章占用：" + body.getSlug()));
        }
        User user = users.findByUsername(auth.getName()).orElse(null);
        if (user == null || !user.isEnabled()) {
            return ResponseEntity.status(401).body(Map.of("error", "账号不可用，请重新登录"));
        }
        boolean admin = "ADMIN".equals(user.getRole());

        Article a = new Article();
        a.setTitle(body.getTitle().trim());
        if (body.getSlug() != null && !body.getSlug().isBlank()) a.setSlug(body.getSlug());
        else a.setSlug(articleService.uniqueSlug(body.getTitle()));
        a.setContent(body.getContent());
        a.setSummary(body.getSummary());
        a.setCover(body.getCover());
        a.setSeoKeywords(body.getSeoKeywords());
        a.setSeoDescription(body.getSeoDescription());
        if (body.getTags() != null) a.setTags(String.join(",", Article.parseTags(body.getTags())));
        a.setAuthor(user.getUsername());
        if (admin) {
            a.setStatus("PUBLISHED");
            a.setPublishedAt(LocalDateTime.now());
        } else {
            // 普通用户请求里的 status 一律不采纳：投稿只有「待审核」一条路，防止绕过审核
            a.setStatus("PENDING");
        }
        Article saved = articles.save(a);
        if (admin) {
            articleService.publishNotify(saved, user.getUsername());
        } else {
            notify.notifyEvent("article", "**写字台新投稿待审核**\n> [" + saved.getTitle() + "]"
                    + "(/api/my/articles)\n> 作者: " + user.getUsername()
                    + "\n> 请到管理后台「文章」里审核（筛选状态=待审核）");
        }
        return ResponseEntity.ok(saved);
    }

    /**
     * 改自己的文章：正文/标题等照改，状态一律回 {@code PENDING} 重新审核
     * （已发布文章被作者改动后也重新进审，防止「先过审再偷偷改内容」）。
     * 管理员改自己的不受此限（可保留原状态或指定 PUBLISHED）。
     */
    @PutMapping("/articles/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Article body, Authentication auth) {
        Article a = articles.findById(id).orElse(null);
        if (a == null) return ResponseEntity.notFound().build();
        if (!auth.getName().equals(a.getAuthor())) {
            return ResponseEntity.status(403).body(Map.of("error", "只能编辑自己的文章"));
        }
        String tagErr = tagsViolation(body.getTags());
        if (tagErr != null) return ResponseEntity.badRequest().body(Map.of("error", tagErr));
        String newSlug = body.getSlug();
        if (newSlug != null && !newSlug.isBlank() && !newSlug.equals(a.getSlug())
                && articles.existsBySlug(newSlug)) {
            return ResponseEntity.status(409).body(Map.of("error", "slug 已被其他文章占用：" + newSlug));
        }
        User user = users.findByUsername(auth.getName()).orElse(null);
        boolean admin = user != null && "ADMIN".equals(user.getRole());

        if (body.getTitle() != null && !body.getTitle().isBlank()) a.setTitle(body.getTitle().trim());
        if (body.getSlug() != null && !body.getSlug().isBlank()) a.setSlug(body.getSlug());
        if (body.getContent() != null) a.setContent(body.getContent());
        if (body.getSummary() != null) a.setSummary(body.getSummary());
        if (body.getCover() != null) a.setCover(body.getCover());
        if (body.getSeoKeywords() != null) a.setSeoKeywords(body.getSeoKeywords());
        if (body.getSeoDescription() != null) a.setSeoDescription(body.getSeoDescription());
        if (body.getTags() != null) a.setTags(String.join(",", Article.parseTags(body.getTags())));

        if (admin) {
            if ("PUBLISHED".equals(body.getStatus()) && !"PUBLISHED".equals(a.getStatus())) {
                a.setStatus("PUBLISHED");
                a.setPublishedAt(LocalDateTime.now());
            }
        } else {
            a.setStatus("PENDING");
            a.setReviewNote(null);
        }
        Article saved = articles.save(a);
        if ("PUBLISHED".equals(saved.getStatus()) && body.getStatus() != null
                && "PUBLISHED".equals(body.getStatus())) {
            articleService.publishNotify(saved, user.getUsername());
        }
        return ResponseEntity.ok(saved);
    }

    /** 删自己的文章 */
    @DeleteMapping("/articles/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id, Authentication auth) {
        Article a = articles.findById(id).orElse(null);
        if (a == null) return ResponseEntity.notFound().build();
        if (!auth.getName().equals(a.getAuthor())) {
            return ResponseEntity.status(403).body(Map.of("error", "只能删除自己的文章"));
        }
        articles.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    private String tagsViolation(String rawTags) {
        if (rawTags == null) return null;
        int n = Article.parseTags(rawTags).size();
        if (n > Article.MAX_TAGS) {
            return "标签最多 " + Article.MAX_TAGS + " 个，当前有 " + n + " 个，请删减后再提交";
        }
        return null;
    }
}
