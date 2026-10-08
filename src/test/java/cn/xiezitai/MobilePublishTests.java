package cn.xiezitai;

import cn.xiezitai.entity.User;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.repository.UserRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 投稿审核流程（App /api/my/**）+ 详情可见性与阅读计数口径。
 *
 * <p>权限模型：管理员发布直接公开；普通用户投稿一律 PENDING（请求里的 status 不采纳），
 * 审核前只有作者本人和管理员可见；驳回后同样只有本人可见，作者可改后重投；
 * 已发布文章被作者改动会回炉重新审核。公开详情接口是手机 App 与网页共用的，
 * 所以阅读计数天然覆盖手机端（预览自己的未公开文章不计数）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MobilePublishTests {

    private static final String ADMIN = "xiezitai";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired UserRepository users;
    @Autowired ArticleRepository articles;
    @Autowired PasswordEncoder encoder;

    private static final AtomicInteger SEQ = new AtomicInteger((int) (System.currentTimeMillis() % 100000));

    private String json(Map<String, ?> map) throws Exception { return om.writeValueAsString(map); }

    private String loginToken(String username, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("token").asText();
    }

    private String adminToken() throws Exception { return loginToken(ADMIN, "xiexiexie"); }

    /** 建一个能正常登录的普通用户，直接置 APPROVED（注册审核不在本类覆盖范围） */
    private String newUser(String password) throws Exception {
        String name = "mob" + SEQ.incrementAndGet();
        User u = new User();
        u.setUsername(name);
        u.setPassword(encoder.encode(password));
        u.setRole("USER");
        u.setStatus("APPROVED");
        users.save(u);
        return loginToken(name, password);
    }

    private String postArticle(String token, String title, String content, String tags) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("title", title);
        body.put("content", content);
        if (tags != null) body.put("tags", tags);
        MvcResult r = mvc.perform(post("/api/my/articles")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("slug").asText();
    }

    private JsonNode getJson(String path, String token) throws Exception {
        MvcResult r = mvc.perform(get(path).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /* ==================== 投稿与可见性 ==================== */

    @Test
    @DisplayName("普通用户投稿 → PENDING；未登录/他人不可见，作者与管理员可见；预览不计数")
    void userSubmissionIsPendingAndVisibility() throws Exception {
        String userToken = newUser("pass123456");
        String admin = adminToken();
        String slug = postArticle(userToken, "投稿可见性测试 " + SEQ.incrementAndGet(), "正文内容", null);

        JsonNode mine = getJson("/api/my/articles?size=50", userToken);
        JsonNode mineArticle = null;
        for (JsonNode a : mine.path("content")) {
            if (slug.equals(a.path("slug").asText())) mineArticle = a;
        }
        assertThat(mineArticle).as("我的文章列表里能看到这篇投稿").isNotNull();
        assertThat(mineArticle.path("status").asText()).isEqualTo("PENDING");

        // 未登录 → 404；登录的其他普通用户 → 404；管理员 → 200
        mvc.perform(get("/api/articles/" + slug)).andExpect(status().isNotFound());
        String other = newUser("pass123456");
        mvc.perform(get("/api/articles/" + slug).header("Authorization", "Bearer " + other))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/articles/" + slug).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk());

        // 作者预览自己的待审文章：可见，但阅读计数不变
        long viewsBefore = mineArticle.path("viewCount").asLong();
        JsonNode preview = getJson("/api/articles/" + slug, userToken);
        assertThat(articles.findBySlug(slug).orElseThrow().getViewCount()).isEqualTo(viewsBefore);
        assertThat(preview.path("status").asText()).isEqualTo("PENDING");

        // 「我的文章」里只有自己的：管理员列表不含这篇
        JsonNode adminMine = getJson("/api/my/articles?size=50", admin);
        for (JsonNode a : adminMine.path("content")) {
            assertThat(a.path("slug").asText()).isNotEqualTo(slug);
        }
    }

    @Test
    @DisplayName("管理员审核通过 → 公开可见，公开访问开始计数；驳回 → REJECTED 只有作者可见")
    void reviewApproveAndReject() throws Exception {
        String userToken = newUser("pass123456");
        String admin = adminToken();

        // —— 通过 ——
        String slugA = postArticle(userToken, "审核通过测试 " + SEQ.incrementAndGet(), "内容A", null);
        long idA = articles.findBySlug(slugA).orElseThrow().getId();
        mvc.perform(post("/api/admin/articles/" + idA + "/review")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("action", "approve"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
        // 公开后匿名可见；公开访问会 +1 计数（手机 App 取详情走同一条接口，天然覆盖）
        long before = articles.findBySlug(slugA).orElseThrow().getViewCount();
        mvc.perform(get("/api/articles/" + slugA)).andExpect(status().isOk());
        assertThat(articles.findBySlug(slugA).orElseThrow().getViewCount()).isEqualTo(before + 1);

        // —— 驳回 ——
        String slugR = postArticle(userToken, "审核驳回测试 " + SEQ.incrementAndGet(), "内容R", null);
        long idR = articles.findBySlug(slugR).orElseThrow().getId();
        mvc.perform(post("/api/admin/articles/" + idR + "/review")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "reject", "note", "内容太薄，补充后再投"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.reviewNote").value("内容太薄，补充后再投"));
        mvc.perform(get("/api/articles/" + slugR)).andExpect(status().isNotFound());
        mvc.perform(get("/api/articles/" + slugR).header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());

        // 非管理员不能调审核接口
        mvc.perform(post("/api/admin/articles/" + idR + "/review")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("action", "approve"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("已发布文章被作者改动 → 回炉重新审核；改完再次通过可恢复公开")
    void editOwnPublishedGoesBackToPending() throws Exception {
        String userToken = newUser("pass123456");
        String admin = adminToken();
        String slug = postArticle(userToken, "回炉测试 " + SEQ.incrementAndGet(), "v1", null);
        long id = articles.findBySlug(slug).orElseThrow().getId();
        mvc.perform(post("/api/admin/articles/" + id + "/review")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("action", "approve"))))
                .andExpect(status().isOk());

        // 作者修改 → 回到 PENDING，公开接口立刻 404
        mvc.perform(put("/api/my/articles/" + id)
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "v2 修改后的内容"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));
        mvc.perform(get("/api/articles/" + slug)).andExpect(status().isNotFound());

        // 再通过 → 恢复公开
        mvc.perform(post("/api/admin/articles/" + id + "/review")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("action", "approve"))))
                .andExpect(status().isOk());
        mvc.perform(get("/api/articles/" + slug)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("管理员投稿直接公开；普通用户的 status 字段不被采纳；边界校验")
    void adminPublishesDirectlyAndGuards() throws Exception {
        String admin = adminToken();

        // 管理员发 → PUBLISHED 立即公开
        String slug = postArticle(admin, "管理员直发 " + SEQ.incrementAndGet(), "内容", null);
        assertThat(articles.findBySlug(slug).orElseThrow().getStatus()).isEqualTo("PUBLISHED");
        mvc.perform(get("/api/articles/" + slug)).andExpect(status().isOk());

        // 普通用户请求里夹带 status=PUBLISHED 也必须被拦回 PENDING
        String userToken = newUser("pass123456");
        MvcResult r = mvc.perform(post("/api/my/articles")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "越权状态测试 " + SEQ.incrementAndGet(),
                                "content", "x", "status", "PUBLISHED"))))
                .andExpect(status().isOk()).andReturn();
        String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(om.readTree(body).path("status").asText()).isEqualTo("PENDING");

        // 未登录投稿 → 401；标签超量 → 400；非作者改/删 → 403
        mvc.perform(post("/api/my/articles").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "匿名", "content", "x"))))
                .andExpect(status().isUnauthorized());
        String tooManyTags = String.join(",", java.util.stream.IntStream.rangeClosed(1, 11)
                .mapToObj(i -> "t" + i).toArray(String[]::new));
        mvc.perform(post("/api/my/articles")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "标签超量", "content", "x", "tags", tooManyTags))))
                .andExpect(status().isBadRequest());

        String other = newUser("pass123456");
        long id = articles.findBySlug(slug).orElseThrow().getId();
        mvc.perform(put("/api/my/articles/" + id)
                        .header("Authorization", "Bearer " + other)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("content", "hack"))))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/my/articles/" + id).header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden());

        // slug 撞车 → 409
        mvc.perform(post("/api/my/articles")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "撞车测试", "content", "x", "slug", slug))))
                .andExpect(status().isConflict());
    }
}
