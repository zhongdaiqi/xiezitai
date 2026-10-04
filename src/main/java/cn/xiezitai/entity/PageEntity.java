package cn.xiezitai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 自定义页面（关于、友链等） */
@Entity
@Table(name = "xiezitai_pages", indexes = @Index(name = "idx_page_slug", columnList = "slug", unique = true))
public class PageEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(unique = true, nullable = false, length = 220)
    private String slug;

    @Lob
    @Column(columnDefinition = "LONGTEXT")
    private String content;

    private boolean published = true;
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
    public boolean isPublished() { return published; }
    public void setPublished(boolean published) { this.published = published; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
