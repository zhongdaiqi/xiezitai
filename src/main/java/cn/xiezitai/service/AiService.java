package cn.xiezitai.service;

import cn.xiezitai.entity.SysConfig;
import cn.xiezitai.repository.SysConfigRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;

/**
 * 大模型接入：管理员在后台配置 API 地址 / Key / 模型名（存 sys_configs: ai.baseUrl / ai.apiKey / ai.model）。
 * 兼容 OpenAI Chat Completions 协议；未配置时返回友好降级文案。
 */
@Service
public class AiService {

    private static final Logger log = LoggerFactory.getLogger(AiService.class);

    private final SysConfigRepository configs;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    /** 取图专用：图床/CDN 基本都会 302，必须跟随重定向，否则只会拿到一个空的重定向响应 */
    private final HttpClient imgHttp = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 下载图片的字节上限，与 MediaStoreService.MAX_BYTES 对齐 */
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    public AiService(SysConfigRepository configs) {
        this.configs = configs;
    }

    /**
     * AI 生成的图片。**只带字节、不带链接** —— 上游（ModelScope 等）返回的是第三方临时地址，
     * 随时可能被清理，落盘到本站媒体库由调用方负责。
     */
    public record GeneratedImage(byte[] data, String contentType) {
        public static final GeneratedImage EMPTY = new GeneratedImage(new byte[0], "");
        public boolean isEmpty() { return data == null || data.length == 0; }
    }

    public String get(String key, String def) {
        return configs.findById(key).map(SysConfig::getConfigValue).orElse(def);
    }

    public void set(String key, String value) {
        SysConfig c = configs.findById(key).orElseGet(() -> {
            SysConfig n = new SysConfig();
            n.setConfigKey(key);
            return n;
        });
        c.setConfigValue(value);
        configs.save(c);
    }

    /** 文章润色 + 纠错 */
    public String polish(String text) {
        return chat("你是中文编辑，请对以下文章进行润色并纠正错别字，保持 Markdown 格式，直接输出修改后的全文：\n\n" + text);
    }

    /** 写摘要 */
    public String summarize(String text) {
        return chat("请为以下文章写一段 100 字以内的中文摘要，直接输出摘要内容：\n\n" + text);
    }

    /** 请求日志安全风险分析 */
    public String analyzeRisk(String logText) {
        return chat("你是安全分析师。以下是一个博客系统的请求日志片段，请识别可疑行为（扫描、爆破、注入、爬虫等）"
                + "并给出风险等级与处置建议：\n\n" + logText);
    }

    /**
     * 生成公众号尺寸封面图，返回**图片字节**（不是链接）。
     *
     * <p>为什么不返回链接：上游给的是第三方平台的临时地址（如 ModelScope 的 OSS 预签名 URL），
     * 过期或被清理后文章封面就变成裂图。所以这里一律把图取回内存，由调用方存进本站媒体库。
     *
     * <p>兼容两种协议：
     *  A) OpenAI 风格同步返回 → data[0].url（下载） / data[0].b64_json（解码）
     *  B) ModelScope(魔搭) 异步任务 → POST 返回 task_id，再轮询 GET /tasks/{id} 取 output_images[0]
     *     （ModelScope 的 Qwen-Image 系列走的是异步任务，必须带 X-ModelScope-Async-Mode 头）
     */
    public GeneratedImage generateCover(String prompt, int width, int height) {
        String baseUrl = get("ai.baseUrl", "");
        String apiKey = get("ai.apiKey", "");
        String imageModel = get("ai.imageModel", "");
        if (baseUrl.isBlank() || apiKey.isBlank() || imageModel.isBlank()) {
            log.info("AI 未配置，跳过封面生成");
            return GeneratedImage.EMPTY;
        }
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("model", imageModel);
            body.put("prompt", prompt);
            body.put("size", width + "x" + height);
            body.put("n", 1);
            HttpRequest req = HttpRequest.newBuilder(URI.create(trim(baseUrl) + "/images/generations"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .header("X-ModelScope-Async-Mode", "true")
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = mapper.readTree(resp.body());

            // A) OpenAI 同步返回：优先 url；其次 base64 内联
            String url = node.path("data").path(0).path("url").asText("");
            String b64 = node.path("data").path(0).path("b64_json").asText("");
            if (url.isBlank() && !b64.isBlank()) return decodeBase64(b64);

            // C) 少数网关直接把结果塞在 output_images 里
            if (url.isBlank()) url = node.path("output_images").path(0).asText("");

            // B) ModelScope 异步任务：拿 task_id 轮询
            if (url.isBlank()) {
                String taskId = node.path("task_id").asText("");
                if (!taskId.isBlank()) url = pollImageTask(baseUrl, apiKey, taskId);
            }

            if (!url.isBlank()) return downloadImage(url);

            log.warn("封面生成未取到图片: {}", abbreviate(resp.body()));
            return GeneratedImage.EMPTY;
        } catch (Exception e) {
            log.warn("封面生成失败: {}", e.getMessage());
            return GeneratedImage.EMPTY;
        }
    }

    /** 把上游返回的图片地址取回成字节；只取内容，不保留链接 */
    private GeneratedImage downloadImage(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            log.warn("封面图片地址不是 http(s)，已忽略: {}", abbreviate(url));
            return GeneratedImage.EMPTY;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET().build();
            HttpResponse<byte[]> resp = imgHttp.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200) {
                log.warn("封面图片下载失败 HTTP {}: {}", resp.statusCode(), abbreviate(url));
                return GeneratedImage.EMPTY;
            }
            byte[] data = resp.body();
            if (data == null || data.length == 0) {
                log.warn("封面图片下载为空: {}", abbreviate(url));
                return GeneratedImage.EMPTY;
            }
            if (data.length > MAX_IMAGE_BYTES) {
                log.warn("封面图片过大（{} 字节），已放弃", data.length);
                return GeneratedImage.EMPTY;
            }
            return new GeneratedImage(data, resp.headers().firstValue("content-type").orElse(""));
        } catch (Exception e) {
            log.warn("封面图片下载异常: {}", e.getMessage());
            return GeneratedImage.EMPTY;
        }
    }

    /** b64_json 可能是裸 base64，也可能带 data:image/png;base64, 前缀 */
    private GeneratedImage decodeBase64(String b64) {
        try {
            String raw = b64.trim();
            int comma = raw.indexOf(',');
            if (raw.startsWith("data:") && comma > 0) raw = raw.substring(comma + 1);
            // 用 MIME 解码器：上游常带换行/空白，严格解码器会直接抛异常
            byte[] data = Base64.getMimeDecoder().decode(raw);
            return data.length == 0 ? GeneratedImage.EMPTY : new GeneratedImage(data, "");
        } catch (Exception e) {
            log.warn("封面 base64 解码失败: {}", e.getMessage());
            return GeneratedImage.EMPTY;
        }
    }

    /** 轮询 ModelScope 异步任务直到出图/失败/超时（上限约 110s） */
    private String pollImageTask(String baseUrl, String apiKey, String taskId) throws InterruptedException {
        String endpoint = trim(baseUrl) + "/tasks/" + taskId;
        for (int i = 0; i < 36; i++) {
            try {
                HttpRequest req = HttpRequest.newBuilder(URI.create(endpoint))
                        .header("Authorization", "Bearer " + apiKey)
                        .header("X-ModelScope-Task-Type", "image_generation")
                        .timeout(Duration.ofSeconds(30))
                        .GET().build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                JsonNode n = mapper.readTree(resp.body());
                String status = n.path("task_status").asText("");
                if ("SUCCEED".equalsIgnoreCase(status)) {
                    String url = n.path("output_images").path(0).asText("");
                    if (url.isBlank()) url = n.path("outputs").path(0).path("url").asText("");
                    return url;
                }
                if ("FAILED".equalsIgnoreCase(status)) {
                    log.warn("封面任务失败: {}", abbreviate(resp.body()));
                    return "";
                }
            } catch (Exception e) {
                log.warn("封面任务轮询异常: {}", e.getMessage());
            }
            Thread.sleep(3000);
        }
        log.warn("封面任务超时: {}", taskId);
        return "";
    }

    private String abbreviate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }

    /** OpenAI 兼容 Chat 调用；未配置返回提示 */
    public String chat(String userPrompt) {
        String baseUrl = get("ai.baseUrl", "");
        String apiKey = get("ai.apiKey", "");
        String model = get("ai.model", "");
        if (baseUrl.isBlank() || apiKey.isBlank() || model.isBlank()) {
            return "（AI 功能未启用：请在后台配置大模型接口地址、API Key 与模型名）";
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", new Object[]{Map.of("role", "user", "content", userPrompt)},
                    "temperature", 0.7);
            HttpRequest req = HttpRequest.newBuilder(URI.create(trim(baseUrl) + "/chat/completions"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = mapper.readTree(resp.body());
            return node.path("choices").path(0).path("message").path("content").asText("（模型无返回）");
        } catch (Exception e) {
            log.warn("AI 调用失败: {}", e.getMessage());
            return "（AI 调用失败：" + e.getMessage() + "）";
        }
    }

    private String trim(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
