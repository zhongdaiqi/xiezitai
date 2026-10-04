package cn.xiezitai.security;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.EnumMap;
import java.util.Map;

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

    /**
     * 把 otpauth 链接渲染成二维码，返回 PNG 的 data URI（data:image/png;base64,...）。
     * 前端直接塞进 &lt;img src&gt; 即可，无需任何前端二维码库或外部 CDN；失败返回空串。
     */
    public String qrDataUri(String content, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
            int[] row = new int[size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    row[x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
                img.setRGB(0, y, size, 1, row, 0, size);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            return "";
        }
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
