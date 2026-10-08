package cn.xiezitai.controller;

import cn.xiezitai.dto.CommentNode;
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
     * 发表评论 / 回复：<b>仅限已登录用户</b>（匿名被 SecurityConfig 拦成 401）。
     * 作者名/邮箱一律取自登录账号，不接受请求体里的 authorName/email —— 否则任何登录用户都能冒充他人。
     * 可选 {@code parentId} 表示回复；只做两级，回复的回复仍归到同一根评论下，
     * replyToName 由服务端按被回复评论的作者写入（前端传值一律忽略）。
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

        Long parentId = parseId(body.get("parentId"));
        String replyToName = null;
        if (parentId != null) {
            Comment parent = comments.findByIdWithArticle(parentId).orElse(null);
            if (parent == null || parent.getArticle() == null
                    || !parent.getArticle().getId().equals(a.getId())) {
                return ResponseEntity.badRequest().body(Map.of("error", "被回复的评论不存在"));
            }
            replyToName = clip(parent.getAuthorName(), 50);
            // 只保留两级：回复一条回复时，仍挂到它所属的一级评论下
            if (parent.getParentId() != null) parentId = parent.getParentId();
        }

        Comment c = new Comment();
        c.setArticle(a);
        c.setAuthorName(clip(user.getUsername(), 50));   // 服务端权威取名
        c.setEmail(clip(user.getEmail(), 100));
        c.setContent(content);
        c.setStatus("PENDING");
        c.setParentId(parentId);
        c.setReplyToName(replyToName);
        comments.save(c);
        notify.notifyEvent("comment", "**写字台新评论待审**\n> 文章: " + a.getTitle()
                + "\n> 评论人: " + c.getAuthorName()
                + (replyToName != null ? "\n> 回复: " + replyToName : ""));
        return ResponseEntity.ok(Map.of("message", parentId == null ? "评论已提交，待审核后展示" : "回复已提交，待审核后展示"));
    }

    /** 公开：已通过审核的评论（两级树形，父评论未过审时其回复自然不展示） */
    @GetMapping("/api/articles/{slug}/comments")
    public List<CommentNode> listPublic(@PathVariable String slug) {
        Article a = articles.findBySlug(slug).orElse(null);
        if (a == null) return List.of();
        return CommentNode.tree(
                comments.findByArticleIdAndStatusOrderByCreatedAtAsc(a.getId(), "APPROVED"));
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

    /** 删除评论：连同其下的回复一起删掉，避免留下孤儿回复 */
    @DeleteMapping("/api/admin/comments/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        List<Comment> children = comments.findByParentId(id);
        if (!children.isEmpty()) comments.deleteAll(children);
        comments.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "已删除，同时移除 " + children.size() + " 条回复"));
    }

    /**
     * 举报评论（App Store 1.2 UGC 要求具备举报机制）。
     * 仅登录用户可举报；同一评论重复举报幂等（置位 + 通知管理员）。
     */
    @PostMapping("/api/comments/{id}/report")
    public ResponseEntity<?> report(@PathVariable Long id, Authentication auth) {
        if (auth == null || auth.getName() == null) {
            return ResponseEntity.status(401).body(Map.of("error", "请先登录后再举报"));
        }
        User user = users.findByUsername(auth.getName()).orElse(null);
        if (user == null || !user.isEnabled()) {
            return ResponseEntity.status(401).body(Map.of("error", "账号不可用，请重新登录"));
        }
        Comment c = comments.findByIdWithArticle(id).orElse(null);
        if (c == null) return ResponseEntity.notFound().build();
        if (!c.isReported()) {
            c.setReported(true);
            comments.save(c);
            notify.notifyEvent("comment", "**写字台评论被举报**\n> 评论人: " + c.getAuthorName()
                    + "\n> 文章: " + (c.getArticle() != null ? c.getArticle().getTitle() : "?")
                    + "\n> 举报人: " + user.getUsername()
                    + "\n> 请到后台「评论」处理（拒绝或删除）");
        }
        return ResponseEntity.ok(Map.of("message", "举报已提交，管理员会尽快处理"));
    }

    /**
     * 用户删除评论：作者本人或管理员可删；连同其下的回复一起删掉，避免孤儿回复。
     * 评论实体只存 authorName 快照，用名字比对判断归属。
     */
    @DeleteMapping("/api/comments/{id}")
    public ResponseEntity<?> deleteAsUser(@PathVariable Long id, Authentication auth) {
        if (auth == null || auth.getName() == null) {
            return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        }
        User user = users.findByUsername(auth.getName()).orElse(null);
        if (user == null) return ResponseEntity.status(401).build();
        Comment c = comments.findById(id).orElse(null);
        if (c == null) return ResponseEntity.notFound().build();
        boolean owner = user.getUsername().equals(c.getAuthorName());
        if (!owner && !"ADMIN".equals(user.getRole())) {
            return ResponseEntity.status(403).body(Map.of("error", "只能删除自己的评论"));
        }
        List<Comment> children = comments.findByParentId(id);
        if (!children.isEmpty()) comments.deleteAll(children);
        comments.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "已删除，同时移除 " + children.size() + " 条回复"));
    }

    private Long parseId(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String clip(String s, int max) {
        if (s == null) return null;
        return s.length() > max ? s.substring(0, max) : s;
    }
}
