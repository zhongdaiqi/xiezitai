package cn.xiezitai.service;

import cn.xiezitai.repository.SysConfigRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import cn.xiezitai.entity.SysConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * 企业微信机器人通知。
 * 管理员可配置 webhook 地址，并按事件类型（login/article/visit/register/comment/upload）开关。
 * 配置项存于 sys_configs：wecom.webhook / notify.enabled / notify.login / notify.article ...
 */
@Service
public class NotifyService {

    private static final Logger log = LoggerFactory.getLogger(NotifyService.class);
    private static final String DEFAULT_WEBHOOK =
            "https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=a6c868f6-d7d9-47b2-99ca-b9fbee6b6ccb";

    private final SysConfigRepository configs;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public NotifyService(SysConfigRepository configs) {
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

    /** 事件通知：event 为 login / article / visit / register / comment / upload */
    public void notifyEvent(String event, String markdown) {
        if (!"true".equals(get("notify.enabled", "true"))) return;
        if (!"true".equals(get("notify." + event, "true"))) return;
        String webhook = get("wecom.webhook", DEFAULT_WEBHOOK);
        if (webhook == null || webhook.isBlank()) return;
        try {
            String content = com.fasterxml.jackson.databind.node.TextNode.valueOf(markdown).toString();
            String body = "{\"msgtype\":\"markdown\",\"markdown\":{\"content\":" + content + "}}";
            HttpRequest req = HttpRequest.newBuilder(URI.create(webhook))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            http.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                    .whenComplete((r, e) -> {
                        if (e != null) log.warn("机器人通知失败: {}", e.getMessage());
                    });
        } catch (Exception e) {
            log.warn("机器人通知构造失败: {}", e.getMessage());
        }
    }
}
