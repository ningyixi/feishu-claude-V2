package com.lark.oapi.sample.ws;

public record AppConfigDTO(
    String feishuAppId,
    String feishuAppSecret,
    String authorizedOpenId,
    String shellWorkingDir,
    String claudeCmdPath,
    String claudeWorkingDir
) {
    public static AppConfigDTO fromEntity(AppConfig entity, boolean maskSecret) {
        String secret = entity.getFeishuAppSecret();
        if (maskSecret && secret != null && secret.length() > 4) {
            secret = secret.substring(0, 4) + "****";
        }
        return new AppConfigDTO(
            entity.getFeishuAppId(),
            secret,
            entity.getAuthorizedOpenId(),
            entity.getShellWorkingDir(),
            entity.getClaudeCmdPath(),
            entity.getClaudeWorkingDir()
        );
    }
}
