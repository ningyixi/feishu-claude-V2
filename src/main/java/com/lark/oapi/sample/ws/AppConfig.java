package com.lark.oapi.sample.ws;

import jakarta.persistence.*;

@Entity
@Table(name = "app_config")
public class AppConfig {

    @Id
    private Long id = 1L;

    private String feishuAppId = "";
    private String feishuAppSecret = "";
    private String authorizedOpenId = "";
    private String shellWorkingDir = "";
    private String claudeCmdPath = "claude";
    private String claudeWorkingDir = "";

    public AppConfig() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getFeishuAppId() { return feishuAppId; }
    public void setFeishuAppId(String feishuAppId) { this.feishuAppId = feishuAppId; }

    public String getFeishuAppSecret() { return feishuAppSecret; }
    public void setFeishuAppSecret(String feishuAppSecret) { this.feishuAppSecret = feishuAppSecret; }

    public String getAuthorizedOpenId() { return authorizedOpenId; }
    public void setAuthorizedOpenId(String authorizedOpenId) { this.authorizedOpenId = authorizedOpenId; }

    public String getShellWorkingDir() { return shellWorkingDir; }
    public void setShellWorkingDir(String shellWorkingDir) { this.shellWorkingDir = shellWorkingDir; }

    public String getClaudeCmdPath() { return claudeCmdPath; }
    public void setClaudeCmdPath(String claudeCmdPath) { this.claudeCmdPath = claudeCmdPath; }

    public String getClaudeWorkingDir() { return claudeWorkingDir; }
    public void setClaudeWorkingDir(String claudeWorkingDir) { this.claudeWorkingDir = claudeWorkingDir; }
}
