package cn.xiezitai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 全量请求日志（安全审计 + AI 风险分析数据源） */
@Entity
@Table(name = "request_logs", indexes = {
        @Index(name = "idx_log_created", columnList = "createdAt"),
        @Index(name = "idx_log_ip", columnList = "ip")
})
public class RequestLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String method;
    private String uri;
    private String ip;
    @Column(length = 300)
    private String userAgent;
    @Column(length = 100)
    private String username;
    private int status;
    private long durationMs;
    private LocalDateTime createdAt = LocalDateTime.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }
    public String getUri() { return uri; }
    public void setUri(String uri) { this.uri = uri; }
    public String getIp() { return ip; }
    public void setIp(String ip) { this.ip = ip; }
    public String getUserAgent() { return userAgent; }
    public void setUserAgent(String userAgent) { this.userAgent = userAgent; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }
    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
