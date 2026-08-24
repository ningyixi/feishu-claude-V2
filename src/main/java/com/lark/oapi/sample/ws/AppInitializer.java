package com.lark.oapi.sample.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 应用启动后的初始化：
 * - 连接 SSE 日志 Appender 到 LogService
 * - 从数据库加载配置到各 Service
 */
@Component
public class AppInitializer {

    private static final Logger log = LoggerFactory.getLogger(AppInitializer.class);

    private final LogService logService;
    private final AppConfigRepository configRepo;
    private final ShellService shellService;
    private final ClaudeCodeService claudeCodeService;

    public AppInitializer(LogService logService, AppConfigRepository configRepo,
                          ShellService shellService, ClaudeCodeService claudeCodeService) {
        this.logService = logService;
        this.configRepo = configRepo;
        this.shellService = shellService;
        this.claudeCodeService = claudeCodeService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        // 连接日志 Appender
        SseLogAppender.setLogService(logService);
        log.info("SSE 日志 Appender 已连接");

        // 加载配置到各 Service
        configRepo.findById(1L).ifPresent(config -> {
            if (config.getShellWorkingDir() != null && !config.getShellWorkingDir().isBlank()) {
                shellService.setWorkingDir(config.getShellWorkingDir());
            }
            if (config.getClaudeCmdPath() != null && !config.getClaudeCmdPath().isBlank()) {
                claudeCodeService.setClaudeCmdPath(config.getClaudeCmdPath());
            }
            if (config.getClaudeWorkingDir() != null && !config.getClaudeWorkingDir().isBlank()) {
                claudeCodeService.setWorkingDir(config.getClaudeWorkingDir());
            }
            log.info("配置已加载到各服务");
        });
    }
}
