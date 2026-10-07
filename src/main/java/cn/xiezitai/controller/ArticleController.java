package cn.xiezitai.controller;

import cn.xiezitai.entity.Article;
import cn.xiezitai.entity.FileEntity;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.service.AiService;
import cn.xiezitai.service.AiTextCleaner;
import cn.xiezitai.service.ArticleService;
import cn.xiezitai.service.MediaStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(ArticleController.class);

    private final ArticleRepository articles;
    private final ArticleService articleService;
    private final AiService ai;
    private final MediaStoreService media;

    public ArticleController(ArticleRepository articles, ArticleService articleService, AiService ai,
                             MediaStoreService media) {
        this.articles = articles;
        this.articleService = articleService;
        this.ai = ai;
        this.media = media;
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

    /**
     * 后台文章列表：分页 + 关键词搜索 + 状态筛选。
     *
     * <p>不传 {@code q} / {@code status} 时等价于「全部文章按更新时间倒序」，与旧行为一致
     * —— e2e 与外部脚本仍在用 {@code ?size=100} 这种裸调用。
     */
    @GetMapping("/admin/articles")
    public Page<Article> listAdmin(@RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "10") int size,
                                   @RequestParam(required = false) String q,
                                   @RequestParam(required = false) String status) {
        String kw = q == null ? "" : q.trim();
        String st = status == null ? "" : status.trim();
        if ("ALL".equalsIgnoreCase(st)) st = "";
        int p = Math.max(page, 0);
        int s = Math.min(Math.max(size, 1), 200);   // 兜住 size=100000 那种一把捞库的调用
        return articles.searchAdmin(kw, st,
                PageRequest.of(p, s, Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"))));
    }

    /**
     * 后台按 id 取单篇。
     *
     * <p>列表分页之后，点「编辑」的那篇**不一定**在已加载的页里（也可能被搜索结果过滤掉），
     * 前端不能只靠列表缓存拼数据，需要一个明确的单篇入口。
     */
    @GetMapping("/admin/articles/{id}")
    public ResponseEntity<Article> getAdmin(@PathVariable Long id) {
        return articles.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build());
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

    /**
     * AI 优化标题：返回**建议标题**（不落库，由前端确认后再由用户保存）。
     *
     * <p>返回的 result 可能带「（AI 功能未启用…）」前缀 —— 那是 {@link AiService#chat} 的降级文案，
     * 前端必须当消息展示，绝不能拿去覆盖用户已经写好的标题。
     */
    @PostMapping("/admin/ai/title")
    public ResponseEntity<Map<String, String>> aiTitle(@RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "");
        String text = body.getOrDefault("text", "");
        String raw = ai.optimizeTitle(title, text);
        return ResponseEntity.ok(Map.of("result", AiTextCleaner.title(raw, title)));
    }

    /** AI 提取 SEO 关键词：返回「A, B, C」，与 articles.seo_keywords 同格式 */
    @PostMapping("/admin/ai/seo-keywords")
    public ResponseEntity<Map<String, String>> aiSeoKeywords(@RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "");
        String text = body.getOrDefault("text", "");
        String raw = ai.seoKeywords(title, text);
        return ResponseEntity.ok(Map.of("result", AiTextCleaner.keywords(raw)));
    }

    /** AI 提取 SEO 描述（Meta Description），与 articles.seo_description 同格式 */
    @PostMapping("/admin/ai/seo-description")
    public ResponseEntity<Map<String, String>> aiSeoDescription(@RequestBody Map<String, String> body) {
        String title = body.getOrDefault("title", "");
        String text = body.getOrDefault("text", "");
        String raw = ai.seoDescription(title, text);
        return ResponseEntity.ok(Map.of("result", AiTextCleaner.seoDescription(raw)));
    }

    /**
     * AI 封面图（公众号尺寸 900x383）。
     *
     * <p>生成结果**一律落到本站媒体库**：上游给的是第三方临时链接，过期就会被清理，
     * 直接存进文章封面字段迟早变裂图。落盘后同时进媒体库列表（复用/删除都走同一套）。
     */
    @PostMapping("/admin/ai/cover")
    public ResponseEntity<Map<String, String>> cover(@RequestBody Map<String, String> body, Authentication auth) {
        String prompt = body.getOrDefault("prompt", body.getOrDefault("title", ""));
        AiService.GeneratedImage img = ai.generateCover(prompt, 900, 383);
        if (img.isEmpty()) {
            return ResponseEntity.status(502).body(Map.of(
                    "error", "AI 封面生成失败：请检查后台的大模型配置，或稍后重试"));
        }
        try {
            FileEntity fe = media.storeImage(img.data(),
                    "ai-cover-" + System.currentTimeMillis(), auth.getName());
            log.info("AI 封面已存到站内媒体库: {}", fe.getStoredName());
            return ResponseEntity.ok(Map.of("coverUrl", "/media/" + fe.getStoredName()));
        } catch (MediaStoreService.NotAnImageException e) {
            // 上游给的地址能下、但下来不是图片（限流/鉴权失败时常常回一个 HTML 错误页）
            log.warn("AI 封面下载内容不是图片: {}", e.getMessage());
            return ResponseEntity.status(502).body(Map.of(
                    "error", "AI 封面生成失败：上游返回的不是图片（可能被限流或鉴权失败），请稍后重试"));
        } catch (Exception e) {
            log.warn("AI 封面保存到媒体库失败: {}", e.getMessage());
            return ResponseEntity.status(500).body(Map.of(
                    "error", "封面已生成但保存到服务器失败：" + e.getMessage()));
        }
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
