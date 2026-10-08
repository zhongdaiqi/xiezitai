package cn.xiezitai.service;

import cn.xiezitai.entity.XzSite;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写字台开放 API 客户端（本站 ⇄ 另一台写字台）。
 *
 * <p>只依赖标准库 HttpClient + Jackson。用到的接口：
 * <ul>
 *   <li>{@code GET /api/v1/articles}                文章列表（分页 / 关键词，返回 JSON）</li>
 *   <li>{@code GET /api/v1/articles/{id}}           单篇文章（正文是 Markdown 原文）</li>
 *   <li>{@code POST /api/v1/publish}                发布一篇新文章（分发用）</li>
 *   <li>{@code PUT /api/v1/articles/{id}}           更新自己发过的文章（分发「更新」用）</li>
 *   <li>{@code GET /media/xxx}                      媒体文件下载</li>
 * </ul>
 * 鉴权统一用 {@code X-API-Token} 请求头（与发布接口同一个 Token）。
 *
 * <p>用户填的是「接口地址」（例如 {@code https://xiezitai.cn/api/v1/publish}），
 * 这里统一推导成 API 根 {@code .../api/v1} 再用 —— 见 {@link #apiRoot(String)}。
 */
@Service
public class XiezitaiClient {

    private static final Logger log = LoggerFactory.getLogger(XiezitaiClient.class);

    /** 单个媒体文件下载上限：对方站点自身的文件才落盘，超出按失败处理（保留外链） */
    public static final long MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 文章列表的一页结果 */
    public record ArticlesPage(List<Map<String, Object>> items, long total, int totalPages) {}

    /** 发布 / 更新成功后对方回来的文章标识；{@code url} 已补成绝对地址 */
    public record RemoteArticle(long id, String slug, String url) {}

    /**
     * 从用户填写的「接口地址」推导开放 API 根（约定以 {@code /api/v1} 结尾）。
     *
     * <p>容错：用户可能填 {@code .../api/v1/publish}（发布接口）、{@code .../api/v1/mcp}
     * （MCP 接口）、{@code .../api/v1}，也可能只填站点地址 —— 都能得出同一个根。
     */
    public static String apiRoot(String apiUrl) {
        String u = apiUrl == null ? "" : apiUrl.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        for (String tail : List.of("/publish", "/mcp", "/articles", "/articles/list")) {
            if (u.endsWith(tail)) {
                u = u.substring(0, u.length() - tail.length());
                break;
            }
        }
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/api/v1")) return u;
        return u.isEmpty() ? "" : u + "/api/v1";
    }

    /** 从接口地址取出站点根（{@code scheme://host[:port]}），用于拼媒体绝对地址与判断同主机 */
    public static String origin(String apiUrl) {
        String u = apiUrl == null ? "" : apiUrl.trim();
        if (u.isEmpty()) return "";
        if (!u.matches("(?i)^https?://.*$")) u = "https://" + u;
        try {
            URI uri = URI.create(u);
            String host = uri.getHost();
            if (host == null || host.isBlank()) return "";
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme();
            return uri.getPort() > 0 ? scheme + "://" + host + ":" + uri.getPort() : scheme + "://" + host;
        } catch (Exception e) {
            return "";
        }
    }

    /** GET 一个开放 API 端点，2xx 返回解析后的 JSON；否则抛带状态码的可读异常 */
    public JsonNode getJson(XzSite site, String pathAndQuery) throws IOException, InterruptedException {
        String root = apiRoot(site.getApiUrl());
        if (root.isEmpty()) throw new IOException("接口地址不合法：" + site.getApiUrl());
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(root + pathAndQuery))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "xiezitai-xz-import")
                .GET();
        String token = site.getApiToken();
        if (token != null && !token.isBlank()) b.header("X-API-Token", token.trim());
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IOException("对方接口返回 HTTP " + res.statusCode() + "：" + snippet(res.body()));
        }
        return mapper.readTree(res.body());
    }

    /**
     * 校验账号 + 对接密钥（顺带核对「填的账号」与「密钥所属账号」是否一致）。
     *
     * @return 接口返回的账号名；凭据无效 / 账号不匹配时抛 IOException
     */
    public String verifyCredentials(XzSite site) throws IOException, InterruptedException {
        JsonNode r = getJson(site, "/articles?size=1");
        String user = r.path("user").asText("");
        if (user.isBlank()) throw new IOException("对方接口未返回账号信息（可能不是写字台站点，或版本过旧）");
        String expect = site.getUsername() == null ? "" : site.getUsername().trim();
        if (!expect.isEmpty() && !expect.equalsIgnoreCase(user)) {
            throw new IOException("账号不匹配：该密钥属于「" + user + "」，与填写的「" + expect + "」不一致");
        }
        return user;
    }

    /** 拉文章列表一页（默认只含已发布文章） */
    public ArticlesPage listArticles(XzSite site, int page, int size, String q) throws IOException, InterruptedException {
        StringBuilder sb = new StringBuilder("/articles?page=").append(Math.max(page, 1))
                .append("&size=").append(Math.min(Math.max(size, 1), 100));
        if (q != null && !q.isBlank()) {
            sb.append("&q=").append(java.net.URLEncoder.encode(q.trim(), StandardCharsets.UTF_8));
        }
        JsonNode r = getJson(site, sb.toString());
        String origin = origin(site.getApiUrl());
        List<Map<String, Object>> items = new ArrayList<>();
        for (JsonNode n : r.path("items")) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.path("id").asLong());
            m.put("slug", n.path("slug").asText(""));
            m.put("title", n.path("title").asText(""));
            m.put("summary", n.path("summary").asText(""));
            m.put("tags", n.path("tags").asText(""));
            m.put("date", n.path("publishedAt").asText(""));
            String rel = n.path("url").asText("");
            m.put("link", rel.startsWith("/") ? origin + rel : rel);
            items.add(m);
        }
        return new ArticlesPage(items,
                r.path("total").asLong(items.size()),
                Math.max(r.path("totalPages").asInt(1), 1));
    }

    /** 取单篇文章（正文已经是 Markdown 原文，媒体链接已被对方绝对化） */
    public JsonNode fetchArticle(XzSite site, long id) throws IOException, InterruptedException {
        return getJson(site, "/articles/" + id);
    }

    /* ---------- 分发：往对方站点写文章 ---------- */

    /**
     * 往对方写字台**发一篇新文章**（{@code POST /api/v1/publish}）。
     *
     * <p>payload 就是发布接口的字段：{@code title} / {@code content} / {@code summary} / {@code slug} 等。
     * 正文必须是 Markdown —— 对方入库后由前台按 Markdown 渲染。
     *
     * <p>不传 slug 时由对方按标题生成（同 slug 撞车对方会自动加序号）。
     */
    public RemoteArticle publishArticle(XzSite site, Map<String, String> payload)
            throws IOException, InterruptedException {
        return write(site, "POST", "/publish", payload);
    }

    /**
     * 更新对方站点上的一篇文章（{@code PUT /api/v1/articles/{id}}）。
     *
     * <p>对方只允许更新「该 Token 所属账号自己发的」文章，别人的会回 403 —— 所以这里
     * 必须用「发出去时记下的远端 id」，不能瞎猜。
     */
    public RemoteArticle updateArticle(XzSite site, long id, Map<String, String> payload)
            throws IOException, InterruptedException {
        return write(site, "PUT", "/articles/" + id, payload);
    }

    /** POST/PUT 一个开放 API 端点（JSON 进、JSON 出），把返回的 url 补成绝对地址 */
    private RemoteArticle write(XzSite site, String method, String pathAndQuery,
                                Map<String, String> payload) throws IOException, InterruptedException {
        String root = apiRoot(site.getApiUrl());
        if (root.isEmpty()) throw new IOException("接口地址不合法：" + site.getApiUrl());
        String token = site.getApiToken();
        if (token == null || token.isBlank()) throw new IOException("该账号未配置对接密钥，无法分发");
        String body = mapper.writeValueAsString(payload);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(root + pathAndQuery))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "xiezitai-xz-dist")
                .header("X-API-Token", token.trim())
                .method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IOException("对方接口返回 HTTP " + res.statusCode() + "：" + snippet(res.body()));
        }
        JsonNode n = mapper.readTree(res.body());
        String rel = n.path("url").asText("");
        String origin = origin(site.getApiUrl());
        String url = rel.startsWith("/") ? origin + rel : rel;
        return new RemoteArticle(n.path("id").asLong(0), n.path("slug").asText(""), url);
    }

    /**
     * 下载一个媒体文件，失败返回 null（调用方保留外链 + 记警告）。
     *
     * <p>媒体接口 {@code /media/**} 是公开的，带上 Token 也无害 —— 万一对方站点将来收紧了权限，
     * 带着 Token 依然能取。
     */
    public byte[] downloadQuietly(XzSite site, String url) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "xiezitai-xz-import")
                    .GET();
            String token = site.getApiToken();
            if (token != null && !token.isBlank()) b.header("X-API-Token", token.trim());
            HttpResponse<byte[]> res = http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() < 200 || res.statusCode() >= 300) {
                throw new IOException("HTTP " + res.statusCode());
            }
            byte[] body = res.body();
            if (body == null || body.length == 0) throw new IOException("空文件");
            if (body.length > MAX_DOWNLOAD_BYTES) {
                throw new IOException("文件超过 " + (MAX_DOWNLOAD_BYTES / 1024 / 1024) + "MB 上限");
            }
            return body;
        } catch (Exception e) {
            log.warn("写字台媒体下载失败（保留外链）: {} - {}", url, e.getMessage());
            return null;
        }
    }

    private static String snippet(String body) {
        if (body == null) return "";
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
