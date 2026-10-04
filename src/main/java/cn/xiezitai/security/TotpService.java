package cn.xiezitai.security;

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;

/** RFC 6238 TOTP 实现（30 秒步长，6 位，±1 窗口容错） */
@Component
public class TotpService {

    private static final String BASE32_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final long STEP = 30;

    private final SecureRandom random = new SecureRandom();

    /** 生成 Base32 编码密钥 */
    public String generateSecret() {
        byte[] buf = new byte[20];
        random.nextBytes(buf);
        return base32Encode(buf);
    }

    public boolean verify(String base32Secret, String code) {
        if (code == null || !code.matches("\\d{6}")) return false;
        byte[] key = base32Decode(base32Secret);
        long now = Instant.now().getEpochSecond() / STEP;
        for (int i = -1; i <= 1; i++) {
            if (code.equals(totp(key, now + i))) return true;
        }
        return false;
    }

    /** 按密钥计算当前动态码（用于自检，非登录校验路径） */
    public String currentCode(String base32Secret) {
        return totp(base32Decode(base32Secret), Instant.now().getEpochSecond() / STEP);
    }

    public String otpAuthUrl(String username, String secret, String issuer) {
        return "otpauth://totp/" + issuer + ":" + username
                + "?secret=" + secret + "&issuer=" + issuer + "&period=30&digits=6";
    }

    private String totp(byte[] key, long counter) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = hash[hash.length - 1] & 0x0F;
            int binary = ((hash[offset] & 0x7F) << 24) | ((hash[offset + 1] & 0xFF) << 16)
                    | ((hash[offset + 2] & 0xFF) << 8) | (hash[offset + 3] & 0xFF);
            return String.format("%06d", binary % 1_000_000);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String base32Encode(byte[] data) {
        StringBuilder sb = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                sb.append(BASE32_ALPHABET.charAt((buffer >>> (bits - 5)) & 31));
                bits -= 5;
            }
        }
        if (bits > 0) sb.append(BASE32_ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return sb.toString();
    }

    private byte[] base32Decode(String s) {
        int buffer = 0, bits = 0;
        byte[] out = new byte[s.length() * 5 / 8];
        int idx = 0;
        for (char c : s.toUpperCase().toCharArray()) {
            int v = BASE32_ALPHABET.indexOf(c);
            if (v < 0) continue;
            buffer = (buffer << 5) | v;
            bits += 5;
            if (bits >= 8) {
                out[idx++] = (byte) (buffer >>> (bits - 8));
                bits -= 8;
            }
        }
        return out;
    }
}
