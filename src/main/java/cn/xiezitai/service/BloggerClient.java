package cn.xiezitai.service;

import cn.xiezitai.entity.BloggerSite;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
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
 * 谷歌 Blogger API v3 客户端（本站 ⇄ Blogger）。
 *
 * <p>用到的接口：
 * <ul>
 *   <li>{@code GET  /users/self/blogs}                    当前授权的账号能看到哪些博客（关联时列出）</li>
 *   <li>{@code GET  /blogs/{blogId}}                      博客信息（含文章总数，整站导入的进度分母）</li>
 *   <li>{@code GET  /blogs/{blogId}/posts}                文章列表（pageToken 翻页，可关掉正文只取元信息）</li>
 *   <li>{@code GET  /blogs/{blogId}/posts/{postId}}       单篇（正文是 HTML）</li>
 *   <li>{@code POST /blogs/{blogId}/posts}                **新建**文章（分发用）</li>
 *   <li>{@code PATCH /blogs/{blogId}/posts/{postId}}      **更新**文章（分发「更新」用；PATCH 是部分更新，
 *                                                        没传的字段不会被清空）</li>
 * </ul>
 *
 * <p>三点与其它渠道不同的地方：
 * <ol>
 *   <li><b>鉴权是 OAuth 的 Bearer token</b>，不是用户填的密钥；令牌由
 *       {@link GoogleAuthService#accessToken(BloggerSite)} 负责续期。</li>
 *   <li><b>文章 id 是长数字串</b>（Blogger 内部是 64 位整数），所以这里一律用 {@code String} 传递，
 *       不做数值转换 —— 转 long 在极端情况下会溢出，而溢出后「更新」就会打到别人的文章上。</li>
 *   <li><b>正文是 HTML</b>：导入要还原成 Markdown，分发要渲染成 HTML（见 BloggerImportService /
 *       DistributeService）。</li>
 * </ol>
 */
@Service
public class BloggerClient {

    private static final Logger log = LoggerFactory.getLogger(BloggerClient.class);

    /** 媒体文件下载上限：来源站点自身的图才落盘，超出上限按失败处理（保留外链） */
    public static final long MAX_DOWNLOAD_BYTES = 20L * 1024 * 1024;

    /** 列表一页最多取多少条（Blogger 上限 500；取 50 兼顾翻页体验与请求体积） */
    public static final int PAGE_SIZE = 50;

    private final GoogleAuthService auth;
    private final String apiBase;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public BloggerClient(GoogleAuthService auth,
                         @Value("${xiezitai.google.api-base:https://www.googleapis.com/blogger/v3}") String apiBase) {
        this.auth = auth;
        String b = apiBase == null ? "" : apiBase.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        this.apiBase = b;
    }

    /** 博客信息 */
    public record BlogInfo(String id, String name, String url, long totalPosts) {}

    /** 列表里的一条文章（{@code content} 是否带上取决于 {@code fetchBodies}） */
    public record PostBrief(String id, String title, String url, String published, String updated,
                            List<String> labels) {}

    /** 文章列表的一页（Blogger 用 pageToken 翻页，没有页码） */
    public record PostPage(List<PostBrief> items, String nextPageToken) {}

    /** 新建 / 更新成功后 Blogger 回来的文章标识 */
    public record RemotePost(String id, String url) {}

    /* ================= 关联：列出账号下的博客 ================= */

    /** 用刚换到的 access token 列出该账号可见的博客（关联时用，此时还没有 BloggerSite 记录） */
    public List<BlogInfo> listBlogs(String accessToken) throws IOException, InterruptedException {
        JsonNode r = send(accessToken, "GET", "/users/self/blogs?maxResults=100", null);
        List<BlogInfo> out = new ArrayList<>();
        for (JsonNode n : r.path("items")) out.add(blogInfoOf(n));
        return out;
    }

    private static BlogInfo blogInfoOf(JsonNode n) {
        return new BlogInfo(n.path("id").asText(""), n.path("name").asText(""), n.path("url").asText(""),
                n.path("posts").path("totalItems").asLong(0));
    }

    /** 博客信息（文章总数 = 整站导入的进度分母） */
    public BlogInfo blogInfo(BloggerSite site) throws IOException, InterruptedException {
        return blogInfoOf(sendFor(site, "GET", "/blogs/" + enc(site.getBlogId()), null));
    }

    /* ================= 读：文章列表与单篇 ================= */

    /**
     * 文章列表一页。
     *
     * @param pageToken 上一页返回的 nextPageToken；第一页传 null / 空
     * @param withBody  是否需要正文（浏览列表不需要，关掉能显著减小响应）
     */
    public PostPage listPosts(BloggerSite site, String pageToken, boolean withBody)
            throws IOException, InterruptedException {
        StringBuilder q = new StringBuilder("/blogs/").append(enc(site.getBlogId()))
                .append("/posts?maxResults=").append(PAGE_SIZE)
                .append("&fetchBodies=").append(withBody)
                .append("&orderBy=published")
                // 只看已发布的线上文章：草稿导进来会变成「本站有、对方没公开」的怪状态
                .append("&status=live");
        if (pageToken != null && !pageToken.isBlank()) q.append("&pageToken=").append(enc(pageToken));
        JsonNode r = sendFor(site, "GET", q.toString(), null);
        List<PostBrief> items = new ArrayList<>();
        for (JsonNode n : r.path("items")) items.add(postBriefOf(n));
        return new PostPage(items, r.path("nextPageToken").asText(""));
    }

    private static PostBrief postBriefOf(JsonNode n) {
        List<String> labels = new ArrayList<>();
        for (JsonNode l : n.path("labels")) labels.add(l.asText(""));
        return new PostBrief(n.path("id").asText(""), n.path("title").asText(""),
                n.path("url").asText(""), n.path("published").asText(""), n.path("updated").asText(""),
                labels);
    }

    /** 单篇文章（内含 HTML 正文） */
    public JsonNode fetchPost(BloggerSite site, String postId) throws IOException, InterruptedException {
        return sendFor(site, "GET", "/blogs/" + enc(site.getBlogId()) + "/posts/" + enc(postId), null);
    }

    /* ================= 写：分发（新建 / 更新） ================= */

    /** 新建一篇文章（分发「发一个新文章」用） */
    public RemotePost createPost(BloggerSite site, String title, String htmlContent, List<String> labels)
            throws IOException, InterruptedException {
        JsonNode r = sendFor(site, "POST", "/blogs/" + enc(site.getBlogId()) + "/posts",
                body(title, htmlContent, labels));
        return new RemotePost(r.path("id").asText(""), r.path("url").asText(""));
    }

    /**
     * 更新已有的文章（分发「更新之前分发的文章」用）。
     *
     * <p>用 PATCH 而不是 PUT：PATCH 是部分更新，没传的字段保持原样。分发时本站没有对方的
     * 「自定义永久链接 / 读者评论设置」这些东西，用 PUT 有可能把它们覆盖掉。
     */
    public RemotePost patchPost(BloggerSite site, String postId, String title, String htmlContent,
                               List<String> labels) throws IOException, InterruptedException {
        JsonNode r = sendFor(site, "PATCH",
                "/blogs/" + enc(site.getBlogId()) + "/posts/" + enc(postId),
                body(title, htmlContent, labels));
        return new RemotePost(r.path("id").asText(""), r.path("url").asText(""));
    }

    private String body(String title, String html, List<String> labels) throws IOException {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("title", title == null ? "" : title);
        m.put("content", html == null ? "" : html);
        // 标签最多 20 个（Blogger 的限制），本站标签本就 ≤10，顺手截断一次防越界
        if (labels != null && !labels.isEmpty()) {
            m.put("labels", labels.size() > 20 ? labels.subList(0, 20) : labels);
        }
        return mapper.writeValueAsString(m);
    }

    /* ================= 媒体下载 ================= */

    /**
     * 下载一张图片 / 附件，失败返回 null（调用方保留外链 + 记警告）。
     *
     * <p>Blogger 的图片托管在 {@code *.googleusercontent.com} / {@code *.blogspot.com} 这类公开 CDN 上，
     * 不需要鉴权；带上 Bearer 也无害（万一是私有博客里的图，带 token 才取得到）。
     */
    public byte[] downloadQuietly(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .header("User-Agent", "xiezitai-blogger-import")
                    .GET()
                    .build();
            HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (res.statusCode() < 200 || res.statusCode() >= 300) throw new IOException("HTTP " + res.statusCode());
            byte[] body = res.body();
            if (body == null || body.length == 0) throw new IOException("空文件");
            if (body.length > MAX_DOWNLOAD_BYTES) {
                throw new IOException("文件超过 " + (MAX_DOWNLOAD_BYTES / 1024 / 1024) + "MB 上限");
            }
            return body;
        } catch (Exception e) {
            log.warn("Blogger 媒体下载失败（保留外链）: {} - {}", url, e.getMessage());
            return null;
        }
    }

    /* ================= 底层请求 ================= */

    private JsonNode sendFor(BloggerSite site, String method, String pathAndQuery, String jsonBody)
            throws IOException, InterruptedException {
        return send(auth.accessToken(site), method, pathAndQuery, jsonBody);
    }

    private JsonNode send(String accessToken, String method, String pathAndQuery, String jsonBody)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + pathAndQuery))
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/json")
                .header("Authorization", "Bearer " + accessToken)
                .header("User-Agent", "xiezitai-blogger");
        if (jsonBody != null) {
            b.header("Content-Type", "application/json; charset=utf-8")
                    .method(method, HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (res.statusCode() < 200 || res.statusCode() >= 300) {
            throw new IOException("Blogger 接口返回 HTTP " + res.statusCode() + "：" + snippet(res.body()));
        }
        return mapper.readTree(res.body());
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static String snippet(String body) {
        if (body == null) return "";
        String s = body.replaceAll("\\s+", " ").trim();
        return s.length() > 200 ? s.substring(0, 200) : s;
    }
}
