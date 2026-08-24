package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.io.File;
import org.springframework.stereotype.Service;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Claude Code CLI 服务
 * <p>
 * 通过调用本地 claude CLI（claude -p）实现对话，
 * 利用 Claude Code 自带的完整对话历史、工具调用能力。
 * 通过 --resume 实现多轮对话续接。
 */
@Service
public class ClaudeCodeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeService.class);
    private static final long TIMEOUT_MINUTES = 5;
    private static final int MAX_OUTPUT_CHARS = 18000;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    /** 当前活跃的 session_id */
    private volatile String currentSessionId = null;

    /** session 历史记录: sessionId -> 创建时间 */
    private final Map<String, String> sessionHistory = new LinkedHashMap<>();

    private volatile String claudeCmdPath = "claude";
    private volatile String workingDir = "";

    public void setClaudeCmdPath(String path) {
        if (path != null && !path.isBlank()) {
            this.claudeCmdPath = path;
        }
    }

    public void setWorkingDir(String dir) {
        if (dir != null) {
            this.workingDir = dir;
        }
    }

    /**
     * 与 Claude Code 对话
     *
     * @param openId  飞书用户 openId
     * @param message 用户消息
     * @return Claude 的回复文本
     */
    public synchronized String chat(String openId, String message) {
        try {
            List<String> command = buildCommand(message);
            log.info("[ClaudeCode] 执行: {}", String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            if (!workingDir.isBlank()) {
                File dir = new File(workingDir);
                if (dir.isDirectory()) {
                    pb.directory(dir);
                }
            }
            pb.redirectErrorStream(false);
            Process process = pb.start();

            // 读取 stdout
            byte[] stdoutBytes = process.getInputStream().readAllBytes();
            // 读取 stderr（丢弃，但防止阻塞）
            byte[] stderrBytes = process.getErrorStream().readAllBytes();

            boolean finished = process.waitFor(TIMEOUT_MINUTES, TimeUnit.MINUTES);

            if (!finished) {
                process.destroyForcibly();
                log.warn("[ClaudeCode] 命令超时 ({}min)", TIMEOUT_MINUTES);
                return "⏰ Claude Code 执行超时 (" + TIMEOUT_MINUTES + " 分钟)，请简化问题或重试。";
            }

            String stderr = new String(stderrBytes, StandardCharsets.UTF_8);
            if (!stderr.isBlank()) {
                log.debug("[ClaudeCode] stderr: {}", stderr.strip());
            }

            String stdout = new String(stdoutBytes, StandardCharsets.UTF_8);
            return parseResponse(stdout);

        } catch (IOException e) {
            log.error("[ClaudeCode] 启动失败", e);
            return "❌ 无法启动 Claude Code，请确认 claude 命令已安装并在 PATH 中。\n错误: " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "❌ 执行被中断。";
        } catch (Exception e) {
            log.error("[ClaudeCode] 执行异常", e);
            return "❌ Claude Code 执行出错: " + e.getMessage();
        }
    }

    /**
     * 开启新 session（下次对话将创建新 session）
     */
    public void newSession() {
        currentSessionId = null;
        log.info("[ClaudeCode] 已重置为新建 session");
    }

    /**
     * 获取 session 列表（格式化文本）
     */
    public String listSessions() {
        if (sessionHistory.isEmpty()) {
            return "📋 暂无对话 session 记录。\n发送消息到 Chat 模式开始对话。";
        }

        StringBuilder sb = new StringBuilder("📋 对话 Session 列表：\n\n");
        int index = 1;
        for (Map.Entry<String, String> entry : sessionHistory.entrySet()) {
            String id = entry.getKey();
            String time = entry.getValue();
            boolean isCurrent = id.equals(currentSessionId);
            String marker = isCurrent ? " ◀ 当前" : "";
            String shortId = id.length() > 8 ? id.substring(0, 8) + "..." : id;
            sb.append(String.format("%d. %s (%s)%s\n", index++, shortId, time, marker));
        }
        sb.append("\n共 ").append(sessionHistory.size()).append(" 个 session");
        return sb.toString();
    }

    /**
     * 获取当前 session ID
     */
    public String getCurrentSessionId() {
        return currentSessionId;
    }

    // ========== 内部方法 ==========

    private List<String> buildCommand(String message) {
        List<String> cmd = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            cmd.add("cmd.exe");
            cmd.add("/c");
        }
        cmd.add(claudeCmdPath);
        cmd.add("-p");
        cmd.add(message);
        cmd.add("--output-format");
        cmd.add("json");
        cmd.add("--bare");

        if (currentSessionId != null) {
            cmd.add("--resume");
            cmd.add(currentSessionId);
        }

        return cmd;
    }

    private String parseResponse(String stdout) {
        if (stdout.isBlank()) {
            return "Claude Code 返回了空响应。";
        }

        try {
            JsonObject json = JsonParser.parseString(stdout).getAsJsonObject();

            // 检查错误
            if (json.has("is_error") && json.get("is_error").getAsBoolean()) {
                String error = json.has("result") ? json.get("result").getAsString() : "unknown error";
                log.error("[ClaudeCode] API 错误: {}", error);
                return "❌ Claude Code 返回错误: " + error;
            }

            // 提取 session_id
            if (json.has("session_id")) {
                String sessionId = json.get("session_id").getAsString();
                if (currentSessionId == null) {
                    // 新 session，记录创建时间
                    sessionHistory.put(sessionId, LocalDateTime.now().format(TIME_FMT));
                    log.info("[ClaudeCode] 新 session 创建: {}", sessionId);
                }
                currentSessionId = sessionId;
            }

            // 提取回复文本
            if (json.has("result")) {
                String result = json.get("result").getAsString();
                return truncate(result.isBlank() ? "(Claude 返回了空回复)" : result);
            }

            log.warn("[ClaudeCode] 响应中没有 result 字段: {}", stdout.substring(0, Math.min(200, stdout.length())));
            return "Claude Code 响应格式异常，请查看日志。";

        } catch (Exception e) {
            log.warn("[ClaudeCode] 解析 JSON 失败，返回原始输出", e);
            return truncate(stdout);
        }
    }

    private String truncate(String text) {
        if (text.length() <= MAX_OUTPUT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_OUTPUT_CHARS)
                + "\n\n... (输出已截断，共 " + text.length() + " 字符)";
    }
}
