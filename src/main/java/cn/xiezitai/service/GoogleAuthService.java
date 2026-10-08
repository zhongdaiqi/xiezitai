package cn.xiezitai.service;

import cn.xiezitai.entity.BloggerSite;
import cn.xiezitai.repository.BloggerSiteRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Google OAuth 2.0（授权码模式）—— 拿授权、换令牌、续期。
 *
 * <p>流程（必须理解的三步）：
 * <ol>
 *   <li><b>发起</b>：{@code GoogleAuthController} 用已登录的管理员身份调
 *       {@link #authorizeUrl(String, String)} 拿一个 {@code accounts.google.com} 的授权地址，
 *       前端在弹窗里打开它；<b>用户同意后 Google 只回调到一个「已经注册过」的 redirect_uri</b>，
 *       所以这里的地址必须与 Google Cloud Console 里登记的完全一致；</li>
 *   <li><b>回调</b>：Google 把浏览器带回 {@code /google/auth/redirect?code=..&state=..}，
 *       校验 {@link #verifyState(String)} 后拿 code 去 {@link #exchange(String, String)} 换令牌；</li>
 *   <li><b>续期</b>：access token 只有 1 小时，之后用 refresh token 换新的（{@link #accessToken(BloggerSite)}）。</li>
 * </ol>
 *
 * <p><b>敏感信息边界</b>：client_id / client_secret 只从配置（环境变量）读，<b>不落库、不入仓库</b>；
 * refresh / access token 落库但序列化永远 WRITE_ONLY。日志里一律不打印任何令牌。
 *
 * <p><b>为什么 state 要自己签名</b>：{@code /google/auth/redirect} 是公开端点（浏览器跳转带不了
 * Authorization 头）。若不校验 state，任何人都能把自己的 Google 账号授权到本站、凭空塞进一堆博客。
 * 方案：state = base64url(payload) + "." + HMAC(payload)，payload 里含「发起时所在的 redirect_uri」
 * 与过期时间 —— 于是回调既可信、又天然知道该用哪个回调地址（多域名站点靠这个才不会串）。
 */
@Service
public class GoogleAuthService {

    private static final Logger log = LoggerFactory.getLogger(GoogleAuthService.class);

    /** Blogger 读写权限 + openid/email（后者让 Google 在 id_token 里带上账号邮箱） */
    public static final String SCOPE = "openid email https://www.googleapis.com/auth/blogger";

    /** state 有效期：10 分钟足够走完一次授权 */
    private static final long STATE_TTL_SECONDS = 600;

    private static final String STATE_PATH = "/google/auth/redirect";

    private final String clientId;
    private final String clientSecret;
    private final String authUri;
    private final String tokenUri;
    /** 显式指定回调地址；留空则按发起时的请求推导（见 {@link #redirectUriFor}） */
    private final String redirectUriOverride;

    /** state 签名密钥复用 JWT 密钥（同一个部署里只有一个站点密钥，没必要再多一份配置） */
    private final byte[] signKey;

    private final BloggerSiteRepository sites;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    /** 一次换令牌的结果（id_token 里解出账号邮箱与显示名） */
    public record Tokens(String accessToken, String refreshToken, LocalDateTime expiresAt,
                         String email, String name) {}

    public GoogleAuthService(BloggerSiteRepository sites,
                             @Value("${xiezitai.google.client-id:}") String clientId,
                             @Value("${xiezitai.google.client-secret:}") String clientSecret,
                             @Value("${xiezitai.google.auth-uri:https://accounts.google.com/o/oauth2/v2/auth}") String authUri,
                             @Value("${xiezitai.google.token-uri:https://oauth2.googleapis.com/token}") String tokenUri,
                             @Value("${xiezitai.google.redirect-uri:}") String redirectUriOverride,
                             @Value("${xiezitai.jwt-secret:xiezitai-default-secret-change-me-0123456789abcdef}") String jwtSecret) {
        this.sites = sites;
        this.clientId = trim(clientId);
        this.clientSecret = trim(clientSecret);
        this.authUri = trim(authUri);
        this.tokenUri = trim(tokenUri);
        this.redirectUriOverride = trim(redirectUriOverride);
        this.signKey = jwtSecret == null ? new byte[0] : jwtSecret.getBytes(StandardCharsets.UTF_8);
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }

    /** 是否配置了 OAuth 客户端（没配就只能看不能关联，前端据此给出提示） */
    public boolean configured() { return !clientId.isEmpty() && !clientSecret.isEmpty(); }

    public String clientId() { return clientId; }

    /**
     * 回调地址：显式配置优先，否则 {@code {scheme}://{host}} + {@code /google/auth/redirect}。
     *
     * <p>host / scheme 要从**反向代理转发的头**里取 —— 站点跑在 nginx 后面时，
     * 应用看到的是 {@code http://127.0.0.1:8080}，而 Google 那边登记的是
     * {@code https://xiezitai.cn/google/auth/redirect}，直接用容器内的地址会被 Google 判「redirect_uri_mismatch」。
     */
    public String redirectUriFor(String scheme, String host) {
        if (!redirectUriOverride.isEmpty()) return redirectUriOverride;
        String s = trim(scheme).isEmpty() ? "https" : trim(scheme);
        String h = trim(host);
        if (h.isEmpty()) return "";
        return s + "://" + h + STATE_PATH;
    }

    /* ================= 发起授权 ================= */

    /** 首参沿用请求推导出的回调地址，直接落进签名 state（回调时照抄，不再推导一次） */
    public String authorizeUrl(String redirectUri) {
        return authUri
                + "?client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc(SCOPE)
                // offline：要 refresh token（不然 1 小时后就得重新授权）
                // prompt=consent：强制执行「同意」页 —— 否则第二次授权不会重新下发 refresh token
                + "&access_type=offline&prompt=consent&include_granted_scopes=true"
                + "&state=" + enc(newState(redirectUri));
    }

    /** state = base64url(payload).HMAC(payload)；payload 里带回调地址与过期时间 */
    String newState(String redirectUri) {
        long exp = Instant.now().getEpochSecond() + STATE_TTL_SECONDS;
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("r", redirectUri);
        payload.put("n", Base64.getUrlEncoder().withoutPadding().encodeToString(nonce));
        payload.put("e", exp);
        String body;
        try {
            body = b64(mapper.writeValueAsBytes(payload));
        } catch (Exception e) {
            // 理论上不会发生（payload 就是我们自己拼的 Map）；真发生了也不能静默放行
            throw new IllegalStateException("构造 OAuth state 失败：" + e.getMessage(), e);
        }
        return body + "." + sign(body);
    }

    /**
     * 校验 state 并取回当初的回调地址。
     *
     * @throws IllegalStateException 签名不对 / 已过期 / 结构损坏
     */
    public String verifyState(String state) {
        if (state == null || state.isBlank()) throw new IllegalStateException("缺少 state 参数");
        int dot = state.lastIndexOf('.');
        if (dot <= 0 || dot == state.length() - 1) throw new IllegalStateException("state 格式不正确");
        String body = state.substring(0, dot);
        String sig = state.substring(dot + 1);
        if (!constantTimeEquals(sign(body), sig)) {
            throw new IllegalStateException("state 校验失败（可能不是本站发起的授权，或密钥已变更）");
        }
        JsonNode p;
        try {
            p = mapper.readTree(Base64.getUrlDecoder().decode(body));
        } catch (Exception e) {
            throw new IllegalStateException("state 无法解析");
        }
        long exp = p.path("e").asLong(0);
        if (Instant.now().getEpochSecond() > exp) {
            throw new IllegalStateException("授权链接已过期，请回到后台重新点「关联 Google 账号」");
        }
        String r = p.path("r").asText("");
        if (r.isBlank()) throw new IllegalStateException("state 里缺少回调地址");
        return r;
    }

    /* ================= 换令牌 / 续期 ================= */

    /**
     * 授权码换令牌（{@code POST /token}，form-urlencoded 而不是 JSON —— Google 的令牌端点只吃表单）。
     *
     * <p>refresh token 只在「首次授权」或 {@code prompt=consent} 时下发；拿不到时返回空串，
     * 由调用方保留库里已有的那个（否则重新授权会把能用的续期凭据抹掉）。
     */
    public Tokens exchange(String code, String redirectUri) throws IOException, InterruptedException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("code", code);
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("redirect_uri", redirectUri);
        form.put("grant_type", "authorization_code");
        JsonNode r = postForm(form);
        String access = r.path("access_token").asText("");
        if (access.isBlank()) throw new IOException("Google 未返回访问令牌：" + snippet(r.toString()));
        String refresh = r.path("refresh_token").asText("");
        long expiresIn = r.path("expires_in").asLong(3600);
        String[] who = identityOf(r.path("id_token").asText(""));
        return new Tokens(access, refresh, LocalDateTime.now().plusSeconds(Math.max(expiresIn - 60, 60)),
                who[0], who[1]);
    }

    /**
     * 拿一个可用的访问令牌：还没过期就直接用，过期/缺失就用 refresh token 换新的并落库。
     *
     * @throws IOException 没有 refresh token，或 Google 拒绝了续期请求
     */
    public String accessToken(BloggerSite site) throws IOException, InterruptedException {
        String token = site.getAccessToken();
        LocalDateTime exp = site.getTokenExpiresAt();
        if (token != null && !token.isBlank() && exp != null && exp.isAfter(LocalDateTime.now())) {
            return token;
        }
        if (!site.isHasAuth()) {
            throw new IOException("该博客未完成 Google 授权（没有刷新令牌），请重新关联");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        form.put("refresh_token", site.getRefreshToken());
        form.put("grant_type", "refresh_token");
        JsonNode r = postForm(form);
        String access = r.path("access_token").asText("");
        if (access.isBlank()) throw new IOException("Google 续期失败：" + snippet(r.toString()));
        long expiresIn = r.path("expires_in").asLong(3600);
        site.setAccessToken(access);
        site.setTokenExpiresAt(LocalDateTime.now().plusSeconds(Math.max(expiresIn - 60, 60)));
        sites.save(site);
        return access;
    }

    private JsonNode postForm(Map<String, String> form) throws IOException, InterruptedException {
        if (!configured()) {
            throw new IOException("本站未配置 Google OAuth 客户端（需要环境变量 GOOGLE-XIEZITAI-CLIENTID / "
                    + "GOOGLE-XIEZITAI-CLIENT_SECRET），无法完成授权");
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (sb.length() > 0) sb.append('&');
            sb.append(enc(e.getKey())).append('=').append(enc(e.getValue()));
        }
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(tokenUri))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
                .header("Accept", "application/json")
                .header("User-Agent", "xiezitai-blogger-oauth")
                .POST(HttpRequest.BodyPublishers.ofString(sb.toString(), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IOException("Google 令牌接口返回 HTTP " + res.statusCode() + "：" + snippet(res.body()));
        }
        return mapper.readTree(res.body());
    }

    /* ================= id_token / 工具 ================= */

    /**
     * 从 id_token 里解出 {@code [email, name]}。
     *
     * <p>不做签名校验：这个令牌是**刚刚**由本站直连 Google 的令牌端点（TLS）拿回来的，
     * 不是从浏览器传上来的，没有可被篡改的中间环节。解不出来就退回空串（只影响展示名）。
     */
    static String[] identityOf(String idToken) {
        if (idToken == null || idToken.isBlank()) return new String[]{"", ""};
        try {
            String[] parts = idToken.split("\\.");
            if (parts.length < 2) return new String[]{"", ""};
            JsonNode p = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[1]));
            String email = p.path("email").asText("");
            String name = p.path("name").asText("");
            if (name.isBlank()) name = email;
            return new String[]{email, name};
        } catch (Exception e) {
            return new String[]{"", ""};
        }
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signKey, "HmacSHA256"));
            return b64(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("state 签名失败：" + e.getMessage(), e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64(byte[] raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String snippet(String body) {
        if (body == null) return "";
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }

    /** epoch 秒 → LocalDateTime（系统时区） */
    static LocalDateTime ofEpoch(long seconds) {
        return LocalDateTime.ofInstant(Instant.ofEpochSecond(seconds), ZoneId.systemDefault());
    }

    /** 打印一条不含任何令牌的授权结果日志 */
    public void logAssociated(String email, int blogCount) {
        log.info("Google 账号授权成功：{}（本次可见 {} 个博客）", email, blogCount);
    }
}
