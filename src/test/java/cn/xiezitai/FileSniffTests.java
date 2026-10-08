package cn.xiezitai;

import cn.xiezitai.service.FileScanService;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 文件头魔数嗅探的边界用例。
 *
 * <p>回归背景：导入渠道（写字台 / WordPress / 博客园）会把对方站点自身的文件走
 * {@code storeImage → sniffImageExt} 落盘。旧实现的 ico 判定只要求「前两字节为 0」，
 * 而 mp4/mov 的 ftyp box 恰好是 {@code 00 00 00 20 'ftyp'} —— 于是**视频被当成 ico 图片**
 * 存成 {@code image/x-icon}（实际在跨站导入实测中抓到：15MB 的 mp4 落成 .ico）。
 */
class FileSniffTests {

    /** sniffImageExt 不依赖仓储，这里传 null 即可 */
    private final FileScanService scanner = new FileScanService(null);

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    private static byte[] concat(byte[] head, String tail) {
        byte[] t = tail.getBytes(StandardCharsets.US_ASCII);
        byte[] out = new byte[head.length + t.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(t, 0, out, head.length, t.length);
        return out;
    }

    @Test
    void mp4AndMovAreNotImages() {
        byte[] mp4 = concat(bytes(0x00, 0x00, 0x00, 0x20), "ftypisom");
        byte[] mov = concat(bytes(0x00, 0x00, 0x00, 0x14), "ftypqt  ");
        assertThat(scanner.sniffImageExt(mp4)).as("mp4 不能被当成图片").isNull();
        assertThat(scanner.sniffImageExt(mov)).as("mov 不能被当成图片").isNull();
    }

    @Test
    void realIconsStillRecognized() {
        assertThat(scanner.sniffImageExt(bytes(0x00, 0x00, 0x01, 0x00, 0x01, 0x00))).isEqualTo("ico");
        assertThat(scanner.sniffImageExt(bytes(0x00, 0x00, 0x02, 0x00, 0x01, 0x00))).isEqualTo("ico");
    }

    @Test
    void commonImagesStillRecognized() {
        assertThat(scanner.sniffImageExt(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A))).isEqualTo("png");
        assertThat(scanner.sniffImageExt(bytes(0xFF, 0xD8, 0xFF, 0xE0))).isEqualTo("jpg");
        assertThat(scanner.sniffImageExt("GIF89a".getBytes(StandardCharsets.US_ASCII))).isEqualTo("gif");
        assertThat(scanner.sniffImageExt(bytes('B', 'M', 0x00, 0x00))).isEqualTo("bmp");
    }

    @Test
    void rubbishIsNotAnImage() {
        assertThat(scanner.sniffImageExt(new byte[]{1, 2, 3})).isNull();
    }

    @Test
    void contentTypeGuessedByExtension() {
        assertThat(scanner.sniffImageExt(new byte[0])).isNull(); // 空数据也不炸
        assertThat(cn.xiezitai.service.MediaStoreService.guessContentType("a.mp4")).isEqualTo("video/mp4");
        assertThat(cn.xiezitai.service.MediaStoreService.guessContentType("a.webm")).isEqualTo("video/webm");
        assertThat(cn.xiezitai.service.MediaStoreService.guessContentType("a.pdf")).isEqualTo("application/pdf");
        assertThat(cn.xiezitai.service.MediaStoreService.guessContentType("a.png")).isEqualTo("image/png");
        assertThat(cn.xiezitai.service.MediaStoreService.guessContentType("noext")).isEqualTo("application/octet-stream");
    }
}
