package cn.xiezitai.service;

import cn.xiezitai.entity.FileEntity;
import cn.xiezitai.repository.FileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * 上传文件安全扫描：
 * 1. 扩展名白名单（用户默认仅限图片/视频，管理员可配置用户类型限制，管理员本人不受限）
 * 2. 文件头魔数嗅探，防改扩展名伪装
 * 3. 检出“未经系统上传”的孤立文件（直接扫描上传目录对比库内记录）
 */
@Service
public class FileScanService {

    private static final Logger log = LoggerFactory.getLogger(FileScanService.class);

    private static final Set<String> IMAGE_EXT = Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "ico");
    private static final Set<String> VIDEO_EXT = Set.of("mp4", "webm", "mov", "avi", "mkv");
    private static final Set<String> ADMIN_EXT  = Set.of("pdf", "zip", "txt", "md", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "7z", "tar", "gz");

    /** 危险扩展名：一律拒绝 */
    private static final Set<String> BLOCK_EXT = Set.of("exe", "dll", "bat", "cmd", "sh", "ps1", "vbs", "js", "jar", "msi", "scr", "com", "php", "jsp", "asp", "aspx", "html", "htm");

    private final FileRepository files;

    public FileScanService(FileRepository files) {
        this.files = files;
    }

    /** 校验扩展名是否允许上传；err 返回 null 表示通过 */
    public String checkExtension(String originalName, boolean isAdmin, String userAllowed) {
        String ext = extOf(originalName);
        if (ext.isEmpty()) return "禁止上传无扩展名文件";
        if (BLOCK_EXT.contains(ext)) return "禁止上传可执行/脚本类型: ." + ext;
        if (isAdmin) {
            if (IMAGE_EXT.contains(ext) || VIDEO_EXT.contains(ext) || ADMIN_EXT.contains(ext)) return null;
            return "管理员暂不支持 ." + ext + " 类型";
        }
        if (userAllowed != null && !userAllowed.isBlank()) {
            List<String> allowed = List.of(userAllowed.toLowerCase().split("[,，]"));
            return allowed.contains(ext) ? null : "仅允许上传: " + userAllowed;
        }
        // 默认媒体限制：图片、视频
        if (IMAGE_EXT.contains(ext) || VIDEO_EXT.contains(ext)) return null;
        return "默认仅允许上传图片/视频文件";
    }

    /** 魔数嗅探：标记 PENDING -> SAFE / DANGEROUS */
    public void scanContent(FileEntity fe, Path path) {
        String verdict = "SAFE";
        String detail = "";
        try (InputStream in = Files.newInputStream(path)) {
            byte[] head = in.readNBytes(16);
            String ext = extOf(fe.getOriginalName());
            boolean declaredImage = IMAGE_EXT.contains(ext);
            boolean declaredVideo = VIDEO_EXT.contains(ext);
            if (declaredImage || declaredVideo) {
                if (!magicOk(head, ext)) {
                    // SVG 是文本格式、头部多样，放行；其余类型魔数不匹配即视为伪装文件
                    boolean svgLike = "svg".equals(ext) && looksText(head);
                    if (!svgLike) {
                        verdict = "DANGEROUS";
                        detail = "文件头与声明类型不符（疑似伪装文件）";
                    }
                }
            }
            // 脚本注入检查：图片/视频里嵌 <?php / <script>
            if ("SAFE".equals(verdict) && (declaredImage || declaredVideo) && head.length > 0) {
                try (InputStream full = Files.newInputStream(path)) {
                    byte[] sample = full.readNBytes(1024 * 64);
                    String s = new String(sample, java.nio.charset.StandardCharsets.US_ASCII).toLowerCase();
                    if (s.contains("<?php") || s.contains("<script") || s.contains("javascript:")) {
                        verdict = "DANGEROUS";
                        detail = "内容包含脚本代码";
                    }
                }
            }
        } catch (Exception e) {
            log.warn("文件扫描失败 {}: {}", fe.getStoredName(), e.getMessage());
            verdict = "PENDING";
            detail = "扫描异常: " + e.getMessage();
        }
        fe.setScanStatus(verdict);
        fe.setScanDetail(detail);
        files.save(fe);
    }

    /** 全库安全扫描：找出孤立文件（存在于磁盘但不在库中 => 未经系统上传） */
    public String scanUploadDir(Path uploadDir) {
        if (!Files.isDirectory(uploadDir)) return "上传目录不存在";
        int orphan = 0, total = 0;
        try (var stream = Files.list(uploadDir)) {
            for (Path p : stream.toList()) {
                total++;
                String name = p.getFileName().toString();
                if (files.findByStoredName(name).isEmpty()) {
                    orphan++;
                    log.warn("发现未经系统上传的孤立文件: {}", name);
                }
            }
        } catch (Exception e) {
            return "扫描失败: " + e.getMessage();
        }
        return "扫描完成: 共 " + total + " 个文件，孤立(未经系统上传) " + orphan + " 个";
    }

    private boolean magicOk(byte[] h, String ext) {
        return switch (ext) {
            case "jpg", "jpeg" -> h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8;
            case "png" -> h.length >= 4 && (h[0] & 0xFF) == 0x89 && h[1] == 'P';
            case "gif" -> h.length >= 3 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F';
            case "bmp" -> h.length >= 2 && h[0] == 'B' && h[1] == 'M';
            case "webp" -> h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                    && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
            case "ico" -> h.length >= 2 && h[0] == 0 && h[1] == 0;
            // mp4/mov: ftyp box；注意必须判到第 8 字节，短文件直接判为不匹配（避免越界）
            case "mp4", "mov" -> h.length >= 8 && h[4] == 'f' && h[5] == 't' && h[6] == 'y' && h[7] == 'p';
            case "avi" -> h.length >= 4 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F';
            case "mkv" -> h.length >= 4 && (h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45
                    && (h[2] & 0xFF) == 0xDF && (h[3] & 0xFF) == 0xA3;
            case "webm" -> h.length >= 4 && (h[0] & 0xFF) == 0x1A && (h[1] & 0xFF) == 0x45;
            default -> true; // 无法判断的类型不误报
        };
    }

    private boolean looksText(byte[] h) {
        for (byte b : h) {
            if (b == 0) return false;
        }
        return true;
    }

    private String extOf(String name) {
        int i = name == null ? -1 : name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase();
    }
}
