package cn.xiezitai.controller;

import cn.xiezitai.entity.WpSite;
import cn.xiezitai.repository.WpSiteRepository;
import cn.xiezitai.service.WordPressClient;
import cn.xiezitai.service.WordPressImportService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WordPress 关联与导入接口（/api/admin/wp/**，登录后可用）。
 *
 * <p>Token 安全：{@link WpSite#getApiToken} 是 WRITE_ONLY，任何响应都不会带出明文；
 * 更新时 token 传空串表示「保持原值」。
 */
@RestController
@RequestMapping("/api/admin/wp")
public class WordPressController {

    private static final Logger log = LoggerFactory.getLogger(WordPressController.class);

    private final WpSiteRepository sites;
    private final WordPressClient client;
    private final WordPressImportService imports;

    public WordPressController(WpSiteRepository sites, WordPressClient client,
                               WordPressImportService imports) {
        this.sites = sites;
        this.client = client;
        this.imports = imports;
    }

    /* ================= 站点管理 ================= */

    @GetMapping("/sites")
    public List<WpSite> listSites() {
        return sites.findAll();
    }

    /**
     * 关联站点。网址会做规范化（补 https://、去尾斜杠）；
     * 添加时顺带探测连通性与凭据 —— 探测失败不拦截保存（站点可能临时打不开），
     * 但把原因放在 warning 里回给前端展示。
     */
    @PostMapping("/sites")
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        String url = normalizeUrl(body.get("url"));
        if (url == null) return ResponseEntity.badRequest().body(Map.of("error", "请填写正确的站点网址"));
        if (sites.existsByUrl(url)) {
            return ResponseEntity.status(409).body(Map.of("error", "该站点已关联：" + url));
        }
        WpSite s = new WpSite();
        s.setUrl(url);
        applyCredentials(s, body);
        s.setName(body.get("name") == null || body.get("name").isBlank()
                ? hostOf(url) : body.get("name").trim());
        WpSite saved = sites.save(s);
        String warning = probeQuietly(saved);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("site", saved);
        if (warning != null) out.put("warning", warning);
        return ResponseEntity.ok(out);
    }

    /** 更新站点（token 留空 = 保持原值） */
    @PutMapping("/sites/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        WpSite s = sites.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        String url = normalizeUrl(body.get("url"));
        if (url != null && !url.equals(s.getUrl())) {
            if (sites.existsByUrl(url)) {
                return ResponseEntity.status(409).body(Map.of("error", "该站点已关联：" + url));
            }
            s.setUrl(url);
            s.setName(hostOf(url));
        }
        applyCredentials(s, body);
        return ResponseEntity.ok(sites.save(s));
    }

    @DeleteMapping("/sites/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!sites.existsById(id)) return ResponseEntity.notFound().build();
        sites.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    /** 连通性 / 凭据测试 */
    @PostMapping("/sites/{id}/test")
    public ResponseEntity<?> test(@PathVariable Long id) {
        WpSite s = sites.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        try {
            Map<String, String> info = client.probe(s);
            info.put("credentials", "anonymous");
            if (hasCredentials(s)) {
                String name = client.verifyCredentials(s);
                info.put("credentials", "ok（" + name + "）");
            }
            return ResponseEntity.ok(info);
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "连接失败：" + e.getMessage()));
        }
    }

    /* ================= 浏览与导入 ================= */

    /** 浏览 WP 站点文章列表（代理 WP REST，前端翻页/搜索） */
    @GetMapping("/sites/{id}/posts")
    public ResponseEntity<?> posts(@PathVariable Long id,
                                   @RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "10") int perPage,
                                   @RequestParam(required = false) String search) {
        WpSite s = sites.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        try {
            WordPressClient.PostsPage pp = client.listPosts(s,
                    Math.max(page, 1), Math.min(Math.max(perPage, 1), 100), search);
            return ResponseEntity.ok(Map.of(
                    "posts", pp.posts(), "total", pp.total(), "totalPages", pp.totalPages()));
        } catch (Exception e) {
            log.warn("WP 文章列表拉取失败 site={}: {}", id, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "拉取文章列表失败：" + e.getMessage()));
        }
    }

    /**
     * 导入单篇（同步执行，媒体较多时可能耗时几十秒）。
     * useWpDate=false 时发布时间用当前时间；onConflict=update 时同 slug 已存在则用 WP 版本覆盖更新；
     * contentMode 控制正文格式：auto（默认，优先原文、HTML 自动转 Markdown）/ html2md / raw。
     */
    @PostMapping("/sites/{id}/import")
    public ResponseEntity<?> importOne(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        WpSite s = sites.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        Object pid = body.get("postId");
        if (!(pid instanceof Number n)) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 postId"));
        }
        boolean useWpDate = !Boolean.FALSE.equals(body.get("useWpDate"));
        String onConflict = "update".equals(body.get("onConflict")) ? "update" : "skip";
        String contentMode = WordPressImportService.normalizeContentMode(body.get("contentMode"));
        try {
            return ResponseEntity.ok(imports.importSingle(s, n.longValue(), useWpDate, onConflict, contentMode));
        } catch (Exception e) {
            log.warn("WP 单篇导入失败 site={} post={}: {}", id, pid, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "导入失败：" + e.getMessage()));
        }
    }

    /**
     * 启动整站导入（后台线程）；useWpDate=false 时发布时间用当前时间，
     * onConflict=update 时同 slug 已存在则覆盖更新。返回 202，前端开始轮询
     */
    @PostMapping("/sites/{id}/import-all")
    public ResponseEntity<?> importAll(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        WpSite s = sites.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        boolean useWpDate = body == null || !Boolean.FALSE.equals(body.get("useWpDate"));
        String onConflict = body != null && "update".equals(body.get("onConflict")) ? "update" : "skip";
        String contentMode = WordPressImportService.normalizeContentMode(body == null ? null : body.get("contentMode"));
        WordPressImportService.WpSyncProgress p = imports.startFullImport(s, useWpDate, onConflict, contentMode);
        if (p == null) {
            return ResponseEntity.status(409).body(Map.of("error", "该站点已有整站导入任务在运行，请等它结束"));
        }
        return ResponseEntity.accepted().body(Map.of("started", true));
    }

    /** 整站导入进度（前端 1~2 秒轮询一次） */
    @GetMapping("/sites/{id}/progress")
    public ResponseEntity<?> progress(@PathVariable Long id) {
        WordPressImportService.WpSyncProgress p = imports.progressOf(id);
        if (p == null) return ResponseEntity.ok(Map.of("phase", "IDLE"));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("phase", p.phase);
        out.put("total", p.total);
        out.put("done", p.done);
        out.put("imported", p.imported);
        out.put("updated", p.updated);
        out.put("skipped", p.skipped);
        out.put("failed", p.failed);
        out.put("useWpDate", p.useWpDate);
        out.put("onConflict", p.onConflict);
        out.put("contentMode", p.contentMode);
        out.put("current", p.current);
        out.put("error", p.error);
        out.put("messages", List.copyOf(p.messages));
        return ResponseEntity.ok(out);
    }

    /* ================= 工具 ================= */

    private void applyCredentials(WpSite s, Map<String, String> body) {
        String user = body.get("username");
        if (user != null) s.setUsername(user.trim());
        String token = body.get("token");
        // 更新时 token 传空串/缺省 = 保持原值；只有显式给了非空值才覆盖
        if (token != null && !token.isBlank()) s.setApiToken(token.trim());
    }

    /** 规范化网址：补协议、去尾斜杠；不合法返回 null */
    static String normalizeUrl(String raw) {
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

    static String hostOf(String url) {
        try {
            String host = URI.create(url).getHost();
            return host == null ? url : host;
        } catch (Exception e) {
            return url;
        }
    }

    private static boolean hasCredentials(WpSite s) {
        return s.getUsername() != null && !s.getUsername().isBlank()
                && s.getApiToken() != null && !s.getApiToken().isBlank();
    }

    /** 添加/更新后顺手探测连通性与凭据；失败不拦截，只回提示文案 */
    private String probeQuietly(WpSite s) {
        try {
            client.probe(s);
            if (hasCredentials(s)) {
                try {
                    client.verifyCredentials(s);
                    return null;
                } catch (Exception e) {
                    return "站点已连通，但用户名/Token 校验失败（" + e.getMessage() + "），匿名导入公开文章不受影响";
                }
            }
            return null;
        } catch (Exception e) {
            return "站点暂时连不上（" + e.getMessage() + "），已保存，稍后可在列表里点「测试连接」";
        }
    }
}
