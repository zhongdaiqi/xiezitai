package cn.xiezitai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "comments")
public class Comment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "article_id", nullable = false)
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties({"content", "hibernateLazyInitializer", "handler"})
    private Article article;

    @Column(length = 50)
    private String authorName;

    @Column(length = 100)
    private String email;

    @Column(length = 2000)
    private String content;

    /** PENDING / APPROVED / REJECTED */
    private String status = "PENDING";

    /**
     * 层级：一级评论为 null；回复挂在一级评论上（只做两级，回复的回复仍归到同一根）。
     * 用 id 而非对象关联，避免自关联带来的懒加载与序列化麻烦。
     */
    private Long parentId;

    /** 回复对象显示名（「回复 @xxx」），由服务端按被回复评论的 authorName 写入，不接受前端传值 */
    @Column(length = 50)
    private String replyToName;

    /**
     * 是否被用户举报过（App 内长按评论举报）。
     * columnDefinition 带 default：ddl-auto=update 给历史行补 false，避免 null。
     */
    @Column(columnDefinition = "boolean default false")
    private boolean reported = false;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Article getArticle() { return article; }
    public void setArticle(Article article) { this.article = article; }
    public String getAuthorName() { return authorName; }
    public void setAuthorName(String authorName) { this.authorName = authorName; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getReplyToName() { return replyToName; }
    public void setReplyToName(String replyToName) { this.replyToName = replyToName; }
    public boolean isReported() { return reported; }
    public void setReported(boolean reported) { this.reported = reported; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
