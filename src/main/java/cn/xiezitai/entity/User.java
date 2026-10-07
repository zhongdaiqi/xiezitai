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

    /**
     * 注册审核状态：PENDING（待审核）/ APPROVED（已通过）/ REJECTED（已驳回）。
     *
     * 默认 APPROVED 是**有意为之**：老库里的历史用户、初始化脚本建的管理员都走不到审核流程，
     * 一旦默认 PENDING 就会把所有人锁在门外。只有走 /api/auth/register 自助注册的才置 PENDING。
     *
     * columnDefinition 里带 DEFAULT 是为了 ddl-auto=update 加列时给历史行补值
     * （MySQL/MariaDB 会拿默认值填已有行），否则老用户 status 为 null。
     */
    @Column(length = 20, columnDefinition = "varchar(20) default 'APPROVED'")
    private String status = "APPROVED";

    /** 审核备注：驳回时展示给用户看的原因（可空） */
    @Column(length = 200)
    private String reviewNote;

    /** TOTP 两步验证 */
    private boolean totpEnabled = false;
    private String totpSecret;

    /** 该用户允许上传的文件类型，逗号分隔；空则继承默认媒体限制。管理员不受限 */
    @Column(length = 500)
    private String allowedFileTypes;

    /** 开放 API 发布令牌 */
    @Column(length = 100)
    private String apiToken;

    /** 注册时间（后台审核列表按它看先后） */
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
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getReviewNote() { return reviewNote; }
    public void setReviewNote(String reviewNote) { this.reviewNote = reviewNote; }

    /** 是否已通过审核；status 为 null（历史数据）按通过处理，避免把老用户锁在门外 */
    public boolean isApproved() { return status == null || "APPROVED".equals(status); }
    public boolean isPending() { return "PENDING".equals(status); }
    public boolean isRejected() { return "REJECTED".equals(status); }

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
