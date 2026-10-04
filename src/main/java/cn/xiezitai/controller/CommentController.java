package cn.xiezitai.controller;

import cn.xiezitai.entity.Article;
import cn.xiezitai.entity.Comment;
import cn.xiezitai.entity.User;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.repository.CommentRepository;
import cn.xiezitai.repository.UserRepository;
import cn.xiezitai.service.NotifyService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
public class CommentController {

    private final CommentRepository comments;
    private final ArticleRepository articles;
    private final UserRepository users;
    private final NotifyService notify;

    public CommentController(CommentRepository comments, ArticleRepository articles,
                             UserRepository users, NotifyService notify) {
        this.comments = comments;
        this.articles = articles;
        this.users = users;
        this.notify = notify;
    }

    /**
     * 发表评论：<b>仅限已登录用户</b>（匿名被 SecurityConfig 拦成 401）。
     * 作者名/邮箱一律取自登录账号，不接受请求体里的 authorName/email —— 否则任何登录用户都能冒充他人。
     * 提交后仍为 PENDING，需管理员审核通过才公开展示。
     */
    @PostMapping("/api/articles/{slug}/comments")
    public ResponseEntity<?> add(@PathVariable String slug, @RequestBody Map<String, String> body,
                                 Authentication auth) {
        if (auth == null || auth.getName() == null) {
            return ResponseEntity.status(401).body(Map.of("error", "请先登录后再评论"));
        }
        User user = users.findByUsername(auth.getName()).orElse(null);
        if (user == null || !user.isEnabled()) {
            return ResponseEntity.status(401).body(Map.of("error", "账号不可用，请重新登录"));
        }
        Article a = articles.findBySlug(slug).orElse(null);
        if (a == null || !"PUBLISHED".equals(a.getStatus())) return ResponseEntity.notFound().build();
        String content = body.getOrDefault("content", "").trim();
        if (content.isEmpty() || content.length() > 2000) {
            return ResponseEntity.badRequest().body(Map.of("error", "评论内容为空或超长"));
        }
        Comment c = new Comment();
        c.setArticle(a);
        c.setAuthorName(clip(user.getUsername(), 50));   // 服务端权威取名
        c.setEmail(clip(user.getEmail(), 100));
        c.setContent(content);
        c.setStatus("PENDING");
        comments.save(c);
        notify.notifyEvent("comment", "**写字台新评论待审**\n> 文章: " + a.getTitle()
                + "\n> 评论人: " + c.getAuthorName());
        return ResponseEntity.ok(Map.of("message", "评论已提交，待审核后展示"));
    }

    /** 公开：已通过审核的评论 */
    @GetMapping("/api/articles/{slug}/comments")
    public List<Comment> listPublic(@PathVariable String slug) {
        Article a = articles.findBySlug(slug).orElse(null);
        if (a == null) return List.of();
        return comments.findByArticleIdAndStatusOrderByCreatedAtDesc(a.getId(), "APPROVED");
    }

    /** 管理：全部评论 */
    @GetMapping("/api/admin/comments")
    public List<Comment> listAll() {
        return comments.findAllByOrderByCreatedAtDesc();
    }

    @PutMapping("/api/admin/comments/{id}/status")
    public ResponseEntity<?> setStatus(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Comment c = comments.findById(id).orElse(null);
        if (c == null) return ResponseEntity.notFound().build();
        c.setStatus(body.getOrDefault("status", "APPROVED"));
        comments.save(c);
        return ResponseEntity.ok(Map.of("message", "已更新"));
    }

    @DeleteMapping("/api/admin/comments/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        comments.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    private String clip(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
