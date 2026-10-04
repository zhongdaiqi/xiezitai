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
    public PageEntity create(@RequestBody PageEntity body) {
        PageEntity p = new PageEntity();
        apply(p, body);
        if (p.getSlug() == null || p.getSlug().isBlank()) p.setSlug(articleService.uniqueSlug(p.getTitle()));
        return pages.save(p);
    }

    @PutMapping("/api/admin/pages/{id}")
    public ResponseEntity<PageEntity> update(@PathVariable Long id, @RequestBody PageEntity body) {
        PageEntity p = pages.findById(id).orElse(null);
        if (p == null) return ResponseEntity.notFound().build();
        apply(p, body);
        return ResponseEntity.ok(pages.save(p));
    }

    @DeleteMapping("/api/admin/pages/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        pages.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    private void apply(PageEntity p, PageEntity body) {
        if (body.getTitle() != null) p.setTitle(body.getTitle());
        if (body.getSlug() != null && !body.getSlug().isBlank()) p.setSlug(body.getSlug());
        if (body.getContent() != null) p.setContent(body.getContent());
        p.setPublished(body.isPublished());
    }
}
