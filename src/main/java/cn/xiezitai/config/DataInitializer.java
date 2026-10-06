package cn.xiezitai.config;

import cn.xiezitai.entity.SysConfig;
import cn.xiezitai.entity.User;
import cn.xiezitai.repository.SysConfigRepository;
import cn.xiezitai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 初始化默认数据：
 * 1) 默认管理员 xiezitai / xiexiexie（不存在时创建）；
 * 2) AI 默认配置（sys_configs: ai.baseUrl / ai.apiKey / ai.model / ai.imageModel），已存在则不覆盖，
 *    管理员可在后台「设置」里随时改成自己的接口；
 * 3) 示例内容（文章 / 页面 / 评论），由 {@link DemoContentSeeder} 负责，
 *    只在库为空且未写过时执行，可用 xiezitai.seed-demo=false 关闭。
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository users;
    private final SysConfigRepository configs;
    private final PasswordEncoder encoder;
    private final DemoContentSeeder demoSeeder;

    private final String aiBaseUrl;
    private final String aiApiKey;
    private final String aiModel;
    private final String aiImageModel;
    private final boolean seedDemo;

    public DataInitializer(UserRepository users,
                           SysConfigRepository configs,
                           PasswordEncoder encoder,
                           DemoContentSeeder demoSeeder,
                           @Value("${xiezitai.ai.base-url:}") String aiBaseUrl,
                           @Value("${xiezitai.ai.api-key:}") String aiApiKey,
                           @Value("${xiezitai.ai.model:}") String aiModel,
                           @Value("${xiezitai.ai.image-model:}") String aiImageModel,
                           @Value("${xiezitai.seed-demo:true}") boolean seedDemo) {
        this.users = users;
        this.configs = configs;
        this.encoder = encoder;
        this.demoSeeder = demoSeeder;
        this.aiBaseUrl = aiBaseUrl;
        this.aiApiKey = aiApiKey;
        this.aiModel = aiModel;
        this.aiImageModel = aiImageModel;
        this.seedDemo = seedDemo;
    }

    @Override
    public void run(String... args) {
        if (users.findByUsername("xiezitai").isEmpty()) {
            User admin = new User();
            admin.setUsername("xiezitai");
            admin.setPassword(encoder.encode("xiexiexie"));
            admin.setRole("ADMIN");
            admin.setApiToken(randomToken());
            users.save(admin);
            log.warn("已创建默认管理员 xiezitai / xiexiexie，请登录后立即修改密码并开启 TOTP！");
        }

        seedConfig("ai.baseUrl", aiBaseUrl);
        seedConfig("ai.apiKey", aiApiKey);
        seedConfig("ai.model", aiModel);
        seedConfig("ai.imageModel", aiImageModel);

        if (seedDemo) {
            // 示例内容：仅当文章/页面都为空、且没写过时才落库（详见 DemoContentSeeder）
            demoSeeder.seedIfEmpty();
        }
    }

    /** 仅在缺少该配置时写入默认值，避免覆盖管理员在后台的修改 */
    private void seedConfig(String key, String value) {
        if (value == null || value.isBlank()) return;
        if (configs.findById(key).isPresent()) return;
        SysConfig c = new SysConfig();
        c.setConfigKey(key);
        c.setConfigValue(value);
        configs.save(c);
        log.info("已写入默认配置 {}", key);
    }

    private String randomToken() {
        byte[] buf = new byte[24];
        new SecureRandom().nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
