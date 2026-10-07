package cn.xiezitai.controller;

import cn.xiezitai.entity.Article;
import cn.xiezitai.entity.User;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.repository.UserRepository;
import cn.xiezitai.service.ArticleService;
import cn.xiezitai.service.MarkdownService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 开放服务：
 *  1) /api/v1/publish     —— 外部系统凭 X-API-Token 发布博客
 *  2) /api/v1/mcp         —— MCP 服务（JSON-RPC 2.0，Streamable HTTP），供 AI 客户端集成
 * 工具：publish_article / list_articles / get_article
 */
@RestController
@RequestMapping("/api/v1")
public class OpenApiController {

    private final UserRepository users;
    private final ArticleRepository articles;
    private final ArticleService articleService;
    private final MarkdownService markdown;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenApiController(UserRepository users, ArticleRepository articles,
                             ArticleService articleService, MarkdownService markdown) {
        this.users = users;
        this.articles = articles;
        this.articleService = articleService;
        this.markdown = markdown;
    }

    /* ---------- 1. 外部发布 API ---------- */

    @PostMapping("/publish")
    public ResponseEntity<?> publish(@RequestHeader(value = "X-API-Token", required = false) String token,
                                     @RequestBody Map<String, String> body) {
        User user = authByToken(token);
        if (user == null) return ResponseEntity.status(401).body(Map.of("error", "无效的 API Token"));
        String title = body.getOrDefault("title", "").trim();
        String content = body.getOrDefault("content", "").trim();
        if (title.isEmpty() || content.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "title 与 content 必填"));
        }
        Article a = new Article();
        a.setTitle(title);
        a.setContent(content);
        a.setSlug(body.get("slug") == null || body.get("slug").isBlank()
                ? articleService.uniqueSlug(title) : body.get("slug"));
        a.setSummary(body.get("summary"));
        a.setCover(body.get("cover"));
        a.setSeoKeywords(body.get("keywords"));
        a.setAuthor(user.getUsername());
        a.setStatus("PUBLISHED");
        a.setPublishedAt(LocalDateTime.now());
        Article saved = articles.save(a);
        articleService.publishNotify(saved, user.getUsername());
        return ResponseEntity.ok(Map.of("id", saved.getId(), "slug", saved.getSlug(),
                "url", "/article/" + saved.getSlug()));
    }

    /* ---------- 2. MCP 服务（Streamable HTTP / JSON-RPC 2.0） ---------- */

    @PostMapping(value = "/mcp", produces = "application/json")
    public ResponseEntity<Map<String, Object>> mcp(@RequestHeader(value = "X-API-Token", required = false) String token,
                                                   @RequestBody JsonNode req) {
        User user = authByToken(token);
        if (user == null) {
            return ResponseEntity.status(401).body(error(null, -32001, "无效的 API Token"));
        }
        String method = req.path("method").asText("");
        JsonNode id = req.path("id");
        Object result = null;
        switch (method) {
            case "initialize" -> result = Map.of(
                    "protocolVersion", req.path("params").path("protocolVersion").asText("2024-11-05"),
                    "capabilities", Map.of("tools", Map.of()),
                    "serverInfo", Map.of("name", "xiezitai-mcp", "version", "1.0.0"));
            case "tools/list" -> result = Map.of("tools", toolSchemas());
            case "tools/call" -> result = callTool(req.path("params"), user);
            case "ping" -> result = Map.of();
            default -> {
                return ResponseEntity.ok(error(id, -32601, "未知方法: " + method));
            }
        }
        Map<String, Object> ok = new java.util.LinkedHashMap<>();
        ok.put("jsonrpc", "2.0");
        ok.put("id", id);
        ok.put("result", result);
        return ResponseEntity.ok(ok);
    }

    private List<Map<String, Object>> toolSchemas() {
        return List.of(
                Map.of("name", "publish_article",
                        "description", "向写字台博客发布一篇文章（Markdown）",
                        "inputSchema", Map.of("type", "object", "required", List.of("title", "content"),
                                "properties", Map.of(
                                        "title", Map.of("type", "string", "description", "文章标题"),
                                        "content", Map.of("type", "string", "description", "Markdown 正文"),
                                        "summary", Map.of("type", "string", "description", "摘要（可选）"),
                                        "slug", Map.of("type", "string", "description", "URL 别名（可选）")))),
                Map.of("name", "list_articles",
                        "description", "列出最近发布的文章",
                        "inputSchema", Map.of("type", "object", "properties", Map.of(
                                "limit", Map.of("type", "integer", "description", "条数，默认 10")))),
                Map.of("name", "get_article",
                        "description", "按 slug 获取文章详情",
                        "inputSchema", Map.of("type", "object", "required", List.of("slug"),
                                "properties", Map.of("slug", Map.of("type", "string")))));
    }

    private Object callTool(JsonNode params, User user) {
        String name = params.path("name").asText("");
        JsonNode args = params.path("arguments");
        try {
            return switch (name) {
                case "publish_article" -> {
                    Article a = new Article();
                    a.setTitle(args.path("title").asText(""));
                    a.setContent(args.path("content").asText(""));
                    String slug = args.path("slug").asText("");
                    a.setSlug(slug.isBlank() ? articleService.uniqueSlug(a.getTitle()) : slug);
                    a.setSummary(args.path("summary").asText(null));
                    a.setAuthor(user.getUsername());
                    a.setStatus("PUBLISHED");
                    a.setPublishedAt(LocalDateTime.now());
                    Article saved = articles.save(a);
                    articleService.publishNotify(saved, user.getUsername());
                    yield content(String.format("已发布: /article/%s", saved.getSlug()));
                }
                case "list_articles" -> {
                    int limit = args.path("limit").asInt(10);
                    List<Article> list = articles
                            .findByStatusOrderByPublishedAtDesc("PUBLISHED",
                                    org.springframework.data.domain.PageRequest.of(0, Math.min(limit, 50)))
                            .getContent();
                    List<String> lines = new ArrayList<>();
                    for (Article a : list) {
                        lines.add(String.format("- [%s](/article/%s) (%s)", a.getTitle(), a.getSlug(), a.getPublishedAt()));
                    }
                    yield content(String.join("\n", lines));
                }
                case "get_article" -> {
                    Article a = articles.findBySlug(args.path("slug").asText("")).orElse(null);
                    yield a == null ? content("未找到文章") : content(a.getTitle() + "\n\n" + markdown.toHtml(a.getContent()));
                }
                default -> errorResult("未知工具: " + name);
            };
        } catch (Exception e) {
            return errorResult("执行失败: " + e.getMessage());
        }
    }

    private Map<String, Object> content(String text) {
        return Map.of("content", List.of(Map.of("type", "text", "text", text)));
    }

    private Map<String, Object> errorResult(String msg) {
        return Map.of("content", List.of(Map.of("type", "text", "text", msg)), "isError", true);
    }

    private Map<String, Object> error(JsonNode id, int code, String msg) {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("jsonrpc", "2.0");
        out.put("id", id);
        out.put("error", Map.of("code", code, "message", msg));
        return out;
    }

    private User authByToken(String token) {
        if (token == null || token.isBlank()) return null;
        User user = users.findByApiToken(token).orElse(null);
        // 与登录同一套口径：账号被停用 / 注册被驳回，已签发的 API Token 一并失效
        return (user != null && user.isEnabled() && user.isApproved()) ? user : null;
    }
}
