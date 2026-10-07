package cn.xiezitai;

import cn.xiezitai.entity.RequestLog;
import cn.xiezitai.entity.User;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.repository.FileRepository;
import cn.xiezitai.repository.RequestLogRepository;
import cn.xiezitai.repository.UserRepository;
import cn.xiezitai.security.LoginAttemptService;
import cn.xiezitai.security.TotpService;
import cn.xiezitai.service.AiService;
import cn.xiezitai.service.AiTextCleaner;
import cn.xiezitai.service.NotifyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 写字台端到端功能自测（MockMvc + H2）。
 * 覆盖：SEO 页面、登录与锁定策略、TOTP 两步验证、注册审核（待审/通过/驳回与令牌失效）、
 *      文章/页面/评论、媒体访问留痕、上传类型限制与魔数扫描、开放 API、MCP 服务、请求日志。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class XiezitaiApplicationTests {

    private static final String ADMIN = "xiezitai";
    private static final String ADMIN_PWD = "xiexiexie";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired UserRepository users;
    @Autowired ArticleRepository articles;
    @Autowired cn.xiezitai.repository.CommentRepository comments;
    @Autowired FileRepository fileRepo;
    @Autowired RequestLogRepository logs;
    @Autowired PasswordEncoder encoder;
    @Autowired NotifyService notify;
    @Autowired TotpService totp;
    @Autowired LoginAttemptService attempts;
    @Autowired AiService ai;

    /** 站点根地址取自配置 xiezitai.site-url：SEO 端点（robots/sitemap/og:image）与页脚都应由它驱动 */
    @Value("${xiezitai.site-url:https://xiezitai.cn}")
    String siteUrlRaw;
    private String siteRoot;
    private String siteHost;

    @BeforeEach
    void setUp() {
        // 测试期间关闭机器人通知，避免外发真实请求
        notify.set("notify.enabled", "false");
        attempts.onSuccess(ADMIN);

        siteRoot = siteUrlRaw == null ? "" : siteUrlRaw.trim().replaceAll("/+$", "");
        String h = java.net.URI.create(siteRoot).getHost();
        siteHost = h == null ? siteRoot.replaceAll("^https?://", "") : h;

        User admin = users.findByUsername(ADMIN).orElse(null);
        if (admin == null) {
            admin = new User();
            admin.setUsername(ADMIN);
            admin.setRole("ADMIN");
            admin.setPassword(encoder.encode(ADMIN_PWD));
            admin.setApiToken("0000000000000000000000000000000000000000000admin");
            users.save(admin);
        } else if (admin.getApiToken() == null) {
            admin.setApiToken("0000000000000000000000000000000000000000000admin");
            users.save(admin);
        }
    }

    /* ==================== SEO / 公开页面 ==================== */

    @Test
    @DisplayName("首页与 SEO 端点可访问")
    void homeAndSeoEndpoints() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk());
        mvc.perform(get("/robots.txt")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "Sitemap: " + siteRoot + "/sitemap.xml")));
        mvc.perform(get("/sitemap.xml")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<urlset")))
                // 站点根地址必须来自配置 xiezitai.site-url，不能再硬编码域名
                .andExpect(content().string(org.hamcrest.Matchers.containsString(
                        "<loc>" + siteRoot + "/</loc>")));
        // 前台页脚 / 副标题显示的域名同样来自配置
        mvc.perform(get("/")).andExpect(content().string(org.hamcrest.Matchers.containsString(siteHost)));
        mvc.perform(get("/admin.html")).andExpect(status().isOk());
    }

    /* ==================== 登录 / 锁定 / TOTP ==================== */

    @Test
    @DisplayName("登录成功返回 token 与角色")
    void loginSucceeds() throws Exception {
        String token = loginToken(ADMIN, ADMIN_PWD);
        assertThat(token).isNotBlank();
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(ADMIN))
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    @DisplayName("密码错误返回 401")
    void loginWithWrongPassword() throws Exception {
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", ADMIN, "password", "wrong-password"))))
                .andExpect(status().isUnauthorized());
        attempts.onSuccess(ADMIN);
    }

    @Test
    @DisplayName("连续 3 次失败后账号锁定（423）")
    void accountLocksAfterThreeFailures() throws Exception {
        String victim = "bruteforce-victim";
        attempts.onSuccess(victim);
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("username", victim, "password", "bad"))))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", victim, "password", "bad"))))
                .andExpect(status().isLocked());
        attempts.onSuccess(victim);
    }

    @Test
    @DisplayName("TOTP 开启后登录必须携带动态码")
    void totpTwoFactorFlow() throws Exception {
        String token = loginToken(ADMIN, ADMIN_PWD);
        MvcResult setup = mvc.perform(post("/api/auth/totp/setup").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        JsonNode setupBody = om.readTree(setup.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String secret = setupBody.path("secret").asText();
        assertThat(secret).isNotBlank();

        // 扫码绑定：setup 应返回可直接显示的二维码，且解码回来的内容必须等于 otpauth 链接（证明真的可扫）
        String qr = setupBody.path("qrCode").asText();
        assertThat(qr).startsWith("data:image/png;base64,");
        BufferedImage qrImg = ImageIO.read(new ByteArrayInputStream(
                Base64.getDecoder().decode(qr.substring("data:image/png;base64,".length()))));
        assertThat(qrImg).isNotNull();
        Result decoded = new MultiFormatReader().decode(
                new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(qrImg))));
        assertThat(decoded.getText()).isEqualTo(setupBody.path("otpauthUrl").asText());

        String code = totp.currentCode(secret);
        mvc.perform(post("/api/auth/totp/enable").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("code", code))))
                .andExpect(status().isOk());

        try {
            // 不带 code -> 提示需要动态码
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("username", ADMIN, "password", ADMIN_PWD))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.error").value("NEED_TOTP"));

            // 错误 code -> 401
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("username", ADMIN, "password", ADMIN_PWD, "totpCode", "000000"))))
                    .andExpect(status().isUnauthorized());

            // 正确 code -> 200
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("username", ADMIN, "password", ADMIN_PWD,
                                    "totpCode", totp.currentCode(secret)))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").isNotEmpty());
        } finally {
            attempts.onSuccess(ADMIN);
            mvc.perform(post("/api/auth/totp/disable").header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk());
        }
    }

    /* ==================== 注册审核 ==================== */

    @Test
    @DisplayName("注册后为待审核：密码对也登录不了，管理员通过后才能登录")
    void registerRequiresAdminApproval() throws Exception {
        String uname = "rev" + (System.currentTimeMillis() % 100000);
        String pwd = "rev123456";
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", uname, "password", pwd))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 待审核：密码正确也进不去。用 403 而不是 401 —— 401 会让用户以为密码错了反复重试
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", uname, "password", pwd))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("PENDING_REVIEW"));

        // 关键回归：待审核被拒不能计入爆破失败，否则用户等审核期间点几次就被锁号
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("username", uname, "password", pwd))))
                    .andExpect(status().isForbidden());
        }

        String admin = loginToken(ADMIN, ADMIN_PWD);
        long id = userIdByName(admin, uname);

        // 后台列表里状态与注册时间都要有（前端审核列依赖它）
        MvcResult listed = mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk()).andReturn();
        JsonNode list = om.readTree(listed.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(list.get(0).path("status").asText())
                .as("待审核用户应排在列表最前面")
                .isEqualTo("PENDING");
        for (JsonNode u : list) {
            if (uname.equals(u.path("username").asText())) {
                assertThat(u.path("createdAt").asText()).isNotBlank();
            }
        }

        // 管理员通过 → 可以登录
        mvc.perform(put("/api/admin/users/" + id + "/audit").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "APPROVED"))))
                .andExpect(status().isOk());

        // 通过之后即可登录
        assertThat(loginToken(uname, pwd)).isNotBlank();
    }

    @Test
    @DisplayName("驳回：登录提示带原因，且驳回前签发的 token 与 API Token 立即失效")
    void rejectedUserBlockedAndTokensRevoked() throws Exception {
        String uname = "rej" + (System.currentTimeMillis() % 100000);
        String pwd = "rej123456";
        String userToken = registerAndApprove(uname, pwd);
        String userApiToken = apiToken(userToken);

        // 前置确认：此时 token / API Token 都是通的
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/publish").header("X-API-Token", userApiToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "驳回前发布-" + uname, "content", "正文"))))
                .andExpect(status().isOk());

        String admin = loginToken(ADMIN, ADMIN_PWD);
        long id = userIdByName(admin, uname);
        mvc.perform(put("/api/admin/users/" + id + "/audit").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "REJECTED", "note", "内容不合规"))))
                .andExpect(status().isOk());

        // 已签发的 JWT 立刻失效，不必等它自然过期（否则驳回形同虚设）
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + userToken))
                .andExpect(status().isUnauthorized());
        // API Token 同一套口径
        mvc.perform(post("/api/v1/publish").header("X-API-Token", userApiToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "驳回后发布-" + uname, "content", "正文"))))
                .andExpect(status().isUnauthorized());

        // 重新登录：403 + 驳回原因透传给用户
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", uname, "password", pwd))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("REJECTED"))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("内容不合规")));

        // 改判为通过 → 又能登录了
        mvc.perform(put("/api/admin/users/" + id + "/audit").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "APPROVED"))))
                .andExpect(status().isOk());
        assertThat(loginToken(uname, pwd)).isNotBlank();
    }

    @Test
    @DisplayName("审核接口：管理员账号不可被驳回，非法状态被拒")
    void auditGuards() throws Exception {
        String admin = loginToken(ADMIN, ADMIN_PWD);
        long adminId = userIdByName(admin, ADMIN);
        // 防呆：把唯一的 ADMIN 驳回等于把自己锁在门外
        mvc.perform(put("/api/admin/users/" + adminId + "/audit").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "REJECTED"))))
                .andExpect(status().isBadRequest());
        // 状态值白名单
        mvc.perform(put("/api/admin/users/" + adminId + "/audit").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "WHATEVER"))))
                .andExpect(status().isBadRequest());
    }

    /* ==================== 文章 ==================== */

    @Test
    @DisplayName("管理员创建并发布文章，公开接口与详情页可读")
    void articleCreatePublishAndRead() throws Exception {
        String token = loginToken(ADMIN, ADMIN_PWD);
        String title = "自测文章-" + System.currentTimeMillis();
        MvcResult created = mvc.perform(post("/api/admin/articles").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", title, "content", "# 标题\n\n正文 **加粗**", "status", "PUBLISHED"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").isNumber())
                .andReturn();
        JsonNode node = om.readTree(created.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String slug = node.path("slug").asText();
        assertThat(slug).isNotBlank();

        // 公开列表
        mvc.perform(get("/api/articles")).andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
        // 公开详情（会累加访问量）
        mvc.perform(get("/api/articles/" + slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value(title));
        // 服务端渲染详情页
        mvc.perform(get("/article/" + slug))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString(title)));

        assertThat(articles.findBySlug(slug).orElseThrow().getViewCount()).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("草稿文章不对外可见")
    void draftArticleHidden() throws Exception {
        String token = loginToken(ADMIN, ADMIN_PWD);
        MvcResult created = mvc.perform(post("/api/admin/articles").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "草稿-" + System.currentTimeMillis(),
                                "content", "未发布内容", "status", "DRAFT"))))
                .andExpect(status().isOk()).andReturn();
        String slug = om.readTree(created.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("slug").asText();

        mvc.perform(get("/api/articles/" + slug)).andExpect(status().isNotFound());
        mvc.perform(get("/article/" + slug)).andExpect(status().is3xxRedirection());
    }

    @Test
    @DisplayName("GFM 任务列表渲染为复选框，而不是字面量 [x]")
    void taskListRenderedAsCheckboxes() throws Exception {
        String token = loginToken(ADMIN, ADMIN_PWD);
        String md = "待办：\n\n- [x] 已完成的事\n- [ ] 还没做的事\n";
        MvcResult created = mvc.perform(post("/api/admin/articles").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "任务列表-" + System.currentTimeMillis(),
                                "content", md, "status", "PUBLISHED"))))
                .andExpect(status().isOk()).andReturn();
        String slug = om.readTree(created.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("slug").asText();

        String html = mvc.perform(get("/article/" + slug)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(html).contains("type=\"checkbox\"");                    // 扩展生效
        assertThat(html).contains("disabled");                             // 只读，不提交
        assertThat(html).contains("checked");                              // 已勾选项
        assertThat(html).doesNotContain("[x]").doesNotContain("[ ]");      // 不再露出字面量

        // 摘要（首页）走同一个 MarkdownService，也应渲染成复选框。
        // 用例执行顺序不确定，别的用例可能往前面塞了更多文章把它挤到第 2 页，所以看前 3 页的并集。
        StringBuilder home = new StringBuilder();
        for (int p = 1; p <= 3; p++) home.append(getBody("/?page=" + p));
        assertThat(home.toString()).contains("type=\"checkbox\"");
    }

    @Test
    @DisplayName("首页分页：每页 10 篇、翻页有真实链接、越界页码钳制而非空列表")
    void homePagination() throws Exception {
        String token = loginToken(ADMIN, ADMIN_PWD);
        String prefix = "分页文章-" + System.currentTimeMillis() + "-";
        for (int i = 1; i <= 12; i++) {
            mvc.perform(post("/api/admin/articles").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("title", prefix + i, "content", "第 " + i + " 篇",
                                    "status", "PUBLISHED"))))
                    .andExpect(status().isOk());
        }

        String p1 = getBody("/");
        assertThat(countOccurrences(p1, "<article>")).isEqualTo(10);            // 每页 10 篇
        assertThat(p1).contains("class=\"pager\"").contains("rel=\"next\"");
        assertThat(p1).doesNotContain("rel=\"prev\"");                         // 第 1 页没有上一页
        assertThat(p1).contains("aria-current=\"page\"");

        String p2 = getBody("/?page=2");
        assertThat(countOccurrences(p2, "<article>")).isBetween(1, 10);
        assertThat(p2).contains("rel=\"prev\"").contains("<link rel=\"canonical\"");
        assertThat(p2).contains("第 2 页");                                    // title 里带页码，便于分享/SEO 区分

        // 越界（999 / 0 / 负数）都必须钳到有效页，不能给空列表
        for (String bad : new String[]{"/?page=999", "/?page=0", "/?page=-3"}) {
            String body = getBody(bad);
            assertThat(countOccurrences(body, "<article>")).as("越界页码 " + bad).isGreaterThanOrEqualTo(1);
        }

        // 新建的 12 篇都能翻到（最多跨 3 页，越界页会重复最后一页，用并集判断更稳）
        StringBuilder all = new StringBuilder();
        for (int p = 1; p <= 3; p++) all.append(getBody("/?page=" + p));
        for (int i = 1; i <= 12; i++) assertThat(all.toString()).contains(prefix + i);
    }

    /* ==================== 评论（懒加载序列化回归） ==================== */

    @Test
    @DisplayName("评论仅限登录用户：匿名 401，作者名取自登录账号（不可伪造），待审后公开")
    void commentRequiresLoginAndNeedsApproval() throws Exception {
        String token = loginToken(ADMIN, ADMIN_PWD);
        MvcResult created = mvc.perform(post("/api/admin/articles").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "评论测试-" + System.currentTimeMillis(),
                                "content", "正文", "status", "PUBLISHED"))))
                .andExpect(status().isOk()).andReturn();
        String slug = om.readTree(created.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("slug").asText();

        // 匿名（未带 token）评论 -> 401，禁止匿名
        mvc.perform(post("/api/articles/" + slug + "/comments").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "匿名灌水", "authorName", "路人甲"))))
                .andExpect(status().isUnauthorized());

        // 注册一个普通用户，审核通过后登录
        String uname = "cmt" + (System.currentTimeMillis() % 100000);
        String userToken = registerAndApprove(uname, "cmt123456");

        // 登录用户评论：请求体里塞 authorName 冒充他人应被忽略，服务端按登录账号取名
        mvc.perform(post("/api/articles/" + slug + "/comments")
                        .header("Authorization", "Bearer " + userToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "写得不错", "authorName", "管理员本尊"))))
                .andExpect(status().isOk());

        // 未审核 -> 公开列表为空
        mvc.perform(get("/api/articles/" + slug + "/comments")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // 管理员看到待审评论（序列化 article 字段，顺带验证 @EntityGraph 生效）
        MvcResult all = mvc.perform(get("/api/admin/comments").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        JsonNode list = om.readTree(all.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(list.isArray()).isTrue();
        long id = -1;
        for (JsonNode c : list) {
            if (slug.equals(c.path("article").path("slug").asText())) {
                id = c.path("id").asLong();
                assertThat(c.path("authorName").asText())
                        .as("作者名必须取自登录账号，不能被请求体伪造")
                        .isEqualTo(uname);
            }
        }
        assertThat(id).isPositive();

        mvc.perform(put("/api/admin/comments/" + id + "/status").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "APPROVED"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/articles/" + slug + "/comments")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("写得不错"))
                .andExpect(jsonPath("$[0].authorName").value(uname))
                .andExpect(jsonPath("$[0].replies.length()").value(0));
    }

    @Test
    @DisplayName("评论层级：回复挂到根评论下（两级封顶），父未过审则回复不展示，删除父则级联删回复")
    void commentRepliesFormTwoLevelTree() throws Exception {
        String admin = loginToken(ADMIN, ADMIN_PWD);
        MvcResult created = mvc.perform(post("/api/admin/articles").header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "层级评论-" + System.currentTimeMillis(),
                                "content", "正文", "status", "PUBLISHED"))))
                .andExpect(status().isOk()).andReturn();
        String slug = om.readTree(created.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("slug").asText();

        // 三个用户：A 发一级评论，B 回复 A，C 回复 B（应被收敛到 A 下面）
        String[] names = new String[3];
        String[] tokens = new String[3];
        for (int i = 0; i < 3; i++) {
            names[i] = "lv" + i + (System.currentTimeMillis() % 100000);
            tokens[i] = registerAndApprove(names[i], "lv123456");
        }

        long rootId = postComment(slug, tokens[0], "一级评论：正文写得不错", null);
        assertThat(rootId).isPositive();
        long replyId = postComment(slug, tokens[1], "回复一级：同意", String.valueOf(rootId));
        long replyOfReply = postComment(slug, tokens[2], "回复二级：+1", String.valueOf(replyId));

        // 回复不存在的评论 -> 400
        mvc.perform(post("/api/articles/" + slug + "/comments")
                        .header("Authorization", "Bearer " + tokens[0])
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "乱回复", "parentId", "99999999"))))
                .andExpect(status().isBadRequest());

        // 全部未审核 -> 公开树为空
        mvc.perform(get("/api/articles/" + slug + "/comments")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // 只通过两条回复，不通过父评论 -> 依然是空（父未过审，回复不展示）
        approve(admin, replyId);
        approve(admin, replyOfReply);
        mvc.perform(get("/api/articles/" + slug + "/comments")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // 通过父评论 -> 树成形：1 个根 + 2 条回复，二级回复被收敛到根下
        approve(admin, rootId);
        mvc.perform(get("/api/articles/" + slug + "/comments")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value((int) rootId))
                .andExpect(jsonPath("$[0].parentId").doesNotExist())
                .andExpect(jsonPath("$[0].replies.length()").value(2))
                .andExpect(jsonPath("$[0].replies[0].content").value("回复一级：同意"))
                .andExpect(jsonPath("$[0].replies[0].replyToName").value(names[0]))
                .andExpect(jsonPath("$[0].replies[1].content").value("回复二级：+1"))
                // 回复的回复被收敛到根评论下，但「回复 @」仍指向被回复的那位
                .andExpect(jsonPath("$[0].replies[1].replyToName").value(names[1]))
                .andExpect(jsonPath("$[0].replies[1].parentId").value((int) rootId));

        // 回复里伪造的 authorName / replyToName 一律忽略
        assertThat(om.readTree(mvc.perform(get("/api/articles/" + slug + "/comments"))
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path(0).path("replies").path(0).path("authorName").asText())
                .isEqualTo(names[1]);

        // 删除根评论 -> 级联删掉两条回复，公开树清空
        mvc.perform(delete("/api/admin/comments/" + rootId).header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("2 条回复")));
        assertThat(comments.findById(rootId)).isEmpty();
        assertThat(comments.findById(replyId)).isEmpty();
        assertThat(comments.findById(replyOfReply)).isEmpty();
        mvc.perform(get("/api/articles/" + slug + "/comments")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    /** 以某个用户身份发评论/回复，返回新评论 id */
    private long postComment(String slug, String token, String content, String parentId) throws Exception {
        Map<String, String> body = new java.util.HashMap<>();
        body.put("content", content);
        if (parentId != null) body.put("parentId", parentId);
        MvcResult r = mvc.perform(post("/api/articles/" + slug + "/comments")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(body)))
                .andExpect(status().isOk()).andReturn();
        // 从后台列表里找出刚创建的那条（公开接口只返回已审核的）
        String admin = loginToken(ADMIN, ADMIN_PWD);
        JsonNode all = om.readTree(mvc.perform(get("/api/admin/comments").header("Authorization", "Bearer " + admin))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        long max = 0;
        for (JsonNode c : all) {
            if (content.equals(c.path("content").asText())) max = Math.max(max, c.path("id").asLong());
        }
        return max;
    }

    private void approve(String adminToken, long commentId) throws Exception {
        mvc.perform(put("/api/admin/comments/" + commentId + "/status")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "APPROVED"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("注册用户名限制字符集（防存储型 XSS / 混淆名）")
    void registerRejectsUnsafeUsername() throws Exception {
        // 含尖括号/空格/中文等一律拒绝
        for (String bad : new String[]{"<script>", "a b", "中文名", "ab", "x".repeat(21)}) {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("username", bad, "password", "pass123456"))))
                    .andExpect(status().isBadRequest());
        }
        // 合法用户名可通过
        String ok = "ok_" + (System.currentTimeMillis() % 100000);
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", ok, "password", "pass123456"))))
                .andExpect(status().isOk());
    }

    /* ==================== 页面 ==================== */

    @Test
    @DisplayName("自定义页面创建、公开访问、删除")
    void pageCrud() throws Exception {
        String token = loginToken(ADMIN, ADMIN_PWD);
        String slug = "about-" + System.currentTimeMillis();
        MvcResult created = mvc.perform(post("/api/admin/pages").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "关于我们", "slug", slug, "content", "**关于**内容", "published", "true"))))
                .andExpect(status().isOk()).andReturn();
        long id = om.readTree(created.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("id").asLong();

        mvc.perform(get("/api/pages/" + slug)).andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("关于我们"));
        mvc.perform(get("/page/" + slug)).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("关于")));

        mvc.perform(delete("/api/admin/pages/" + id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    /* ==================== 文件上传限制与扫描 ==================== */

    @Test
    @DisplayName("管理员可传文档、禁止可执行文件")
    void adminUploadRules() throws Exception {
        String token = loginToken(ADMIN, ADMIN_PWD);
        // 允许：txt
        MvcResult up = mvc.perform(multipart("/api/admin/files/upload").file(
                        new MockMultipartFile("file", "note.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(up.getResponse().getStatus())
                .as("txt 上传响应: " + up.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        assertThat(up.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("/media/");
        // 禁止：exe
        mvc.perform(multipart("/api/admin/files/upload").file(
                        new MockMultipartFile("file", "evil.exe", "application/octet-stream", new byte[]{0x4D, 0x5A}))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("普通用户仅允许图片/视频，伪装文件被标记为危险")
    void userUploadRulesAndScan() throws Exception {
        String uname = "tester" + (System.currentTimeMillis() % 100000);
        String token = registerAndApprove(uname, "tester123");

        // 真 JPEG：应通过且扫描为 SAFE
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
                'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00, 0x00, 0x01};
        MvcResult ok = mvc.perform(multipart("/api/admin/files/upload").file(
                        new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpeg))
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(ok.getResponse().getStatus())
                .as("jpeg 上传响应: " + ok.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("SAFE");

        // 真 MP4（ftyp box）：应通过且为 SAFE，同时回归短文件魔数越界问题
        byte[] mp4 = new byte[]{0x00, 0x00, 0x00, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o', 'm'};
        MvcResult vid = mvc.perform(multipart("/api/admin/files/upload").file(
                        new MockMultipartFile("file", "clip.mp4", "video/mp4", mp4))
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(vid.getResponse().getStatus())
                .as("mp4 上传响应: " + vid.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        assertThat(vid.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("SAFE");

        // 伪装 jpg（实为 PHP 脚本）：扩展名放行但内容扫描判为 DANGEROUS
        mvc.perform(multipart("/api/admin/files/upload").file(
                        new MockMultipartFile("file", "shell.jpg", "image/jpeg",
                                "<?php system($_GET['c']); ?>".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scanStatus").value("DANGEROUS"));

        // 脚本类型直接拒绝
        mvc.perform(multipart("/api/admin/files/upload").file(
                        new MockMultipartFile("file", "x.sh", "text/x-sh", "rm -rf /".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());

        // 普通用户不允许上传 pdf
        mvc.perform(multipart("/api/admin/files/upload").file(
                        new MockMultipartFile("file", "doc.pdf", "application/pdf", "%PDF-1.4".getBytes(StandardCharsets.UTF_8)))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("媒体访问经 Spring 输出并写入访问日志，危险文件不对外")
    void mediaAccessAndLogging() throws Exception {
        String token = loginToken(ADMIN, ADMIN_PWD);
        byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
                'J', 'F', 'I', 'F', 0x00, 0x01, 0x01, 0x00};
        MvcResult up = mvc.perform(multipart("/api/admin/files/upload").file(
                        new MockMultipartFile("file", "pic.jpg", "image/jpeg", jpeg))
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        assertThat(up.getResponse().getStatus())
                .as("媒体上传响应: " + up.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(200);
        JsonNode node = om.readTree(up.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String stored = node.path("storedName").asText();

        mvc.perform(get("/media/" + stored))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline")))
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("image/jpeg")))
                .andExpect(header().string("Accept-Ranges", "bytes"));

        // HTTP Range：视频/<audio> 按需拉流与拖动进度条依赖 206 Partial Content
        MvcResult range = mvc.perform(get("/media/" + stored).header("Range", "bytes=0-3")).andReturn();
        assertThat(range.getResponse().getStatus()).as("Range 请求应返回 206").isEqualTo(206);
        String contentRange = range.getResponse().getHeader("Content-Range");
        assertThat(contentRange).as("Content-Range 应存在").isNotNull();
        assertThat(contentRange).as("Content-Range 内容").startsWith("bytes 0-3/");
        assertThat(range.getResponse().getContentAsByteArray())
                .as("只返回请求的 4 个字节")
                .containsExactly((byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0);

        // 起止非法 → 416
        mvc.perform(get("/media/" + stored).header("Range", "bytes=999999-")).andExpect(
                status().isRequestedRangeNotSatisfiable());

        boolean logged = logs.findAll().stream()
                .anyMatch(l -> ("/media/" + stored).equals(l.getUri()));
        assertThat(logged).as("媒体访问应写入日志").isTrue();

        // 未登记的文件不对外
        mvc.perform(get("/media/not-exist-file.png")).andExpect(status().isNotFound());
    }

    /* ==================== AI 封面：必须落盘到本站媒体库 ==================== */

    /** 1x1 PNG，用于冒充第三方图床返回的封面 */
    private static final byte[] PNG_1X1 = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8/x8AAwMCAO+ip1sAAAAASUVORK5CYII=");

    private static void respond(HttpExchange ex, int code, String contentType, byte[] body) throws java.io.IOException {
        ex.getResponseHeaders().add("Content-Type", contentType);
        ex.sendResponseHeaders(code, body.length);
        try (var os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    /**
     * AI 封面绝不能把上游（ModelScope 等）的第三方临时链接存进封面字段 —— 那种链接过期即裂图。
     * 这里起一个本机假图床，走完整的「生成接口 → 下载图片 → 落盘媒体库 → 返回 /media/xxx」链路。
     */
    @Test
    @DisplayName("AI 封面：第三方图片链接必须下载落盘到本站媒体库，不返回外部 URL")
    void aiCoverSavedToServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/v1/images/generations", ex -> respond(ex, 200, "application/json",
                ("{\"data\":[{\"url\":\"http://127.0.0.1:" + port + "/cdn/cover.png\"}]}")
                        .getBytes(StandardCharsets.UTF_8)));
        // 图床故意先 302（CDN 常态），验证下载会跟随重定向
        server.createContext("/cdn/cover.png", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + port + "/real/cover.png");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/real/cover.png", ex -> respond(ex, 200, "image/png", PNG_1X1));
        server.start();

        String baseBackup = ai.get("ai.baseUrl", "");
        String keyBackup = ai.get("ai.apiKey", "");
        String modelBackup = ai.get("ai.imageModel", "");
        try {
            ai.set("ai.baseUrl", "http://127.0.0.1:" + port + "/v1");
            ai.set("ai.apiKey", "test-key");
            ai.set("ai.imageModel", "test-model");

            String token = loginToken(ADMIN, ADMIN_PWD);
            MvcResult r = mvc.perform(post("/api/admin/ai/cover")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("prompt", "测试封面")))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn();
            String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(body).as("AI 封面响应: " + body).contains("/media/");
            assertThat(body).as("上游的第三方链接不能出现在返回里").doesNotContain("127.0.0.1");

            Matcher m = Pattern.compile("/media/([0-9a-f]{16}\\.png)").matcher(body);
            assertThat(m.find()).as("应返回本站 /media/xxx.png: " + body).isTrue();
            String stored = m.group(1);

            assertThat(fileRepo.findByStoredName(stored))
                    .as("落盘的图必须登记进媒体库，否则会被全库扫描判为「孤立文件」").isPresent();
            mvc.perform(get("/media/" + stored))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("image/png")));
        } finally {
            server.stop(0);
            ai.set("ai.baseUrl", baseBackup);
            ai.set("ai.apiKey", keyBackup);
            ai.set("ai.imageModel", modelBackup);
        }
    }

    @Test
    @DisplayName("AI 封面：上游返回的是错误页而非图片时，明确失败且不往媒体库塞脏文件")
    void aiCoverRejectsNonImage() throws Exception {
        long before = fileRepo.count();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/v1/images/generations", ex -> respond(ex, 200, "application/json",
                ("{\"data\":[{\"url\":\"http://127.0.0.1:" + port + "/cdn/err.html\"}]}")
                        .getBytes(StandardCharsets.UTF_8)));
        // 图床把 HTML 错误页当图片返回（限流 / 403 时很常见）
        server.createContext("/cdn/err.html", ex -> respond(ex, 200, "text/html",
                "<html><body>403 Forbidden</body></html>".getBytes(StandardCharsets.UTF_8)));
        server.start();

        String baseBackup = ai.get("ai.baseUrl", "");
        String keyBackup = ai.get("ai.apiKey", "");
        String modelBackup = ai.get("ai.imageModel", "");
        try {
            ai.set("ai.baseUrl", "http://127.0.0.1:" + port + "/v1");
            ai.set("ai.apiKey", "test-key");
            ai.set("ai.imageModel", "test-model");

            String token = loginToken(ADMIN, ADMIN_PWD);
            MvcResult r = mvc.perform(post("/api/admin/ai/cover")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("prompt", "测试封面")))
                            .header("Authorization", "Bearer " + token))
                    .andReturn();
            String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);

            assertThat(r.getResponse().getStatus())
                    .as("拿到的不是图片，应明确报错而不是静默成功: " + body).isEqualTo(502);
            assertThat(body).as("错误信息应可读: " + body).contains("AI 封面生成失败");
            assertThat(body).as("不能返回任何可直接写进封面的地址").doesNotContain("/media/");
            assertThat(fileRepo.count()).as("不该往媒体库塞脏文件").isEqualTo(before);
        } finally {
            server.stop(0);
            ai.set("ai.baseUrl", baseBackup);
            ai.set("ai.apiKey", keyBackup);
            ai.set("ai.imageModel", modelBackup);
        }
    }

    /**
     * AI 优化标题 / 提取 SEO 关键词 / 提取 SEO 描述：起一个本机假 chat 上游，故意回「模型不守格式」
     * 的典型脏输出（带包裹引号、带「优化后的标题：」标签、带序号的列表），
     * 验证接口返回的是清洗后可直接落库的文本（这三点都会写进表单字段再随文章保存）。
     */
    @Test
    @DisplayName("AI 标题/关键词/SEO 描述：把模型的脏输出清洗成可直接落库的文本")
    void aiTitleKeywordsAndSeoDescription() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> lastChatReq = new java.util.concurrent.atomic.AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        server.createContext("/v1/chat/completions", ex -> {
            String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastChatReq.set(req);
            String content;
            if (req.contains("SEO 关键词")) {
                content = "关键词：1. 写字台、2. 博客系统\n3. 内容管理";
            } else if (req.contains("Meta Description")) {
                content = "「写字台是一套开箱即用的自托管博客系统，支持文章、页面、评论与媒体管理。」";
            } else {
                content = "\"优化后的标题：写字台 · 自托管博客系统\"";
            }
            respond(ex, 200, MediaType.APPLICATION_JSON_VALUE, ("{\"choices\":[{\"message\":{\"content\":"
                    + om.writeValueAsString(content) + "}}]}").getBytes(StandardCharsets.UTF_8));
        });
        server.start();

        String baseBackup = ai.get("ai.baseUrl", "");
        String keyBackup = ai.get("ai.apiKey", "");
        String modelBackup = ai.get("ai.model", "");
        try {
            ai.set("ai.baseUrl", "http://127.0.0.1:" + port + "/v1");
            ai.set("ai.apiKey", "test-key");
            ai.set("ai.model", "fake-chat");

            String token = loginToken(ADMIN, ADMIN_PWD);
            Map<String, String> in = Map.of("title", "写字台", "text", "# 写字台\n一套自托管博客系统");
            String titleBody = postAi(token, "/api/admin/ai/title", in);
            String kwBody = postAi(token, "/api/admin/ai/seo-keywords", in);
            String descBody = postAi(token, "/api/admin/ai/seo-description", in);

            assertThat(om.readTree(titleBody).path("result").asText())
                    .as("标题应去掉引号与「优化后的标题：」标签: " + titleBody)
                    .isEqualTo("写字台 · 自托管博客系统");
            assertThat(om.readTree(kwBody).path("result").asText())
                    .as("关键词应把带序号的列表洗成逗号分隔: " + kwBody)
                    .isEqualTo("写字台, 博客系统, 内容管理");
            assertThat(om.readTree(descBody).path("result").asText())
                    .as("描述应去掉包裹的书名号: " + descBody)
                    .isEqualTo("写字台是一套开箱即用的自托管博客系统，支持文章、页面、评论与媒体管理。");

            assertThat(lastChatReq.get())
                    .as("发给模型的不该只有标题，正文必须一起带上")
                    .contains("自托管博客系统").contains("fake-chat");
        } finally {
            server.stop(0);
            ai.set("ai.baseUrl", baseBackup);
            ai.set("ai.apiKey", keyBackup);
            ai.set("ai.model", modelBackup);
        }
    }

    /**
     * 未配置大模型时，三个新接口必须回「（AI 功能未启用…）」这类提示文案而不是空串 ——
     * 前端靠这个前缀判断「AI 没干活」，从而不去覆盖用户已经写好的标题/关键词/描述。
     */
    @Test
    @DisplayName("AI 未配置：标题/关键词/描述接口返回可展示的降级文案，而不是空结果")
    void aiSeoHelpersDegradeWhenUnconfigured() throws Exception {
        String baseBackup = ai.get("ai.baseUrl", "");
        String keyBackup = ai.get("ai.apiKey", "");
        String modelBackup = ai.get("ai.model", "");
        try {
            ai.set("ai.baseUrl", "");
            ai.set("ai.apiKey", "");
            ai.set("ai.model", "");

            String token = loginToken(ADMIN, ADMIN_PWD);
            for (String path : new String[]{"/api/admin/ai/title", "/api/admin/ai/seo-keywords",
                    "/api/admin/ai/seo-description"}) {
                String body = postAi(token, path, Map.of("title", "标题", "text", "正文"));
                String result = om.readTree(body).path("result").asText();
                assertThat(result).as(path + " 的降级文案必须可展示: " + body).startsWith("（AI ");
                assertThat(AiTextCleaner.isUnavailable(result))
                        .as(path + " 必须能被前端识别为「AI 没干活」，否则会覆盖用户内容").isTrue();
            }
        } finally {
            ai.set("ai.baseUrl", baseBackup);
            ai.set("ai.apiKey", keyBackup);
            ai.set("ai.model", modelBackup);
        }
    }

    @Test
    @DisplayName("AI 文本清洗：未配置提示原样透出、清洗后为空则回退原标题")
    void aiTextCleanerEdgeCases() {
        String unavailable = "（AI 功能未启用：请在后台配置大模型接口地址、API Key 与模型名）";
        assertThat(AiTextCleaner.isUnavailable(unavailable)).isTrue();
        assertThat(AiTextCleaner.title(unavailable, "原标题")).as("提示文案不能写进标题字段").isEqualTo(unavailable);
        assertThat(AiTextCleaner.keywords(unavailable)).isEqualTo(unavailable);
        assertThat(AiTextCleaner.seoDescription(unavailable)).isEqualTo(unavailable);

        assertThat(AiTextCleaner.title("# 标题：**我的博客**", "原")).isEqualTo("我的博客");
        assertThat(AiTextCleaner.title("   ", "原标题")).as("模型给了空白就保留原标题").isEqualTo("原标题");
        assertThat(AiTextCleaner.keywords("A,B,A,C")).as("关键词要去重").isEqualTo("A, B, C");
        assertThat(AiTextCleaner.keywords("这是很长的一整句话根本没有分隔符所以提取不出关键词"))
                .as("整句不是关键词，宁可为空也不要脏数据").isEmpty();
        assertThat(AiTextCleaner.seoDescription("描述：\n\n这是一段描述。")).isEqualTo("这是一段描述。");
    }

    /** 调 AI 接口并返回响应体（都要求已登录管理员） */
    private String postAi(String token, String path, Map<String, ?> body) throws Exception {
        MvcResult r = mvc.perform(post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /* ==================== 开放 API / MCP ==================== */

    @Test
    @DisplayName("开放 API：无 token 拒绝，有 token 发布成功")
    void openApiPublish() throws Exception {
        mvc.perform(post("/api/v1/publish").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "外部文章", "content", "内容"))))
                .andExpect(status().isUnauthorized());

        String token = loginToken(ADMIN, ADMIN_PWD);
        String apiToken = apiToken(token);
        assertThat(apiToken).isNotBlank();

        MvcResult r = mvc.perform(post("/api/v1/publish").header("X-API-Token", apiToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "外部系统推送-" + System.currentTimeMillis(),
                                "content", "## 来自 API\n\n正文"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slug").isNotEmpty())
                .andReturn();
        String slug = om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("slug").asText();
        mvc.perform(get("/api/articles/" + slug)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("MCP 服务：握手 / 工具列表 / 调用发布")
    void mcpJsonRpc() throws Exception {
        String token = loginToken(ADMIN, ADMIN_PWD);
        String apiToken = apiToken(token);

        mvc.perform(post("/api/v1/mcp").header("X-API-Token", apiToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("jsonrpc", "2.0", "id", 1, "method", "initialize",
                                "params", Map.of("protocolVersion", "2024-11-05")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.result.serverInfo.name").value("xiezitai-mcp"));

        mvc.perform(post("/api/v1/mcp").header("X-API-Token", apiToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("jsonrpc", "2.0", "id", 2, "method", "tools/list"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools.length()").value(3));

        mvc.perform(post("/api/v1/mcp").header("X-API-Token", apiToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("jsonrpc", "2.0", "id", 3, "method", "tools/call",
                                "params", Map.of("name", "publish_article",
                                        "arguments", Map.of("title", "MCP 发布-" + System.currentTimeMillis(),
                                                "content", "**来自 MCP**"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.content[0].text").value(org.hamcrest.Matchers.containsString("已发布")));

        // 未知方法
        mvc.perform(post("/api/v1/mcp").header("X-API-Token", apiToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("jsonrpc", "2.0", "id", 4, "method", "no/such"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32601));
    }

    /* ==================== 管理端权限 / 日志 ==================== */

    @Test
    @DisplayName("未登录访问管理接口返回 401")
    void adminApiRequiresAuth() throws Exception {
        mvc.perform(get("/api/admin/settings")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/users")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("非管理员访问管理接口返回 403")
    void nonAdminGets403() throws Exception {
        String uname = "normal" + (System.currentTimeMillis() % 100000);
        String token = registerAndApprove(uname, "normal123");
        mvc.perform(get("/api/admin/settings").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("登录用户可修改密码：旧密码校验 + 新密码生效")
    void changePassword() throws Exception {
        String uname = "pwdchg" + (System.currentTimeMillis() % 100000);
        String token = registerAndApprove(uname, "oldpass66");

        // 未登录不能改密码
        mvc.perform(post("/api/auth/password").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("oldPassword", "oldpass66", "newPassword", "newpass77"))))
                .andExpect(status().isUnauthorized());

        // 原密码错误 → 400
        mvc.perform(post("/api/auth/password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("oldPassword", "wrongpwd1", "newPassword", "newpass77"))))
                .andExpect(status().isBadRequest());

        // 新密码太短 → 400
        mvc.perform(post("/api/auth/password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("oldPassword", "oldpass66", "newPassword", "123"))))
                .andExpect(status().isBadRequest());

        // 正确修改 → 200
        mvc.perform(post("/api/auth/password").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("oldPassword", "oldpass66", "newPassword", "newpass77"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("密码已修改"));

        // 旧密码登录失败、新密码登录成功
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", uname, "password", "oldpass66"))))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", uname, "password", "newpass77"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("记住登录：勾选后签发 30 天 token，未勾选为默认 72 小时")
    void rememberMeTokenTtl() throws Exception {
        String uname = "remember" + (System.currentTimeMillis() % 100000);
        registerAndApprove(uname, "pass123456");

        // 未勾选 → 默认（测试环境 1 小时）
        MvcResult r1 = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", uname, "password", "pass123456"))))
                .andExpect(status().isOk()).andReturn();
        long ttl1 = tokenTtlSeconds(om.readTree(r1.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("token").asText());
        assertThat(ttl1).as("默认有效期应为 1 小时").isBetween(3500L, 3700L);

        // 勾选记住登录 → 30 天
        MvcResult r2 = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", uname, "password", "pass123456", "remember", "true"))))
                .andExpect(status().isOk()).andReturn();
        long ttl2 = tokenTtlSeconds(om.readTree(r2.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("token").asText());
        assertThat(ttl2).as("记住登录有效期应为 30 天").isBetween(29L * 86400, 31L * 86400);
    }

    /** 解析 JWT payload 的 exp - iat（秒） */
    private long tokenTtlSeconds(String jwt) {
        String[] parts = jwt.split("\\.");
        byte[] payload = java.util.Base64.getUrlDecoder().decode(parts[1]);
        try {
            com.fasterxml.jackson.databind.JsonNode n = om.readTree(payload);
            return n.path("exp").asLong() - n.path("iat").asLong();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("请求日志被完整记录")
    void requestLogsRecorded() throws Exception {
        mvc.perform(get("/robots.txt")).andExpect(status().isOk());
        boolean logged = logs.findAll().stream()
                .anyMatch(l -> "/robots.txt".equals(l.getUri()) && l.getStatus() == 200);
        assertThat(logged).as("请求日志应记录 /robots.txt").isTrue();
    }

    @Test
    @DisplayName("管理员设置与用户管理可用")
    void adminSettingsAndUsers() throws Exception {
        String token = loginToken(ADMIN, ADMIN_PWD);

        mvc.perform(post("/api/admin/settings").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("notify.enabled", "false", "ai.model", "test-model"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/admin/settings").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$['wecom.webhook']").exists())
                .andExpect(jsonPath("$['ai.model']").value("test-model"));

        mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].username").exists());

        // 请求日志分页接口
        mvc.perform(get("/api/admin/logs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());

        // 防篡改自检 + 上传目录扫描
        mvc.perform(get("/api/admin/security/tamper-check").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        mvc.perform(post("/api/admin/security/scan-uploads").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").isNotEmpty());

        // AI 未配置时返回友好降级文案，而不是报错
        mvc.perform(post("/api/admin/ai/summary").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("text", "测试内容"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").isNotEmpty());
    }

    @Test
    @DisplayName("页面：连续建「纯中文标题」也能成功（slug 去重查的是页面表）")
    void pageSlugsDedupeAgainstPagesTable() throws Exception {
        String token = loginToken(ADMIN, ADMIN_PWD);
        String stamp = String.valueOf(System.currentTimeMillis());
        // 两个标题 slugify 后只剩同一串数字（中文被剥掉）——
        // 修之前第二个会撞 xiezitai_pages.slug 唯一索引，接口直接 500
        long[] ids = new long[] { -1, -1 };
        try {
            String slug1 = createPage(token, "中文页面甲" + stamp, ids, 0);
            String slug2 = createPage(token, "中文页面乙" + stamp, ids, 1);
            assertThat(slug1).as("第一个页面的 slug").isNotBlank();
            assertThat(slug2).as("同基础 slug 的第二个页面应被改成别的").isNotEqualTo(slug1);

            // 显式指定一个已被占用的 slug → 409 + 可读文案，而不是 500
            mvc.perform(post("/api/admin/pages").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("title", "冲突页" + stamp, "slug", slug1,
                                    "content", "x", "published", true))))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error").isNotEmpty());
        } finally {
            for (long id : ids) {
                if (id > 0) mvc.perform(delete("/api/admin/pages/" + id).header("Authorization", "Bearer " + token));
            }
        }
    }

    /** 建一个页面：返回服务端分配的 slug，并把 id 记进 ids 供用例收尾删除 */
    private String createPage(String token, String title, long[] ids, int slot) throws Exception {
        MvcResult r = mvc.perform(post("/api/admin/pages").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", title, "slug", "", "content", "# " + title, "published", true))))
                .andExpect(status().isOk()).andReturn();
        JsonNode node = om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        ids[slot] = node.path("id").asLong();
        return node.path("slug").asText();
    }

    @Test
    void articleTagsNormalizeLimitAndSearch() throws Exception {
        String token = loginToken(ADMIN, ADMIN_PWD);
        String stamp = String.valueOf(System.currentTimeMillis());
        long[] ids = new long[] { -1, -1 };
        try {
            // ① 归一化：中英文逗号/顿号混用 + 去重 + 去空白，存成干净的逗号串
            MvcResult r = mvc.perform(post("/api/admin/articles").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("title", "标签归一化" + stamp, "slug", "",
                                    "content", "正文", "tags", "Java, Spring Boot ，写作 、Java; 运维, 独门" + stamp))))
                    .andExpect(status().isOk()).andReturn();
            JsonNode a = om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
            ids[0] = a.path("id").asLong();
            assertThat(a.path("tags").asText())
                    .as("标签应去空白/去重并统一为英文逗号分隔")
                    .isEqualTo("Java,Spring Boot,写作,运维,独门" + stamp);

            // ② 归一化后仍超 10 个 → 400 + 可读文案（不是静默截断）
            String eleven = "t1,t2,t3,t4,t5,t6,t7,t8,t9,t10,t11";
            mvc.perform(post("/api/admin/articles").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("title", "标签超量" + stamp, "slug", "",
                                    "content", "正文", "tags", eleven))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value(
                            org.hamcrest.Matchers.containsString("标签最多 10 个")));

            // ③ 后台搜索按标签命中：独门标签只出现在 tags 里，标题/摘要/正文都没有它
            mvc.perform(get("/api/admin/articles").header("Authorization", "Bearer " + token)
                            .param("q", "独门" + stamp).param("size", "50"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalElements").value(1))
                    .andExpect(jsonPath("$.content[0].tags").value("Java,Spring Boot,写作,运维,独门" + stamp));

            // ④ 更新时把标签清空 → 存空串
            mvc.perform(put("/api/admin/articles/" + ids[0]).header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("title", "标签归一化" + stamp, "tags", ""))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.tags").value(""));
        } finally {
            for (long id : ids) {
                if (id > 0) mvc.perform(delete("/api/admin/articles/" + id).header("Authorization", "Bearer " + token));
            }
        }
    }

    /* ==================== 工具方法 ==================== */

    private String json(Map<String, ?> map) throws Exception {
        return om.writeValueAsString(map);
    }

    private String loginToken(String username, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("token").asText();
    }

    /**
     * 注册一个普通用户并置为「审核通过」，返回登录 token。
     * 走 /api/auth/register 注册的用户默认是 PENDING、登录会被 403 拦下；
     * 除审核专项用例外，其它用例要的是「能正常使用的普通用户」，所以这里直接放行。
     */
    private String registerAndApprove(String username, String password) throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk());
        User u = users.findByUsername(username).orElseThrow();
        u.setStatus("APPROVED");
        users.save(u);
        return loginToken(username, password);
    }

    /** 用管理员 token 按用户名查用户 id（后台列表接口） */
    private long userIdByName(String adminToken, String username) throws Exception {
        MvcResult r = mvc.perform(get("/api/admin/users").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk()).andReturn();
        JsonNode list = om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        for (JsonNode u : list) {
            if (username.equals(u.path("username").asText())) return u.path("id").asLong();
        }
        throw new IllegalArgumentException("后台列表里找不到用户: " + username);
    }

    private String apiToken(String jwt) throws Exception {
        MvcResult r = mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("apiToken").asText();
    }

    /** GET 一个 HTML 页面并按 UTF-8 取回正文（中文断言必须显式解码，否则乱码） */
    private String getBody(String path) throws Exception {
        return mvc.perform(get(path)).andExpect(status().isOk()).andReturn()
                .getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static int countOccurrences(String haystack, String needle) {
        int n = 0, i = haystack.indexOf(needle);
        while (i >= 0) { n++; i = haystack.indexOf(needle, i + needle.length()); }
        return n;
    }
}
