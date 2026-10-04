package cn.xiezitai.dto;

import cn.xiezitai.entity.Comment;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评论树节点（对外展示用，避免把 Comment 实体的 article 关联一起序列化）。
 * 两级结构：{@code replies} 为空表示一级评论；回复的 parentId 指向根评论 id。
 */
public class CommentNode {

    private Long id;
    private Long parentId;
    private String authorName;
    private String replyToName;
    private String content;
    private String status;
    private LocalDateTime createdAt;
    private List<CommentNode> replies = new ArrayList<>();

    public static CommentNode of(Comment c) {
        CommentNode n = new CommentNode();
        n.id = c.getId();
        n.parentId = c.getParentId();
        n.authorName = c.getAuthorName();
        n.replyToName = c.getReplyToName();
        n.content = c.getContent();
        n.status = c.getStatus();
        n.createdAt = c.getCreatedAt();
        return n;
    }

    /**
     * 组装两级评论树。
     * 入参应为「已通过审核」的评论（按 createdAt 升序），因此父评论未过审时，其回复自然找不到根而被丢弃——
     * 无需额外判断，审核策略天然生效。孤儿回复（父已被删除）同样被忽略。
     * 排序：一级评论最新在前；同一根下的回复按时间正序（对话顺序）。
     */
    public static List<CommentNode> tree(List<Comment> approved) {
        Map<Long, CommentNode> roots = new LinkedHashMap<>();
        for (Comment c : approved) {
            if (c.getParentId() == null) roots.put(c.getId(), of(c));
        }
        for (Comment c : approved) {
            if (c.getParentId() == null) continue;
            CommentNode root = roots.get(c.getParentId());
            if (root != null) root.getReplies().add(of(c));
        }
        List<CommentNode> out = new ArrayList<>(roots.values());
        java.util.Collections.reverse(out);   // 最新的一级评论排前面
        return out;
    }

    /** 已审核评论总数（含回复），用于「评论 N」计数 */
    public static int count(List<CommentNode> nodes) {
        int n = 0;
        for (CommentNode node : nodes) n += 1 + node.getReplies().size();
        return n;
    }

    public Long getId() { return id; }
    public Long getParentId() { return parentId; }
    public String getAuthorName() { return authorName; }
    public String getReplyToName() { return replyToName; }
    public String getContent() { return content; }
    public String getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public List<CommentNode> getReplies() { return replies; }
}
