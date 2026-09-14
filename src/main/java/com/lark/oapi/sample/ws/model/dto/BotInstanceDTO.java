package com.lark.oapi.sample.ws.model.dto;

import com.lark.oapi.sample.ws.model.entity.BotInstance;

public record BotInstanceDTO(
    Long id,
    String name,
    String feishuAppId,
    String feishuAppSecret,
    String authorizedOpenId,
    String shellWorkingDir,
    String claudeWorkingDir,
    String claudeModel,
    boolean enabled,
    int sandboxLevel,
    String createdAt,
    String updatedAt
) {
    public static BotInstanceDTO fromEntity(BotInstance e, boolean maskSecret) {
        String secret = e.getFeishuAppSecret();
        if (maskSecret && secret != null && secret.length() > 4) {
            secret = secret.substring(0, 4) + "****";
        }
        return new BotInstanceDTO(
            e.getId(),
            e.getName(),
            e.getFeishuAppId(),
            secret,
            e.getAuthorizedOpenId(),
            e.getShellWorkingDir(),
            e.getClaudeWorkingDir(),
            e.getClaudeModel(),
            e.isEnabled(),
            e.getSandboxLevel(),
            e.getCreatedAt() != null ? e.getCreatedAt().toString() : "",
            e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : ""
        );
    }
}
