package cn.xiezitai.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统防黑：启动时对关键代码/配置文件计算基线 SHA-256，之后可随时比对，
 * 识别程序文件是否被篡改（基线持久化到 data/tamper-baseline.txt）。
 */
@Service
public class TamperGuardService {

    private static final Logger log = LoggerFactory.getLogger(TamperGuardService.class);
    private static final String[] WATCH = {"application.yml", "application-prod.yml"};

    private final Path dataDir;

    public TamperGuardService(@Value("${xiezitai.upload-dir}") String uploadDir) {
        this.dataDir = Path.of(uploadDir).getParent() == null ? Path.of("./data") : Path.of(uploadDir).getParent();
    }

    /** 与基线比对，返回 {文件: 状态}，状态: OK / CHANGED / NEW / UNWATCHED */
    public Map<String, String> verify() {
        Map<String, String> result = new LinkedHashMap<>();
        Path baselineFile = dataDir.resolve("tamper-baseline.txt");
        Map<String, String> baseline = readBaseline(baselineFile);
        for (String name : WATCH) {
            Path p = findResource(name);
            if (p == null) {
                result.put(name, "UNWATCHED");
                continue;
            }
            String now = sha256(p);
            String old = baseline.get(name);
            result.put(name, old == null ? "NEW" : (old.equals(now) ? "OK" : "CHANGED"));
            baseline.put(name, now);
        }
        // 更新基线（首次运行即建立基线）
        writeBaseline(baselineFile, baseline);
        boolean changed = result.containsValue("CHANGED");
        if (changed) log.error("检测到系统文件被篡改！请立即排查: {}", result);
        return result;
    }

    private Path findResource(String name) {
        try {
            Path p = Path.of("src/main/resources", name);
            if (Files.exists(p)) return p;
            Path cp = Path.of("config", name);
            if (Files.exists(cp)) return cp;
        } catch (Exception ignored) {
        }
        return null;
    }

    private String sha256(Path p) {
        try (InputStream in = Files.newInputStream(p)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = in.readAllBytes();
            return HexFormat.of().formatHex(md.digest(buf));
        } catch (Exception e) {
            return "ERR";
        }
    }

    private Map<String, String> readBaseline(Path f) {
        Map<String, String> m = new LinkedHashMap<>();
        try {
            if (Files.exists(f)) {
                for (String line : Files.readAllLines(f)) {
                    String[] parts = line.split("=", 2);
                    if (parts.length == 2) m.put(parts[0], parts[1]);
                }
            }
        } catch (Exception ignored) {
        }
        return m;
    }

    private void writeBaseline(Path f, Map<String, String> m) {
        try {
            Files.createDirectories(f.getParent());
            StringBuilder sb = new StringBuilder();
            m.forEach((k, v) -> sb.append(k).append("=").append(v).append("\n"));
            Files.writeString(f, sb.toString());
        } catch (Exception e) {
            log.warn("防篡改基线写入失败: {}", e.getMessage());
        }
    }
}
