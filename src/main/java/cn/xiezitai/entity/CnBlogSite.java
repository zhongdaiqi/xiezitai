package cn.xiezitai.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 关联的博客园账号（MetaWeblog XML-RPC 接口，用于从博客园导入文章）。
 *
 * <p>一个账号一行：RPC 接口地址 + 用户名 + 对接密钥。密钥属敏感信息：
 * <ul>
 *   <li>只在「关联账号」时经 HTTPS 写进本站数据库；</li>
 *   <li>所有对外序列化都标了 {@link JsonProperty.Access#WRITE_ONLY} ——
 *       接口永远不回传明文，前端只显示「已配置 / 未配置」；</li>
 *   <li>绝不写进代码、配置文件或 git 仓库。</li>
 * </ul>
 */
@Entity
@Table(name = "cn_sites", indexes = {
        @Index(name = "idx_cn_site_url", columnList = "siteUrl", unique = true)
})
public class CnBlogSite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 展示名（默认取用户名或博客园账号名） */
    @Column(length = 200)
    private String name;

    /** MetaWeblog RPC 接口地址（如 https://rpc.cnblogs.com/metaweblog/{user}，无尾斜杠） */
    @Column(name = "siteUrl", nullable = false, length = 500)
    private String url;

    /** 博客园账号名（也是发布人名；RPC 调用的 username 参数） */
    @Column(length = 100)
    private String username;

    /** 对接密钥（MetaWeblog password 参数；敏感：WRITE_ONLY，永不序列化出去） */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(length = 500)
    private String appKey;

    /**
     * 只给前端一个「是否已配置密钥」的布尔值（列表里显示已配置/未配置），
     * 明文永远不出接口。无对应字段，JPA 字段访问模式会忽略它。
     */
    @JsonProperty("hasToken")
    public boolean isHasToken() {
        return appKey != null && !appKey.isBlank();
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
    public String getAppKey() { return appKey; }
    public void setAppKey(String appKey) { this.appKey = appKey; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(LocalDateTime lastSyncAt) { this.lastSyncAt = lastSyncAt; }
}
