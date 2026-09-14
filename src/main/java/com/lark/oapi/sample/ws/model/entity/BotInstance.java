package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "bot_instance")
public class BotInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name = "";

    @Column(name = "feishu_app_id")
    private String feishuAppId = "";

    @Column(name = "feishu_app_secret")
    private String feishuAppSecret = "";

    @Column(name = "authorized_open_id")
    private String authorizedOpenId = "";

    @Column(name = "shell_working_dir")
    private String shellWorkingDir = "";

    @Column(name = "claude_working_dir")
    private String claudeWorkingDir = "";

    @Column(name = "claude_model")
    private String claudeModel = "";

    private boolean enabled = true;

    /**
     * 沙箱档位（见 SandboxPolicy）：0=🔓全权限(bypassPermissions,默认) | 1=🛡宽松策略沙箱 | 2=🔒严格策略沙箱。
     * 实例被数字员工绑定时自动切到 2，解绑自动回 0（仅 0↔2 自动切换，手动设置的 1 不动）。
     * <p>
     * 用包装类型：ddl-auto=update 给已有数据的表加列时存量行为 NULL，
     * 原始 int 会让 Hibernate 装载旧行直接崩（"Can not set int field to null value"）；getter 归一为 0。
     */
    @Column(name = "sandbox_level")
    private Integer sandboxLevel = 0;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public BotInstance() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getFeishuAppId() { return feishuAppId; }
    public void setFeishuAppId(String feishuAppId) { this.feishuAppId = feishuAppId; }

    public String getFeishuAppSecret() { return feishuAppSecret; }
    public void setFeishuAppSecret(String feishuAppSecret) { this.feishuAppSecret = feishuAppSecret; }

    public String getAuthorizedOpenId() { return authorizedOpenId; }
    public void setAuthorizedOpenId(String authorizedOpenId) { this.authorizedOpenId = authorizedOpenId; }

    public String getShellWorkingDir() { return shellWorkingDir; }
    public void setShellWorkingDir(String shellWorkingDir) { this.shellWorkingDir = shellWorkingDir; }

    public String getClaudeWorkingDir() { return claudeWorkingDir; }
    public void setClaudeWorkingDir(String claudeWorkingDir) { this.claudeWorkingDir = claudeWorkingDir; }

    public String getClaudeModel() { return claudeModel; }
    public void setClaudeModel(String claudeModel) { this.claudeModel = claudeModel; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    /** 空值归一：存量行 sandbox_level 为 NULL 时按 0（全权限，与升级前行为一致） */
    public int getSandboxLevel() { return sandboxLevel != null ? sandboxLevel : 0; }
    public void setSandboxLevel(int sandboxLevel) { this.sandboxLevel = sandboxLevel; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
