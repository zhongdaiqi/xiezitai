package cn.xiezitai.controller;

import cn.xiezitai.entity.PageEntity;
import cn.xiezitai.repository.PageRepository;
import cn.xiezitai.service.ArticleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class PageController {

    private final PageRepository pages;
    private final ArticleService articleService;

    public PageController(PageRepository pages, ArticleService articleService) {
        this.pages = pages;
        this.articleService = articleService;
    }

    /* 公开 */
    @GetMapping("/api/pages/{slug}")
    public ResponseEntity<PageEntity> getPublic(@PathVariable String slug) {
        PageEntity p = pages.findBySlug(slug).filter(PageEntity::isPublished).orElse(null);
        return p == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(p);
    }

    /* 管理 */
    @GetMapping("/api/admin/pages")
    public List<PageEntity> list() {
        return pages.findAll();
    }

    @PostMapping("/api/admin/pages")
    public ResponseEntity<?> create(@RequestBody PageEntity body) {
        PageEntity p = new PageEntity();
        String err = apply(p, body, null);                 // null = 新建（没有需要排除的自身 id）
        if (err != null) return ResponseEntity.status(409).body(Map.of("error", err));
        // 纯中文标题 slugify 后都变成 post → 必须查**页面表**去重，否则第二个就撞唯一索引 500
        if (p.getSlug() == null || p.getSlug().isBlank()) {
            p.setSlug(articleService.uniqueSlug(p.getTitle(), pages::existsBySlug));
        }
        return ResponseEntity.ok(pages.save(p));
    }

    @PutMapping("/api/admin/pages/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody PageEntity body) {
        PageEntity p = pages.findById(id).orElse(null);
        if (p == null) return ResponseEntity.notFound().build();
        String err = apply(p, body, id);                   // 改自己时要把自己从「已占用」里排除
        if (err != null) return ResponseEntity.status(409).body(Map.of("error", err));
        return ResponseEntity.ok(pages.save(p));
    }

    @DeleteMapping("/api/admin/pages/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        pages.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    /**
     * 把请求体写进实体。
     *
     * @param selfId 更新时传自己的 id（slug 撞到自己不算冲突）；新建时传 {@code null}
     * @return 出错文案（如 slug 被别的页面占用），{@code null} 表示没问题
     */
    private String apply(PageEntity p, PageEntity body, Long selfId) {
        if (body.getTitle() != null) p.setTitle(body.getTitle());
        if (body.getSlug() != null && !body.getSlug().isBlank()) {
            String slug = body.getSlug().trim();
            boolean taken = pages.findBySlug(slug)
                    .filter(other -> selfId == null || !other.getId().equals(selfId))
                    .isPresent();
            if (taken) return "slug「" + slug + "」已被其他页面使用，请换一个";
            p.setSlug(slug);
        }
        if (body.getContent() != null) p.setContent(body.getContent());
        p.setPublished(body.isPublished());
        return null;
    }
}
