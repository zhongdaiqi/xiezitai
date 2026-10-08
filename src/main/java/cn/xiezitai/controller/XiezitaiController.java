package cn.xiezitai.controller;

import cn.xiezitai.entity.XzSite;
import cn.xiezitai.repository.XzSiteRepository;
import cn.xiezitai.service.XiezitaiClient;
import cn.xiezitai.service.XiezitaiImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写字台账号关联与导入接口（/api/admin/xz/**，登录后可用）。
 *
 * <p>对接密钥安全：{@link XzSite#getApiToken} 是 WRITE_ONLY，任何响应都不会带出明文；
 * 更新时密钥传空串表示「保持原值」。密钥只落库，绝不写进代码 / 配置 / git 仓库。
 */
@RestController
@RequestMapping("/api/admin/xz")
public class XiezitaiController {

    private static final Logger log = LoggerFactory.getLogger(XiezitaiController.class);

    private final XzSiteRepository sites;
    private final XiezitaiClient client;
    private final XiezitaiImportService imports;

    public XiezitaiController(XzSiteRepository sites, XiezitaiClient client, XiezitaiImportService imports) {
        this.sites = sites;
        this.client = client;
        this.imports = imports;
    }

    /* ================= 账号管理 ================= */

    @GetMapping("/sites")
    public List<XzSite> listSites() {
        return sites.findAll();
    }

    /**
     * 关联写字台账号。接口地址做规范化（补 https://、去尾斜杠），账号必填
     * （同一个站点可以挂多个账号，唯一键是「接口地址 + 账号」）。
     * 添加时顺带校验连通性与密钥 —— 校验失败不拦截保存（对方可能临时打不开），
     * 但把原因放在 warning 里回给前端展示。
     */
    @PostMapping("/sites")
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        String apiUrl = normalizeApiUrl(body.get("url"));
        if (apiUrl == null) return ResponseEntity.badRequest().body(Map.of("error", "请填写正确的接口地址"));
        String username = body.get("username") == null ? "" : body.get("username").trim();
        if (username.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "请填写写字台账号"));
        if (sites.existsByApiUrlAndUsername(apiUrl, username)) {
            return ResponseEntity.status(409).body(Map.of("error", "该站点已关联此账号：" + username + " @ " + apiUrl));
        }
        XzSite s = new XzSite();
        s.setApiUrl(apiUrl);
        s.setUsername(username);
        applyToken(s, body);
        s.setName(body.get("name") == null || body.get("name").isBlank()
                ? defaultName(username, apiUrl) : body.get("name").trim());
        XzSite saved = sites.save(s);
        String warning = probeQuietly(saved);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("site", saved);
        if (warning != null) out.put("warning", warning);
        return ResponseEntity.ok(out);
    }

    /** 更新账号（密钥留空 = 保持原值） */
    @PutMapping("/sites/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        XzSite s = sites.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        String apiUrl = normalizeApiUrl(body.get("url"));
        String username = body.get("username") == null ? s.getUsername() : body.get("username").trim();
        if (username == null || username.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请填写写字台账号"));
        }
        String targetUrl = apiUrl == null ? s.getApiUrl() : apiUrl;
        if (!targetUrl.equals(s.getApiUrl()) || !username.equals(s.getUsername())) {
            if (sites.existsByApiUrlAndUsername(targetUrl, username)) {
                return ResponseEntity.status(409).body(Map.of("error", "该站点已关联此账号：" + username + " @ " + targetUrl));
            }
            if (!targetUrl.equals(s.getApiUrl())) {
                s.setApiUrl(targetUrl);
                s.setName(defaultName(username, targetUrl));
            }
            s.setUsername(username);
        }
        applyToken(s, body);
        return ResponseEntity.ok(sites.save(s));
    }

    @DeleteMapping("/sites/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!sites.existsById(id)) return ResponseEntity.notFound().build();
        sites.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    /** 连通性 / 账号与密钥校验 */
    @PostMapping("/sites/{id}/test")
    public ResponseEntity<?> test(@PathVariable Long id) {
        XzSite s = sites.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        try {
            String account = client.verifyCredentials(s);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("account", account);
            out.put("apiRoot", XiezitaiClient.apiRoot(s.getApiUrl()));
            out.put("site", XiezitaiClient.origin(s.getApiUrl()));
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "连接失败：" + e.getMessage()));
        }
    }

    /* ================= 浏览与导入 ================= */

    /** 浏览对方站点的文章列表（代理开放 API，前端翻页/搜索） */
    @GetMapping("/sites/{id}/posts")
    public ResponseEntity<?> posts(@PathVariable Long id,
                                   @RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "10") int perPage,
                                   @RequestParam(required = false) String search) {
        XzSite s = sites.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        try {
            XiezitaiClient.ArticlesPage pp = client.listArticles(s,
                    Math.max(page, 1), Math.min(Math.max(perPage, 1), 100), search);
            return ResponseEntity.ok(Map.of(
                    "posts", pp.items(), "total", pp.total(), "totalPages", pp.totalPages()));
        } catch (Exception e) {
            log.warn("写字台文章列表拉取失败 site={}: {}", id, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "拉取文章列表失败：" + e.getMessage()));
        }
    }

    /**
     * 导入单篇（同步执行，媒体较多时可能耗时几十秒）。
     * useSrcDate=false 时发布时间用当前时间；onConflict=update 时同 slug 已存在则用对方版本覆盖更新。
     */
    @PostMapping("/sites/{id}/import")
    public ResponseEntity<?> importOne(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        XzSite s = sites.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        Object pid = body.get("postId");
        if (!(pid instanceof Number n)) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 postId"));
        }
        boolean useSrcDate = !Boolean.FALSE.equals(body.get("useSrcDate"));
        String onConflict = "update".equals(body.get("onConflict")) ? "update" : "skip";
        try {
            return ResponseEntity.ok(imports.importSingle(s, n.longValue(), useSrcDate, onConflict));
        } catch (Exception e) {
            log.warn("写字台单篇导入失败 site={} post={}: {}", id, pid, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "导入失败：" + e.getMessage()));
        }
    }

    /** 启动整站导入（后台线程）。返回 202，前端开始轮询进度 */
    @PostMapping("/sites/{id}/import-all")
    public ResponseEntity<?> importAll(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        XzSite s = sites.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        boolean useSrcDate = body == null || !Boolean.FALSE.equals(body.get("useSrcDate"));
        String onConflict = body != null && "update".equals(body.get("onConflict")) ? "update" : "skip";
        XiezitaiImportService.XzSyncProgress p = imports.startFullImport(s, useSrcDate, onConflict);
        if (p == null) {
            return ResponseEntity.status(409).body(Map.of("error", "该账号已有整站导入任务在运行，请等它结束"));
        }
        return ResponseEntity.accepted().body(Map.of("started", true));
    }

    /** 整站导入进度（前端 1~2 秒轮询一次） */
    @GetMapping("/sites/{id}/progress")
    public ResponseEntity<?> progress(@PathVariable Long id) {
        XiezitaiImportService.XzSyncProgress p = imports.progressOf(id);
        if (p == null) return ResponseEntity.ok(Map.of("phase", "IDLE"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phase", p.phase);
        out.put("total", p.total);
        out.put("done", p.done);
        out.put("imported", p.imported);
        out.put("updated", p.updated);
        out.put("skipped", p.skipped);
        out.put("failed", p.failed);
        out.put("useSrcDate", p.useSrcDate);
        out.put("onConflict", p.onConflict);
        out.put("current", p.current);
        out.put("error", p.error);
        out.put("messages", List.copyOf(p.messages));
        return ResponseEntity.ok(out);
    }

    /* ================= 工具 ================= */

    private void applyToken(XzSite s, Map<String, String> body) {
        String token = body.get("token");
        // 更新时密钥传空串/缺省 = 保持原值；只有显式给了非空值才覆盖
        if (token != null && !token.isBlank()) s.setApiToken(token.trim());
    }

    /** 规范化接口地址：补协议、去尾斜杠；不合法返回 null */
    static String normalizeApiUrl(String raw) {
        if (raw == null) return null;
        String u = raw.trim();
        if (u.isEmpty()) return null;
        if (!u.matches("(?i)^https?://.+$")) u = "https://" + u;
        try {
            URI uri = URI.create(u);
            if (uri.getHost() == null || uri.getHost().isBlank()) return null;
            while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
            return u;
        } catch (Exception e) {
            return null;
        }
    }

    private static String defaultName(String username, String apiUrl) {
        String host = "";
        try {
            host = URI.create(apiUrl).getHost();
        } catch (Exception ignore) { /* 保持空 */ }
        return (host == null || host.isBlank()) ? username : username + "@" + host;
    }

    /** 添加/更新后顺手校验连通性与密钥；失败不拦截，只回提示文案 */
    private String probeQuietly(XzSite s) {
        if (s.getApiToken() == null || s.getApiToken().isBlank()) {
            return "未填写对接密钥，仅能浏览，无法导入（对方接口需要 X-API-Token）";
        }
        try {
            client.verifyCredentials(s);
            return null;
        } catch (Exception e) {
            return "已保存，但连通性/密钥校验未通过（" + e.getMessage() + "），可稍后在列表里点「测试连接」重试";
        }
    }
}
