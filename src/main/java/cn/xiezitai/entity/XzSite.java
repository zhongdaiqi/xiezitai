package cn.xiezitai.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 关联的「写字台」账号（用于从另一个写字台站点导入文章）。
 *
 * <p>一个账号一行：接口地址 + 写字台账号 + 对接密钥（API Token）。同一个站点可以关联多个账号，
 * 所以唯一键是「接口地址 + 账号」而不是只有接口地址。
 *
 * <p>对接密钥属敏感信息：
 * <ul>
 *   <li>只在「关联账号」时经 HTTPS 写进本站数据库；</li>
 *   <li>所有对外序列化都标了 {@link JsonProperty.Access#WRITE_ONLY} ——
 *       接口永远不回传明文，前端只显示「已配置 / 未配置」；</li>
 *   <li>绝不写进代码、配置文件或 git 仓库。</li>
 * </ul>
 */
@Entity
@Table(name = "xz_sites",
        uniqueConstraints = @UniqueConstraint(name = "uk_xz_site_url_user", columnNames = {"apiUrl", "username"}),
        indexes = @Index(name = "idx_xz_site_url", columnList = "apiUrl"))
public class XzSite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 展示名（默认「账号@主机名」） */
    @Column(length = 200)
    private String name;

    /**
     * 对接的接口地址（规范化后，如 {@code https://xiezitai.cn/api/v1/publish}）。
     * 导入时由她推导出开放 API 根（{@code .../api/v1}）与站点主机名。
     */
    @Column(name = "apiUrl", nullable = false, length = 500)
    private String apiUrl;

    /** 写字台账号（该站点上的用户名，导入的文章以它为发布人） */
    @Column(length = 100)
    private String username;

    /** 对接密钥 / API Token（敏感：WRITE_ONLY，永不序列化出去） */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(length = 500)
    private String apiToken;

    /**
     * 只给前端一个「是否已配置密钥」的布尔值（列表里显示已配置/未配置），
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
    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String apiUrl) { this.apiUrl = apiUrl; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getApiToken() { return apiToken; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(LocalDateTime lastSyncAt) { this.lastSyncAt = lastSyncAt; }
}
