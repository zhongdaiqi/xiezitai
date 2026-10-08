package cn.xiezitai.controller;

import cn.xiezitai.entity.CnBlogSite;
import cn.xiezitai.entity.DistRecord;
import cn.xiezitai.repository.CnBlogSiteRepository;
import cn.xiezitai.repository.DistRecordRepository;
import cn.xiezitai.service.CnBlogImportService;
import cn.xiezitai.service.MetaWeblogClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 博客园关联与导入接口（/api/admin/cnblogs/**，登录后可用）。
 *
 * <p>密钥安全：{@link CnBlogSite#getAppKey} 是 WRITE_ONLY，任何响应都不会带出明文；
 * 更新时密钥传空串表示「保持原值」。
 */
@RestController
@RequestMapping("/api/admin/cnblogs")
public class CnBlogController {

    private static final Logger log = LoggerFactory.getLogger(CnBlogController.class);

    /** 浏览文章列表一次拉取的上限（MetaWeblog 无分页游标，内存里翻页/过滤） */
    private static final int BROWSE_MAX = 500;

    private final CnBlogSiteRepository sites;
    private final MetaWeblogClient client;
    private final CnBlogImportService imports;
    private final DistRecordRepository distRecords;

    public CnBlogController(CnBlogSiteRepository sites, MetaWeblogClient client,
                            CnBlogImportService imports, DistRecordRepository distRecords) {
        this.sites = sites;
        this.client = client;
        this.imports = imports;
        this.distRecords = distRecords;
    }

    /* ================= 账号管理 ================= */

    @GetMapping("/sites")
    public List<CnBlogSite> listSites() {
        return sites.findAll();
    }

    /**
     * 关联账号。接口地址会做规范化（补 https://、去尾斜杠）；
     * 添加时顺带校验凭据（blogger.getUsersBlogs）—— 失败不拦截保存，
     * 把原因放在 warning 里回给前端展示。
     */
    @PostMapping("/sites")
    public ResponseEntity<?> create(@RequestBody Map<String, String> body) {
        String url = normalizeUrl(body.get("url"));
        if (url == null) return ResponseEntity.badRequest().body(Map.of("error", "请填写正确的 MetaWeblog 接口地址"));
        if (sites.existsByUrl(url)) {
            return ResponseEntity.status(409).body(Map.of("error", "该接口地址已关联：" + url));
        }
        CnBlogSite s = new CnBlogSite();
        s.setUrl(url);
        applyCredentials(s, body);
        s.setName(body.get("name") == null || body.get("name").isBlank()
                ? (s.getUsername() == null || s.getUsername().isBlank() ? hostOf(url) : s.getUsername())
                : body.get("name").trim());
        CnBlogSite saved = sites.save(s);
        String warning = probeQuietly(saved);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("site", saved);
        if (warning != null) out.put("warning", warning);
        return ResponseEntity.ok(out);
    }

    /** 更新账号（密钥留空 = 保持原值） */
    @PutMapping("/sites/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        CnBlogSite s = sites.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        String url = normalizeUrl(body.get("url"));
        if (url != null && !url.equals(s.getUrl())) {
            if (sites.existsByUrl(url)) {
                return ResponseEntity.status(409).body(Map.of("error", "该接口地址已关联：" + url));
            }
            s.setUrl(url);
        }
        applyCredentials(s, body);
        return ResponseEntity.ok(sites.save(s));
    }

    @DeleteMapping("/sites/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!sites.existsById(id)) return ResponseEntity.notFound().build();
        // 指向该账号的分发记录一并清掉，避免列表上挂着一堆点不开的历史目标
        distRecords.deleteByChannelAndTargetId(DistRecord.CHANNEL_CNBLOG, id);
        sites.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    /** 连通性 / 凭据测试 */
    @PostMapping("/sites/{id}/test")
    public ResponseEntity<?> test(@PathVariable Long id) {
        CnBlogSite s = sites.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        try {
            List<Map<String, Object>> blogs = client.verifyCredentials(s);
            Map<String, Object> b = blogs.get(0);
            return ResponseEntity.ok(Map.of(
                    "blogName", String.valueOf(b.getOrDefault("blogName", "")),
                    "blogUrl", String.valueOf(b.getOrDefault("url", "")),
                    "credentials", "ok"));
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "连接失败：" + e.getMessage()));
        }
    }

    /* ================= 浏览与导入 ================= */

    /**
     * 浏览账号文章列表（拉最近 BROWSE_MAX 篇，内存里按标题过滤 + 翻页）。
     * MetaWeblog 的 getRecentPosts 没有分页游标，博客园账号文章量级（几十~几百）一次足够。
     */
    @GetMapping("/sites/{id}/posts")
    public ResponseEntity<?> posts(@PathVariable Long id,
                                   @RequestParam(defaultValue = "1") int page,
                                   @RequestParam(defaultValue = "10") int perPage,
                                   @RequestParam(required = false) String search) {
        CnBlogSite s = sites.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        try {
            List<MetaWeblogClient.CnPost> all = client.getRecentPosts(s, BROWSE_MAX);
            List<MetaWeblogClient.CnPost> filtered = new ArrayList<>(all);
            if (search != null && !search.isBlank()) {
                String kw = search.toLowerCase();
                filtered.removeIf(p -> p.title() == null || !p.title().toLowerCase().contains(kw));
            }
            int total = filtered.size();
            int size = Math.min(Math.max(perPage, 1), 50);
            int pages = Math.max((total + size - 1) / size, 1);
            int idx = Math.min(Math.max(page, 1), pages);
            List<Map<String, Object>> posts = new ArrayList<>();
            for (MetaWeblogClient.CnPost p : filtered.subList((idx - 1) * size, Math.min(idx * size, total))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.postId());
                m.put("title", p.title());
                m.put("date", p.dateCreated() == null ? "" : p.dateCreated().toString());
                m.put("status", "publish");
                m.put("link", p.link());
                posts.add(m);
            }
            return ResponseEntity.ok(Map.of("posts", posts, "total", total, "totalPages", pages));
        } catch (Exception e) {
            log.warn("博客园文章列表拉取失败 site={}: {}", id, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "拉取文章列表失败：" + e.getMessage()));
        }
    }

    /** 导入单篇（同步执行，媒体较多时可能耗时几十秒） */
    @PostMapping("/sites/{id}/import")
    public ResponseEntity<?> importOne(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        CnBlogSite s = sites.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        Object pid = body.get("postId");
        if (!(pid instanceof Number n)) {
            return ResponseEntity.badRequest().body(Map.of("error", "缺少 postId"));
        }
        boolean useWpDate = !Boolean.FALSE.equals(body.get("useWpDate"));
        String onConflict = "update".equals(body.get("onConflict")) ? "update" : "skip";
        try {
            return ResponseEntity.ok(imports.importSingle(s, n.longValue(), useWpDate, onConflict));
        } catch (Exception e) {
            log.warn("博客园单篇导入失败 site={} post={}: {}", id, pid, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "导入失败：" + e.getMessage()));
        }
    }

    /** 启动整站导入（后台线程）。返回 202，前端开始轮询 */
    @PostMapping("/sites/{id}/import-all")
    public ResponseEntity<?> importAll(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, Object> body) {
        CnBlogSite s = sites.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        boolean useWpDate = body == null || !Boolean.FALSE.equals(body.get("useWpDate"));
        String onConflict = body != null && "update".equals(body.get("onConflict")) ? "update" : "skip";
        CnBlogImportService.CnSyncProgress p = imports.startFullImport(s, useWpDate, onConflict);
        if (p == null) {
            return ResponseEntity.status(409).body(Map.of("error", "该账号已有整站导入任务在运行，请等它结束"));
        }
        return ResponseEntity.accepted().body(Map.of("started", true));
    }

    /** 整站导入进度（前端 1~2 秒轮询一次） */
    @GetMapping("/sites/{id}/progress")
    public ResponseEntity<?> progress(@PathVariable Long id) {
        CnBlogImportService.CnSyncProgress p = imports.progressOf(id);
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
        out.put("current", p.current);
        out.put("error", p.error);
        out.put("messages", List.copyOf(p.messages));
        return ResponseEntity.ok(out);
    }

    /* ================= 工具 ================= */

    private void applyCredentials(CnBlogSite s, Map<String, String> body) {
        String user = body.get("username");
        if (user != null) s.setUsername(user.trim());
        String key = body.get("token");
        // 更新时密钥传空串/缺省 = 保持原值；只有显式给了非空值才覆盖
        if (key != null && !key.isBlank()) s.setAppKey(key.trim());
    }

    /** 规范化接口地址：补协议、去尾斜杠；不合法返回 null */
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

    /** 添加/更新后顺手校验凭据；失败不拦截，只回提示文案 */
    private String probeQuietly(CnBlogSite s) {
        try {
            client.verifyCredentials(s);
            return null;
        } catch (Exception e) {
            return "账号暂时校验失败（" + e.getMessage() + "），已保存，稍后可在列表里点「测试连接」";
        }
    }
}
