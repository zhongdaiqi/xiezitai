package cn.xiezitai.controller;

import cn.xiezitai.entity.FileEntity;
import cn.xiezitai.entity.RequestLog;
import cn.xiezitai.repository.FileRepository;
import cn.xiezitai.repository.RequestLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.time.LocalDateTime;

/**
 * 媒体输出统一走 Spring：所有媒体访问先记录访问日志（含来源），再对外输出文件。
 * 危险文件（DANGEROUS）不对外提供。
 */
@RestController
public class MediaController {

    private final FileRepository files;
    private final RequestLogRepository logs;
    private final Path uploadDir;

    public MediaController(FileRepository files, RequestLogRepository logs,
                           @org.springframework.beans.factory.annotation.Value("${xiezitai.upload-dir}") String uploadDir) {
        this.files = files;
        this.logs = logs;
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    @GetMapping("/media/{storedName:.+}")
    public ResponseEntity<FileSystemResource> serve(@PathVariable String storedName, HttpServletRequest request) {
        // 访问日志（媒体内容不直接对外，每次访问均留痕）
        RequestLog rl = new RequestLog();
        rl.setMethod("GET");
        rl.setUri("/media/" + storedName);
        rl.setIp(request.getRemoteAddr());
        rl.setUserAgent(request.getHeader("User-Agent") == null ? "" : request.getHeader("User-Agent"));
        rl.setStatus(200);
        rl.setCreatedAt(LocalDateTime.now());
        logs.save(rl);

        FileEntity fe = files.findByStoredName(storedName).orElse(null);
        if (fe == null || "DANGEROUS".equals(fe.getScanStatus())) {
            return ResponseEntity.notFound().build();
        }
        Path target = uploadDir.resolve(storedName).normalize();
        if (!target.startsWith(uploadDir) || !java.nio.file.Files.exists(target)) {
            return ResponseEntity.notFound().build();
        }
        MediaType mt = fe.getContentType() != null ? MediaType.parseMediaType(fe.getContentType()) : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + storedName + "\"")
                .contentType(mt)
                .body(new FileSystemResource(target));
    }
}
