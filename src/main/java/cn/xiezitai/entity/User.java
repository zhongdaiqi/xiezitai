package cn.xiezitai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(nullable = false)
    private String password;

    private String email;

    /** ADMIN / USER */
    private String role = "USER";

    private boolean enabled = true;

    /** TOTP 两步验证 */
    private boolean totpEnabled = false;
    private String totpSecret;

    /** 该用户允许上传的文件类型，逗号分隔；空则继承默认媒体限制。管理员不受限 */
    @Column(length = 500)
    private String allowedFileTypes;

    /** 开放 API 发布令牌 */
    @Column(length = 100)
    private String apiToken;

    private LocalDateTime createdAt = LocalDateTime.now();

    // getters / setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public boolean isTotpEnabled() { return totpEnabled; }
    public void setTotpEnabled(boolean totpEnabled) { this.totpEnabled = totpEnabled; }
    public String getTotpSecret() { return totpSecret; }
    public void setTotpSecret(String totpSecret) { this.totpSecret = totpSecret; }
    public String getAllowedFileTypes() { return allowedFileTypes; }
    public void setAllowedFileTypes(String allowedFileTypes) { this.allowedFileTypes = allowedFileTypes; }
    public String getApiToken() { return apiToken; }
    public void setApiToken(String apiToken) { this.apiToken = apiToken; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
