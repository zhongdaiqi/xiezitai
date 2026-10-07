package cn.xiezitai.service;

import cn.xiezitai.entity.WpSite;
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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WordPress REST API 客户端（wp/v2）。
 *
 * <p>只依赖标准库 HttpClient + Jackson，不引第三方 WP SDK —— 用到的接口就三个：
 * 文章列表 / 单篇文章 / 下载媒体。鉴权用 Application Password 的 Basic Auth
 * （{@code username:token}，token 里的空格 WP 会自己忽略，原样带上即可）；
 * 用户名留空时匿名访问，只能拿到已发布文章。
 */
@Service
public class WordPressClient {

    private static final Logger log = LoggerFactory.getLogger(WordPressClient.class);

    /** 单个媒体文件下载上限：WP 站点自身的附件才落盘，超出按失败处理（保留外链） */
    public static final long MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** 文章列表的一页结果 */
    public record PostsPage(List<Map<String, Object>> posts, long total, int totalPages) {}

    /** Basic Auth 头；未配置用户名/Token 时返回 null（匿名） */
    private String authHeader(WpSite site) {
        String user = site.getUsername() == null ? "" : site.getUsername().trim();
        String token = site.getApiToken() == null ? "" : site.getApiToken().trim();
        if (user.isEmpty() || token.isEmpty()) return null;
        String raw = user + ":" + token;
        return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** GET 一个 WP REST 端点，2xx 返回解析后的 JSON；否则抛出带状态码的可读异常 */
    public JsonNode getJson(WpSite site, String pathAndQuery) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(site.getUrl() + pathAndQuery))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "xiezitai-wp-import")
                .GET();
        String auth = authHeader(site);
        if (auth != null) b.header("Authorization", auth);
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            String snippet = res.body() == null ? "" : res.body();
            if (snippet.length() > 200) snippet = snippet.substring(0, 200);
            throw new IOException("WP 接口返回 HTTP " + res.statusCode() + "：" + snippet.replaceAll("\\s+", " "));
        }
        return mapper.readTree(res.body());
    }

    /** 探测连通性：GET /wp-json/ 根，返回站点名与描述（用于「测试连接」与添加站点时的回显） */
    public Map<String, String> probe(WpSite site) throws IOException, InterruptedException {
        JsonNode root = getJson(site, "/wp-json/");
        Map<String, String> info = new LinkedHashMap<>();
        info.put("name", root.path("name").asText(""));
        info.put("description", root.path("description").asText(""));
        return info;
    }

    /**
     * 验证用户名 + Application Password（GET /users/me）。
     *
     * @return WP 显示名；凭据无效时抛 IOException（消息里带 HTTP 状态）
     */
    public String verifyCredentials(WpSite site) throws IOException, InterruptedException {
        JsonNode me = getJson(site, "/wp-json/wp/v2/users/me?context=edit");
        return me.path("name").asText(site.getUsername());
    }

    /** 拉文章列表一页（公开字段，前端浏览用） */
    public PostsPage listPosts(WpSite site, int page, int perPage, String search)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder("/wp-json/wp/v2/posts?per_page=")
                .append(perPage).append("&page=").append(page)
                .append("&_fields=id,title,slug,date,status,link");
        if (search != null && !search.isBlank()) {
            q.append("&search=").append(java.net.URLEncoder.encode(search.trim(), StandardCharsets.UTF_8));
        }
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(site.getUrl() + q))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("User-Agent", "xiezitai-wp-import")
                .GET();
        String auth = authHeader(site);
        if (auth != null) b.header("Authorization", auth);
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        // WP 在「页码超出范围」时返回 400 + rest_post_invalid_page_number —— 这是正常的翻到头，不是错误
        if (res.statusCode() == 400 && res.body() != null && res.body().contains("rest_post_invalid_page_number")) {
            return new PostsPage(List.of(), 0, 0);
        }
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            String snippet = res.body() == null ? "" : res.body();
            if (snippet.length() > 200) snippet = snippet.substring(0, 200);
            throw new IOException("WP 文章列表返回 HTTP " + res.statusCode() + "：" + snippet.replaceAll("\\s+", " "));
        }
        JsonNode arr = mapper.readTree(res.body());
        List<Map<String, Object>> posts = new ArrayList<>();
        for (JsonNode n : arr) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", n.path("id").asLong());
            m.put("title", unescapeEntities(n.path("title").path("rendered").asText("")));
            m.put("slug", n.path("slug").asText(""));
            m.put("date", n.path("date").asText(""));
            m.put("status", n.path("status").asText(""));
            m.put("link", n.path("link").asText(""));
            posts.add(m);
        }
        long total = Long.parseLong(res.headers().firstValue("X-WP-Total").orElse(String.valueOf(posts.size())));
        int totalPages = Integer.parseInt(res.headers().firstValue("X-WP-TotalPages").orElse("1"));
        return new PostsPage(posts, total, totalPages);
    }

    /** 取单篇文章（含正文与 _embed：特色图、分类/标签） */
    public JsonNode fetchPost(WpSite site, long postId) throws IOException, InterruptedException {
        return getJson(site, "/wp-json/wp/v2/posts/" + postId + "?_embed=1");
    }

    /**
     * 下载一个媒体文件。内容类型未知（WP 附件可能是图片/PDF/zip），按字节读回来交给
     * 上层按文件头嗅探；超限抛 IOException。
     */
    public byte[] download(WpSite site, String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("User-Agent", "xiezitai-wp-import")
                .GET()
                .build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IOException("HTTP " + res.statusCode());
        }
        if (res.body() == null || res.body().length == 0) throw new IOException("空文件");
        if (res.body().length > MAX_DOWNLOAD_BYTES) {
            throw new IOException("文件超过 " + (MAX_DOWNLOAD_BYTES / 1024 / 1024) + "MB 上限");
        }
        return res.body();
    }

    /** 下载错误只影响单张图，不该中断整篇导入 —— 打个日志让上层降级为「保留外链」 */
    public byte[] downloadQuietly(WpSite site, String url) {
        try {
            return download(site, url);
        } catch (Exception e) {
            log.warn("WP 媒体下载失败（保留外链）: {} - {}", url, e.getMessage());
            return null;
        }
    }

    /* ================= 小工具 ================= */

    /** WP 返回的标题/摘要是 HTML 转义过的（&#8217; 等），落库前还原 */
    public static String unescapeEntities(String s) {
        if (s == null || s.isEmpty()) return "";
        String out = s.replaceAll("(?i)<br\\s*/?>", " ").replaceAll("<[^>]*>", "").trim();
        StringBuilder sb = new StringBuilder(out.length());
        int i = 0;
        while (i < out.length()) {
            char c = out.charAt(i);
            if (c == '&') {
                int semi = out.indexOf(';', i);
                if (semi > i && semi - i <= 10) {
                    String ent = out.substring(i + 1, semi);
                    Integer code = namedEntity(ent);
                    if (code == null && ent.matches("#[0-9]+")) code = Integer.parseInt(ent.substring(1));
                    if (code == null && ent.matches("#x[0-9a-fA-F]+")) code = Integer.parseInt(ent.substring(2), 16);
                    if (code != null) { sb.appendCodePoint(code); i = semi + 1; continue; }
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static Integer namedEntity(String ent) {
        return switch (ent) {
            case "amp" -> (int) '&';
            case "lt" -> (int) '<';
            case "gt" -> (int) '>';
            case "quot" -> (int) '"';
            case "apos" -> (int) '\'';
            case "nbsp" -> (int) ' ';
            case "hellip" -> 0x2026;
            case "ldquo" -> 0x201C;
            case "rdquo" -> 0x201D;
            case "lsquo" -> 0x2018;
            case "rsquo" -> 0x2019;
            case "mdash" -> 0x2014;
            case "ndash" -> 0x2013;
            case "copy" -> 0x00A9;
            default -> null;
        };
    }

    /** 去掉 HTML 标签 + 实体还原（摘要用） */
    public static String stripTags(String html) {
        return unescapeEntities(html == null ? "" : html);
    }
}
