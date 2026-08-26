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

    private boolean enabled = true;

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

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
