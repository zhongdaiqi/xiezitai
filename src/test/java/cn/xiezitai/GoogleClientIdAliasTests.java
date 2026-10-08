package cn.xiezitai;

import cn.xiezitai.service.GoogleAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 谷歌 Blogger 凭据「环境变量别名」的回归测试。
 *
 * <p>凭据的正式键名是 {@code XIEZITAI_GOOGLE_CLIENT_ID} / {@code XIEZITAI_GOOGLE_CLIENT_SECRET}
 * （{@code .env} 里就写这个，compose 也照这个名字透传进容器）；历史名是
 * {@code GOOGLE_XIEZITAI_CLIENT_ID} / {@code GOOGLE_XIEZITAI_CLIENT_SECRET}，留着做兼容回退，
 * 靠 {@code application.yml} 里的占位符链
 * {@code ${XIEZITAI_GOOGLE_CLIENT_ID:${GOOGLE_XIEZITAI_CLIENT_ID:}}} 打通。
 *
 * <p>这里**只给历史名那一对**，验证兼容回退没断（老的 {@code .env} 不改名也要能跑）。
 * 之所以要单独测：改键名（如 CLIENTID → CLIENT_ID）最容易漏的就是这条链，漏了不报编译错，
 * 只表现为后台提示「未配置 Google OAuth 客户端」或授权时报 {@code invalid_client}，很难往配置回追。
 * 主测试类是把最终的 {@code xiezitai.google.client-id} 直接写进去，绕过了这层别名，测不到。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // 只给「.env 里的名字」，不给应用自己的名字
        "GOOGLE_XIEZITAI_CLIENT_ID=alias-client-id.apps.googleusercontent.com",
        "GOOGLE_XIEZITAI_CLIENT_SECRET=alias-client-secret"
})
class GoogleClientIdAliasTests {

    @Autowired
    GoogleAuthService auth;

    @Test
    void envAliasFromDotEnvIsPickedUp() {
        assertThat(auth.clientId())
                .as("只给 GOOGLE_XIEZITAI_CLIENT_ID 时也应读到值")
                .isEqualTo("alias-client-id.apps.googleusercontent.com");
        assertThat(auth.configured())
                .as("client-id 与 client-secret 都给了别名，应判定为「已配置」")
                .isTrue();
        assertThat(auth.authorizeUrl("https://xiezitai.cn/google/auth/redirect"))
                .as("授权地址要带上别名里那个 client_id")
                .contains("client_id=alias-client-id.apps.googleusercontent.com");
    }
}
