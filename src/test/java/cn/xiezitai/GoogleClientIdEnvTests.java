package cn.xiezitai;

import cn.xiezitai.service.GoogleAuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 谷歌 Blogger 凭据环境变量名的回归测试。
 *
 * <p>全站只认一种写法：{@code XIEZITAI_GOOGLE_CLIENT_ID} / {@code XIEZITAI_GOOGLE_CLIENT_SECRET}
 * ——{@code .env} 里写它、五份 compose 照这个名字透传进容器、裸机 / JAR 直跑也用它，
 * 靠 {@code application.yml} 里的 {@code ${XIEZITAI_GOOGLE_CLIENT_ID:}} 读进来。
 *
 * <p>这里**只给环境变量这一对名字**（不直接写 {@code xiezitai.google.client-id}），验证这条链没断。
 * 之所以要单独测：这一串名字在「.env / compose 透传名 / application.yml 占位符」三处各写一遍，
 * 任何一处拼错（如 {@code CLIENTID} 少了那道下划线）都不报编译错，只表现为后台提示
 * 「未配置 Google OAuth 客户端」或授权时报 {@code invalid_client}，很难往配置回追——
 * 主测试类是把最终属性直接写进去的，绕过了这层，测不到。
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        // 只给「.env 里的名字」，不给 xiezitai.google.client-id
        "XIEZITAI_GOOGLE_CLIENT_ID=env-client-id.apps.googleusercontent.com",
        "XIEZITAI_GOOGLE_CLIENT_SECRET=env-client-secret"
})
class GoogleClientIdEnvTests {

    @Autowired
    GoogleAuthService auth;

    @Test
    void credentialsFromEnvNamesArePickedUp() {
        assertThat(auth.clientId())
                .as("只给 XIEZITAI_GOOGLE_CLIENT_ID 时也应读到值")
                .isEqualTo("env-client-id.apps.googleusercontent.com");
        assertThat(auth.configured())
                .as("client-id 与 client-secret 都从环境变量名读到了，应判定为「已配置」")
                .isTrue();
        assertThat(auth.authorizeUrl("https://xiezitai.cn/google/auth/redirect"))
                .as("授权地址要带上环境变量里那个 client_id")
                .contains("client_id=env-client-id.apps.googleusercontent.com");
    }
}
