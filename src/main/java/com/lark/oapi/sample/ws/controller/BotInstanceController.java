package com.lark.oapi.sample.ws.controller;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.Client;
import com.lark.oapi.sample.ws.model.dto.BotInstanceDTO;
import com.lark.oapi.sample.ws.model.dto.InstanceStatusDTO;
import com.lark.oapi.sample.ws.model.dto.TestResultDTO;
import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.model.entity.ChatMessage;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.ChatMessageRepository;
import com.lark.oapi.sample.ws.service.FeishuBotService;
import com.lark.oapi.sample.ws.service.InstanceStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/instances")
public class BotInstanceController {

    private static final Logger log = LoggerFactory.getLogger(BotInstanceController.class);

    private final BotInstanceRepository instanceRepo;
    private final ChatMessageRepository chatMessageRepo;
    private final FeishuBotService feishuBotService;
    private final InstanceStatusService instanceStatusService;

    public BotInstanceController(BotInstanceRepository instanceRepo,
                                 ChatMessageRepository chatMessageRepo,
                                 FeishuBotService feishuBotService,
                                 InstanceStatusService instanceStatusService) {
        this.instanceRepo = instanceRepo;
        this.chatMessageRepo = chatMessageRepo;
        this.feishuBotService = feishuBotService;
        this.instanceStatusService = instanceStatusService;
    }

    /** 全部实例的实时运行状态（前端 4s 轮询，趣味徽章） */
    @GetMapping("/status")
    public List<InstanceStatusDTO> statuses() {
        return instanceStatusService.resolveAll();
    }

    @GetMapping
    public List<BotInstanceDTO> list() {
        return instanceRepo.findAll().stream()
                .map(e -> BotInstanceDTO.fromEntity(e, true))
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}")
    public ResponseEntity<BotInstanceDTO> get(@PathVariable Long id) {
        return instanceRepo.findById(id)
                .map(e -> ResponseEntity.ok(BotInstanceDTO.fromEntity(e, true)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public BotInstanceDTO create(@RequestBody BotInstanceDTO dto) {
        BotInstance instance = new BotInstance();
        applyDto(instance, dto);
        instanceRepo.save(instance);
        log.info("[Instance] 创建: id={}, name={}", instance.getId(), instance.getName());
        // 创建后自动连接飞书
        if (instance.isEnabled()) {
            feishuBotService.connectInstance(instance);
        }
        return BotInstanceDTO.fromEntity(instance, true);
    }

    @PutMapping("/{id}")
    public ResponseEntity<BotInstanceDTO> update(@PathVariable Long id, @RequestBody BotInstanceDTO dto) {
        return instanceRepo.findById(id).map(instance -> {
            applyDto(instance, dto);
            instanceRepo.save(instance);
            log.info("[Instance] 更新: id={}, name={}", id, instance.getName());
            // 更新后重新连接飞书
            if (instance.isEnabled()) {
                feishuBotService.connectInstance(instance);
            }
            return ResponseEntity.ok(BotInstanceDTO.fromEntity(instance, true));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        if (instanceRepo.existsById(id)) {
            instanceRepo.deleteById(id);
            log.info("[Instance] 删除: id={}", id);
            return ResponseEntity.ok().build();
        }
        return ResponseEntity.notFound().build();
    }

    // ===== 测试连接 =====

    @PostMapping("/{id}/test-feishu")
    public ResponseEntity<TestResultDTO> testFeishu(@PathVariable Long id) {
        return instanceRepo.findById(id).map(instance -> {
            if (instance.getFeishuAppId().isBlank()) {
                return ResponseEntity.ok(TestResultDTO.fail("飞书 APP ID 未配置"));
            }
            try {
                Client testClient = Client.newBuilder(instance.getFeishuAppId(), instance.getFeishuAppSecret()).build();
                testClient.im().message().list(com.lark.oapi.service.im.v1.model.ListMessageReq.newBuilder()
                        .containerIdType("chat")
                        .containerId("oc_test")
                        .pageSize(1)
                        .build());
                return ResponseEntity.ok(TestResultDTO.ok("飞书连接成功", "APP ID: " + instance.getFeishuAppId()));
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                if (msg.contains("invalid") || msg.contains("app_id") || msg.contains("app_secret")) {
                    return ResponseEntity.ok(TestResultDTO.fail("飞书凭证无效", msg));
                }
                return ResponseEntity.ok(TestResultDTO.ok("飞书凭证有效（业务错误，鉴权通过）", msg));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/test-claude")
    public ResponseEntity<TestResultDTO> testClaude(@PathVariable Long id) {
        return instanceRepo.findById(id).map(instance -> {
            String workDir = instance.getClaudeWorkingDir();
            if (workDir == null || workDir.isBlank()) {
                workDir = instance.getShellWorkingDir();
            }
            // 验证目录
            if (workDir != null && !workDir.isBlank()) {
                Path dirPath = Path.of(workDir);
                if (!Files.exists(dirPath)) {
                    return ResponseEntity.ok(TestResultDTO.fail("Claude 项目目录不存在", workDir));
                }
                if (!Files.isDirectory(dirPath)) {
                    return ResponseEntity.ok(TestResultDTO.fail("不是有效的目录", workDir));
                }
            }

            try {
                List<String> command = new ArrayList<>();
                String os = System.getProperty("os.name", "").toLowerCase();
                if (os.contains("win")) {
                    command.add("cmd.exe");
                    command.add("/c");
                }
                command.add("claude");
                command.add("-p");
                command.add("hi");
                command.add("--output-format");
                command.add("json");
                command.add("--bare");

                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(false);
                if (workDir != null && !workDir.isBlank()) {
                    pb.directory(new File(workDir));
                }
                Process process = pb.start();
                byte[] stdout = process.getInputStream().readAllBytes();
                process.getErrorStream().readAllBytes();

                boolean finished = process.waitFor(60, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return ResponseEntity.ok(TestResultDTO.fail("Claude Code 超时 (60s)"));
                }

                String output = new String(stdout, StandardCharsets.UTF_8);
                JsonObject json = JsonParser.parseString(output).getAsJsonObject();
                if (json.has("is_error") && json.get("is_error").getAsBoolean()) {
                    return ResponseEntity.ok(TestResultDTO.fail("Claude 返回错误",
                            json.has("result") ? json.get("result").getAsString() : "unknown"));
                }
                String result = json.has("result") ? json.get("result").getAsString() : "(empty)";
                return ResponseEntity.ok(TestResultDTO.ok("Claude Code 连接正常", result));

            } catch (IOException e) {
                return ResponseEntity.ok(TestResultDTO.fail("无法启动 Claude: " + e.getMessage()));
            } catch (Exception e) {
                return ResponseEntity.ok(TestResultDTO.fail("测试异常: " + e.getMessage()));
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    // ===== 对话历史 =====

    @GetMapping("/{id}/messages")
    public ResponseEntity<List<ChatMessage>> messages(@PathVariable Long id) {
        if (!instanceRepo.existsById(id)) return ResponseEntity.notFound().build();
        List<ChatMessage> msgs = chatMessageRepo.findTop100ByInstanceIdOrderByCreatedAtDesc(id);
        // 反转为时间正序
        java.util.Collections.reverse(msgs);
        return ResponseEntity.ok(msgs);
    }

    // ===== 内部方法 =====

    private void applyDto(BotInstance instance, BotInstanceDTO dto) {
        if (dto.name() != null) instance.setName(dto.name());
        if (dto.feishuAppId() != null) instance.setFeishuAppId(dto.feishuAppId());
        if (dto.feishuAppSecret() != null && !dto.feishuAppSecret().contains("****")) {
            instance.setFeishuAppSecret(dto.feishuAppSecret());
        }
        if (dto.authorizedOpenId() != null) instance.setAuthorizedOpenId(dto.authorizedOpenId());
        if (dto.shellWorkingDir() != null) instance.setShellWorkingDir(dto.shellWorkingDir());
        if (dto.claudeWorkingDir() != null) instance.setClaudeWorkingDir(dto.claudeWorkingDir());
        if (dto.claudeModel() != null) instance.setClaudeModel(dto.claudeModel());
        instance.setEnabled(dto.enabled());
    }
}
