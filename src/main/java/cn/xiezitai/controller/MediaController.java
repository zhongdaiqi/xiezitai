package cn.xiezitai.controller;

import cn.xiezitai.entity.FileEntity;
import cn.xiezitai.entity.RequestLog;
import cn.xiezitai.repository.FileRepository;
import cn.xiezitai.repository.RequestLogRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 媒体输出统一走 Spring：所有媒体访问先记录访问日志（含来源），再对外输出文件。
 * 危险文件（DANGEROUS）不对外提供。
 * <p>
 * 支持 HTTP Range（206 Partial Content）：视频/<audio> 需要按需拉流与拖动进度条，
 * 只返回 200 全量体会导致浏览器无法 seek（甚至拒绝播放大文件）。
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
    public ResponseEntity<?> serve(@PathVariable String storedName,
                                   @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader,
                                   HttpServletRequest request) throws Exception {
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
        if (!target.startsWith(uploadDir) || !Files.exists(target)) {
            return ResponseEntity.notFound().build();
        }
        MediaType mt = fe.getContentType() != null
                ? MediaType.parseMediaType(fe.getContentType()) : MediaType.APPLICATION_OCTET_STREAM;
        Resource resource = new FileSystemResource(target);
        long length = resource.contentLength();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(mt);
        headers.set(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + storedName + "\"");
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.setCacheControl("public, max-age=604800");

        if (rangeHeader == null || length <= 0) {
            return new ResponseEntity<>(resource, headers, HttpStatus.OK);
        }
        List<HttpRange> ranges;
        try {
            ranges = HttpRange.parseRanges(rangeHeader);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).build();
        }
        if (ranges.isEmpty()) {
            return new ResponseEntity<>(resource, headers, HttpStatus.OK);
        }
        // 只服务第一个区间（浏览器播放器不会一次要多段）
        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(length);
        long end = range.getRangeEnd(length);
        if (start >= length || start > end) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                    .header(HttpHeaders.CONTENT_RANGE, "bytes */" + length).build();
        }
        long count = end - start + 1;
        headers.set(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
        // 注意：这里刻意不用 Spring 的 ResourceRegion —— 它要求未预设 Content-Type，
        // 而我们希望响应带真实 MIME（video/mp4 等），且 ResourceRegionHttpMessageConverter
        // 在“预设 Content-Type”场景下会报 No converter 而 500。
        // 改为返回“限定区间的 Resource”，走已验证可用的 ResourceHttpMessageConverter，流式输出不占内存。
        return new ResponseEntity<>(new RangedFileResource(target, start, count), headers, HttpStatus.PARTIAL_CONTENT);
    }

    /** 只对外暴露文件 [position, position+count) 区间的 Resource，contentLength 即区间长度 */
    private static final class RangedFileResource extends FileSystemResource {

        private final long position;
        private final long count;

        RangedFileResource(Path path, long position, long count) {
            super(path);
            this.position = position;
            this.count = count;
        }

        @Override
        public long contentLength() {
            return count;
        }

        @Override
        public InputStream getInputStream() throws IOException {
            InputStream in = super.getInputStream();
            if (position > 0) {
                in.skipNBytes(position);
            }
            return new BoundedInputStream(in, count);
        }
    }

    /** 读取上限为 limit 字节的输入流包装 */
    private static final class BoundedInputStream extends FilterInputStream {

        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) return -1;
            int b = super.read();
            if (b >= 0) remaining--;
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) return -1;
            int n = super.read(b, off, (int) Math.min(len, remaining));
            if (n > 0) remaining -= n;
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            long skipped = super.skip(Math.min(n, remaining));
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(super.available(), remaining);
        }
    }
}
