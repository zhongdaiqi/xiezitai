package cn.xiezitai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 用户屏蔽关系（App Store 准则 1.2 UGC 强制要求）。
 *
 * <p>屏蔽是**单向**的：A 屏蔽 B 只影响 A 看到的内容，B 无感知，也不会被通知是誰屏蔽了他。
 * 屏蔽生效后，B 的文章与评论会立刻从 A 的文章流 / 评论列表里消失 —— 这是审查员原话里
 * "remove it from the user's feed instantly" 的落点，由查询层过滤实现（删除数据会影响其他人看到的内容，
 * 所以不能真删，只能对屏蔽者不可见）。
 *
 * <p>被屏蔽方只存用户名、不存外键：文章 {@code author} 与评论 {@code authorName} 都是字符串快照，
 * 过滤时用名字直接比对最快；也避免被屏蔽者注销账号后留下悬空引用。
 */
@Entity
@Table(
        name = "user_blocks",
        uniqueConstraints = @UniqueConstraint(name = "uk_block", columnNames = {"blocker_id", "blocked_username"}),
        indexes = @Index(name = "idx_blocker", columnList = "blocker_id")
)
public class UserBlock {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 发起屏蔽的人 */
    @Column(name = "blocker_id", nullable = false)
    private Long blockerId;

    @Column(name = "blocker_username", nullable = false, length = 50)
    private String blockerUsername;

    /** 被屏蔽的用户名 */
    @Column(name = "blocked_username", nullable = false, length = 50)
    private String blockedUsername;

    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getBlockerId() { return blockerId; }
    public void setBlockerId(Long blockerId) { this.blockerId = blockerId; }
    public String getBlockerUsername() { return blockerUsername; }
    public void setBlockerUsername(String blockerUsername) { this.blockerUsername = blockerUsername; }
    public String getBlockedUsername() { return blockedUsername; }
    public void setBlockedUsername(String blockedUsername) { this.blockedUsername = blockedUsername; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
