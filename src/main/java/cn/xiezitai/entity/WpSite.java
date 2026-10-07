package cn.xiezitai.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 关联的 WordPress 站点（用于从 WP 导入文章）。
 *
 * <p>一个站一行：网址 + 应用密码（Application Password）。Token 属敏感信息：
 * <ul>
 *   <li>只在「关联站点」时经 HTTPS 写进本站数据库；</li>
 *   <li>所有对外序列化都标了 {@link JsonProperty.Access#WRITE_ONLY} ——
 *       接口永远不回传明文，前端只显示「已配置 / 未配置」；</li>
 *   <li>绝不写进代码、配置文件或 git 仓库。</li>
 * </ul>
 */
@Entity
@Table(name = "wp_sites", indexes = {
        @Index(name = "idx_wp_site_url", columnList = "siteUrl", unique = true)
})
public class WpSite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 展示名（默认取主机名） */
    @Column(length = 200)
    private String name;

    /** 规范化后的站点网址（无尾斜杠，如 https://example.com） */
    @Column(name = "siteUrl", nullable = false, length = 500)
    private String url;

    /** WP 应用密码对应的用户名（Basic Auth 用；留空则匿名访问公开文章） */
    @Column(length = 100)
    private String username;

    /** WP Application Password（敏感：WRITE_ONLY，永不序列化出去） */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(length = 500)
    private String apiToken;

    /**
     * 只给前端一个「是否已配置 Token」的布尔值（列表里显示已配置/未配置），
     * 明文永远不出接口。无对应字段，JPA 字段访问模式会忽略它。
     */
    @JsonProperty("hasToken")
    public boolean isHasToken() {
        return apiToken != null && !apiToken.isBlank();
    }

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime lastSyncAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getApiToken() { return apiToken; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(LocalDateTime lastSyncAt) { this.lastSyncAt = lastSyncAt; }
}
