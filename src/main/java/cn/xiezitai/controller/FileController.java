package cn.xiezitai.controller;

import cn.xiezitai.entity.FileEntity;
import cn.xiezitai.repository.FileRepository;
import cn.xiezitai.repository.UserRepository;
import cn.xiezitai.service.FileScanService;
import cn.xiezitai.service.MediaStoreService;
import cn.xiezitai.service.NotifyService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class FileController {

    private final FileRepository files;
    private final UserRepository users;
    private final FileScanService scanner;
    private final MediaStoreService media;
    private final NotifyService notify;

    public FileController(FileRepository files, UserRepository users, FileScanService scanner,
                          MediaStoreService media, NotifyService notify) {
        this.files = files;
        this.users = users;
        this.scanner = scanner;
        this.media = media;
        this.notify = notify;
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

        // 落盘 / 入库 / 扫描统一走 MediaStoreService（与 AI 封面共用同一入口）
        // 用流而不是 getBytes()：视频上限 200MB，整块读进内存会直接把堆打爆
        FileEntity fe = media.store(file.getInputStream(), file.getOriginalFilename(),
                file.getContentType(), file.getSize(), auth.getName());

        notify.notifyEvent("upload", "**写字台文件上传**\n> 文件: " + fe.getOriginalName()
                + "\n> 用户: " + auth.getName() + "\n> 扫描: " + fe.getScanStatus());

        return ResponseEntity.ok(Map.of(
                "id", fe.getId(),
                "storedName", fe.getStoredName(),
                "url", "/media/" + fe.getStoredName(),
                "scanStatus", fe.getScanStatus()));
    }

    @DeleteMapping("/admin/files/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) throws Exception {
        FileEntity fe = files.findById(id).orElse(null);
        if (fe == null) return ResponseEntity.notFound().build();
        Files.deleteIfExists(media.dir().resolve(fe.getStoredName()));
        files.delete(fe);
        return ResponseEntity.ok(Map.of("message", "已删除"));
    }
}
