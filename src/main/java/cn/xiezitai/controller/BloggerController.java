package cn.xiezitai.controller;

import cn.xiezitai.entity.BloggerSite;
import cn.xiezitai.entity.DistRecord;
import cn.xiezitai.repository.BloggerSiteRepository;
import cn.xiezitai.repository.DistRecordRepository;
import cn.xiezitai.service.BloggerClient;
import cn.xiezitai.service.BloggerImportService;
import cn.xiezitai.service.GoogleAuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 谷歌 Blogger 账号关联与导入接口（{@code /api/admin/blogger/**}，登录后可用）。
 *
 * <p>与其它渠道最大的差别是<b>凭据来自 OAuth，不是用户手填</b>：
 * <ol>
 *   <li>前端调 {@code POST /oauth/url} 拿到 Google 授权地址，在弹窗里打开；</li>
 *   <li>用户同意后 Google 把浏览器带回公开回调 {@code /google/auth/redirect}（见
 *       {@link GoogleOAuthController}），那里换令牌、列博客、落库；</li>
 *   <li>回到前台后，这里的 {@code /sites} 就有内容了 —— 之后既是<b>导入源</b>
 *       （{@code /sites/{id}/posts|import|import-all}）又是<b>分发目标</b>
 *       （见 {@code /api/admin/dist/targets}）。</li>
 * </ol>
 *
 * <p>令牌安全：{@link BloggerSite} 的 refresh / access token 都是 WRITE_ONLY，
 * 接口只回 {@code hasAuth} 布尔值；client_id / client_secret 只从环境变量读，不落库不入仓库。
 */
@RestController
@RequestMapping("/api/admin/blogger")
public class BloggerController {

    private static final Logger log = LoggerFactory.getLogger(BloggerController.class);

    private final BloggerSiteRepository sites;
    private final GoogleAuthService auth;
    private final BloggerClient client;
    private final BloggerImportService imports;
    private final DistRecordRepository distRecords;

    public BloggerController(BloggerSiteRepository sites, GoogleAuthService auth, BloggerClient client,
                             BloggerImportService imports, DistRecordRepository distRecords) {
        this.sites = sites;
        this.auth = auth;
        this.client = client;
        this.imports = imports;
        this.distRecords = distRecords;
    }

    /* ================= OAuth 发起 ================= */

    /**
     * 生成本次授权要用的 Google 授权地址。
     *
     * <p>回调地址在这里一次定死（写进签名 state）：从**当前请求**的协议与主机推导，
     * 而不是拿站点根地址拼 —— 站点可能同时挂在 {@code xiezitai.cn} / {@code www.xiezitai.cn} /
     * 自定义域名上，而 Google 那边的「已获授权的重定向 URI」是逐个登记的，
     * 只有和用户此刻所在域名一致才不会踩 {@code redirect_uri_mismatch}。
     *
     * <p>反向代理会把协议与主机放在 {@code X-Forwarded-Proto} / {@code X-Forwarded-Host} 里，
     * 应用自己看到的是容器内的 {@code http://127.0.0.1:8080}，所以转发头优先。
     */
    @PostMapping("/oauth/url")
    public ResponseEntity<?> oauthUrl(HttpServletRequest request) {
        if (!auth.configured()) {
            return ResponseEntity.status(503).body(Map.of("error",
                    "本站未配置 Google OAuth 客户端。需要在部署环境里提供 "
                            + "GOOGLE-XIEZITAI-CLIENTID 与 GOOGLE-XIEZITAI-CLIENT_SECRET（或 "
                            + "XIEZITAI_GOOGLE_CLIENT_ID / XIEZITAI_GOOGLE_CLIENT_SECRET）后重启。"));
        }
        String scheme = firstHeader(request, "X-Forwarded-Proto", request.getScheme());
        String host = firstHeader(request, "X-Forwarded-Host", request.getHeader("Host"));
        String redirectUri = auth.redirectUriFor(scheme, host);
        if (redirectUri.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "无法确定授权回调地址，请检查反向代理的转发头"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("url", auth.authorizeUrl(redirectUri));
        out.put("redirectUri", redirectUri);
        out.put("configured", true);
        return ResponseEntity.ok(out);
    }

    /** 取转发头（可能是逗号分隔的一串，只认第一段），为空则用兜底值 */
    static String firstHeader(HttpServletRequest request, String name, String fallback) {
        String v = request.getHeader(name);
        if (v == null || v.isBlank()) return fallback;
        String first = v.split(",")[0].trim();
        return first.isBlank() ? fallback : first;
    }

    /* ================= 博客（关联结果）管理 ================= */

    @GetMapping("/sites")
    public List<BloggerSite> listSites() {
        return sites.findAll();
    }

    /** 关联状态：前端据此决定「关联 Google 账号」按钮是否可点 */
    @GetMapping("/status")
    public Map<String, Object> status() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", auth.configured());
        out.put("count", sites.count());
        return out;
    }

    @DeleteMapping("/sites/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!sites.existsById(id)) return ResponseEntity.notFound().build();
        // 博客关联删了，指向它的分发记录也留不住 —— 留着只是列表里点不开的悬空徽标
        distRecords.deleteByChannelAndTargetId(DistRecord.CHANNEL_BLOGGER, id);
        sites.deleteById(id);
        return ResponseEntity.ok(Map.of("message", "已解除关联"));
    }

    /** 解除整个 Google 账号：该账号下的博客关联与分发记录一并清掉 */
    @DeleteMapping("/accounts")
    public ResponseEntity<?> deleteAccount(@RequestParam String email) {
        String e = email == null ? "" : email.trim();
        if (e.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "缺少 email"));
        List<BloggerSite> mine = sites.findByGoogleEmail(e);
        if (mine.isEmpty()) return ResponseEntity.notFound().build();
        for (BloggerSite s : mine) {
            distRecords.deleteByChannelAndTargetId(DistRecord.CHANNEL_BLOGGER, s.getId());
        }
        sites.deleteByGoogleEmail(e);
        return ResponseEntity.ok(Map.of("message", "已解除 " + mine.size() + " 个博客的关联", "removed", mine.size()));
    }

    /** 连通性 / 授权有效性校验 */
    @PostMapping("/sites/{id}/test")
    public ResponseEntity<?> test(@PathVariable Long id) {
        BloggerSite s = sites.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        try {
            BloggerClient.BlogInfo info = client.blogInfo(s);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("blog", info.name());
            out.put("blogUrl", info.url());
            out.put("totalPosts", info.totalPosts());
            out.put("account", s.getGoogleEmail());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error", "连接失败：" + e.getMessage()));
        }
    }

    /* ================= 浏览与导入 ================= */

    /**
     * 浏览该博客的文章列表。
     *
     * <p>Blogger 的列表接口是 {@code pageToken} 翻页（没有页码、也没有总数），
     * 所以这里把 token 原样透传给前端，由前端维护一个 token 栈来实现「上一页」；
     * 总数另外取一次博客信息补上，纯粹为了给用户一个「共 N 篇」的量感。
     */
    @GetMapping("/sites/{id}/posts")
    public ResponseEntity<?> posts(@PathVariable Long id, @RequestParam(required = false) String pageToken) {
        BloggerSite s = sites.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        try {
            BloggerClient.PostPage page = client.listPosts(s, pageToken, false);
            long total = -1;
            try {
                total = client.blogInfo(s).totalPosts();
            } catch (Exception ignore) { /* 总数只是锦上添花，取不到不影响列表 */ }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("posts", page.items());
            out.put("nextPageToken", page.nextPageToken());
            out.put("total", total);
            out.put("blogName", s.getName());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.warn("Blogger 文章列表拉取失败 site={}: {}", id, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "拉取文章列表失败：" + e.getMessage()));
        }
    }

    /** 导入单篇（同步执行，媒体较多时可能耗时几十秒） */
    @PostMapping("/sites/{id}/import")
    public ResponseEntity<?> importOne(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        BloggerSite s = sites.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        String postId = body.get("postId") == null ? "" : String.valueOf(body.get("postId")).trim();
        if (postId.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "缺少 postId"));
        boolean useSrcDate = !Boolean.FALSE.equals(body.get("useSrcDate"));
        String onConflict = "update".equals(body.get("onConflict")) ? "update" : "skip";
        try {
            return ResponseEntity.ok(imports.importSingle(s, postId, useSrcDate, onConflict));
        } catch (Exception e) {
            log.warn("Blogger 单篇导入失败 site={} post={}: {}", id, postId, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error", "导入失败：" + e.getMessage()));
        }
    }

    /** 启动整站导入（后台线程）。返回 202，前端开始轮询进度 */
    @PostMapping("/sites/{id}/import-all")
    public ResponseEntity<?> importAll(@PathVariable Long id,
                                      @RequestBody(required = false) Map<String, Object> body) {
        BloggerSite s = sites.findById(id).orElse(null);
        if (s == null) return ResponseEntity.notFound().build();
        boolean useSrcDate = body == null || !Boolean.FALSE.equals(body.get("useSrcDate"));
        String onConflict = body != null && "update".equals(body.get("onConflict")) ? "update" : "skip";
        BloggerImportService.BloggerSyncProgress p = imports.startFullImport(s, useSrcDate, onConflict);
        if (p == null) {
            return ResponseEntity.status(409).body(Map.of("error", "该博客已有整站导入任务在运行，请等它结束"));
        }
        return ResponseEntity.accepted().body(Map.of("started", true));
    }

    /** 整站导入进度（前端 1.2 秒轮询一次） */
    @GetMapping("/sites/{id}/progress")
    public ResponseEntity<?> progress(@PathVariable Long id) {
        BloggerImportService.BloggerSyncProgress p = imports.progressOf(id);
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
}
