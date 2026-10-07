package cn.xiezitai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

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

    /** DRAFT / PUBLISHED */
    private String status = "DRAFT";

    @Column(length = 50)
    private String author;

    /** SEO */
    @Column(length = 500)
    private String seoKeywords;

    @Column(length = 500)
    private String seoDescription;

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
    public String getSeoKeywords() { return seoKeywords; }
    public void setSeoKeywords(String seoKeywords) { this.seoKeywords = seoKeywords; }
    public String getSeoDescription() { return seoDescription; }
    public void setSeoDescription(String seoDescription) { this.seoDescription = seoDescription; }
    public Long getViewCount() { return viewCount; }
    public void setViewCount(Long viewCount) { this.viewCount = viewCount; }
    public LocalDateTime getPublishedAt() { return publishedAt; }
    public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
