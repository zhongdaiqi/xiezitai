package cn.xiezitai.controller;

import cn.xiezitai.entity.Article;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.service.AiService;
import cn.xiezitai.service.ArticleService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class ArticleController {

    private final ArticleRepository articles;
    private final ArticleService articleService;
    private final AiService ai;

    public ArticleController(ArticleRepository articles, ArticleService articleService, AiService ai) {
        this.articles = articles;
        this.articleService = articleService;
        this.ai = ai;
    }

    /* ================= 公开接口 ================= */

    @GetMapping("/articles")
    public Page<Article> listPublic(@RequestParam(defaultValue = "0") int page,
                                    @RequestParam(defaultValue = "10") int size) {
        return articles.findByStatusOrderByPublishedAtDesc("PUBLISHED", PageRequest.of(page, size));
    }

    @GetMapping("/articles/{slug}")
    public ResponseEntity<Article> getPublic(@PathVariable String slug) {
        Article a = articles.findBySlug(slug).orElse(null);
        if (a == null || !ArticleService.isPublished(a)) return ResponseEntity.notFound().build();
        articleService.increaseView(a);
        return ResponseEntity.ok(a);
    }

    /* ================= 管理接口 ================= */

    @GetMapping("/admin/articles")
    public Page<Article> listAdmin(@RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "10") int size) {
        return articles.findAllByOrderByUpdatedAtDesc(PageRequest.of(page, size));
    }

    @PostMapping("/admin/articles")
    public Article create(@RequestBody Article body, Authentication auth) {
        Article a = new Article();
        apply(a, body);
        a.setSlug(body.getSlug() == null || body.getSlug().isBlank()
                ? articleService.uniqueSlug(body.getTitle()) : body.getSlug());
        a.setAuthor(auth.getName());
        if ("PUBLISHED".equals(a.getStatus())) a.setPublishedAt(LocalDateTime.now());
        Article saved = articles.save(a);
        if ("PUBLISHED".equals(saved.getStatus())) articleService.publishNotify(saved, auth.getName());
        return saved;
    }

    @PutMapping("/admin/articles/{id}")
    public ResponseEntity<Article> update(@PathVariable Long id, @RequestBody Article body, Authentication auth) {
        Article a = articles.findById(id).orElse(null);
        if (a == null) return ResponseEntity.notFound().build();
        boolean wasPublished = "PUBLISHED".equals(a.getStatus());
        apply(a, body);
        if ("PUBLISHED".equals(a.getStatus()) && a.getPublishedAt() == null) a.setPublishedAt(LocalDateTime.now());
        Article saved = articles.save(a);
        if (!wasPublished && "PUBLISHED".equals(saved.getStatus())) articleService.publishNotify(saved, auth.getName());
        return ResponseEntity.ok(saved);
    }

    @DeleteMapping("/admin/articles/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        articles.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    /** AI 润色 / 纠错 */
    @PostMapping("/admin/ai/polish")
    public ResponseEntity<Map<String, String>> polish(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(Map.of("result", ai.polish(body.getOrDefault("text", ""))));
    }

    /** AI 摘要 */
    @PostMapping("/admin/ai/summary")
    public ResponseEntity<Map<String, String>> summary(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(Map.of("result", ai.summarize(body.getOrDefault("text", ""))));
    }

    /** AI 封面图（公众号尺寸 900x383） */
    @PostMapping("/admin/ai/cover")
    public ResponseEntity<Map<String, String>> cover(@RequestBody Map<String, String> body) {
        return ResponseEntity.ok(Map.of("coverUrl", ai.generateCover(
                body.getOrDefault("prompt", body.getOrDefault("title", "")), 900, 383)));
    }

    private void apply(Article a, Article body) {
        if (body.getTitle() != null) a.setTitle(body.getTitle());
        if (body.getSlug() != null && !body.getSlug().isBlank()) a.setSlug(body.getSlug());
        if (body.getContent() != null) a.setContent(body.getContent());
        if (body.getSummary() != null) a.setSummary(body.getSummary());
        if (body.getCover() != null) a.setCover(body.getCover());
        if (body.getStatus() != null) a.setStatus(body.getStatus());
        if (body.getSeoKeywords() != null) a.setSeoKeywords(body.getSeoKeywords());
        if (body.getSeoDescription() != null) a.setSeoDescription(body.getSeoDescription());
    }
}
