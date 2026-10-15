package cn.xiezitai.controller;

import cn.xiezitai.entity.AbuseReport;
import cn.xiezitai.entity.User;
import cn.xiezitai.entity.UserBlock;
import cn.xiezitai.repository.AbuseReportRepository;
import cn.xiezitai.service.NotifyService;
import cn.xiezitai.service.SafetyService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 内容安全接口（App Store 准则 1.2 UGC）。
 *
 * <p>客户端只需要这一组接口即可满足合规要求：
 * <pre>
 *   POST   /api/blocks                  屏蔽用户（同时通知开发者、内容立即从信息流移除）
 *   DELETE /api/blocks/{username}       解除屏蔽
 *   GET    /api/blocks                  我的屏蔽名单
 *   POST   /api/reports                 举报文章 / 评论 / 用户
 *   GET    /api/admin/reports           举报工单列表（?status=OPEN 只看待处理）
 *   POST   /api/admin/reports/{id}/resolve   处置：删内容 / 封禁 / 两者 / 驳回
 *   POST   /api/admin/users/{username}/unban 解封
 * </pre>
 *
 * <p>旧接口 {@code POST /api/comments/{id}/report} 仍在（见 CommentController），
 * 内部已改为委派到同一套工单，老版本 App 不会失效。
 */
@RestController
public class SafetyController {

    private final SafetyService safety;
    private final AbuseReportRepository reports;
    private final NotifyService notify;

    public SafetyController(SafetyService safety, AbuseReportRepository reports, NotifyService notify) {
        this.safety = safety;
        this.reports = reports;
        this.notify = notify;
    }

    /* ==================== 屏蔽 ==================== */

    /** 屏蔽某用户。屏蔽后其文章与评论立即从我的信息流消失，并通知管理员。 */
    @PostMapping("/api/blocks")
    public ResponseEntity<?> block(@RequestBody Map<String, String> body, Authentication auth) {
        User me = safety.currentUser(auth);
        if (me == null) return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        Map<String, Object> r = safety.block(me, body.getOrDefault("username", ""));
        return ResponseEntity.ok(r);
    }

    @DeleteMapping("/api/blocks/{username}")
    public ResponseEntity<?> unblock(@PathVariable String username, Authentication auth) {
        User me = safety.currentUser(auth);
        if (me == null) return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        return ResponseEntity.ok(safety.unblock(me, username));
    }

    /** 我的屏蔽名单 */
    @GetMapping("/api/blocks")
    public ResponseEntity<?> myBlocks(Authentication auth) {
        User me = safety.currentUser(auth);
        if (me == null) return ResponseEntity.status(401).body(Map.of("error", "请先登录"));
        List<Map<String, Object>> list = safety.myBlocks(me).stream().map(b -> {
            Map<String, Object> m = new HashMap<>();
            m.put("username", b.getBlockedUsername());
            m.put("blockedAt", b.getCreatedAt() == null ? null : b.getCreatedAt().toString());
            return m;
        }).toList();
        return ResponseEntity.ok(Map.of("blocks", list, "total", list.size()));
    }

    /* ==================== 举报 ==================== */

    /**
     * 统一举报入口。
     *
     * @param body {type: ARTICLE|COMMENT|USER, targetId: Long, reason: SPAM|ABUSE|PORN|VIOLENCE|ILLEGAL|OTHER, detail?: String}
     */
    @PostMapping("/api/reports")
    public ResponseEntity<?> report(@RequestBody Map<String, Object> body, Authentication auth) {
        User me = safety.currentUser(auth);
        if (me == null) return ResponseEntity.status(401).body(Map.of("error", "请先登录后再举报"));
        String type = str(body.get("type"));
        Long targetId = parseId(body.get("targetId"));
        String reason = str(body.get("reason"));
        String detail = str(body.get("detail"));
        Map<String, Object> r = safety.report(me, type, targetId, reason, detail);
        if (Boolean.FALSE.equals(r.get("ok"))) {
            return ResponseEntity.badRequest().body(r);
        }
        return ResponseEntity.ok(r);
    }

    /* ==================== 管理员：工单处理 ==================== */

    /** 举报工单列表；status 省略时返回全部 */
    @GetMapping("/api/admin/reports")
    public ResponseEntity<?> listReports(@RequestParam(required = false) String status, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "需要管理员权限"));
        String st = status == null ? "" : status.trim().toUpperCase();
        List<AbuseReport> list = st.isEmpty() ? reports.findAllByOrderByCreatedAtDesc()
                : reports.findByStatusOrderByCreatedAtDesc(st);
        return ResponseEntity.ok(list);
    }

    /**
     * 工单看板统计：待处理数、已逾期数（超 24 小时）、今日已处理数。
     * 逾期数是给管理员看的 SLA 红线 —— 准则 1.2 明确要求 24 小时内处置。
     */
    @GetMapping("/api/admin/reports/stats")
    public ResponseEntity<?> reportStats(Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "需要管理员权限"));
        List<AbuseReport> open = reports.findByStatusOrderByCreatedAtDesc(AbuseReport.STATUS_OPEN);
        long overdue = open.stream().filter(AbuseReport::isOverdue).count();
        LocalDateTime today = LocalDateTime.now().toLocalDate().atStartOfDay();
        long handledToday = reports.findAllByOrderByCreatedAtDesc().stream()
                .filter(r -> r.getHandledAt() != null && !r.getHandledAt().isBefore(today)).count();
        Map<String, Object> m = new HashMap<>();
        m.put("open", open.size());
        m.put("overdue", overdue);
        m.put("handledToday", handledToday);
        m.put("resolved", reports.countByStatus(AbuseReport.STATUS_RESOLVED));
        m.put("dismissed", reports.countByStatus(AbuseReport.STATUS_DISMISSED));
        return ResponseEntity.ok(m);
    }

    /**
     * 处置工单。
     *
     * @param body {action: remove_content|ban_user|remove_and_ban|dismiss, note?: String}
     */
    @PostMapping("/api/admin/reports/{id}/resolve")
    public ResponseEntity<?> resolve(@PathVariable Long id, @RequestBody Map<String, String> body,
                                     Authentication auth) {
        User admin = safety.currentUser(auth);
        if (admin == null || !"ADMIN".equals(admin.getRole())) {
            return ResponseEntity.status(403).body(Map.of("error", "需要管理员权限"));
        }
        Map<String, Object> r = safety.resolve(id, body.getOrDefault("action", ""),
                body.get("note"), admin);
        if (Boolean.FALSE.equals(r.get("ok"))) return ResponseEntity.badRequest().body(r);
        return ResponseEntity.ok(r);
    }

    /** 解封误封的账号 */
    @PostMapping("/api/admin/users/{username}/unban")
    public ResponseEntity<?> unban(@PathVariable String username, Authentication auth) {
        User admin = safety.currentUser(auth);
        if (admin == null || !"ADMIN".equals(admin.getRole())) {
            return ResponseEntity.status(403).body(Map.of("error", "需要管理员权限"));
        }
        Map<String, Object> r = safety.unban(username, admin);
        if (Boolean.FALSE.equals(r.get("ok"))) return ResponseEntity.badRequest().body(r);
        return ResponseEntity.ok(r);
    }

    /**
     * SLA 巡检：手动触发一次逾期扫描，把超过 24 小时未处理的工单推给管理员。
     * 可以挂到定时任务上；这里做成接口是为了让管理员在后台点一下就能看到当前欠账。
     */
    @PostMapping("/api/admin/reports/scan-overdue")
    public ResponseEntity<?> scanOverdue(Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "需要管理员权限"));
        List<AbuseReport> overdue = reports.findByStatusOrderByCreatedAtDesc(AbuseReport.STATUS_OPEN)
                .stream().filter(AbuseReport::isOverdue).toList();
        if (!overdue.isEmpty()) {
            StringBuilder sb = new StringBuilder("**写字台：举报工单逾期告警**\n");
            sb.append("> 共 ").append(overdue.size()).append(" 单超过 24 小时未处理：\n");
            for (AbuseReport r : overdue) {
                sb.append("> - #").append(r.getId()).append(" ")
                  .append(r.getReasonLabel()).append(" · ")
                  .append(r.getTargetSummary()).append("\n");
            }
            sb.append("> <font color=\"warning\">请立即到后台「举报处理」处置，否则违反 App Store 准则 1.2</font>");
            notify.notifyEvent("comment", sb.toString());
        }
        return ResponseEntity.ok(Map.of("overdue", overdue.size()));
    }

    /* ==================== 辅助 ==================== */

    private boolean isAdmin(Authentication auth) {
        User u = safety.currentUser(auth);
        return u != null && "ADMIN".equals(u.getRole());
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long parseId(Object o) {
        if (o == null) return null;
        if (o instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(o).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
