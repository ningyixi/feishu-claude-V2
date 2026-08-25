package com.lark.oapi.sample.ws.controller;

import com.lark.oapi.sample.ws.model.dto.AppConfigDTO;
import com.lark.oapi.sample.ws.model.dto.TestResultDTO;
import com.lark.oapi.sample.ws.model.entity.AppConfig;
import com.lark.oapi.sample.ws.repository.AppConfigRepository;
import com.lark.oapi.sample.ws.service.ClaudeCodeService;
import com.lark.oapi.sample.ws.service.FeishuBotService;
import com.lark.oapi.sample.ws.service.ShellService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/config")
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);

    private final AppConfigRepository configRepo;
    private final FeishuBotService feishuBotService;
    private final ClaudeCodeService claudeCodeService;
    private final ShellService shellService;

    public ConfigController(AppConfigRepository configRepo, FeishuBotService feishuBotService,
                            ClaudeCodeService claudeCodeService, ShellService shellService) {
        this.configRepo = configRepo;
        this.feishuBotService = feishuBotService;
        this.claudeCodeService = claudeCodeService;
        this.shellService = shellService;
    }

    @GetMapping
    public AppConfigDTO getConfig() {
        AppConfig config = getOrCreate();
        return AppConfigDTO.fromEntity(config, true);
    }

    @PutMapping
    public ResponseEntity<?> saveConfig(@RequestBody AppConfigDTO dto) {
        AppConfig config = getOrCreate();
        String oldAppId = config.getFeishuAppId();
        String oldSecret = config.getFeishuAppSecret();

        // 先检测飞书凭证是否变更
        String newAppId = dto.feishuAppId() != null ? dto.feishuAppId() : oldAppId;
        String newSecret = (dto.feishuAppSecret() != null && !dto.feishuAppSecret().contains("****"))
                ? dto.feishuAppSecret() : oldSecret;
        boolean feishuChanged = !newAppId.equals(oldAppId) || !newSecret.equals(oldSecret);

        // 飞书凭证变更时，先测试连通性
        if (feishuChanged && !newAppId.isBlank()) {
            TestResultDTO testResult = feishuBotService.testConnection(newAppId, newSecret);
            if (!testResult.success()) {
                return ResponseEntity.badRequest().body(
                        java.util.Map.of("error", "飞书凭证验证失败，配置未保存",
                                "detail", testResult.message() + (testResult.detail() != null ? ": " + testResult.detail() : "")));
            }
        }

        // 验证通过，保存配置
        if (dto.feishuAppId() != null) config.setFeishuAppId(dto.feishuAppId());
        if (dto.feishuAppSecret() != null && !dto.feishuAppSecret().contains("****")) {
            config.setFeishuAppSecret(dto.feishuAppSecret());
        }
        if (dto.authorizedOpenId() != null) config.setAuthorizedOpenId(dto.authorizedOpenId());
        if (dto.shellWorkingDir() != null) config.setShellWorkingDir(dto.shellWorkingDir());
        if (dto.claudeWorkingDir() != null) config.setClaudeWorkingDir(dto.claudeWorkingDir());

        configRepo.save(config);
        log.info("配置已保存");

        // 传播配置到各 Service（无需重启即可生效）
        if (config.getShellWorkingDir() != null && !config.getShellWorkingDir().isBlank()) {
            shellService.setWorkingDir(config.getShellWorkingDir());
        }
        // Claude 项目目录优先使用 claudeWorkingDir，为空时回退到 shellWorkingDir
        String claudeDir = config.getClaudeWorkingDir();
        if (claudeDir == null || claudeDir.isBlank()) {
            claudeDir = config.getShellWorkingDir();
        }
        if (claudeDir != null && !claudeDir.isBlank()) {
            claudeCodeService.setWorkingDir(claudeDir);
        }

        // 飞书凭证变更时重启 bot
        if (feishuChanged && !config.getFeishuAppId().isBlank()) {
            feishuBotService.restart();
        }

        return ResponseEntity.ok(AppConfigDTO.fromEntity(config, true));
    }

    private AppConfig getOrCreate() {
        return configRepo.findById(1L).orElseGet(() -> {
            AppConfig c = new AppConfig();
            c.setId(1L);
            return configRepo.save(c);
        });
    }
}
