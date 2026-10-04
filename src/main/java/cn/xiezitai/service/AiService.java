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

    public AiService(SysConfigRepository configs) {
        this.configs = configs;
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

    /** 生成公众号尺寸封面图，返回图片 URL */
    public String generateCover(String prompt, int width, int height) {
        String baseUrl = get("ai.baseUrl", "");
        String apiKey = get("ai.apiKey", "");
        String imageModel = get("ai.imageModel", "");
        if (baseUrl.isBlank() || apiKey.isBlank() || imageModel.isBlank()) {
            log.info("AI 未配置，跳过封面生成");
            return "";
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", imageModel,
                    "prompt", prompt,
                    "size", width + "x" + height,
                    "n", 1);
            HttpRequest req = HttpRequest.newBuilder(URI.create(trim(baseUrl) + "/images/generations"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(120))
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = mapper.readTree(resp.body());
            String url = node.path("data").path(0).path("url").asText("");
            if (url.isBlank()) {
                // 兼容返回 b64_json 的情况
                String b64 = node.path("data").path(0).path("b64_json").asText("");
                if (!b64.isBlank()) return "data:image/png;base64," + b64;
            }
            return url;
        } catch (Exception e) {
            log.warn("封面生成失败: {}", e.getMessage());
            return "";
        }
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
