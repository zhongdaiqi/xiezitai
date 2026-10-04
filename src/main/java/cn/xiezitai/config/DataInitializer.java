package cn.xiezitai.config;

import cn.xiezitai.entity.User;
import cn.xiezitai.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.HexFormat;

/** 初始化默认管理员 xiezitai / xiexiexie（仅当不存在时创建） */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final UserRepository users;
    private final PasswordEncoder encoder;

    public DataInitializer(UserRepository users, PasswordEncoder encoder) {
        this.users = users;
        this.encoder = encoder;
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
    }

    private String randomToken() {
        byte[] buf = new byte[24];
        new SecureRandom().nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
