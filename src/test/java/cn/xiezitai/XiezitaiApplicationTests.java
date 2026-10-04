package cn.xiezitai;

import cn.xiezitai.entity.RequestLog;
import cn.xiezitai.entity.User;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.repository.FileRepository;
import cn.xiezitai.repository.RequestLogRepository;
import cn.xiezitai.repository.UserRepository;
import cn.xiezitai.security.LoginAttemptService;
import cn.xiezitai.security.TotpService;
import cn.xiezitai.service.NotifyService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.Result;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

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
 * 覆盖：SEO 页面、登录与锁定策略、TOTP 两步验证、文章/页面/评论、媒体访问留痕、
 *      上传类型限制与魔数扫描、开放 API、MCP 服务、请求日志。
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
    @Autowired FileRepository fileRepo;
    @Autowired RequestLogRepository logs;
    @Autowired PasswordEncoder encoder;
    @Autowired NotifyService notify;
    @Autowired TotpService totp;
    @Autowired LoginAttemptService attempts;

    @BeforeEach
    void setUp() {
        // 测试期间关闭机器人通知，避免外发真实请求
        notify.set("notify.enabled", "false");
        attempts.onSuccess(ADMIN);

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
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Sitemap:")));
        mvc.perform(get("/sitemap.xml")).andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("<urlset")));
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

    /* ==================== 评论（懒加载序列化回归） ==================== */

    @Test
    @DisplayName("游客评论待审，管理员审核后公开可见")
    void visitorCommentNeedsApproval() throws Exception {
        String token = loginToken(ADMIN, ADMIN_PWD);
        MvcResult created = mvc.perform(post("/api/admin/articles").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("title", "评论测试-" + System.currentTimeMillis(),
                                "content", "正文", "status", "PUBLISHED"))))
                .andExpect(status().isOk()).andReturn();
        String slug = om.readTree(created.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("slug").asText();

        mvc.perform(post("/api/articles/" + slug + "/comments").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "写得不错", "authorName", "路人甲"))))
                .andExpect(status().isOk());

        // 未审核 -> 公开列表为空
        mvc.perform(get("/api/articles/" + slug + "/comments")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));

        // 管理员看到待审评论（此处会序列化 article 字段，验证 @EntityGraph 生效）
        MvcResult all = mvc.perform(get("/api/admin/comments").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn();
        JsonNode list = om.readTree(all.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(list.isArray()).isTrue();
        long id = -1;
        for (JsonNode c : list) {
            if (slug.equals(c.path("article").path("slug").asText())) {
                id = c.path("id").asLong();
                assertThat(c.path("authorName").asText()).isEqualTo("路人甲");
            }
        }
        assertThat(id).isPositive();

        mvc.perform(put("/api/admin/comments/" + id + "/status").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json(Map.of("status", "APPROVED"))))
                .andExpect(status().isOk());

        mvc.perform(get("/api/articles/" + slug + "/comments")).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].content").value("写得不错"));
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
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", uname, "password", "tester123"))))
                .andExpect(status().isOk());
        String token = loginToken(uname, "tester123");

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
                .andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("inline")));

        boolean logged = logs.findAll().stream()
                .anyMatch(l -> ("/media/" + stored).equals(l.getUri()));
        assertThat(logged).as("媒体访问应写入日志").isTrue();

        // 未登记的文件不对外
        mvc.perform(get("/media/not-exist-file.png")).andExpect(status().isNotFound());
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
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", uname, "password", "normal123"))))
                .andExpect(status().isOk());
        String token = loginToken(uname, "normal123");
        mvc.perform(get("/api/admin/settings").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
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

    private String apiToken(String jwt) throws Exception {
        MvcResult r = mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk()).andReturn();
        return om.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("apiToken").asText();
    }
}
