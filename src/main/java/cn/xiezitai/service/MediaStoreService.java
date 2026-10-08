package cn.xiezitai.service;

import cn.xiezitai.entity.FileEntity;
import cn.xiezitai.repository.FileRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 站内媒体库的**唯一写入口**：随机文件名 → 落盘 → 入库 → 安全扫描。
 *
 * <p>上传接口与「AI 生成的封面」共用这一处，是为了保证两边的命名规则、库内记录、
 * 扫描口径完全一致 —— 否则 AI 落盘的图会变成「未经系统上传的孤立文件」，
 * 被 {@link FileScanService#scanUploadDir} 反复告警，且媒体库列表里也看不到。
 */
@Service
public class MediaStoreService {

    /** 直接以字节入库时的上限（AI 图片远小于此，防上游返回超大响应） */
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    private final FileRepository files;
    private final FileScanService scanner;
    private final Path uploadDir;

    public MediaStoreService(FileRepository files, FileScanService scanner,
                            @Value("${xiezitai.upload-dir}") String uploadDir) {
        this.files = files;
        this.scanner = scanner;
        // 必须绝对化 + normalize，否则相对路径与 resolve().normalize() 结果不一致，会误判“非法路径”
        this.uploadDir = Path.of(uploadDir).toAbsolutePath().normalize();
    }

    /** 媒体根目录（供删除、全库扫描复用，避免各处自己拼路径） */
    public Path dir() {
        return uploadDir;
    }

    /**
     * 拿到的字节不是有效图片。单独成类，是为了让调用方能区分
     * 「上游给了个假图片地址」（上游故障，应答 502）和「我们自己的存储出了问题」（500）。
     */
    public static class NotAnImageException extends IOException {
        public NotAnImageException(String message) {
            super(message);
        }
    }

    /**
     * 把一段字节存进媒体库。
     * 文件名后缀取自 {@code originalName}；调用方若不确定格式，请用 {@link #storeImage}。
     */
    public FileEntity store(byte[] data, String originalName, String contentType, String uploader) throws IOException {
        return store(data, originalName, contentType, uploader, MAX_BYTES);
    }

    /**
     * 以字节入库，可自定义大小上限。
     *
     * <p>默认上限 {@link #MAX_BYTES} 是为「上游返回的字节」设的护栏（AI 图片、错误页）。
     * 导入对方站点**自身的文件**时用更大的上限（视频/附件常常十几兆），由调用方传入。
     * 落盘走的是无上限的流式 {@link #store(InputStream, String, String, long, String)}，
     * 这里只是加一道前置校验，不会二次读取。
     */
    public FileEntity store(byte[] data, String originalName, String contentType, String uploader,
                            long maxBytes) throws IOException {
        if (data == null || data.length == 0) throw new IOException("空文件");
        if (maxBytes > 0 && data.length > maxBytes) {
            throw new IOException("文件过大（上限 " + (maxBytes / 1024 / 1024) + "MB）");
        }
        return store(new ByteArrayInputStream(data), originalName, contentType, data.length, uploader);
    }

    /** 流式入媒体库（上传大文件用，避免整块读进内存） */
    public FileEntity store(InputStream in, String originalName, String contentType, long size,
                            String uploader) throws IOException {
        String original = (originalName == null || originalName.isBlank()) ? "file" : originalName;
        String ext = extOf(original);
        Files.createDirectories(uploadDir);

        String stored = HexFormat.of().formatHex(randomBytes(8)) + "." + ext;
        Path target = uploadDir.resolve(stored).normalize();
        if (!target.startsWith(uploadDir)) throw new IOException("非法路径");

        // 边写边算 SHA-256：完全一样的字节流不再重复落盘，直接引用已入库的那份
        // （WP 整站导入时多篇文章共用同一张图，是重复写入的大头）
        java.security.MessageDigest digest;
        try {
            digest = java.security.MessageDigest.getInstance("SHA-256");
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
        String sha256;
        try (InputStream src = new java.security.DigestInputStream(in, digest)) {
            Files.copy(src, target);
        }
        sha256 = HexFormat.of().formatHex(digest.digest());
        FileEntity existing = files.findFirstBySha256OrderByIdAsc(sha256).orElse(null);
        if (existing != null) {
            Files.deleteIfExists(target);       // 刚写的这份是重复的，删掉，复用库里那份
            return existing;
        }

        FileEntity fe = new FileEntity();
        fe.setOriginalName(original);
        fe.setStoredName(stored);
        fe.setContentType(contentType);
        fe.setSize(size >= 0 ? size : Files.size(target));
        fe.setUploader(uploader);
        fe.setSha256(sha256);
        files.save(fe);

        scanner.scanContent(fe, target);
        return fe;
    }

    /**
     * 图片入媒体库：扩展名与 Content-Type 一律**按文件头嗅探的真实格式**决定，
     * 而不是听上游说了算。这样两类脏数据都会被挡住：
     * <ul>
     *   <li>上游把错误页 / HTML（403、404、限流提示）当图片返回 → 非图片，直接拒绝；</li>
     *   <li>上游声明的 Content-Type 与真实格式不符 → 以真实格式落盘，避免浏览器按错误类型解析。</li>
     * </ul>
     *
     * @param originalName 展示用的原始文件名（后缀会被真实格式覆写）
     * @return 已入库的文件实体
     * @throws IOException 空数据、超限，或字节不是有效的图片
     */
    public FileEntity storeImage(byte[] data, String originalName, String uploader) throws IOException {
        return storeImage(data, originalName, uploader, MAX_BYTES);
    }

    /** {@link #storeImage(byte[], String, String)} 的「自定义上限」版本，供导入大图/大附件时使用 */
    public FileEntity storeImage(byte[] data, String originalName, String uploader, long maxBytes) throws IOException {
        if (data == null || data.length == 0) throw new IOException("空文件");
        if (maxBytes > 0 && data.length > maxBytes) {
            throw new IOException("文件过大（上限 " + (maxBytes / 1024 / 1024) + "MB）");
        }
        String ext = scanner.sniffImageExt(data);
        if (ext == null) throw new NotAnImageException("不是有效的图片数据（上游可能返回了错误页）");

        String base = (originalName == null || originalName.isBlank()) ? "image" : originalName;
        base = base.replaceAll("\\.[^.]*$", "");     // 去掉原有后缀，改用嗅探出的真实后缀
        return store(data, base + "." + ext, contentTypeOf(ext), uploader, maxBytes);
    }

    /**
     * 按文件名（扩展名）推断 Content-Type。
     *
     * <p>导入渠道把对方站点自身的**视频 / 附件**落盘时用它，避免一律落成
     * {@code application/octet-stream} —— 否则 {@code <video>} 取到 octet-stream 播不出来。
     */
    public static String guessContentType(String fileName) {
        return contentTypeOf(extOf(fileName));
    }

    private static String contentTypeOf(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "bmp" -> "image/bmp";
            case "ico" -> "image/x-icon";
            case "svg" -> "image/svg+xml";
            case "mp4", "m4v" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            case "avi" -> "video/x-msvideo";
            case "mkv" -> "video/x-matroska";
            case "mp3" -> "audio/mpeg";
            case "wav" -> "audio/wav";
            case "ogg" -> "audio/ogg";
            case "pdf" -> "application/pdf";
            case "zip" -> "application/zip";
            case "json" -> "application/json";
            case "txt", "md", "log" -> "text/plain;charset=UTF-8";
            default -> "application/octet-stream";
        };
    }

    private static String extOf(String name) {
        if (name == null) return "bin";
        int i = name.lastIndexOf('.');
        return i < 0 ? "bin" : name.substring(i + 1).toLowerCase();
    }

    private byte[] randomBytes(int n) {
        byte[] b = new byte[n];
        new SecureRandom().nextBytes(b);
        return b;
    }
}
