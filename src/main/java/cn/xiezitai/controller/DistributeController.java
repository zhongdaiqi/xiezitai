package cn.xiezitai.controller;

import cn.xiezitai.entity.Article;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.service.DistributeService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 文章分发接口（{@code /api/admin/dist/**}，登录后可用）。
 *
 * <ul>
 *   <li>{@code GET /targets?articleId=} —— 可选目标清单 + 该文章在每个目标上的分发状态；</li>
 *   <li>{@code POST /run} —— 执行分发（可一次发多个目标，逐个回结果，单个失败不影响其余）；</li>
 *   <li>{@code GET /map?ids=1,2,3} —— 列表页「已分发」徽标的批量查询。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/dist")
public class DistributeController {

    private final DistributeService dist;
    private final ArticleRepository articles;

    public DistributeController(DistributeService dist, ArticleRepository articles) {
        this.dist = dist;
        this.articles = articles;
    }

    /** 目标清单：前端弹窗据此渲染勾选框、标出「已分发」并给出更新/新文章二选一 */
    @GetMapping("/targets")
    public ResponseEntity<?> targets(@RequestParam Long articleId) {
        Article a = articles.findById(articleId).orElse(null);
        if (a == null) return ResponseEntity.notFound().build();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("articleId", a.getId());
        out.put("title", a.getTitle());
        out.put("slug", a.getSlug());
        out.put("siteUrl", dist.siteUrl());
        out.put("sourceUrl", dist.articleUrl(a.getSlug()));
        out.put("targets", dist.targetsFor(articleId));
        return ResponseEntity.ok(out);
    }

    /** 执行分发 */
    @PostMapping("/run")
    public ResponseEntity<?> run(@RequestBody Map<String, Object> body, Authentication auth) {
        Long articleId = asLong(body.get("articleId"));
        if (articleId == null) return ResponseEntity.badRequest().body(Map.of("error", "缺少 articleId"));
        List<DistributeService.DistTarget> targets = parseTargets(body.get("targets"));
        if (targets.isEmpty()) return ResponseEntity.badRequest().body(Map.of("error", "请至少选择一个分发目标"));
        try {
            return ResponseEntity.ok(dist.distribute(articleId, str(body.get("mode")), str(body.get("format")),
                    targets, auth == null ? "admin" : auth.getName()));
        } catch (IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * 批量徽标：{@code {"12":["我的WP站","博客园"]}}。
     *
     * <p>文章列表接口返回的是完整实体，不方便再塞分发信息（会连带影响其它调用方），
     * 这里单独给一个按 id 批量查的轻接口，列表渲染完顺手刷一次即可。
     */
    @GetMapping("/map")
    public ResponseEntity<?> map(@RequestParam(required = false) String ids) {
        Set<Long> parsed = new LinkedHashSet<>();
        if (ids != null) {
            for (String s : ids.split(",")) {
                Long v = asLong(s);
                if (v != null) parsed.add(v);
            }
        }
        if (parsed.isEmpty()) return ResponseEntity.ok(Map.of());
        Map<String, Object> out = new LinkedHashMap<>();
        dist.badgeMap(new ArrayList<>(parsed)).forEach((k, v) -> out.put(String.valueOf(k), v));
        return ResponseEntity.ok(out);
    }

    /* ================= 工具 ================= */

    @SuppressWarnings("unchecked")
    private static List<DistributeService.DistTarget> parseTargets(Object raw) {
        List<DistributeService.DistTarget> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            Long id = asLong(m.get("targetId"));
            if (id == null) continue;
            out.add(new DistributeService.DistTarget(
                    str(m.get("channel")), id, str(m.get("action"))));
        }
        return out;
    }

    private static Long asLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        if (o == null) return null;
        try { return Long.parseLong(String.valueOf(o).trim()); } catch (Exception e) { return null; }
    }

    private static String str(Object o) { return o == null ? "" : String.valueOf(o); }
}
