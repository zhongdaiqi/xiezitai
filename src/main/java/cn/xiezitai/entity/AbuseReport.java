package cn.xiezitai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 不良内容举报工单（App Store 准则 1.2）。
 *
 * <p>审查员的要求里有一条硬指标：开发者必须在 24 小时内处理举报 —— 删内容 + 把人踢出去。
 * 光给内容打一个「被举报」标记是不够的（看不出有没有处理、什么时候处理的），
 * 所以这里落成一张**工单**：谁举报的、举报了什么、什么理由、管理员最后怎么处置的，全程留痕，
 * 后台列表按 OPEN 排序，逾期未处理的可以被扫出来催办。
 *
 * <p>举报对象分三类：文章 / 评论 / 用户。targetId 是对应主键，
 * targetSummary 与 targetAuthor 是快照 —— 原内容被删掉之后工单还得能看懂当时举报的是什么。
 */
@Entity
@Table(name = "abuse_reports",
        indexes = {
                @Index(name = "idx_report_status", columnList = "status"),
                @Index(name = "idx_report_created", columnList = "createdAt")
        })
public class AbuseReport {

    /** 举报对象类型 */
    public static final String TYPE_ARTICLE = "ARTICLE";
    public static final String TYPE_COMMENT = "COMMENT";
    public static final String TYPE_USER = "USER";

    /** 工单状态 */
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_DISMISSED = "DISMISSED";

    /** 处置动作 */
    public static final String ACTION_REMOVE = "remove_content";
    public static final String ACTION_BAN = "ban_user";
    public static final String ACTION_REMOVE_AND_BAN = "remove_and_ban";
    public static final String ACTION_DISMISS = "dismiss";

    /**
     * 举报理由枚举（前端下拉与后台展示共用，服务端白名单校验）。
     * 收窄成枚举而不是自由文本，是为了让管理员能按类型分流、也便于统计哪类违规最多。
     */
    public static final String REASON_SPAM = "SPAM";
    public static final String REASON_ABUSE = "ABUSE";
    public static final String REASON_PORN = "PORN";
    public static final String REASON_VIOLENCE = "VIOLENCE";
    public static final String REASON_ILLEGAL = "ILLEGAL";
    public static final String REASON_OTHER = "OTHER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** ARTICLE / COMMENT / USER */
    @Column(nullable = false, length = 20)
    private String type = TYPE_ARTICLE;

    /** 被举报对象主键（USER 类型为该用户 id） */
    @Column(nullable = false)
    private Long targetId;

    /** 快照：文章标题 / 评论正文前 200 字 / 用户名 */
    @Column(length = 500)
    private String targetSummary;

    /** 快照：被举报内容的作者；USER 类型即被举报人本人 */
    @Column(length = 50)
    private String targetAuthor;

    /** 举报人；系统自动举报（如风控）时为 null */
    @Column(length = 50)
    private String reporterUsername;

    @Column(nullable = false, length = 20)
    private String reason = REASON_OTHER;

    /** 举报人补充描述，可选 */
    @Column(length = 500)
    private String detail;

    /** OPEN / RESOLVED / DISMISSED */
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20) default 'OPEN'")
    private String status = STATUS_OPEN;

    /** 处置动作：remove_content / ban_user / remove_and_ban / dismiss */
    @Column(length = 30)
    private String actionTaken;

    /** 处理管理员 */
    @Column(length = 50)
    private String handledBy;

    /** 处置备注（给内部看的，不对外展示） */
    @Column(length = 500)
    private String handleNote;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime handledAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public Long getTargetId() { return targetId; }
    public void setTargetId(Long targetId) { this.targetId = targetId; }
    public String getTargetSummary() { return targetSummary; }
    public void setTargetSummary(String targetSummary) { this.targetSummary = targetSummary; }
    public String getTargetAuthor() { return targetAuthor; }
    public void setTargetAuthor(String targetAuthor) { this.targetAuthor = targetAuthor; }
    public String getReporterUsername() { return reporterUsername; }
    public void setReporterUsername(String reporterUsername) { this.reporterUsername = reporterUsername; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getActionTaken() { return actionTaken; }
    public void setActionTaken(String actionTaken) { this.actionTaken = actionTaken; }
    public String getHandledBy() { return handledBy; }
    public void setHandledBy(String handledBy) { this.handledBy = handledBy; }
    public String getHandleNote() { return handleNote; }
    public void setHandleNote(String handleNote) { this.handleNote = handleNote; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getHandledAt() { return handledAt; }
    public void setHandledAt(LocalDateTime handledAt) { this.handledAt = handledAt; }

    /** 是否已超过 24 小时仍未处理（App Store 1.2 的 SLA 红线） */
    @Transient
    public boolean isOverdue() {
        if (!STATUS_OPEN.equals(status)) return false;
        return createdAt != null && createdAt.plusHours(24).isBefore(LocalDateTime.now());
    }

    /** 距 24 小时截止还剩多少小时（已逾期返回负数）；非 OPEN 返回 null */
    @Transient
    public Double hoursLeft() {
        if (!STATUS_OPEN.equals(status) || createdAt == null) return null;
        return java.time.Duration.between(LocalDateTime.now(), createdAt.plusHours(24)).toMinutes() / 60.0;
    }

    @Transient
    public String getReasonLabel() {
        return switch (reason) {
            case REASON_SPAM -> "垃圾广告 / 刷屏";
            case REASON_ABUSE -> "辱骂骚扰 / 人身攻击";
            case REASON_PORN -> "色情或露骨内容";
            case REASON_VIOLENCE -> "暴力或危害他人";
            case REASON_ILLEGAL -> "违法违规内容";
            default -> "其他";
        };
    }
}
