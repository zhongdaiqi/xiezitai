package cn.xiezitai.controller;

import cn.xiezitai.entity.BloggerSite;
import cn.xiezitai.repository.BloggerSiteRepository;
import cn.xiezitai.service.BloggerClient;
import cn.xiezitai.service.GoogleAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Google OAuth 2.0 回调端点：{@code GET /google/auth/redirect}。
 *
 * <p><b>为什么是公开端点</b>：这个地址是 Google 拿浏览器跳回来的（服务端 302），
 * 浏览器跳转带不了 {@code Authorization: Bearer} 头，所以它不能挂在 {@code /api/admin/**} 下。
 * 安全性由 <b>签名 state</b> 保证：state 只有在本站已登录的管理员调
 * {@code POST /api/admin/blogger/oauth/url} 时才由本站签发（HMAC），有效期 10 分钟，
 * 且里面带着「发起时所在的回调地址」。没有有效 state，任何人都无法凭空把博客关联进来。
 *
 * <p>回调地址必须在 Google Cloud Console 的「已获授权的重定向 URI」里逐个登记，
 * 形如 {@code https://xiezitai.cn/google/auth/redirect}（本站支持多域名，所以每个常用域名都要登记）。
 *
 * <p>处理完不返回 JSON，而是返回一张极小的页面：把结果 {@code postMessage} 给打开它的后台窗口
 * 并自动关闭；万一不是弹窗打开的（用户在同一个标签页里点了授权），就退回跳转到后台 Blogger 面板。
 */
@RestController
public class GoogleOAuthController {

    private static final Logger log = LoggerFactory.getLogger(GoogleOAuthController.class);

    private final GoogleAuthService auth;
    private final BloggerClient client;
    private final BloggerSiteRepository sites;

    public GoogleOAuthController(GoogleAuthService auth, BloggerClient client, BloggerSiteRepository sites) {
        this.auth = auth;
        this.client = client;
        this.sites = sites;
    }

    @GetMapping(value = "/google/auth/redirect", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> redirect(@RequestParam(required = false) String code,
                                           @RequestParam(required = false) String state,
                                           @RequestParam(required = false) String error,
                                           @RequestParam(name = "error_description", required = false) String errorDesc) {
        String redirectUri;
        try {
            redirectUri = auth.verifyState(state);
        } catch (Exception e) {
            return page(false, "", 0, e.getMessage());
        }
        if (error != null && !error.isBlank()) {
            // 用户在同意页点了「取消」，或 Google 侧拒绝 —— 不是故障，如实回一句
            return page(false, "", 0, "Google 授权未完成：" + (errorDesc == null || errorDesc.isBlank() ? error : errorDesc));
        }
        if (code == null || code.isBlank()) return page(false, "", 0, "回调缺少 code 参数");

        try {
            GoogleAuthService.Tokens tokens = auth.exchange(code, redirectUri);
            List<BloggerClient.BlogInfo> blogs = client.listBlogs(tokens.accessToken());
            String email = tokens.email().isBlank() ? "google-account" : tokens.email();
            List<String> names = new ArrayList<>();
            for (BloggerClient.BlogInfo b : blogs) {
                upsert(b, tokens, email);
                names.add(nz(b.name(), b.url()));
            }
            auth.logAssociated(email, blogs.size());
            if (blogs.isEmpty()) {
                return page(false, email, 0,
                        "授权成功，但该 Google 账号下没有可见的博客（Blogger 里可能还没建博客，或该账号无权访问）");
            }
            return page(true, email, blogs.size(), "已关联 " + blogs.size() + " 个博客：" + String.join("、", names));
        } catch (Exception e) {
            log.warn("Google OAuth 回调处理失败：{}", e.getMessage());
            return page(false, "", 0, "授权失败：" + e.getMessage());
        }
    }

    /** 一个博客一行：同一个博客重复授权只刷新令牌与展示名，不会长出重复行 */
    private void upsert(BloggerClient.BlogInfo b, GoogleAuthService.Tokens t, String email) {
        if (b.id() == null || b.id().isBlank()) return;
        BloggerSite s = sites.findByBlogId(b.id()).orElseGet(BloggerSite::new);
        s.setBlogId(b.id());
        s.setName(nz(b.name(), b.url()));
        s.setUrl(b.url());
        s.setGoogleEmail(email);
        s.setAccountName(t.name());
        // 拿不到新的 refresh token（Google 只在首次授权/强制同意时下发）就保留库里那个，
        // 否则重新授权反而会把能用的续期凭据抹掉
        if (t.refreshToken() != null && !t.refreshToken().isBlank()) s.setRefreshToken(t.refreshToken());
        s.setAccessToken(t.accessToken());
        s.setTokenExpiresAt(t.expiresAt());
        sites.save(s);
    }

    private static String nz(String a, String b) { return a == null || a.isBlank() ? (b == null ? "" : b) : a; }

    /** 结果页：极简白底，自动通知后台并关闭 */
    private static ResponseEntity<String> page(boolean ok, String email, int count, String message) {
        String payload = "{\"ok\":" + ok
                + ",\"email\":\"" + js(email) + "\""
                + ",\"count\":" + count
                + ",\"message\":\"" + js(message) + "\"}";
        String title = ok ? "关联成功" : "关联未完成";
        String html = "<!doctype html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n"
                + "<title>" + esc(title) + "</title>\n"
                + "<style>body{margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;"
                + "background:#f7f7f8;color:#1f2328;font:15px/1.7 -apple-system,'Segoe UI','Microsoft YaHei',sans-serif}"
                + "div{max-width:420px;padding:28px 30px;background:#fff;border:1px solid #e5e7eb;border-radius:14px;text-align:center}"
                + "b{display:block;font-size:17px;margin-bottom:8px}p{margin:0;color:#57606a;font-size:13px;word-break:break-all}"
                + "</style>\n</head>\n<body>\n<div>\n<b>" + esc(title) + "</b>\n<p>" + esc(message) + "</p>\n"
                + "<p id=\"tip\" style=\"margin-top:12px\">正在返回后台…</p>\n</div>\n"
                + "<script>\nvar result = " + payload + ";\n"
                + "try{ if(window.opener){ window.opener.postMessage({type:'blogger-oauth', result:result}, location.origin); } }catch(e){}\n"
                + "setTimeout(function(){ if(window.opener){ window.close(); } else { location.replace('/admin.html#blogger'); } }, 600);\n"
                + "</script>\n</body>\n</html>\n";
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_TYPE, "text/html;charset=UTF-8").body(html);
    }

    private static String js(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("<", "\\u003c")
                .replace(">", "\\u003e").replace("\n", " ").replace("\r", " ");
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
