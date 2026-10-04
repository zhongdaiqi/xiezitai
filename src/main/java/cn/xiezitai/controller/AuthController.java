package cn.xiezitai.controller;

import cn.xiezitai.entity.User;
import cn.xiezitai.repository.UserRepository;
import cn.xiezitai.security.JwtUtil;
import cn.xiezitai.security.LoginAttemptService;
import cn.xiezitai.security.TotpService;
import cn.xiezitai.service.NotifyService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JwtUtil jwt;
    private final LoginAttemptService attempts;
    private final TotpService totp;
    private final NotifyService notify;

    public AuthController(UserRepository users, PasswordEncoder encoder, JwtUtil jwt,
                          LoginAttemptService attempts, TotpService totp, NotifyService notify) {
        this.users = users;
        this.encoder = encoder;
        this.jwt = jwt;
        this.attempts = attempts;
        this.totp = totp;
        this.notify = notify;
    }

    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");
        String totpCode = body.get("totpCode");

        if (attempts.isLocked(username)) {
            return ResponseEntity.status(423).body(Map.of(
                    "error", "账号已锁定，请 " + attempts.remainingLockSeconds(username) + " 秒后再试"));
        }
        User user = users.findByUsername(username).orElse(null);
        if (user == null || !user.isEnabled() || !encoder.matches(password, user.getPassword())) {
            attempts.onFailure(username);
            return ResponseEntity.status(401).body(Map.of("error", "用户名或密码错误"));
        }
        if (user.isTotpEnabled()) {
            if (totpCode == null || totpCode.isBlank()) {
                return ResponseEntity.status(401).body(Map.of("error", "NEED_TOTP", "message", "已开启两步验证，请输入动态码"));
            }
            if (!totp.verify(user.getTotpSecret(), totpCode)) {
                attempts.onFailure(username);
                return ResponseEntity.status(401).body(Map.of("error", "动态验证码错误"));
            }
        }
        attempts.onSuccess(username);
        String token = jwt.generate(user.getUsername(), user.getRole());
        notify.notifyEvent("login", "**写字台登录通知**\n> 用户: " + user.getUsername()
                + "\n> 时间: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        Map<String, Object> resp = new HashMap<>();
        resp.put("token", token);
        resp.put("username", user.getUsername());
        resp.put("role", user.getRole());
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody Map<String, String> body) {
        String username = body.getOrDefault("username", "").trim();
        String password = body.getOrDefault("password", "");
        if (username.length() < 3 || password.length() < 6) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户名至少 3 位，密码至少 6 位"));
        }
        if (users.findByUsername(username).isPresent()) {
            return ResponseEntity.badRequest().body(Map.of("error", "用户名已存在"));
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(encoder.encode(password));
        user.setEmail(body.get("email"));
        user.setRole("USER");
        user.setApiToken(randomToken());
        users.save(user);
        notify.notifyEvent("register", "**写字台新用户注册**\n> 用户: " + username);
        return ResponseEntity.ok(Map.of("message", "注册成功"));
    }

    @GetMapping("/me")
    public ResponseEntity<?> me(Authentication auth) {
        User user = users.findByUsername(auth.getName()).orElse(null);
        if (user == null) return ResponseEntity.status(401).build();
        Map<String, Object> resp = new HashMap<>();
        resp.put("username", user.getUsername());
        resp.put("role", user.getRole());
        resp.put("email", user.getEmail());
        resp.put("totpEnabled", user.isTotpEnabled());
        resp.put("apiToken", user.getApiToken());
        resp.put("allowedFileTypes", user.getAllowedFileTypes());
        return ResponseEntity.ok(resp);
    }

    /** 生成 TOTP 密钥：返回密钥、otpauth 链接，以及可直接显示的二维码（PNG data URI） */
    @PostMapping("/totp/setup")
    public ResponseEntity<?> totpSetup(Authentication auth) {
        User user = users.findByUsername(auth.getName()).orElseThrow();
        String secret = totp.generateSecret();
        user.setTotpSecret(secret);
        users.save(user);
        String otpauthUrl = totp.otpAuthUrl(user.getUsername(), secret, "xiezitai");
        Map<String, Object> out = new HashMap<>();
        out.put("secret", secret);
        out.put("otpauthUrl", otpauthUrl);
        out.put("qrCode", totp.qrDataUri(otpauthUrl, 240));
        return ResponseEntity.ok(out);
    }

    /** 验证动态码并正式开启 TOTP */
    @PostMapping("/totp/enable")
    public ResponseEntity<?> totpEnable(Authentication auth, @RequestBody Map<String, String> body) {
        User user = users.findByUsername(auth.getName()).orElseThrow();
        if (user.getTotpSecret() == null) return ResponseEntity.badRequest().body(Map.of("error", "请先调用 setup 生成密钥"));
        if (!totp.verify(user.getTotpSecret(), body.get("code"))) {
            return ResponseEntity.badRequest().body(Map.of("error", "动态验证码错误"));
        }
        user.setTotpEnabled(true);
        users.save(user);
        return ResponseEntity.ok(Map.of("message", "TOTP 已开启"));
    }

    @PostMapping("/totp/disable")
    public ResponseEntity<?> totpDisable(Authentication auth) {
        User user = users.findByUsername(auth.getName()).orElseThrow();
        user.setTotpEnabled(false);
        user.setTotpSecret(null);
        users.save(user);
        return ResponseEntity.ok(Map.of("message", "TOTP 已关闭"));
    }

    @PostMapping("/password")
    public ResponseEntity<?> changePassword(Authentication auth, @RequestBody Map<String, String> body) {
        User user = users.findByUsername(auth.getName()).orElseThrow();
        if (!encoder.matches(body.getOrDefault("oldPassword", ""), user.getPassword())) {
            return ResponseEntity.badRequest().body(Map.of("error", "原密码错误"));
        }
        String np = body.getOrDefault("newPassword", "");
        if (np.length() < 6) return ResponseEntity.badRequest().body(Map.of("error", "新密码至少 6 位"));
        user.setPassword(encoder.encode(np));
        users.save(user);
        return ResponseEntity.ok(Map.of("message", "密码已修改"));
    }

    private String randomToken() {
        byte[] buf = new byte[24];
        new SecureRandom().nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }
}
