package cn.xiezitai.controller;

import cn.xiezitai.entity.FileEntity;
import cn.xiezitai.repository.FileRepository;
import cn.xiezitai.repository.UserRepository;
import cn.xiezitai.service.FileScanService;
import cn.xiezitai.service.NotifyService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class FileController {

    private final FileRepository files;
    private final UserRepository users;
    private final FileScanService scanner;
    private final NotifyService notify;
    private final Path uploadDir;

    public FileController(FileRepository files, UserRepository users, FileScanService scanner,
                          NotifyService notify,
                          @org.springframework.beans.factory.annotation.Value("${xiezitai.upload-dir}") String uploadDir) {
        this.files = files;
        this.users = users;
        this.scanner = scanner;
        this.notify = notify;
        this.uploadDir = Path.of(uploadDir);
    }

    @GetMapping("/admin/files")
    public List<FileEntity> list() {
        return files.findAllByOrderByCreatedAtDesc();
    }

    /** 媒体上传：用户默认仅限图片/视频；管理员不受限；上传后异步通知 */
    @PostMapping("/admin/files/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file, Authentication auth) throws Exception {
        var user = users.findByUsername(auth.getName()).orElseThrow();
        boolean isAdmin = "ADMIN".equals(user.getRole());
        String extCheck = scanner.checkExtension(file.getOriginalFilename(), isAdmin, user.getAllowedFileTypes());
        if (extCheck != null) {
            return ResponseEntity.badRequest().body(Map.of("error", extCheck));
        }

        Files.createDirectories(uploadDir);
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String ext = original.contains(".")
                ? original.substring(original.lastIndexOf('.') + 1).toLowerCase() : "bin";
        String stored = HexFormat.of().formatHex(randomBytes(8)) + "." + ext;

        Path target = uploadDir.resolve(stored).normalize();
        if (!target.startsWith(uploadDir)) {
            return ResponseEntity.badRequest().body(Map.of("error", "非法路径"));
        }
        file.transferTo(target);

        FileEntity fe = new FileEntity();
        fe.setOriginalName(original);
        fe.setStoredName(stored);
        fe.setContentType(file.getContentType());
        fe.setSize(file.getSize());
        fe.setUploader(auth.getName());
        files.save(fe);

        scanner.scanContent(fe, target);
        notify.notifyEvent("upload", "**写字台文件上传**\n> 文件: " + original
                + "\n> 用户: " + auth.getName() + "\n> 扫描: " + fe.getScanStatus());

        return ResponseEntity.ok(Map.of(
                "id", fe.getId(),
                "storedName", stored,
                "url", "/media/" + stored,
                "scanStatus", fe.getScanStatus()));
    }

    @DeleteMapping("/admin/files/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) throws Exception {
        FileEntity fe = files.findById(id).orElse(null);
        if (fe == null) return ResponseEntity.notFound().build();
        Files.deleteIfExists(uploadDir.resolve(fe.getStoredName()));
        files.delete(fe);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }
}
