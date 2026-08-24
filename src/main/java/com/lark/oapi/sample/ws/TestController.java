package com.lark.oapi.sample.ws;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/test")
public class TestController {

    private static final Logger log = LoggerFactory.getLogger(TestController.class);

    private final FeishuBotService feishuBotService;
    private final AppConfigRepository configRepo;

    public TestController(FeishuBotService feishuBotService, AppConfigRepository configRepo) {
        this.feishuBotService = feishuBotService;
        this.configRepo = configRepo;
    }

    @PostMapping("/feishu")
    public TestResultDTO testFeishu() {
        AppConfig config = configRepo.findById(1L).orElse(null);
        if (config == null || config.getFeishuAppId().isBlank()) {
            return TestResultDTO.fail("飞书凭证未配置");
        }
        return feishuBotService.testConnection(config.getFeishuAppId(), config.getFeishuAppSecret());
    }

    @PostMapping("/claude")
    public TestResultDTO testClaude() {
        AppConfig config = configRepo.findById(1L).orElse(null);
        String cmdPath = config != null && !config.getClaudeCmdPath().isBlank()
                ? config.getClaudeCmdPath() : "claude";

        // 验证 Claude 工作目录是否存在
        String workDir = config != null ? config.getClaudeWorkingDir() : "";
        if (workDir != null && !workDir.isBlank()) {
            Path dirPath = Path.of(workDir);
            if (!Files.exists(dirPath)) {
                return TestResultDTO.fail("Claude 工作目录不存在", workDir);
            }
            if (!Files.isDirectory(dirPath)) {
                return TestResultDTO.fail("Claude 工作目录不是有效的目录", workDir);
            }
        }

        try {
            List<String> command = new ArrayList<>();
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                command.add("cmd.exe");
                command.add("/c");
            }
            command.add(cmdPath);
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
            process.getErrorStream().readAllBytes(); // drain

            boolean finished = process.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return TestResultDTO.fail("Claude Code 超时 (60s)");
            }

            String output = new String(stdout, StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(output).getAsJsonObject();
            if (json.has("is_error") && json.get("is_error").getAsBoolean()) {
                return TestResultDTO.fail("Claude Code 返回错误",
                        json.has("result") ? json.get("result").getAsString() : "unknown");
            }

            String result = json.has("result") ? json.get("result").getAsString() : "(empty)";
            return TestResultDTO.ok("Claude Code 连接正常", result);

        } catch (IOException e) {
            return TestResultDTO.fail("无法启动 Claude Code: " + e.getMessage());
        } catch (Exception e) {
            return TestResultDTO.fail("测试异常: " + e.getMessage());
        }
    }
}
