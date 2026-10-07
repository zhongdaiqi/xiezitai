package cn.xiezitai.controller;

import cn.xiezitai.entity.User;
import cn.xiezitai.repository.RequestLogRepository;
import cn.xiezitai.repository.UserRepository;
import cn.xiezitai.service.AiService;
import cn.xiezitai.service.FileScanService;
import cn.xiezitai.service.NotifyService;
import cn.xiezitai.service.TamperGuardService;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 管理后台：系统设置 / 用户管理 / 日志 / 安全扫描 / 防篡改 —— 仅 ADMIN */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    /** 后台列表里的时间展示格式（yyyy-MM-dd HH:mm:ss） */
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final NotifyService notify;
    private final AiService ai;
    private final FileScanService scanner;
    private final TamperGuardService tamper;
    private final UserRepository users;
    private final RequestLogRepository logs;
    private final Path uploadDir;

    public AdminController(NotifyService notify, AiService ai, FileScanService scanner,
                           TamperGuardService tamper, UserRepository users, RequestLogRepository logs,
                           @org.springframework.beans.factory.annotation.Value("${xiezitai.upload-dir}") String uploadDir) {
        this.notify = notify;
        this.ai = ai;
        this.scanner = scanner;
        this.tamper = tamper;
        this.users = users;
        this.logs = logs;
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    /* ---------- 权限 ---------- */

    private boolean isAdmin(Authentication auth) {
        User u = users.findByUsername(auth.getName()).orElse(null);
        return u != null && "ADMIN".equals(u.getRole());
    }

    /* ---------- 系统设置（机器人 / AI / 通知开关） ---------- */

    @GetMapping("/settings")
    public ResponseEntity<?> settings(Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "需要管理员权限"));
        Map<String, String> out = new java.util.HashMap<>();
        for (String key : List.of("wecom.webhook", "notify.enabled", "notify.login", "notify.article",
                "notify.visit", "notify.register", "notify.comment", "notify.upload",
                "ai.baseUrl", "ai.model", "ai.imageModel")) {
            out.put(key, notify.get(key, ""));
        }
        out.put("ai.apiKey", notify.get("ai.apiKey", "").isEmpty() ? "" : "******");
        return ResponseEntity.ok(out);
    }

    @PostMapping("/settings")
    public ResponseEntity<?> saveSettings(@RequestBody Map<String, String> body, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "需要管理员权限"));
        body.forEach((k, v) -> {
            if (v != null && !"******".equals(v)) {
                if (k.startsWith("ai.") || k.startsWith("wecom.") || k.startsWith("notify.")) {
                    ai.set(k, v);
                }
            }
        });
        return ResponseEntity.ok(Map.of("message", "设置已保存"));
    }

    @PostMapping("/settings/notify/test")
    public ResponseEntity<?> testNotify(Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "需要管理员权限"));
        notify.notifyEvent("login", "**写字台测试通知**\n> 触发人: " + auth.getName());
        return ResponseEntity.ok(Map.of("message", "测试通知已发送（若开启）"));
    }

    /* ---------- 用户管理 ---------- */

    @GetMapping("/users")
    public ResponseEntity<?> listUsers(Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "需要管理员权限"));
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : users.findAll()) {
            Map<String, Object> m = new java.util.HashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("email", u.getEmail() == null ? "" : u.getEmail());
            m.put("role", u.getRole());
            m.put("status", u.getStatus() == null ? "APPROVED" : u.getStatus());
            m.put("reviewNote", u.getReviewNote() == null ? "" : u.getReviewNote());
            m.put("createdAt", u.getCreatedAt() == null ? "" : u.getCreatedAt().format(DT));
            m.put("enabled", u.isEnabled());
            m.put("totpEnabled", u.isTotpEnabled());
            m.put("allowedFileTypes", u.getAllowedFileTypes() == null ? "" : u.getAllowedFileTypes());
            out.add(m);
        }
        // 待审核的排最前面：管理员打开列表就能直接处理，不用在几百行里翻
        out.sort((a, b) -> {
            int sa = "PENDING".equals(a.get("status")) ? 0 : 1;
            int sb = "PENDING".equals(b.get("status")) ? 0 : 1;
            return sa != sb ? Integer.compare(sa, sb) : Long.compare((Long) a.get("id"), (Long) b.get("id"));
        });
        return ResponseEntity.ok(out);
    }

    /**
     * 注册审核：PENDING / APPROVED / REJECTED。
     * 用独立端点而不是塞进 updateUser，是因为只有这条路径要发通知、也只该由管理员显式触发。
     */
    @PutMapping("/users/{id}/audit")
    public ResponseEntity<?> auditUser(@PathVariable Long id, @RequestBody Map<String, String> body, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "需要管理员权限"));
        User u = users.findById(id).orElse(null);
        if (u == null) return ResponseEntity.notFound().build();
        String status = body.getOrDefault("status", "").trim().toUpperCase();
        if (!List.of("APPROVED", "REJECTED", "PENDING").contains(status)) {
            return ResponseEntity.badRequest().body(Map.of("error", "status 只能是 APPROVED / REJECTED / PENDING"));
        }
        String note = body.get("note");
        if (note != null && note.length() > 200) note = note.substring(0, 200);
        // 防呆：管理员账号不走审核闸门，否则一次误操作就可能把唯一的 ADMIN 锁在门外
        if ("ADMIN".equals(u.getRole()) && !"APPROVED".equals(status)) {
            return ResponseEntity.badRequest().body(Map.of("error", "管理员账号无需审核；要停用请取消「启用」"));
        }
        u.setStatus(status);
        u.setReviewNote(status.equals("REJECTED") ? note : null);
        users.save(u);
        if (!"PENDING".equals(status)) {
            notify.notifyEvent("register", "**写字台注册审核**\n> 用户: " + u.getUsername()
                    + "\n> 结果: " + ("APPROVED".equals(status) ? "已通过" : "已驳回")
                    + (status.equals("REJECTED") && note != null && !note.isBlank() ? "\n> 备注: " + note : ""));
        }
        return ResponseEntity.ok(Map.of("message", "已更新", "status", status));
    }

    @PutMapping("/users/{id}")
    public ResponseEntity<?> updateUser(@PathVariable Long id, @RequestBody Map<String, String> body, Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "需要管理员权限"));
        User u = users.findById(id).orElse(null);
        if (u == null) return ResponseEntity.notFound().build();
        if (body.containsKey("role")) u.setRole(body.get("role"));
        if (body.containsKey("enabled")) u.setEnabled(Boolean.parseBoolean(body.get("enabled")));
        if (body.containsKey("allowedFileTypes")) u.setAllowedFileTypes(body.get("allowedFileTypes"));
        users.save(u);
        return ResponseEntity.ok(Map.of("message", "已更新"));
    }

    /* ---------- 请求日志 + AI 风险分析 ---------- */

    @GetMapping("/logs")
    public ResponseEntity<?> recentLogs(@RequestParam(defaultValue = "0") int page,
                                        @RequestParam(defaultValue = "50") int size,
                                        Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "需要管理员权限"));
        return ResponseEntity.ok(logs.findAll(PageRequest.of(page, Math.min(size, 200),
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id"))));
    }

    @PostMapping("/logs/analyze")
    public ResponseEntity<?> analyzeLogs(Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "需要管理员权限"));
        StringBuilder sb = new StringBuilder();
        logs.findAll(PageRequest.of(0, 200,
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.DESC, "id")))
                .forEach(l -> sb.append(l.getMethod()).append(' ').append(l.getUri())
                        .append(" ip=").append(l.getIp()).append(" status=").append(l.getStatus()).append('\n'));
        return ResponseEntity.ok(Map.of("result", ai.analyzeRisk(sb.length() == 0 ? "（暂无日志）" : sb.toString())));
    }

    /* ---------- 安全扫描 / 防篡改 ---------- */

    @PostMapping("/security/scan-uploads")
    public ResponseEntity<?> scanUploads(Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "需要管理员权限"));
        return ResponseEntity.ok(Map.of("result", scanner.scanUploadDir(uploadDir)));
    }

    @GetMapping("/security/tamper-check")
    public ResponseEntity<?> tamperCheck(Authentication auth) {
        if (!isAdmin(auth)) return ResponseEntity.status(403).body(Map.of("error", "需要管理员权限"));
        return ResponseEntity.ok(tamper.verify());
    }
}
