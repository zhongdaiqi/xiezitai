package cn.xiezitai.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 系统配置（机器人 webhook、通知开关、AI 配置等），管理员可在线修改 */
@Entity
@Table(name = "sys_configs")
public class SysConfig {
    @Id
    @Column(length = 100)
    private String configKey;

    @Column(length = 2000)
    private String configValue;

    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() { this.updatedAt = LocalDateTime.now(); }

    public String getConfigKey() { return configKey; }
    public void setConfigKey(String configKey) { this.configKey = configKey; }
    public String getConfigValue() { return configValue; }
    public void setConfigValue(String configValue) { this.configValue = configValue; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
