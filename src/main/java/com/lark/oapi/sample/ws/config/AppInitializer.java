package com.lark.oapi.sample.ws.config;

import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.service.FeishuBotService;
import com.lark.oapi.sample.ws.service.LogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AppInitializer {

    private static final Logger log = LoggerFactory.getLogger(AppInitializer.class);

    private final LogService logService;
    private final BotInstanceRepository instanceRepo;
    private final FeishuBotService feishuBotService;

    public AppInitializer(LogService logService,
                          BotInstanceRepository instanceRepo,
                          FeishuBotService feishuBotService) {
        this.logService = logService;
        this.instanceRepo = instanceRepo;
        this.feishuBotService = feishuBotService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        SseLogAppender.setLogService(logService);

        // 启动时自动连接所有已启用的实例
        List<BotInstance> instances = instanceRepo.findAll();
        long enabledCount = instances.stream().filter(BotInstance::isEnabled).count();
        log.info("应用已启动，发现 {} 个实例（{} 个已启用）", instances.size(), enabledCount);

        for (BotInstance inst : instances) {
            if (inst.isEnabled() && !inst.getFeishuAppId().isBlank()) {
                feishuBotService.connectInstance(inst);
            }
        }
    }
}
