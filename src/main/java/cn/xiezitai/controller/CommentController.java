package cn.xiezitai.controller;

import cn.xiezitai.dto.CommentNode;
import cn.xiezitai.entity.AbuseReport;
import cn.xiezitai.entity.Article;
import cn.xiezitai.entity.Comment;
import cn.xiezitai.entity.User;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.repository.CommentRepository;
import cn.xiezitai.repository.UserRepository;
import cn.xiezitai.service.NotifyService;
import cn.xiezitai.service.SafetyService;
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
    private final SafetyService safety;

    public CommentController(CommentRepository comments, ArticleRepository articles,
                             UserRepository users, NotifyService notify, SafetyService safety) {
        this.comments = comments;
        this.articles = articles;
        this.users = users;
        this.notify = notify;
        this.safety = safety;
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

    /**
     * 公开：已通过审核的评论（两级树形，父评论未过审时其回复自然不展示）。
     *
     * <p>叠加屏蔽过滤：被当前用户屏蔽的人发的评论直接不返回。
     * 父评论被过滤时，它下面的回复也一起消失（回复挂在根评论 id 下，
     * 树形组装只认存活的根，所以自然级联，不需要额外处理）。
     */
    @GetMapping("/api/articles/{slug}/comments")
    public List<CommentNode> listPublic(@PathVariable String slug, Authentication auth) {
        Article a = articles.findBySlug(slug).orElse(null);
        if (a == null) return List.of();
        List<Comment> list = comments.findByArticleIdAndStatusOrderByCreatedAtAsc(a.getId(), "APPROVED");
        java.util.Set<String> blocked = safety.blockedUsernamesFor(auth);
        if (!blocked.isEmpty()) {
            list = list.stream().filter(c -> !blocked.contains(c.getAuthorName())).toList();
        }
        return CommentNode.tree(list);
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
     *
     * <p><b>兼容入口</b>：仅登录用户可举报；实际逻辑已统一委派到
     * {@link SafetyService#report} 落成举报工单（带理由分类、状态、SLA 与处置留痕），
     * 老版本 App 不升级也能继续用。新接口请用 {@code POST /api/reports}，支持理由分类
     * 与文章 / 用户举报，本接口固定按「其他」理由归类。
     */
    @PostMapping("/api/comments/{id}/report")
    public ResponseEntity<?> report(@PathVariable Long id, @RequestBody(required = false) Map<String, String> body,
                                    Authentication auth) {
        User user = safety.currentUser(auth);
        if (user == null || !user.isEnabled()) {
            return ResponseEntity.status(401).body(Map.of("error", "请先登录后再举报"));
        }
        Comment c = comments.findByIdWithArticle(id).orElse(null);
        if (c == null) return ResponseEntity.notFound().build();
        // 保留旧行为：给评论打标记，后台「评论」列表里也能一眼看到被举报过
        if (!c.isReported()) {
            c.setReported(true);
            comments.save(c);
        }
        String reason = body == null ? null : body.get("reason");
        Map<String, Object> r = safety.report(user, AbuseReport.TYPE_COMMENT, id, reason,
                body == null ? null : body.get("detail"));
        if (Boolean.FALSE.equals(r.get("ok"))) return ResponseEntity.badRequest().body(r);
        return ResponseEntity.ok(r);
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
