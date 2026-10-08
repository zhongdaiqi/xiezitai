package cn.xiezitai;

import cn.xiezitai.service.SlugUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 根级 slug 的路径编码规则。
 *
 * <p>这三条分支在真实数据里都跑着，随便简化一条线上就坏：
 * ASCII 原样、中文百分号编码、**已经是 %xx 形态的老 slug 绝不再编一层**。
 */
class SlugPathTests {

    @Test
    @DisplayName("普通 ASCII slug 原样输出")
    void asciiSlugStaysLiteral() {
        assertThat(SlugUtil.publicPath("why-self-host")).isEqualTo("/why-self-host");
        assertThat(SlugUtil.publicPath("links")).isEqualTo("/links");
        assertThat(SlugUtil.publicPath("v1.2-notes")).isEqualTo("/v1.2-notes");
    }

    @Test
    @DisplayName("真中文 slug 逐字节百分号编码（空格是 %20，不是 +）")
    void chineseSlugIsPercentEncoded() {
        assertThat(SlugUtil.publicPath("你好")).isEqualTo("/%E4%BD%A0%E5%A5%BD");
        assertThat(SlugUtil.publicPath("rag 优化")).isEqualTo("/rag%20%E4%BC%98%E5%8C%96");
    }

    @Test
    @DisplayName("已是 %xx 形态的老 slug 不许再编一层（%25 会被安全防火墙拦成 400）")
    void alreadyEncodedSlugIsNotEncodedTwice() {
        assertThat(SlugUtil.publicPath("%e4%bd%a0%e5%a5%bd-legacy"))
                .isEqualTo("/%e4%bd%a0%e5%a5%bd-legacy");
        // WP 存小写、Java 产大写，两种都要认
        assertThat(SlugUtil.publicPath("%E4%BD%A0")).isEqualTo("/%E4%BD%A0");
        // 裸 %（后面不是两位十六进制）是字面量，该编就得编
        assertThat(SlugUtil.publicPath("100%")).isEqualTo("/100%25");
    }

    @Test
    @DisplayName("空 slug 退化成站点根；publicUrl 会吃掉多余的尾斜杠")
    void blankSlugDegradesToSiteRoot() {
        assertThat(SlugUtil.publicPath(null)).isEqualTo("/");
        assertThat(SlugUtil.publicPath("   ")).isEqualTo("/");
        assertThat(SlugUtil.publicUrl("https://xiezitai.cn/", "links")).isEqualTo("https://xiezitai.cn/links");
        assertThat(SlugUtil.publicUrl("https://xiezitai.cn", "why-self-host"))
                .isEqualTo("https://xiezitai.cn/why-self-host");
    }
}
