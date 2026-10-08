package cn.xiezitai.entity;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 关联的「谷歌 Blogger 博客」（一个 Google 账号可以挂多个博客，一个博客一行）。
 *
 * <p>与其它渠道最大的不同：<b>凭据不是用户填的</b>，而是走 Google OAuth 2.0 授权拿到的
 * refresh token / access token。所以这一行同时承担两个角色：
 * <ol>
 *   <li><b>导入源</b> —— 浏览该博客的文章并按单篇 / 整站导入本站；</li>
 *   <li><b>分发目标</b> —— 出现在「文章分发」弹窗的目标清单里，往 Blogger 写文章走
 *       {@code POST /blogs/{blogId}/posts}（新建）与 {@code PATCH .../posts/{postId}}（更新）。</li>
 * </ol>
 *
 * <p>令牌属敏感信息：
 * <ul>
 *   <li>只在 OAuth 回调里经 HTTPS 写进本站数据库；</li>
 *   <li>序列化一律 {@link JsonProperty.Access#WRITE_ONLY} —— 接口永不回传明文，
 *       前端只看得到 {@code hasAuth} 这个布尔值；</li>
 *   <li>client_id / client_secret 更是只从环境变量读，连库都不落（见 {@code xiezitai.google.*}）。</li>
 * </ul>
 *
 * <p>{@link #blogId} 全局唯一：一个 Blogger 博客只应该被关联一次。同一个 Google 账号重新授权时
 * 按 blogId 覆盖（刷新令牌与博客名），不会长出重复行。
 */
@Entity
@Table(name = "blogger_sites",
        uniqueConstraints = @UniqueConstraint(name = "uk_blogger_blog", columnNames = {"blogId"}),
        indexes = @Index(name = "idx_blogger_email", columnList = "googleEmail"))
public class BloggerSite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属 Google 账号邮箱（OAuth 的 id_token 里带来；用来在列表里分组与「解除整个账号」） */
    @Column(name = "googleEmail", length = 200)
    private String googleEmail;

    /** Google 账号显示名 */
    @Column(length = 200)
    private String accountName;

    /** Blogger 博客 id（数字串；Blogger 的 id 是 64 位整数，直接用字符串存，不做数值转换） */
    @Column(name = "blogId", nullable = false, length = 64)
    private String blogId;

    /** 博客名（展示名） */
    @Column(length = 300)
    private String name;

    /** 博客地址（https://xxx.blogspot.com/） */
    @Column(length = 500)
    private String url;

    /** 刷新令牌（敏感：WRITE_ONLY）—— access token 过期后拿它换新的 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(length = 1000)
    private String refreshToken;

    /** 当前访问令牌（敏感：WRITE_ONLY）；过期后用 refreshToken 续期并覆盖本字段 */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    @Column(length = 2000)
    private String accessToken;

    /** 访问令牌到期时间（提前 60 秒续期） */
    private LocalDateTime tokenExpiresAt;

    /**
     * 只给前端一个「授权是否可用」的布尔值，明文令牌永远不出接口。
     * 无对应字段，JPA 字段访问模式会忽略它。
     */
    @JsonProperty("hasAuth")
    public boolean isHasAuth() {
        return refreshToken != null && !refreshToken.isBlank();
    }

    private LocalDateTime createdAt = LocalDateTime.now();
    private LocalDateTime lastSyncAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getGoogleEmail() { return googleEmail; }
    public void setGoogleEmail(String googleEmail) { this.googleEmail = googleEmail; }
    public String getAccountName() { return accountName; }
    public void setAccountName(String accountName) { this.accountName = accountName; }
    public String getBlogId() { return blogId; }
    public void setBlogId(String blogId) { this.blogId = blogId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }
    public LocalDateTime getTokenExpiresAt() { return tokenExpiresAt; }
    public void setTokenExpiresAt(LocalDateTime tokenExpiresAt) { this.tokenExpiresAt = tokenExpiresAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getLastSyncAt() { return lastSyncAt; }
    public void setLastSyncAt(LocalDateTime lastSyncAt) { this.lastSyncAt = lastSyncAt; }
}
