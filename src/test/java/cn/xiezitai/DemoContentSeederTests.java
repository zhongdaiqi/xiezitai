package cn.xiezitai;

import cn.xiezitai.config.DemoContentSeeder;
import cn.xiezitai.repository.ArticleRepository;
import cn.xiezitai.repository.CommentRepository;
import cn.xiezitai.repository.PageRepository;
import cn.xiezitai.repository.SysConfigRepository;
import cn.xiezitai.service.NotifyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 示例内容（DemoContentSeeder）单独自测：这里**故意打开** seed-demo，
 * 并用独立的 H2 库（xiezitai-demo）与主测试类隔离 ——
 * 主测试类跑的是纯空库、seed 关掉的场景，两边互不影响。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:xiezitai-demo;MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "xiezitai.seed-demo=true"
})
class DemoContentSeederTests {

    @Autowired MockMvc mvc;
    @Autowired ArticleRepository articles;
    @Autowired PageRepository pages;
    @Autowired CommentRepository comments;
    @Autowired SysConfigRepository configs;
    @Autowired DemoContentSeeder seeder;
    @Autowired NotifyService notify;

    @BeforeEach
    void setUp() {
        notify.set("notify.enabled", "false");
    }

    @Test
    @DisplayName("库为空时写入示例内容，且只写一次；首页/文章页/页面/站点地图都能正常渲染")
    void seedsOnceOnEmptyDatabase() throws Exception {
        // 1) 内容落库
        assertThat(articles.count()).isEqualTo(4);
        assertThat(pages.count()).isEqualTo(2);
        assertThat(comments.count()).isEqualTo(5);
        assertThat(articles.findBySlug("welcome")).isPresent();
        assertThat(pages.findBySlug("about")).isPresent();
        assertThat(pages.findBySlug("links")).isPresent();
        assertThat(configs.findById(DemoContentSeeder.SEEDED_FLAG)).isPresent();

        Long welcomeId = articles.findBySlug("welcome").orElseThrow().getId();
        assertThat(comments.findByArticleIdAndStatusOrderByCreatedAtAsc(welcomeId, "APPROVED")).hasSize(4);
        assertThat(comments.findByArticleIdAndStatusOrderByCreatedAtAsc(welcomeId, "PENDING")).hasSize(1);

        // 2) 幂等：标记已存在时不再写入
        assertThat(seeder.seedIfEmpty()).isFalse();
        assertThat(articles.count()).isEqualTo(4);

        // 3) 公开页面能渲染出来（模板新增了 navPages，这里顺带守住）
        mvc.perform(get("/")).andExpect(status().isOk())
                .andExpect(content().string(containsString("欢迎来到写字台")))
                .andExpect(content().string(containsString("href=\"/page/about\"")))
                .andExpect(content().string(containsString("href=\"/page/links\"")));

        mvc.perform(get("/article/welcome")).andExpect(status().isOk())
                .andExpect(content().string(containsString("这是什么")))
                .andExpect(content().string(containsString("夜航船")))
                .andExpect(content().string(containsString("回复 @")))
                // 待审核的那条不该出现在前台
                .andExpect(content().string(org.hamcrest.Matchers.not(containsString("请问支持画流程图吗"))));

        mvc.perform(get("/page/about")).andExpect(status().isOk())
                .andExpect(content().string(containsString("关于这个站点")));

        mvc.perform(get("/sitemap.xml")).andExpect(status().isOk())
                .andExpect(content().string(containsString("/article/markdown-guide")))
                .andExpect(content().string(containsString("/page/links")));
    }
}
