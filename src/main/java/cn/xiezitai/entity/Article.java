package cn.xiezitai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "articles", indexes = {
        @Index(name = "idx_article_slug", columnList = "slug", unique = true),
        @Index(name = "idx_article_status", columnList = "status")
})
public class Article {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(unique = true, nullable = false, length = 220)
    private String slug;

    /**
     * Markdown 正文（ByteMD 编辑产出）。
     *
     * <p>这里刻意**不加** {@code @Lob}：它会把字段按 CLOB 处理，而 HQL 的 {@code lower()} / {@code like}
     * 不接受 CLOB 类型的参数（Hibernate 6 直接抛
     * {@code Parameter 1 of function 'lower()' has type 'STRING', but argument is of type '...' mapped to 'CLOB'}），
     * 后台「按正文搜索」就没法写。列定义仍是 LONGTEXT，读写走 setString/getString ——
     * 对 Markdown 这种纯文本反而更合适，也省掉一层流式读取。
     */
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    /** 摘要（可由 AI 生成） */
    @Column(length = 1000)
    private String summary;

    /** 封面图（公众号封面尺寸由 AI 生成） */
    @Column(length = 500)
    private String cover;

    /**
     * DRAFT / PENDING / PUBLISHED / REJECTED。
     *
     * <ul>
     *   <li>{@code DRAFT}    草稿（后台手工建文默认）</li>
     *   <li>{@code PENDING}  待审核 —— 普通登录用户提交的文章，只有作者本人和管理员可见</li>
     *   <li>{@code PUBLISHED}已发布（公开可见；必须带 publishedAt 才算真发布，见 ArticleService.isPublished）</li>
     *   <li>{@code REJECTED} 已驳回 —— 审核没过，同样只有作者本人和管理员可见；作者改完可再次提交</li>
     * </ul>
     */
    private String status = "DRAFT";

    @Column(length = 50)
    private String author;

    /** 驳回原因：审核驳回时写给作者看（App 内「我的文章」里展示） */
    @Column(length = 300)
    private String reviewNote;

    /** SEO */
    @Column(length = 500)
    private String seoKeywords;

    @Column(length = 500)
    private String seoDescription;

    /**
     * 标签：归一化后的逗号分隔串（如 {@code "Java,Spring Boot,AI"}）。
     *
     * <p>**只存这个串，不做关联表**：站点标签不需要计数/聚合查询，一列就够了；
     * 「最多 10 个」等规则由 {@link #parseTags(String)} 在写入前执行，
     * 所以这张表里永远不会再出现空白项、重复项或超量标签。
     */
    @Column(length = 300)
    private String tags;

    /** 每篇文章最多多少个标签 */
    public static final int MAX_TAGS = 10;

    private Long viewCount = 0L;

    private LocalDateTime publishedAt;
    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() { this.updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getSlug() { return slug; }
    public void setSlug(String slug) { this.slug = slug; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public String getCover() { return cover; }
    public void setCover(String cover) { this.cover = cover; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getAuthor() { return author; }
    public void setAuthor(String author) { this.author = author; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }
    public String getSeoKeywords() { return seoKeywords; }
    public void setSeoKeywords(String seoKeywords) { this.seoKeywords = seoKeywords; }
    public String getSeoDescription() { return seoDescription; }
    public void setSeoDescription(String seoDescription) { this.seoDescription = seoDescription; }
    public String getTags() { return tags; }
    public void setTags(String tags) { this.tags = tags; }

    /**
     * 解析用户输入的标签串 → 去重、去空白后的列表（不改顺序）。
     *
     * <p>分隔符兼容中英文逗号、顿号、分号和换行 —— 后台用 chip 控件时主要产生英文逗号，
     * 但用户直接在输入框里粘「Java，Spring」这种中文串也照常能拆开。
     *
     * @return 永不为 null；条数可能超过 {@link #MAX_TAGS}，是否放行由调用方（controller）决定
     */
    public static List<String> parseTags(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String t : raw.split("[，,、;；\\n]+")) {
            String v = t.trim();
            if (!v.isEmpty() && !out.contains(v)) out.add(v);
        }
        return out;
    }
    public Long getViewCount() { return viewCount; }
    public void setViewCount(Long viewCount) { this.viewCount = viewCount; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
