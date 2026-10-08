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
@org.springframework.test.context.TestPropertySource(properties = "xiezitai.cn-media-hosts=127.0.0.1")
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

    @Test
    @DisplayName("首页搜索：标题/正文/标签命中已发布文章、草稿不露头、搜索页 noindex、翻页带 q")
    void homeSearch() throws Exception {
        String token = loginToken(ADMIN, ADMIN_PWD);
        String kw = "ZK" + (System.currentTimeMillis() % 1000000);
        String prefix = "搜索命中-" + kw + "-";
        // 12 篇标题命中：凑出第 2 页，顺带验证搜索与分页叠加
        for (int i = 1; i <= 12; i++) {
            mvc.perform(post("/api/admin/articles").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("title", prefix + i, "content", "第 " + i + " 篇",
                                    "status", "PUBLISHED"))))
                    .andExpect(status().isOk());
        }
        // 正文命中（标题里没有这个词）
        mvc.perform(post("/api/admin/articles").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "正文命中-" + kw, "content", "正文里写着 " + kw,
                                "status", "PUBLISHED"))))
                .andExpect(status().isOk());
        // 标签命中（标题与正文都不含）
        mvc.perform(post("/api/admin/articles").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "标签命中-" + kw, "content", "正文无关词",
                                "tags", "E2E," + kw, "status", "PUBLISHED"))))
                .andExpect(status().isOk());
        // 草稿命中：状态是硬编码的 PUBLISHED 条件，草稿绝不能被搜出来
        mvc.perform(post("/api/admin/articles").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "草稿命中-" + kw, "content", "草稿 " + kw,
                                "status", "DRAFT"))))
                .andExpect(status().isOk());

        String p1 = getBody("/?q=" + kw);
        assertThat(countOccurrences(p1, "<article>")).as("搜索结果同样每页 10 篇").isEqualTo(10);
        assertThat(p1).contains("class=\"search\"");                       // 搜索框在
        assertThat(p1).contains(kw).contains("命中 <b>14</b> 篇");          // 14 = 12 标题 + 1 正文 + 1 标签
        assertThat(p1).contains("name=\"robots\" content=\"noindex,follow\"");
        assertThat(p1).contains("?q=" + kw + "&amp;page=2");               // 翻页链接带着关键词
        assertThat(p1).doesNotContain("草稿命中-" + kw);

        String p2 = getBody("/?q=" + kw + "&page=2");
        assertThat(countOccurrences(p2, "<article>")).as("第 2 页剩 4 篇").isEqualTo(4);
        assertThat(p2).contains("?q=" + kw + "&amp;page=1");               // 「上一页」也带 q
        assertThat(p2).doesNotContain("草稿命中-" + kw);

        // 搜不到：可读空态，且不给分页条（1 页不需要翻）
        String none = getBody("/?q=" + kw + "绝无此词");
        assertThat(none).contains("没有找到匹配").doesNotContain("class=\"pager\"");

        // 空关键词按普通首页处理：不 noindex、不显示命中提示
        String blank = getBody("/?q=");
        assertThat(blank).doesNotContain("name=\"robots\"").doesNotContain("class=\"hit\"");
        // 无 q 时翻页链接不带 q（与改造前一致）
        assertThat(getBody("/")).doesNotContain("?page=2&q=");
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

    /* ==================== WordPress 关联与导入 ==================== */

    @Autowired cn.xiezitai.service.WordPressImportService wpImports;

    @Test
    @DisplayName("WordPress：sameHost 判定与 HTML 实体还原")
    void wpHelpers() {
        assertThat(wpImports.sameHost("https://www.Example.com/wp-content/a.png", "https://example.com")).isTrue();
        assertThat(wpImports.sameHost("https://example.com/a.png", "https://www.example.com")).isTrue();
        assertThat(wpImports.sameHost("https://cdn.other.com/a.png", "https://example.com")).isFalse();
        assertThat(wpImports.sameHost("/relative/a.png", "https://example.com")).isFalse();
        // slug 多候选：中文要给出 WP 风格的大小写两种编码形态；%xx 字面串要能解码回中文
        assertThat(cn.xiezitai.controller.PageViewController.slugCandidates("你好世界"))
                .contains("%E4%BD%A0%E5%A5%BD%E4%B8%96%E7%95%8C", "%e4%bd%a0%e5%a5%bd%e4%b8%96%e7%95%8c");
        assertThat(cn.xiezitai.controller.PageViewController.slugCandidates("%e4%bd%a0%e5%a5%bd"))
                .contains("你好", "%25e4%25bd%25a0%25e5%25a5%25bd");   // 再解码 + 双重编码兜底
        // 大小写差异由 findBySlugIgnoreCase 消化，不需要在候选里枚举大写形态
        assertThat(cn.xiezitai.controller.PageViewController.slugCandidates("hello-world"))
                .containsExactly("hello-world");
        // WP 中文 slug 导入前先解码：避免 %xx 字面串入库后（链接 %25）被安全防火墙 400 拦截
        assertThat(wpImports.normalizeWpSlug("%e4%bd%a0%e5%a5%bd%e4%b8%96%e7%95%8c")).isEqualTo("你好世界");
        assertThat(wpImports.normalizeWpSlug("hello-world")).isEqualTo("hello-world");
        assertThat(wpImports.normalizeWpSlug("a%20b")).as("解码后含空格不合格，保持原样").isEqualTo("a%20b");
        assertThat(cn.xiezitai.service.WordPressClient.unescapeEntities("A &#8217; B &amp; C &#x4e2d;"))
                .isEqualTo("A ’ B & C 中");
        assertThat(cn.xiezitai.service.WordPressClient.stripTags("<p>Ex &amp; <b>bold</b></p>"))
                .isEqualTo("Ex & bold");
    }

    /**
     * 起一个本机假 WP 站点（REST API + 媒体文件），走完整链路：
     * 关联站点（Token 不回显）→ 浏览文章 → 单篇导入（站点自身图片落盘、外站图保留外链）
     * → 重复导入跳过 → 整站导入（后台线程 + 进度轮询到 DONE）。
     */
    @Test
    @DisplayName("WordPress：关联/Token 脱敏/单篇导入媒体落盘/整站导入进度")
    void wpAssociateImportAndMediaLocalize() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;
        final String wpToken = "abcd efgh ijkl mnop";
        final String expectedAuth = "Basic " + Base64.getEncoder()
                .encodeToString(("bob:" + wpToken).getBytes(StandardCharsets.UTF_8));

        server.createContext("/wp-json/", ex -> respond(ex, 200, "application/json",
                "{\"name\":\"Mock WP\",\"description\":\"测试站点\"}".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/wp-json/wp/v2/users/me", ex -> {
            if (!expectedAuth.equals(ex.getRequestHeaders().getFirst("Authorization"))) {
                respond(ex, 401, "application/json", "{\"code\":\"rest_forbidden\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(ex, 200, "application/json", "{\"name\":\"Bob\"}".getBytes(StandardCharsets.UTF_8));
        });
        String listJson = "[{\"id\":101,\"title\":{\"rendered\":\"Hello &#8217; World\"},\"slug\":\"wp-hello-101\","
                + "\"date\":\"2026-01-02T10:00:00\",\"status\":\"publish\",\"link\":\"" + base + "/?p=101\"},"
                + "{\"id\":102,\"title\":{\"rendered\":\"Second Post\"},\"slug\":\"wp-second-102\","
                + "\"date\":\"2026-01-03T11:00:00\",\"status\":\"draft\",\"link\":\"" + base + "/?p=102\"}]";
        server.createContext("/wp-json/wp/v2/posts", ex -> {
            ex.getResponseHeaders().add("X-WP-Total", "2");
            ex.getResponseHeaders().add("X-WP-TotalPages", "1");
            respond(ex, 200, "application/json", listJson.getBytes(StandardCharsets.UTF_8));
        });
        // 站点自身图片：应落盘；外站图（cdn.external.com 不可达也无妨）：应保留外链
        String content101 = "<p>intro</p>"
                + "<figure><img src=\"" + base + "/wp-content/uploads/2026/01/pic.png\" "
                + "srcset=\"" + base + "/wp-content/uploads/2026/01/pic.png 300w\"/></figure>"
                + "<p><img src=\"http://cdn.external.com/ext.png\"/></p>"
                + "<p><a href=\"" + base + "/wp-content/uploads/2026/01/doc.pdf\">doc</a></p>";
        // 装了 Markdown 插件的站点：content.rendered 是插件渲染后的 HTML，content.raw 才是编辑器里的
        // Markdown 原文。导入必须优先用 raw（否则 HTML 被当 Markdown 入库，文章显示错乱）。
        String raw101 = "intro\n\n"
                + "![pic](" + base + "/wp-content/uploads/2026/01/pic.png)\n\n"
                + "![](http://cdn.external.com/ext.png)\n\n"
                + "[doc](" + base + "/wp-content/uploads/2026/01/doc.pdf)\n";
        String post101 = "{\"id\":101,\"title\":{\"rendered\":\"Hello &#8217; World\"},\"slug\":\"wp-hello-101\","
                + "\"status\":\"publish\",\"date_gmt\":\"2026-01-02T10:00:00\","
                + "\"content\":{\"raw\":\"" + raw101.replace("\n", "\\n").replace("\"", "\\\"")
                + "\",\"rendered\":\"" + content101.replace("\"", "\\\"") + "\"},"
                + "\"excerpt\":{\"rendered\":\"<p>Excerpt &amp; text</p>\"},"
                + "\"_embedded\":{\"wp:featuredmedia\":[{\"source_url\":\"" + base + "/wp-content/uploads/2026/01/cover.png\"}],"
                + "\"wp:term\":[[{\"name\":\"News\",\"taxonomy\":\"category\"}],[{\"name\":\"Java\",\"taxonomy\":\"post_tag\"}]]}}";
        server.createContext("/wp-json/wp/v2/posts/101", ex ->
                respond(ex, 200, "application/json", post101.getBytes(StandardCharsets.UTF_8)));
        // 102：没有 raw（老式 HTML 编辑器）且正文就是 HTML → auto 模式应自动还原成 Markdown
        String html102 = "<h2>二级标题</h2>"
                + "<p>带 <strong>加粗</strong> 与 <em>斜体</em>，还有 <code>inline()</code>。</p>"
                + "<ul><li>甲</li><li>乙</li></ul>"
                + "<pre class=\"language-java\"><code>System.out.println(1);</code></pre>"
                + "<blockquote><p>引用一句</p></blockquote>";
        String post102 = "{\"id\":102,\"title\":{\"rendered\":\"Second Post\"},\"slug\":\"wp-second-102\","
                + "\"status\":\"draft\",\"date_gmt\":\"2026-01-03T11:00:00\","
                + "\"content\":{\"rendered\":\"" + html102.replace("\"", "\\\"") + "\"},\"excerpt\":{\"rendered\":\"\"}}";
        server.createContext("/wp-json/wp/v2/posts/102", ex ->
                respond(ex, 200, "application/json", post102.getBytes(StandardCharsets.UTF_8)));
        // 103：不带 _embedded —— 标签/分类走 id 兜底接口（/tags、/categories）；发布时间可选当前时间
        String post103 = "{\"id\":103,\"title\":{\"rendered\":\"Third Post\"},\"slug\":\"wp-third-103\","
                + "\"status\":\"publish\",\"date_gmt\":\"2025-06-01T08:30:00\","
                + "\"content\":{\"rendered\":\"<p>body three</p>\"},\"excerpt\":{\"rendered\":\"\"},"
                + "\"categories\":[3],\"tags\":[7,8]}";
        server.createContext("/wp-json/wp/v2/posts/103", ex ->
                respond(ex, 200, "application/json", post103.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/wp-json/wp/v2/categories", ex ->
                respond(ex, 200, "application/json", "[{\"id\":3,\"name\":\"随笔\"}]".getBytes(StandardCharsets.UTF_8)));
        server.createContext("/wp-json/wp/v2/tags", ex ->
                respond(ex, 200, "application/json", "[{\"id\":7,\"name\":\"PHP\"},{\"id\":8,\"name\":\"AI\"}]".getBytes(StandardCharsets.UTF_8)));
        // 104：WP 对中文标题生成的 slug 就是 %xx 小写字面串 —— 链接里的 %xx 会被容器解码，需多候选匹配
        String post104 = "{\"id\":104,\"title\":{\"rendered\":\"WP Style Slug\"},"
                + "\"slug\":\"%e4%bd%a0%e5%a5%bd%e4%b8%96%e7%95%8c\","
                + "\"status\":\"publish\",\"date_gmt\":\"2024-05-05T05:05:05\","
                + "\"content\":{\"rendered\":\"<p>wp style body</p>\"},\"excerpt\":{\"rendered\":\"\"}}";
        server.createContext("/wp-json/wp/v2/posts/104", ex ->
                respond(ex, 200, "application/json", post104.getBytes(StandardCharsets.UTF_8)));
        // 105：作者把 Markdown 粘进古腾堡 —— 每行成了 <p>，行首 # 被吃成 <strong>，
        //      **、```、![..](..) 原样留成字面文本（raw 与 rendered 都是这种形状）。
        //      这类正文必须走「还原式」转换，否则入库的是被转义过的伪 Markdown。
        String gutenberg = "<!-- wp:paragraph -->\n"
                + "<p class=\"wp-block-paragraph\"><strong># 标题一</strong></p>\n"
                + "<!-- /wp:paragraph -->\n"
                + "<p class=\"wp-block-paragraph\">正文：<strong>**要点**</strong>。</p>\n"
                + "<p class=\"wp-block-paragraph\">1. <strong>**甲**</strong>：说明</p>\n"
                + "<p class=\"wp-block-paragraph\">2. <strong>**乙**</strong>：说明</p>\n"
                + "<p class=\"wp-block-paragraph\">```python</p>\n"
                + "<p class=\"wp-block-paragraph\"># 注释别当标题</p>\n"
                + "<p class=\"wp-block-paragraph\">&nbsp; &nbsp; x = 1</p>\n"
                + "<p class=\"wp-block-paragraph\">```</p>\n"
                + "<p class=\"wp-block-paragraph\">![外站图](http://cdn.external.com/md.png)</p>\n"
                + "<p class=\"wp-block-paragraph\">![站点图](" + base + "/wp-content/uploads/2026/01/pic.png)</p>\n"
                + "<p class=\"wp-block-paragraph\"><img src=\"" + base + "/wp-content/uploads/2026/01/pic.png\"/></p>\n"
                + "<p class=\"wp-block-paragraph\">| 列A | 列B |</p>\n"
                + "<p class=\"wp-block-paragraph\">|&#8212;&#8212;&#8212;|&#8212;&#8212;&#8212;|</p>\n"
                + "<p class=\"wp-block-paragraph\">| <strong>**甲**</strong> | 1 |</p>";
        String gutenbergJson = gutenberg.replace("\"", "\\\"").replace("\n", "\\n");
        String post105 = "{\"id\":105,\"title\":{\"rendered\":\"Gutenberg Pasted MD\"},\"slug\":\"wp-gutenberg-105\","
                + "\"status\":\"publish\",\"date_gmt\":\"2026-02-02T09:00:00\","
                + "\"content\":{\"raw\":\"" + gutenbergJson + "\",\"rendered\":\"" + gutenbergJson + "\"},"
                + "\"excerpt\":{\"rendered\":\"\"}}";
        server.createContext("/wp-json/wp/v2/posts/105", ex ->
                respond(ex, 200, "application/json", post105.getBytes(StandardCharsets.UTF_8)));
        // 106：老数据场景 —— 本地库里已经躺着一条 slug 是 WP 原样 %xx 字面串的文章（早于 slug 归一的
        //      导入产物）。再导入同一篇时不能重复建文；更新模式下要顺手把 slug 归一成真中文。
        String post106 = "{\"id\":106,\"title\":{\"rendered\":\"Legacy Percent Slug\"},"
                + "\"slug\":\"%e4%bd%a0%e5%a5%bd-legacy\","
                + "\"status\":\"publish\",\"date_gmt\":\"2025-03-03T03:03:03\","
                + "\"content\":{\"rendered\":\"<p>legacy body v2</p>\"},\"excerpt\":{\"rendered\":\"\"}}";
        server.createContext("/wp-json/wp/v2/posts/106", ex ->
                respond(ex, 200, "application/json", post106.getBytes(StandardCharsets.UTF_8)));
        server.createContext("/wp-content/uploads/2026/01/pic.png", ex -> respond(ex, 200, "image/png", PNG_1X1));
        server.createContext("/wp-content/uploads/2026/01/cover.png", ex -> respond(ex, 200, "image/png", PNG_1X1));
        server.createContext("/wp-content/uploads/2026/01/doc.pdf", ex ->
                respond(ex, 200, "application/pdf", "fake-pdf".getBytes(StandardCharsets.UTF_8)));
        server.start();

        String adminToken = loginToken(ADMIN, ADMIN_PWD);
        java.util.List<Long> madeArticles = new java.util.ArrayList<>();
        Long siteId = null;
        try {
            // ① 关联站点：200 + hasToken=true，响应体绝不能带出明文 Token
            MvcResult r = mvc.perform(post("/api/admin/wp/sites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("url", base, "username", "bob", "token", wpToken)))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.site.hasToken").value(true))
                    .andReturn();
            String body = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(body).as("响应: " + body).doesNotContain(wpToken);
            siteId = om.readTree(body).path("site").path("id").asLong();
            assertThat(siteId).isPositive();

            // ② 站点列表同样不回显 Token；重复关联同一网址回 409
            String listBody = mvc.perform(get("/api/admin/wp/sites")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            assertThat(listBody).doesNotContain(wpToken);
            mvc.perform(post("/api/admin/wp/sites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("url", base)))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isConflict());

            // ③ 单篇导入：媒体落盘 / 外链保留 / 标签合并 / 发布时间
            MvcResult imp = mvc.perform(post("/api/admin/wp/sites/" + siteId + "/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("postId", 101)))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andReturn();
            String impBody = imp.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(om.readTree(impBody).path("imported").asBoolean())
                    .as("导入响应: " + impBody).isTrue();
            long articleId = om.readTree(impBody).path("articleId").asLong();
            madeArticles.add(articleId);
            assertThat(om.readTree(impBody).path("warnings").isArray()
                    && om.readTree(impBody).path("warnings").size() == 0)
                    .as("同站媒体都应成功落盘，不应有警告: " + impBody).isTrue();

            MvcResult got = mvc.perform(get("/api/admin/articles/" + articleId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn();
            String artJson = got.getResponse().getContentAsString(StandardCharsets.UTF_8);
            JsonNode art = om.readTree(artJson);
            assertThat(art.path("title").asText()).isEqualTo("Hello ’ World");
            assertThat(art.path("summary").asText()).isEqualTo("Excerpt & text");
            assertThat(art.path("status").asText()).isEqualTo("PUBLISHED");
            assertThat(art.path("publishedAt").asText()).startsWith("2026-01-02T10:00");
            assertThat(art.path("tags").asText()).isEqualTo("News,Java");
            // 发布人 = 关联站点时填写的 WP 用户名（bob），而非固定 "wordpress"
            assertThat(art.path("author").asText())
                    .as("导入文章发布人应为站点用户名").isEqualTo("bob");
            String content = art.path("content").asText();
            assertThat(content).as("正文: " + content).contains("/media/");
            assertThat(content).as("WP 自身图片 URL 不应残留在正文: " + content).doesNotContain("wp-content");
            assertThat(content).as("外站图片应保留外链: " + content).contains("http://cdn.external.com/ext.png");
            // 装了 Markdown 插件的站点：优先用 content.raw（Markdown 原文），而不是渲染后的 HTML
            assertThat(content).as("应优先采用 content.raw 的 Markdown 原文: " + content)
                    .contains("![pic](/media/").doesNotContain("<figure>").doesNotContain("<p>intro</p>");
            // Markdown 版媒体本地化：raw 里的图片与附件链接都要改写成 /media/
            assertThat(content).as("Markdown 图片应本地化: " + content)
                    .containsPattern("!\\[pic\\]\\(/media/[0-9a-f]{16}\\.png\\)");
            assertThat(content).as("Markdown 附件链接应本地化: " + content)
                    .containsPattern("\\[doc\\]\\(/media/[0-9a-f]{16}\\.pdf\\)");
            assertThat(art.path("cover").asText()).startsWith("/media/");
            // 落盘文件按内容去重：正文图与封面是同一份字节 → 共用 1 个文件 + pdf 附件 = 2 个
            Matcher fm = Pattern.compile("/media/([0-9a-f]{16}\\.[a-z]+)").matcher(content + art.path("cover").asText());
            java.util.Set<String> storedNames = new java.util.LinkedHashSet<>();
            while (fm.find()) storedNames.add(fm.group(1));
            assertThat(storedNames).as("正文图与封面同字节共用 1 文件 + pdf 附件（内容去重）").hasSize(2);
            for (String stored : storedNames) {
                assertThat(fileRepo.findByStoredName(stored)).isPresent();
            }

            // ④ 重复导入：同 slug 已存在 → 跳过而非建重复文章
            mvc.perform(post("/api/admin/wp/sites/" + siteId + "/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("postId", 101)))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.imported").value(false));

            // ⑤ 整站导入：启动 202 → 轮询进度到 DONE → 第二篇（草稿状态）也进来
            mvc.perform(post("/api/admin/wp/sites/" + siteId + "/import-all")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isAccepted());
            String progBody = "";
            for (int i = 0; i < 40; i++) {
                Thread.sleep(250);
                progBody = mvc.perform(get("/api/admin/wp/sites/" + siteId + "/progress")
                                .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk()).andReturn().getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
                if (om.readTree(progBody).path("phase").asText().equals("DONE")) break;
            }
            JsonNode prog = om.readTree(progBody);
            assertThat(prog.path("phase").asText()).as("进度: " + progBody).isEqualTo("DONE");
            assertThat(prog.path("imported").asInt()).isEqualTo(1);
            assertThat(prog.path("skipped").asInt()).isEqualTo(1);
            Long secondId = articles.findBySlug("wp-second-102").orElseThrow().getId();
            madeArticles.add(secondId);
            assertThat(articles.findBySlug("wp-second-102").orElseThrow().getStatus()).isEqualTo("DRAFT");
            // 没有 raw 且正文是 HTML（老式编辑器 / 插件渲染结果）→ auto 模式自动还原成 Markdown
            String md102 = articles.findBySlug("wp-second-102").orElseThrow().getContent();
            assertThat(md102).as("HTML 应还原成 Markdown: " + md102)
                    .contains("## 二级标题")
                    .contains("**加粗**").contains("*斜体*").contains("`inline()`")
                    .contains("- 甲").contains("- 乙")
                    .contains("```java").contains("System.out.println(1);")
                    .contains("> 引用一句")
                    .doesNotContain("<h2>").doesNotContain("<ul>").doesNotContain("<blockquote>");

            // ⑥ 单篇导入（useWpDate=false + 无 _embedded 的文章）：
            //    发布时间用当前时间而非 WP 的 2025-06-01；标签按 id 走 /categories、/tags 兜底接口换名称
            MvcResult imp3 = mvc.perform(post("/api/admin/wp/sites/" + siteId + "/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("postId", 103, "useWpDate", false)))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.imported").value(true))
                    .andReturn();
            long articleId3 = om.readTree(imp3.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("articleId").asLong();
            madeArticles.add(articleId3);
            JsonNode art3 = om.readTree(mvc.perform(get("/api/admin/articles/" + articleId3)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8));
            assertThat(art3.path("status").asText()).isEqualTo("PUBLISHED");
            assertThat(art3.path("tags").asText())
                    .as("分类+标签兜底换名（分类在前，与 _embedded 顺序一致）: " + art3.path("tags").asText())
                    .isEqualTo("随笔,PHP,AI");
            String pub3 = art3.path("publishedAt").asText();
            assertThat(pub3).as("useWpDate=false 应为当前时间，而非 WP 的 2025-06-01").startsWith(java.time.LocalDate.now().toString());
            assertThat(pub3).doesNotStartWith("2025-06-01");

            // ⑦ 同 slug 冲突可选「更新」：不新建文章（id 不变），重复媒体因内容去重不新增文件
            long filesBefore = fileRepo.count();
            MvcResult impUp = mvc.perform(post("/api/admin/wp/sites/" + siteId + "/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("postId", 101, "onConflict", "update")))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.imported").value(true))
                    .andExpect(jsonPath("$.updated").value(true))
                    .andReturn();
            long upId = om.readTree(impUp.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("articleId").asLong();
            assertThat(upId).as("更新模式应复用原文章 id，不新建").isEqualTo(articleId);
            assertThat(fileRepo.count()).as("完全相同的媒体落盘时应去重复用，不新增文件记录").isEqualTo(filesBefore);

            // ⑧ WP 风格 %xx 中文 slug 导入时应解码成真中文入库（否则链接里的 % 会被安全防火墙拦成 400），
            //    详情页以中文 slug 正常打开
            String cnSlug = "你好世界";
            MvcResult impWp = mvc.perform(post("/api/admin/wp/sites/" + siteId + "/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("postId", 104)))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.imported").value(true))
                    .andReturn();
            madeArticles.add(om.readTree(impWp.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("articleId").asLong());
            assertThat(articles.findBySlugIgnoreCase(cnSlug))
                    .as("%xx 字面 slug 应已解码为真中文入库").isPresent();
            mvc.perform(get("/article/你好世界"))
                    .andExpect(status().isOk());

            // ⑨ 「Markdown 粘进古腾堡」的正文：HTML 只是外壳，里面全是字面 Markdown。
            //    必须走还原式转换 —— 标题/粗体/围栏/字面图片/表格都要还原成真 Markdown。
            MvcResult impG = mvc.perform(post("/api/admin/wp/sites/" + siteId + "/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("postId", 105)))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.imported").value(true))
                    .andReturn();
            madeArticles.add(om.readTree(impG.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("articleId").asLong());
            String mdG = articles.findBySlug("wp-gutenberg-105").orElseThrow().getContent();
            assertThat(mdG).as("还原式转换后的正文: " + mdG)
                    .contains("# 标题一")
                    .contains("正文：**要点**。")
                    .contains("1. **甲**：说明\n2. **乙**：说明")
                    .contains("```python\n# 注释别当标题\n    x = 1\n```")
                    .contains("![外站图](http://cdn.external.com/md.png)")
                    .contains("| 列A | 列B |\n|---|---|\n| **甲** | 1 |")
                    .doesNotContain("**# 标题一**")
                    .doesNotContain("wp-block-paragraph")
                    .doesNotContain("&nbsp;");
            // 还原式带出来的字面 Markdown 图片同样要本地化（与正文 <img> 同字节 → 共用一份）
            assertThat(mdG).as("字面 Markdown 图片应本地化: " + mdG)
                    .containsPattern("!\\[站点图\\]\\(/media/[0-9a-f]{16}\\.png\\)");

            // ⑩ 老数据：库里已有一条 slug 为 WP 原样 %xx 字面串的文章（早于 slug 归一的导入产物）。
            //    —— skip 模式必须判定为「已存在」，绝不能又建一篇重复文章；
            //    —— update 模式复用同一条并把 slug 归一成真中文；
            //    —— 归一后新旧两种链接都要能打开（旧 %xx 链接靠前台多候选解码命中）。
            cn.xiezitai.entity.Article legacy = new cn.xiezitai.entity.Article();
            legacy.setTitle("Legacy Percent Slug");
            legacy.setSlug("%e4%bd%a0%e5%a5%bd-legacy");
            legacy.setContent("legacy body v1");
            legacy.setSummary("legacy");
            legacy.setAuthor("wordpress");
            legacy.setStatus("PUBLISHED");
            legacy.setPublishedAt(java.time.LocalDateTime.now().minusDays(30));
            cn.xiezitai.entity.Article savedLegacy = articles.save(legacy);
            madeArticles.add(savedLegacy.getId());
            long countBeforeLegacy = articles.count();

            mvc.perform(post("/api/admin/wp/sites/" + siteId + "/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("postId", 106)))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.imported").value(false))
                    .andExpect(jsonPath("$.reason").value("exists"));
            assertThat(articles.count()).as("%xx 老文章应被识别为已存在，不得新增重复文章")
                    .isEqualTo(countBeforeLegacy);

            MvcResult impLegacy = mvc.perform(post("/api/admin/wp/sites/" + siteId + "/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("postId", 106, "onConflict", "update")))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updated").value(true))
                    .andExpect(jsonPath("$.slug").value("你好-legacy"))
                    .andReturn();
            assertThat(om.readTree(impLegacy.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("articleId").asLong()).as("更新模式应复用老文章 id").isEqualTo(savedLegacy.getId());
            assertThat(articles.findBySlugIgnoreCase("你好-legacy")).as("slug 应已归一为真中文").isPresent();
            assertThat(articles.findBySlugIgnoreCase("%e4%bd%a0%e5%a5%bd-legacy"))
                    .as("旧的 %xx slug 不应残留").isEmpty();
            assertThat(articles.count()).as("更新模式不新增文章").isEqualTo(countBeforeLegacy);

            mvc.perform(get("/article/你好-legacy")).andExpect(status().isOk());
            // 旧的 %xx 链接依然可用：容器先解码一次、前台路由的多候选再兜一层解码。
            // 这里只断言候选逻辑 —— MockMvc 会把 URL 里的 % 再编码成 %25，与真实容器的行为不同，
            // 直接 perform 一个 %xx 路径会被防火墙挡成 400，测不出真实链路。
            assertThat(cn.xiezitai.controller.PageViewController
                    .slugCandidates("%e4%bd%a0%e5%a5%bd-legacy"))
                    .as("旧 %xx 链接的解码候选应命中归一后的中文 slug")
                    .contains("你好-legacy");
        } finally {
            server.stop(0);
            for (Long id : madeArticles) articles.deleteById(id);
            if (siteId != null) {
                mvc.perform(delete("/api/admin/wp/sites/" + siteId)
                                .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk());
            }
        }
    }

    /* ==================== HTML → Markdown 还原（Markdown 插件场景） ==================== */

    @Test
    @DisplayName("HTML→Markdown：插件渲染出的 HTML 能还原成 Markdown 文本")
    void htmlToMarkdownRestoresRenderedHtml() {
        // 判定：有块级标签才算 HTML 正文；纯 Markdown（哪怕混着裸 img/a）不算
        assertThat(cn.xiezitai.service.HtmlToMarkdown.looksLikeHtml("<p>hi</p>")).isTrue();
        assertThat(cn.xiezitai.service.HtmlToMarkdown.looksLikeHtml("## 标题\n\n![](http://a/b.png)")).isFalse();

        // 标题 / 段落 / 行内样式 / 行内代码（代码里的实体要还原，且不被再次转义）
        assertThat(cn.xiezitai.service.HtmlToMarkdown.convert(
                "<h1>大标题</h1><p>带 <strong>粗</strong>、<em>斜</em>、<del>删</del> 与 <code>a&lt;b</code> 的段落</p>"))
                .isEqualTo("# 大标题\n\n带 **粗**、*斜*、~~删~~ 与 `a<b` 的段落");

        // 列表（含嵌套）与有序列表
        assertThat(cn.xiezitai.service.HtmlToMarkdown.convert(
                "<ul><li>甲</li><li>乙<ul><li>乙一</li></ul></li></ul>"))
                .isEqualTo("- 甲\n- 乙\n  - 乙一");
        assertThat(cn.xiezitai.service.HtmlToMarkdown.convert("<ol><li>第一</li><li>第二</li></ol>"))
                .isEqualTo("1. 第一\n2. 第二");

        // 引用
        assertThat(cn.xiezitai.service.HtmlToMarkdown.convert("<blockquote><p>引用一句</p></blockquote>"))
                .isEqualTo("> 引用一句");

        // 代码块（带语言）——缩进与特殊字符必须原样保留
        assertThat(cn.xiezitai.service.HtmlToMarkdown.convert(
                "<pre class=\"language-java\"><code>if (a &lt; b) {\n  go();\n}</code></pre>"))
                .isEqualTo("```java\nif (a < b) {\n  go();\n}\n```");

        // 链接 / 图片 / 懒加载图片（data-src 优先）
        assertThat(cn.xiezitai.service.HtmlToMarkdown.convert(
                "<p><a href=\"https://a.com/x\">站点</a> <img src=\"https://a.com/i.png\" alt=\"图\"/></p>"))
                .isEqualTo("[站点](https://a.com/x) ![图](https://a.com/i.png)");
        assertThat(cn.xiezitai.service.HtmlToMarkdown.convert(
                "<p><img src=\"data:image/gif;base64,xx\" data-src=\"https://a.com/lazy.png\" alt=\"懒\"/></p>"))
                .isEqualTo("![懒](https://a.com/lazy.png)");

        // 表格 → GFM
        assertThat(cn.xiezitai.service.HtmlToMarkdown.convert(
                "<table><thead><tr><th>名</th><th>值</th></tr></thead>"
                        + "<tbody><tr><td>a</td><td>1</td></tr></tbody></table>"))
                .isEqualTo("| 名 | 值 |\n| --- | --- |\n| a | 1 |");

        // Gutenberg 块注释 + figure 图片说明
        assertThat(cn.xiezitai.service.HtmlToMarkdown.convert(
                "<!-- wp:paragraph --><p>正文</p><!-- /wp:paragraph -->"))
                .isEqualTo("正文");
        assertThat(cn.xiezitai.service.HtmlToMarkdown.convert(
                "<figure><img src=\"https://a.com/p.png\" alt=\"x\"/><figcaption>说明文字</figcaption></figure>"))
                .isEqualTo("![x](https://a.com/p.png)\n\n*说明文字*");

        // 正文里的 Markdown 元字符要转义，避免还原后被当成语法
        assertThat(cn.xiezitai.service.HtmlToMarkdown.convert("<p>2*3 与 [方括号]</p>"))
                .isEqualTo("2\\*3 与 \\[方括号\\]");

        // 富媒体原样保留（Markdown 允许内联 HTML）
        assertThat(cn.xiezitai.service.HtmlToMarkdown.convert(
                "<p>视频</p><iframe src=\"https://v.qq.com/x\" allowfullscreen></iframe>"))
                .contains("<iframe src=\"https://v.qq.com/x\"");

        // 空输入不炸
        assertThat(cn.xiezitai.service.HtmlToMarkdown.convert(null)).isEmpty();
        assertThat(cn.xiezitai.service.HtmlToMarkdown.convert("   ")).isEmpty();
    }

    @Test
    @DisplayName("HTML→Markdown：Markdown 被逐行包进 <p> 时按还原式转换")
    void htmlToMarkdownSalvagesMarkdownWrappedInHtml() {
        // 作者把 Markdown 粘进古腾堡后的真实形状：每行一个 <p>，行首 # 被吃成 <strong>，
        // **、```、![..](..)、|表格| 原样留成字面文本。
        String html = "<!-- wp:paragraph --><p class=\"wp-block-paragraph\"><strong># 标题一</strong></p><!-- /wp:paragraph -->"
                + "<p class=\"wp-block-paragraph\"><strong>## 1. 小节</strong></p>"
                + "<p class=\"wp-block-paragraph\">正文：<strong>**要点**</strong>。</p>"
                + "<p class=\"wp-block-paragraph\">1. <strong>**甲**</strong>：说明</p>"
                + "<p class=\"wp-block-paragraph\">2. <strong>**乙**</strong>：说明</p>"
                + "<p class=\"wp-block-paragraph\">```python</p>"
                + "<p class=\"wp-block-paragraph\"># 注释别当标题</p>"
                + "<p class=\"wp-block-paragraph\">&nbsp; &nbsp; x = 1</p>"
                + "<p class=\"wp-block-paragraph\">```</p>"
                + "<p class=\"wp-block-paragraph\">![外站图](http://cdn.external.com/md.png)</p>"
                + "<p class=\"wp-block-paragraph\">| 列A | 列B |</p>"
                + "<p class=\"wp-block-paragraph\">|&#8212;&#8212;&#8212;|&#8212;&#8212;&#8212;|</p>"
                + "<p class=\"wp-block-paragraph\">| <strong>**甲**</strong> | 1 |</p>"
                + "<p class=\"wp-block-paragraph\"><strong>**原始查询**</strong>：</p>"
                + "<p class=\"wp-block-paragraph\">```</p>"
                + "<p class=\"wp-block-paragraph\">1. 围栏里的一行</p>"
                + "<p class=\"wp-block-paragraph\">```</p>";

        // 识别：强特征命中 → 走还原式；普通 HTML 正文不能被误判
        assertThat(cn.xiezitai.service.HtmlToMarkdown.looksLikeMarkdownInHtml(html)).isTrue();
        assertThat(cn.xiezitai.service.HtmlToMarkdown.looksLikeMarkdownInHtml(
                "<p>普通 <strong>加粗</strong> 正文，还有 <code>x</code></p>")).isFalse();

        String md = cn.xiezitai.service.HtmlToMarkdown.convertMarkdownWrapped(html);

        // 被吃成加粗的标题还原成 ATX 标题（不能留 **# 标题**）
        assertThat(md).as("正文: " + md).contains("# 标题一").contains("## 1. 小节")
                .doesNotContain("**# 标题一**").doesNotContain("<strong>");
        // 外层 strong + 内层字面 ** → 去掉重复加粗，保留一层
        assertThat(md).contains("正文：**要点**。").contains("**原始查询**：");
        // 行内 strong 在行首时不能变成标题
        assertThat(md).contains("**原始查询**：");
        // 列表项之间不留空行（否则是 loose list）
        assertThat(md).contains("1. **甲**：说明\n2. **乙**：说明");
        // 围栏 → 真代码块；块内 # 注释与缩进原样保留
        assertThat(md).contains("```python\n# 注释别当标题\n    x = 1\n```");
        // 字面 Markdown 图片原样保留；围栏内的 1. 不会被当成列表
        assertThat(md).contains("![外站图](http://cdn.external.com/md.png)");
        assertThat(md).contains("```\n1. 围栏里的一行\n```");
        // 表格：分隔行的 「———」 要还原成 ---，且行与行之间不能有空行（否则 GFM 表格失效）
        assertThat(md).contains("| 列A | 列B |\n|---|---|\n| **甲** | 1 |");
        // 还原式不转义 Markdown 元字符（这些本来就是语法）
        assertThat(cn.xiezitai.service.HtmlToMarkdown.convertMarkdownWrapped("<p>2*3 与 [方括号]</p>"))
                .isEqualTo("2*3 与 [方括号]");
        // 普通式仍然照旧转义
        assertThat(cn.xiezitai.service.HtmlToMarkdown.convert("<p>2*3 与 [方括号]</p>"))
                .isEqualTo("2\\*3 与 \\[方括号\\]");
        // 空输入不炸
        assertThat(cn.xiezitai.service.HtmlToMarkdown.convertMarkdownWrapped(null)).isEmpty();
    }

    /* ==================== 博客园关联与导入（MetaWeblog） ==================== */

    @Autowired cn.xiezitai.service.CnBlogImportService cnImports;

    @Test
    @DisplayName("博客园：主机判定与稳定 slug")
    void cnHelpers() {
        assertThat(cnImports.isCnblogsHost("https://img2024.cnblogs.com/blog/a.png")).isTrue();
        assertThat(cnImports.isCnblogsHost("https://i.cnblogs.com/Files/a.png")).isTrue();
        assertThat(cnImports.isCnblogsHost("https://www.cnblogs.com/itbuddy/p/1.html")).isTrue();
        assertThat(cnImports.isCnblogsHost("https://cdn.other.com/a.png")).isFalse();
        assertThat(cnImports.isCnblogsHost("https://cnblogs.com.evil.io/a.png")).as("后缀伪装不算").isFalse();
        assertThat(cnImports.isCnblogsHost("/relative/a.png")).isFalse();
        // 稳定 slug：ASCII 标题走 slugify；纯中文标题走 cnblog-{postid}（时间戳会漂移，不能用于冲突检测）
        assertThat(cn.xiezitai.service.CnBlogImportService.baseSlugOf("Hello World", 201)).isEqualTo("hello-world");
        assertThat(cn.xiezitai.service.CnBlogImportService.baseSlugOf("你好世界", 202)).isEqualTo("cnblog-202");
        assertThat(cn.xiezitai.service.CnBlogImportService.baseSlugOf("你好世界", 203)).as("不同文章 slug 必须不同").isEqualTo("cnblog-203");
    }

    private static String xmlStr(String s) {
        return "<value><string>" + s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                + "</string></value>";
    }

    private static String cnPostXml(long id, String title, String desc, String excerpt,
                                    String categoriesXml, String date, String link) {
        return "<value><struct>"
                + "<member><name>postid</name><value><int>" + id + "</int></value></member>"
                + "<member><name>title</name>" + xmlStr(title) + "</member>"
                + "<member><name>description</name>" + xmlStr(desc) + "</member>"
                + "<member><name>mt_text_more</name>" + xmlStr("") + "</member>"
                + "<member><name>mt_excerpt</name>" + xmlStr(excerpt) + "</member>"
                + "<member><name>categories</name><value><array><data>" + categoriesXml + "</data></array></value></member>"
                + "<member><name>dateCreated</name><value><dateTime.iso8601>" + date + "</dateTime.iso8601></value></member>"
                + "<member><name>link</name>" + xmlStr(link) + "</member>"
                + "</struct></value>";
    }

    private static String xmlRpcResp(String inner) {
        return "<?xml version=\"1.0\"?><methodResponse><params><param>" + inner + "</param></params></methodResponse>";
    }

    private static String xmlRpcFault(String msg) {
        return "<?xml version=\"1.0\"?><methodResponse><fault><value><struct>"
                + "<member><name>faultCode</name><value><int>1</int></value></member>"
                + "<member><name>faultString</name>" + xmlStr(msg) + "</member>"
                + "</struct></value></fault></methodResponse>";
    }

    /**
     * 起一个假博客园 MetaWeblog 服务器（XML-RPC + 媒体文件），走完整链路：
     * 关联账号（密钥不回显、密钥错误报 502）→ 浏览文章 → 单篇导入（cnblogs 域图片落盘、外站图保留外链）
     * → 重复导入跳过 → 更新模式复用 id 且媒体去重 → 整站导入（后台线程 + 进度轮询到 DONE）。
     */
    @Test
    @DisplayName("博客园：关联/密钥脱敏/单篇导入媒体落盘/整站导入进度")
    void cnAssociateImportAndMediaLocalize() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;
        final String cnKey = "mock-cn-key-0123456789abcdef";

        final String post201Xml = cnPostXml(201, "CN E2E Post 01",
                "<p>intro</p><img src=\"" + base + "/blog/pic.png\"/>"
                        + "<p><img src=\"http://cdn.external.com/ext.png\"/></p>",
                "CN Excerpt", "<value><string>CnTag</string></value>",
                "20250601T08:30:00", base + "/p/201");
        final String post202Xml = cnPostXml(202, "中文博客文章",
                "<p>中文正文</p>", "", "", "20240505T05:05:05", base + "/p/202");

        server.createContext("/", ex -> {
            String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            java.util.regex.Matcher mm = Pattern.compile("<methodName>([\\w.]+)</methodName>").matcher(req);
            String method = mm.find() ? mm.group(1) : "";
            String resp;
            if (!req.contains(cnKey)) {
                resp = xmlRpcFault("密钥错误");
            } else if ("blogger.getUsersBlogs".equals(method)) {
                resp = xmlRpcResp("<value><array><data>"
                        + "<value><struct>"
                        + "<member><name>blogid</name><value><string>879366</string></value></member>"
                        + "<member><name>url</name>" + xmlStr(base + "/") + "</member>"
                        + "<member><name>blogName</name>" + xmlStr("MockCN") + "</member>"
                        + "</struct></value>"
                        + "</data></array></value>");
            } else if ("metaWeblog.getRecentPosts".equals(method)) {
                resp = xmlRpcResp("<value><array><data>" + post201Xml + post202Xml + "</data></array></value>");
            } else if ("metaWeblog.getPost".equals(method)) {
                resp = xmlRpcResp(req.contains(">201<") ? post201Xml : post202Xml);
            } else {
                resp = xmlRpcFault("unknown method: " + method);
            }
            respond(ex, 200, "text/xml", resp.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/blog/pic.png", ex -> respond(ex, 200, "image/png", PNG_1X1));
        server.start();

        Long siteId = null;
        java.util.List<Long> madeArticles = new java.util.ArrayList<>();
        String adminToken = loginToken(ADMIN, ADMIN_PWD);
        try {
            // ① 关联账号：200 + hasToken=true，响应体绝不能带出明文密钥
            MvcResult r = mvc.perform(post("/api/admin/cnblogs/sites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("url", base + "/metaweblog/cnbob", "username", "cnbob", "token", cnKey)))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.site.hasToken").value(true))
                    .andReturn();
            String createBody = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(createBody).as("响应不能带出密钥明文").doesNotContain(cnKey);
            siteId = om.readTree(createBody).path("site").path("id").asLong();
            assertThat(siteId).isPositive();

            // ② 站点列表同样不回显密钥；重复关联同一地址回 409
            String listBody = mvc.perform(get("/api/admin/cnblogs/sites")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn().getResponse()
                    .getContentAsString(StandardCharsets.UTF_8);
            assertThat(listBody).doesNotContain(cnKey);
            mvc.perform(post("/api/admin/cnblogs/sites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("url", base + "/metaweblog/cnbob")))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isConflict());

            // ③ 密钥错误：测试连接 502
            MvcResult badSite = mvc.perform(post("/api/admin/cnblogs/sites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("url", base + "/metaweblog/bad", "username", "bad", "token", "wrong-key")))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn();
            long badId = om.readTree(badSite.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("site").path("id").asLong();
            mvc.perform(post("/api/admin/cnblogs/sites/" + badId + "/test")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isBadGateway());
            mvc.perform(delete("/api/admin/cnblogs/sites/" + badId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk());

            // ④ 浏览：共 2 篇
            MvcResult browse = mvc.perform(get("/api/admin/cnblogs/sites/" + siteId + "/posts")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn();
            JsonNode postsNode = om.readTree(browse.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(postsNode.path("total").asInt()).isEqualTo(2);
            assertThat(postsNode.path("posts").get(0).path("title").asText()).isEqualTo("CN E2E Post 01");

            // ⑤ 单篇导入：媒体落盘 / 外链保留 / 标签 / 发布人 / 原发布时间
            MvcResult imp = mvc.perform(post("/api/admin/cnblogs/sites/" + siteId + "/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("postId", 201)))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andReturn();
            String impBody = imp.getResponse().getContentAsString(StandardCharsets.UTF_8);
            JsonNode impNode = om.readTree(impBody);
            assertThat(impNode.path("imported").asBoolean()).as("导入响应: " + impBody).isTrue();
            long articleId = impNode.path("articleId").asLong();
            madeArticles.add(articleId);
            assertThat(impNode.path("warnings").isArray() && impNode.path("warnings").size() == 0)
                    .as("cnblogs 域图片应成功落盘，不应有警告: " + impBody).isTrue();
            assertThat(impNode.path("tags").asText()).isEqualTo("CnTag");

            MvcResult got = mvc.perform(get("/api/admin/articles/" + articleId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn();
            JsonNode art = om.readTree(got.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(art.path("title").asText()).isEqualTo("CN E2E Post 01");
            assertThat(art.path("author").asText()).as("发布人应为博客园用户名").isEqualTo("cnbob");
            assertThat(art.path("publishedAt").asText()).startsWith("2025-06-01T08:30");
            assertThat(art.path("summary").asText()).isEqualTo("CN Excerpt");
            String content = art.path("content").asText();
            assertThat(content).as("正文: " + content).contains("/media/");
            assertThat(content).as("博客园图床 URL 不应残留在正文: " + content).doesNotContain("/blog/pic.png");
            assertThat(content).as("外站图片应保留外链: " + content).contains("http://cdn.external.com/ext.png");

            // ⑥ 重复导入：同 slug 已存在 → 跳过；更新模式复用 id 且媒体去重（同字节图片不新增文件）
            mvc.perform(post("/api/admin/cnblogs/sites/" + siteId + "/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("postId", 201)))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.imported").value(false));
            long filesBefore = fileRepo.count();
            MvcResult impUp = mvc.perform(post("/api/admin/cnblogs/sites/" + siteId + "/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("postId", 201, "onConflict", "update")))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.updated").value(true))
                    .andReturn();
            assertThat(om.readTree(impUp.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("articleId").asLong()).as("更新模式应复用原文章 id").isEqualTo(articleId);
            assertThat(fileRepo.count()).as("完全相同的媒体落盘时应去重复用").isEqualTo(filesBefore);

            // ⑦ 纯中文标题：slug 稳定为 cnblog-202；useWpDate=false 时发布时间用当前时间
            MvcResult imp2 = mvc.perform(post("/api/admin/cnblogs/sites/" + siteId + "/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("postId", 202, "useWpDate", false)))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.imported").value(true))
                    .andReturn();
            long secondId = om.readTree(imp2.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("articleId").asLong();
            madeArticles.add(secondId);
            cn.xiezitai.entity.Article second = articles.findById(secondId).orElseThrow();
            assertThat(second.getSlug()).as("纯中文标题 slug 应稳定为 cnblog-{postid}").isEqualTo("cnblog-202");
            assertThat(second.getPublishedAt()).as("useWpDate=false 应为当前时间").isNotNull();
            assertThat(second.getPublishedAt().toLocalDate())
                    .isEqualTo(java.time.LocalDate.now());
            mvc.perform(get("/article/" + second.getSlug()))
                    .andExpect(status().isOk());

            // ⑧ 整站导入：启动 202 → 轮询进度到 DONE → 两篇都已导入过 → 全部跳过
            mvc.perform(post("/api/admin/cnblogs/sites/" + siteId + "/import-all")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isAccepted());
            String progBody = "";
            for (int i = 0; i < 40; i++) {
                Thread.sleep(250);
                progBody = mvc.perform(get("/api/admin/cnblogs/sites/" + siteId + "/progress")
                                .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk()).andReturn().getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
                if (progBody.contains("\"phase\":\"DONE\"") || progBody.contains("\"phase\":\"FAILED\"")) break;
            }
            JsonNode prog = om.readTree(progBody);
            assertThat(prog.path("phase").asText()).as("进度: " + progBody).isEqualTo("DONE");
            assertThat(prog.path("total").asLong()).isEqualTo(2);
            assertThat(prog.path("skipped").asInt()).isEqualTo(2);
            assertThat(prog.path("failed").asInt()).isEqualTo(0);
        } finally {
            server.stop(0);
            for (Long id : madeArticles) articles.deleteById(id);
            if (siteId != null) {
                mvc.perform(delete("/api/admin/cnblogs/sites/" + siteId)
                                .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk());
            }
        }
    }

    /* ==================== 文章分发（→ WordPress 站点 / 博客园账号） ==================== */

    @Autowired cn.xiezitai.service.DistributeService distService;

    @Test
    @DisplayName("分发：正文绝对化与转载尾注")
    void distBuildBody() {
        cn.xiezitai.entity.Article a = new cn.xiezitai.entity.Article();
        a.setTitle("标题 *含* 特殊字符");
        a.setSlug("dist-body-测试");
        a.setContent("正文\n\n![图](/media/abc.png)\n\n<img src=\"/media/d.mp4\">\n\n[外链](https://other.com/x.png)");

        String original = distService.buildBody(a, "original", "markdown");
        assertThat(original).contains(distService.siteUrl() + "/media/abc.png");
        assertThat(original).contains("src=\"" + distService.siteUrl() + "/media/d.mp4\"");
        assertThat(original).as("外链不该被改写").contains("(https://other.com/x.png)");
        assertThat(original).as("原文分发不带转载尾注").doesNotContain("本文由");

        String repost = distService.buildBody(a, "repost", "markdown");
        assertThat(repost).contains("本文由 [写字台](" + distService.siteUrl() + ") 首发");
        assertThat(repost).contains("原文链接：[标题 *含* 特殊字符]("
                + distService.articleUrl("dist-body-测试") + ")");
        assertThat(distService.articleUrl("dist-body-测试")).as("中文 slug 要百分号编码")
                .isEqualTo(distService.siteUrl() + "/article/dist-body-%E6%B5%8B%E8%AF%95");

        String html = distService.buildBody(a, "repost", "html");
        assertThat(html).as("转 HTML 后不该有 Markdown 语法残留").contains("<p>正文</p>")
                .contains("<img src=\"" + distService.siteUrl() + "/media/abc.png\"");
        assertThat(html).contains("<blockquote><p>本文由 <a href=\"" + distService.siteUrl() + "\">写字台</a> 首发");
        // 站点地址为空（没配 site-url）时不该往正文里塞半截地址
        assertThat(cn.xiezitai.service.DistributeService.absolutizeMedia("![a](/media/x.png)", ""))
                .isEqualTo("![a](/media/x.png)");
    }

    /**
     * 假 WP 站点（支持建/改文章与建标签）+ 假博客园 MetaWeblog（支持 newPost/editPost），
     * 走完整分发链路：目标清单 → 首次分发（原文/转载）→ 记录出现「已分发」→
     * 再分发时选「更新」复用远端 id / 选「发新文章」换 id → 列表徽标。
     */
    @Test
    @DisplayName("分发：多目标分发、已分发记录、更新与新文章、转载尾注")
    void distributeToWpAndCnBlog() throws Exception {
        java.util.List<String> wpCalls = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        java.util.List<String> cnCalls = java.util.Collections.synchronizedList(new java.util.ArrayList<>());

        // ---------- 假 WP ----------
        HttpServer wpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String wpBase = "http://127.0.0.1:" + wpServer.getAddress().getPort();
        final String wpToken = "abcd efgh ijkl mnop";
        final String wpAuth = "Basic " + Base64.getEncoder()
                .encodeToString(("bob:" + wpToken).getBytes(StandardCharsets.UTF_8));
        final java.util.concurrent.atomic.AtomicLong nextId = new java.util.concurrent.atomic.AtomicLong(900);
        wpServer.createContext("/wp-json/", ex -> {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            if (!wpAuth.equals(auth)) {
                respond(ex, 401, "application/json", "{\"code\":\"rest_forbidden\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/wp-json/")) {
                respond(ex, 200, "application/json",
                        "{\"name\":\"Mock WP\",\"description\":\"d\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/wp-json/wp/v2/users/me")) {
                respond(ex, 200, "application/json", "{\"name\":\"Bob\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/wp-json/wp/v2/tags") && "POST".equals(method)) {
                wpCalls.add("TAG " + body);
                respond(ex, 201, "application/json", "{\"id\":77,\"name\":\"t\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.equals("/wp-json/wp/v2/posts") && "POST".equals(method)) {
                long id = nextId.incrementAndGet();
                wpCalls.add("CREATE " + body);
                respond(ex, 201, "application/json",
                        ("{\"id\":" + id + ",\"link\":\"" + wpBase + "/?p=" + id + "\"}")
                                .getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.startsWith("/wp-json/wp/v2/posts/") && "POST".equals(method)) {
                String id = path.substring("/wp-json/wp/v2/posts/".length());
                wpCalls.add("UPDATE " + id + " " + body);
                respond(ex, 200, "application/json",
                        ("{\"id\":" + id + ",\"link\":\"" + wpBase + "/?p=" + id + "\"}")
                                .getBytes(StandardCharsets.UTF_8));
                return;
            }
            respond(ex, 404, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        });
        wpServer.start();

        // ---------- 假博客园 ----------
        HttpServer cnServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String cnBase = "http://127.0.0.1:" + cnServer.getAddress().getPort();
        final String cnKey = "mock-cn-key-0123456789abcdef";
        cnServer.createContext("/", ex -> {
            String req = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            java.util.regex.Matcher mm = Pattern.compile("<methodName>([\\w.]+)</methodName>").matcher(req);
            String method = mm.find() ? mm.group(1) : "";
            String resp;
            if (!req.contains(cnKey)) {
                resp = xmlRpcFault("密钥错误");
            } else if ("blogger.getUsersBlogs".equals(method)) {
                resp = xmlRpcResp("<value><array><data><value><struct>"
                        + "<member><name>blogid</name><value><string>879366</string></value></member>"
                        + "<member><name>url</name>" + xmlStr(cnBase + "/") + "</member>"
                        + "<member><name>blogName</name>" + xmlStr("MockCN") + "</member>"
                        + "</struct></value></data></array></value>");
            } else if ("metaWeblog.newPost".equals(method)) {
                cnCalls.add("NEW " + req);
                resp = xmlRpcResp("<value><string>555</string></value>");
            } else if ("metaWeblog.editPost".equals(method)) {
                cnCalls.add("EDIT " + req);
                resp = xmlRpcResp("<value><boolean>1</boolean></value>");
            } else if ("metaWeblog.getPost".equals(method)) {
                resp = xmlRpcResp(cnPostXml(555, "CN Post", "<p>x</p>", "", "",
                        "20260101T00:00:00", cnBase + "/p/555"));
            } else {
                resp = xmlRpcFault("unknown method: " + method);
            }
            respond(ex, 200, "text/xml", resp.getBytes(StandardCharsets.UTF_8));
        });
        cnServer.start();

        String adminToken = loginToken(ADMIN, ADMIN_PWD);
        Long wpSiteId = null, cnSiteId = null, articleId = null;
        try {
            // ① 关联两个目标（都要带凭据，否则不可分发）
            MvcResult wpCreate = mvc.perform(post("/api/admin/wp/sites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("url", wpBase, "username", "bob", "token", wpToken,
                                    "name", "我的WP站")))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn();
            wpSiteId = om.readTree(wpCreate.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("site").path("id").asLong();
            MvcResult cnCreate = mvc.perform(post("/api/admin/cnblogs/sites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("url", cnBase + "/metaweblog/bob", "username", "bob",
                                    "token", cnKey, "name", "我的博客园")))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn();
            cnSiteId = om.readTree(cnCreate.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("site").path("id").asLong();

            // ② 一篇文章（正文带站内图，标签用于验证 WP 标签同步）
            MvcResult art = mvc.perform(post("/api/admin/articles")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("title", "分发用例文章", "slug", "dist-e2e-article",
                                    "content", "正文\n\n![图](/media/e2e-dist.png)",
                                    "summary", "摘要", "status", "PUBLISHED", "tags", "Java,AI")))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn();
            articleId = om.readTree(art.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("id").asLong();

            // ③ 目标清单：两个目标都在、都可用、都还没分发过
            MvcResult tg = mvc.perform(get("/api/admin/dist/targets?articleId=" + articleId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn();
            JsonNode targets = om.readTree(tg.getResponse().getContentAsString(StandardCharsets.UTF_8));
            String siteUrl = targets.path("siteUrl").asText();
            assertThat(targets.path("targets")).hasSize(2);
            assertThat(targets.path("targets").get(0).path("channel").asText()).isEqualTo("wp");
            assertThat(targets.path("targets").get(1).path("channel").asText()).isEqualTo("cnblog");
            for (JsonNode t : targets.path("targets")) {
                assertThat(t.path("ready").asBoolean()).as("两个目标都应具备发文凭据").isTrue();
                assertThat(t.path("dist").isNull()).as("首次分发前不该有分发记录").isTrue();
            }
            assertThat(targets.path("sourceUrl").asText()).isEqualTo(siteUrl + "/article/dist-e2e-article");

            // ④ 一次发往两个目标，转载 + Markdown
            MvcResult run = mvc.perform(post("/api/admin/dist/run")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("articleId", articleId, "mode", "repost", "format", "markdown",
                                    "targets", java.util.List.of(
                                            Map.of("channel", "wp", "targetId", wpSiteId, "action", "create"),
                                            Map.of("channel", "cnblog", "targetId", cnSiteId, "action", "create")))))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn();
            JsonNode runData = om.readTree(run.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(runData.path("ok").asInt()).as("分发结果: " + runData).isEqualTo(2);
            assertThat(runData.path("failed").asInt()).isEqualTo(0);
            long wpRemoteId = runData.path("results").get(0).path("remotePostId").asLong();
            assertThat(wpRemoteId).isPositive();
            assertThat(runData.path("results").get(1).path("remotePostId").asLong()).isEqualTo(555L);
            assertThat(runData.path("results").get(1).path("remoteUrl").asText()).isEqualTo(cnBase + "/p/555");

            // WP 侧：正文媒体已绝对化、转载尾注在、标签换成了 term id
            String wpCreateCall = wpCalls.stream().filter(s -> s.startsWith("CREATE")).findFirst().orElse("");
            assertThat(wpCreateCall).contains(siteUrl + "/media/e2e-dist.png");
            assertThat(wpCreateCall).contains("本文由 [写字台](" + siteUrl + ") 首发");
            assertThat(wpCreateCall).contains("\"tags\":[77");
            assertThat(wpCalls.stream().anyMatch(s -> s.startsWith("TAG") && s.contains("Java"))).isTrue();
            // 博客园侧：正文是 Markdown 原文 + 分类带 [Markdown]（否则博客园会把源码当 HTML 贴出来）
            String cnNewCall = cnCalls.stream().filter(s -> s.startsWith("NEW")).findFirst().orElse("");
            assertThat(cnNewCall).contains("<name>categories</name>")
                    .contains("<string>[Markdown]</string>");
            assertThat(cnNewCall).contains("![图](" + siteUrl + "/media/e2e-dist.png)");

            // ⑤ 再查目标：都变成「已分发过」，带上远端链接
            JsonNode again = om.readTree(mvc.perform(get("/api/admin/dist/targets?articleId=" + articleId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(again.path("targets").get(0).path("dist").path("remotePostId").asLong()).isEqualTo(wpRemoteId);
            assertThat(again.path("targets").get(0).path("dist").path("remoteUrl").asText()).contains("/?p=");
            assertThat(again.path("targets").get(0).path("dist").path("distCount").asInt()).isEqualTo(1);

            // ⑥ 选「更新之前分发的文章」：远端 id 不变，但走的是对方的更新接口
            wpCalls.clear();
            cnCalls.clear();
            JsonNode upd = om.readTree(mvc.perform(post("/api/admin/dist/run")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("articleId", articleId, "mode", "original", "format", "markdown",
                                    "targets", java.util.List.of(
                                            Map.of("channel", "wp", "targetId", wpSiteId, "action", "update"),
                                            Map.of("channel", "cnblog", "targetId", cnSiteId, "action", "update")))))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(upd.path("ok").asInt()).isEqualTo(2);
            assertThat(upd.path("results").get(0).path("remotePostId").asLong()).isEqualTo(wpRemoteId);
            assertThat(wpCalls.stream().anyMatch(s -> s.startsWith("UPDATE " + wpRemoteId))).isTrue();
            assertThat(cnCalls.stream().anyMatch(s -> s.startsWith("EDIT"))).isTrue();
            assertThat(wpCalls.stream().anyMatch(s -> s.startsWith("UPDATE") && s.contains("本文由")))
                    .as("原文分发不能带转载尾注").isFalse();

            // ⑦ 选「分发一个新文章」：远端 id 换成新的，记录跟着换
            wpCalls.clear();
            JsonNode fresh = om.readTree(mvc.perform(post("/api/admin/dist/run")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("articleId", articleId, "mode", "original", "format", "markdown",
                                    "targets", java.util.List.of(
                                            Map.of("channel", "wp", "targetId", wpSiteId, "action", "create")))))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsString(StandardCharsets.UTF_8));
            long newRemoteId = fresh.path("results").get(0).path("remotePostId").asLong();
            assertThat(newRemoteId).isNotEqualTo(wpRemoteId);
            JsonNode after = om.readTree(mvc.perform(get("/api/admin/dist/targets?articleId=" + articleId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(after.path("targets").get(0).path("dist").path("remotePostId").asLong()).isEqualTo(newRemoteId);
            assertThat(after.path("targets").get(0).path("dist").path("distCount").asInt())
                    .as("三次分发累计计数").isEqualTo(3);

            // ⑧ 列表徽标：按 id 批量查出「已分发」的目标名，且删站点后记录一并清掉
            String mapBody = mvc.perform(get("/api/admin/dist/map?ids=" + articleId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(mapBody).contains("我的博客园");
            mvc.perform(delete("/api/admin/cnblogs/sites/" + cnSiteId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk());
            String mapAfter = mvc.perform(get("/api/admin/dist/map?ids=" + articleId)
                            .header("Authorization", "Bearer " + adminToken))
                    .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(mapAfter).as("账号删了，指向它的分发记录也该没了").doesNotContain("我的博客园");
            cnSiteId = null;

            // ⑨ 边界：没选目标回 400；没配凭据的站点不可分发
            mvc.perform(post("/api/admin/dist/run")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("articleId", articleId, "targets", java.util.List.of())))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isBadRequest());
            MvcResult anonSite = mvc.perform(post("/api/admin/wp/sites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("url", "http://127.0.0.1:1/anon-wp")))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn();
            long anonId = om.readTree(anonSite.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("site").path("id").asLong();
            try {
                JsonNode anonTargets = om.readTree(mvc.perform(get("/api/admin/dist/targets?articleId=" + articleId)
                                .header("Authorization", "Bearer " + adminToken))
                        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
                JsonNode anon = null;
                for (JsonNode t : anonTargets.path("targets")) {
                    if (t.path("targetId").asLong() == anonId) anon = t;
                }
                assertThat(anon).isNotNull();
                assertThat(anon.path("ready").asBoolean()).as("匿名站点不能发文").isFalse();
                String anonRun = mvc.perform(post("/api/admin/dist/run")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("articleId", articleId, "targets",
                                        java.util.List.of(Map.of("channel", "wp", "targetId", anonId, "action", "create")))))
                                .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk()).andReturn().getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
                JsonNode anonResult = om.readTree(anonRun);
                assertThat(anonResult.path("ok").asInt()).isEqualTo(0);
                assertThat(anonResult.path("failed").asInt()).isEqualTo(1);
                assertThat(anonResult.path("results").get(0).path("message").asText()).contains("未配置");
            } finally {
                mvc.perform(delete("/api/admin/wp/sites/" + anonId)
                                .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk());
            }
        } finally {
            wpServer.stop(0);
            cnServer.stop(0);
            if (articleId != null) articles.deleteById(articleId);
            if (wpSiteId != null) {
                mvc.perform(delete("/api/admin/wp/sites/" + wpSiteId)
                                .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk());
            }
            if (cnSiteId != null) {
                mvc.perform(delete("/api/admin/cnblogs/sites/" + cnSiteId)
                                .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk());
            }
        }
    }

    /* ==================== 写字台账号关联与导入 ==================== */

    @Test
    @DisplayName("写字台：接口地址推导 API 根与站点根")
    void xzApiRootDerivation() {
        assertThat(cn.xiezitai.service.XiezitaiClient.apiRoot("https://a.cn/api/v1/publish")).isEqualTo("https://a.cn/api/v1");
        assertThat(cn.xiezitai.service.XiezitaiClient.apiRoot("https://a.cn/api/v1/mcp")).isEqualTo("https://a.cn/api/v1");
        assertThat(cn.xiezitai.service.XiezitaiClient.apiRoot("https://a.cn/api/v1")).isEqualTo("https://a.cn/api/v1");
        assertThat(cn.xiezitai.service.XiezitaiClient.apiRoot("https://a.cn/api/v1/")).isEqualTo("https://a.cn/api/v1");
        assertThat(cn.xiezitai.service.XiezitaiClient.apiRoot("https://a.cn")).isEqualTo("https://a.cn/api/v1");
        assertThat(cn.xiezitai.service.XiezitaiClient.origin("http://127.0.0.1:8099/api/v1/publish")).isEqualTo("http://127.0.0.1:8099");
        assertThat(cn.xiezitai.service.XiezitaiClient.origin("https://a.cn")).isEqualTo("https://a.cn");
        // 同主机判定复用 WP 渠道的通用工具（忽略 www.）
        assertThat(cn.xiezitai.service.WordPressImportService
                .sameHost("http://127.0.0.1:8099/media/a.png", "http://127.0.0.1:8099/api/v1/publish")).isTrue();
        assertThat(cn.xiezitai.service.WordPressImportService
                .sameHost("https://other.cn/media/a.png", "http://127.0.0.1:8099/api/v1/publish")).isFalse();
    }

    @Test
    @DisplayName("写字台开放 API：列表/详情 JSON，站内媒体绝对化，草稿不外泄")
    void xzOpenApiReadEndpoints() throws Exception {
        String adminToken = loginToken(ADMIN, ADMIN_PWD);
        String apiTok = apiToken(adminToken);
        String sfx = String.valueOf(System.nanoTime());

        cn.xiezitai.entity.Article pub = new cn.xiezitai.entity.Article();
        pub.setTitle("写字台导出测试 " + sfx);
        pub.setSlug("xz-export-" + sfx);
        pub.setContent("正文开头\n\n![站内图](/media/pic-export.png)\n\n![外站图](https://cdn.external.com/ext.png)\n");
        pub.setCover("/media/cover-export.png");
        pub.setSummary("导出摘要");
        pub.setTags("Java,导出");
        pub.setStatus("PUBLISHED");
        pub.setPublishedAt(java.time.LocalDateTime.now());
        pub = articles.save(pub);

        cn.xiezitai.entity.Article draft = new cn.xiezitai.entity.Article();
        draft.setTitle("写字台导出草稿 " + sfx);
        draft.setSlug("xz-export-draft-" + sfx);
        draft.setContent("草稿正文不该被导出");
        draft.setStatus("DRAFT");
        draft = articles.save(draft);

        try {
            // ① 无 Token → 401
            mvc.perform(get("/api/v1/articles")).andExpect(status().isUnauthorized());

            // ② 列表：含已发布、不含草稿，封面绝对化
            MvcResult r = mvc.perform(get("/api/v1/articles?size=100").header("X-API-Token", apiTok))
                    .andExpect(status().isOk()).andReturn();
            JsonNode root = om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(root.path("user").asText()).isEqualTo(ADMIN);
            assertThat(root.path("site").asText()).isEqualTo(siteRoot);
            JsonNode mine = null;
            for (JsonNode n : root.path("items")) {
                assertThat(n.path("id").asLong()).isNotEqualTo(draft.getId());
                if (n.path("id").asLong() == pub.getId()) mine = n;
            }
            assertThat(mine).isNotNull();
            assertThat(mine.path("title").asText()).isEqualTo("写字台导出测试 " + sfx);
            assertThat(mine.path("cover").asText()).isEqualTo(siteRoot + "/media/cover-export.png");
            assertThat(mine.path("tags").asText()).isEqualTo("Java,导出");
            assertThat(mine.path("url").asText()).isEqualTo("/article/xz-export-" + sfx);

            // ③ 关键词过滤：只命中这一篇
            MvcResult s = mvc.perform(get("/api/v1/articles")
                            .param("q", "写字台导出测试 " + sfx)
                            .header("X-API-Token", apiTok))
                    .andExpect(status().isOk()).andReturn();
            JsonNode sr = om.readTree(s.getResponse().getContentAsString(StandardCharsets.UTF_8));
            assertThat(sr.path("total").asLong()).isEqualTo(1);
            assertThat(sr.path("items").get(0).path("id").asLong()).isEqualTo(pub.getId());

            // ④ 详情：正文 Markdown 原样 + 站内媒体绝对化，外链不动
            MvcResult d = mvc.perform(get("/api/v1/articles/" + pub.getId()).header("X-API-Token", apiTok))
                    .andExpect(status().isOk()).andReturn();
            JsonNode detail = om.readTree(d.getResponse().getContentAsString(StandardCharsets.UTF_8));
            String content = detail.path("content").asText();
            assertThat(content).contains(siteRoot + "/media/pic-export.png");
            assertThat(content).contains("https://cdn.external.com/ext.png");
            assertThat(content).doesNotContain("](/media/");
            assertThat(detail.path("status").asText()).isEqualTo("PUBLISHED");
            assertThat(detail.path("cover").asText()).isEqualTo(siteRoot + "/media/cover-export.png");

            // ⑤ 不存在的 id → 404
            mvc.perform(get("/api/v1/articles/99999999").header("X-API-Token", apiTok))
                    .andExpect(status().isNotFound());
        } finally {
            articles.deleteById(pub.getId());
            articles.deleteById(draft.getId());
        }
    }

    @Test
    @DisplayName("写字台：关联/密钥脱敏/单篇导入媒体落盘/重复跳过/整站导入进度")
    void xzAssociateImportAndMediaLocalize() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = server.getAddress().getPort();
        String base = "http://127.0.0.1:" + port;          // 模拟「对方写字台」的站点根
        String apiUrl = base + "/api/v1/publish";          // 用户填的是发布接口地址
        final String xzToken = "xz-token-0123456789abcdef";
        final String sfx = String.valueOf(port);

        String listJson = "{\"site\":\"" + base + "\",\"user\":\"xiezitai\",\"page\":1,\"size\":100,"
                + "\"total\":2,\"totalPages\":1,\"items\":["
                + "{\"id\":201,\"slug\":\"xz-hello-" + sfx + "\",\"title\":\"Hello XZ\",\"summary\":\"对方摘要\","
                + "\"tags\":\"Java,AI\",\"cover\":\"" + base + "/media/cover.png\","
                + "\"publishedAt\":\"2026-03-01T09:00:00\",\"url\":\"/article/xz-hello-" + sfx + "\"},"
                + "{\"id\":202,\"slug\":\"xz-second-" + sfx + "\",\"title\":\"Second XZ\",\"summary\":\"\","
                + "\"tags\":\"\",\"cover\":null,\"publishedAt\":\"2026-03-02T09:00:00\","
                + "\"url\":\"/article/xz-second-" + sfx + "\"}]}";

        // 对方导出的正文里：本站媒体（同主机）→ 应落盘；第三方图床 → 应保留外链
        String md201 = "开头一段\n\n"
                + "![本站图](" + base + "/media/pic.png)\n\n"
                + "![外站图](https://cdn.external.com/ext.png)\n\n"
                + "[附件](" + base + "/media/doc.pdf)\n\n"
                + "<img src=\"" + base + "/media/pic.png\"/>\n";
        String detail201 = "{\"id\":201,\"slug\":\"xz-hello-" + sfx + "\",\"title\":\"Hello XZ\","
                + "\"summary\":\"对方摘要\",\"tags\":\"Java,AI\",\"status\":\"PUBLISHED\","
                + "\"cover\":\"" + base + "/media/cover.png\",\"publishedAt\":\"2026-03-01T09:00:00\","
                + "\"author\":\"xiezitai\",\"url\":\"/article/xz-hello-" + sfx + "\","
                + "\"content\":\"" + md201.replace("\n", "\\n").replace("\"", "\\\"") + "\"}";
        String detail202 = "{\"id\":202,\"slug\":\"xz-second-" + sfx + "\",\"title\":\"Second XZ\","
                + "\"summary\":\"\",\"tags\":\"\",\"status\":\"DRAFT\",\"cover\":\"\","
                + "\"publishedAt\":\"2026-03-02T09:00:00\",\"author\":\"xiezitai\","
                + "\"url\":\"/article/xz-second-" + sfx + "\",\"content\":\"草稿正文\"}";

        server.createContext("/api/v1/articles", ex -> {
            if (!xzToken.equals(ex.getRequestHeaders().getFirst("X-API-Token"))) {
                respond(ex, 401, "application/json", "{\"error\":\"无效的 API Token\"}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String path = ex.getRequestURI().getPath();
            String body = path.endsWith("/201") ? detail201 : (path.endsWith("/202") ? detail202 : listJson);
            respond(ex, 200, "application/json", body.getBytes(StandardCharsets.UTF_8));
        });
        server.createContext("/media/pic.png", ex -> respond(ex, 200, "image/png", PNG_1X1));
        server.createContext("/media/cover.png", ex -> respond(ex, 200, "image/png", PNG_1X1));
        server.createContext("/media/doc.pdf", ex ->
                respond(ex, 200, "application/pdf", "fake-pdf".getBytes(StandardCharsets.UTF_8)));
        server.start();

        String adminToken = loginToken(ADMIN, ADMIN_PWD);
        Long siteId = null;
        try {
            // ① 关联：200 + hasToken=true + 响应体绝不带出明文密钥 + 校验通过（无 warning）
            MvcResult r = mvc.perform(post("/api/admin/xz/sites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("url", apiUrl, "username", "xiezitai", "token", xzToken)))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn();
            String created = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertThat(created).doesNotContain(xzToken);
            JsonNode cj = om.readTree(created);
            siteId = cj.path("site").path("id").asLong();
            assertThat(cj.path("site").path("hasToken").asBoolean()).isTrue();
            assertThat(cj.path("site").path("apiUrl").asText()).isEqualTo(apiUrl);
            assertThat(cj.has("warning")).isFalse();

            // 同站点同账号重复关联 → 409
            mvc.perform(post("/api/admin/xz/sites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("url", apiUrl, "username", "xiezitai", "token", xzToken)))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isConflict());

            // 缺账号 → 400
            mvc.perform(post("/api/admin/xz/sites")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("url", apiUrl, "username", " ")))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isBadRequest());

            // ② 测试连接：账号与密钥校验通过
            mvc.perform(post("/api/admin/xz/sites/" + siteId + "/test")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.account").value("xiezitai"));

            // ③ 浏览对方文章（代理远端，link 补成绝对地址）
            mvc.perform(get("/api/admin/xz/sites/" + siteId + "/posts?page=1&per_page=10")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.total").value(2))
                    .andExpect(jsonPath("$.posts[0].link").value(base + "/article/xz-hello-" + sfx));

            // ④ 单篇导入：正文 Markdown 原样、本站媒体落盘、外链保留
            MvcResult ir = mvc.perform(post("/api/admin/xz/sites/" + siteId + "/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("postId", 201)))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn();
            assertThat(om.readTree(ir.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("imported").asBoolean()).isTrue();

            cn.xiezitai.entity.Article got = articles.findBySlug("xz-hello-" + sfx).orElseThrow();
            assertThat(got.getTitle()).isEqualTo("Hello XZ");
            assertThat(got.getStatus()).isEqualTo("PUBLISHED");
            assertThat(got.getTags()).isEqualTo("Java,AI");
            assertThat(got.getAuthor()).isEqualTo("xiezitai");
            assertThat(got.getSummary()).isEqualTo("对方摘要");
            assertThat(got.getPublishedAt()).isNotNull();
            assertThat(got.getContent()).contains("开头一段");
            assertThat(got.getContent()).contains("cdn.external.com/ext.png");   // 外链保留
            assertThat(got.getContent()).doesNotContain("127.0.0.1");            // 本站媒体已落盘改写
            assertThat(got.getContent()).contains("/media/");
            assertThat(got.getCover()).startsWith("/media/");                    // 封面也落盘
            assertThat(fileRepo.findAll().stream()
                    .anyMatch(f -> ("/media/" + f.getStoredName()).equals(got.getCover()))).isTrue();

            // ⑤ 同一篇再导一次 → 跳过
            MvcResult ir2 = mvc.perform(post("/api/admin/xz/sites/" + siteId + "/import")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("postId", 201)))
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk()).andReturn();
            assertThat(om.readTree(ir2.getResponse().getContentAsString(StandardCharsets.UTF_8))
                    .path("imported").asBoolean()).isFalse();

            // ⑥ 整站导入：202 + 轮询到 DONE（201 跳过、202 新导入）
            mvc.perform(post("/api/admin/xz/sites/" + siteId + "/import-all")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isAccepted());

            JsonNode prog = null;
            for (int i = 0; i < 120; i++) {
                MvcResult pr = mvc.perform(get("/api/admin/xz/sites/" + siteId + "/progress")
                                .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk()).andReturn();
                prog = om.readTree(pr.getResponse().getContentAsString(StandardCharsets.UTF_8));
                if (!"RUNNING".equals(prog.path("phase").asText())) break;
                Thread.sleep(100);
            }
            assertThat(prog).isNotNull();
            assertThat(prog.path("phase").asText()).isEqualTo("DONE");
            assertThat(prog.path("total").asLong()).isEqualTo(2);
            assertThat(prog.path("skipped").asInt()).isGreaterThanOrEqualTo(1);
            assertThat(prog.path("imported").asInt()).isGreaterThanOrEqualTo(1);

            // 草稿在对方是草稿，导入后仍是草稿
            assertThat(articles.findBySlug("xz-second-" + sfx).orElseThrow().getStatus()).isEqualTo("DRAFT");
        } finally {
            server.stop(0);
            if (siteId != null) {
                mvc.perform(delete("/api/admin/xz/sites/" + siteId)
                                .header("Authorization", "Bearer " + adminToken))
                        .andExpect(status().isOk());
            }
            for (String slug : new String[]{"xz-hello-" + sfx, "xz-second-" + sfx}) {
                articles.findBySlug(slug).ifPresent(a -> articles.deleteById(a.getId()));
            }
        }
    }
}
