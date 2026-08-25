package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.model.entity.ClaudeSession;
import com.lark.oapi.sample.ws.repository.ClaudeSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Service
public class ClaudeCodeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeService.class);
    private static final long TIMEOUT_MINUTES = 5;
    private static final int MAX_OUTPUT_CHARS = 18000;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final ClaudeSessionRepository sessionRepo;
    private volatile String workingDir = "";

    public ClaudeCodeService(ClaudeSessionRepository sessionRepo) {
        this.sessionRepo = sessionRepo;
    }

    public void setWorkingDir(String dir) {
        if (dir != null) {
            this.workingDir = dir;
            log.info("[ClaudeCode] 工作目录已设置为: {}", dir);
        }
    }

    public synchronized String chat(String openId, String message) {
        try {
            // 从 DB 查找已有的 session
            String resumeSessionId = null;
            Optional<ClaudeSession> existing = sessionRepo.findByOpenIdAndWorkingDir(openId, workingDir);
            if (existing.isPresent()) {
                resumeSessionId = existing.get().getSessionId();
                log.info("[ClaudeCode] 复用 session: {} (openId={}, dir={})", resumeSessionId, openId, workingDir);
            }

            List<String> command = buildCommand(message, resumeSessionId);
            log.info("[ClaudeCode] 执行: {}", String.join(" ", command));

            ProcessBuilder pb = new ProcessBuilder(command);
            if (!workingDir.isBlank()) {
                File dir = new File(workingDir);
                if (dir.isDirectory()) {
                    pb.directory(dir);
                    log.info("[ClaudeCode] 工作目录: {}", dir.getAbsolutePath());
                }
            }
            pb.redirectErrorStream(false);
            Process process = pb.start();

            byte[] stdoutBytes = process.getInputStream().readAllBytes();
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
            return parseResponse(stdout, openId);

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

    @Transactional
    public void newSession(String openId) {
        sessionRepo.deleteByOpenIdAndWorkingDir(openId, workingDir);
        log.info("[ClaudeCode] 已重置 session (openId={}, dir={})", openId, workingDir);
    }

    public String listSessions() {
        List<ClaudeSession> sessions = sessionRepo.findAll();
        if (sessions.isEmpty()) {
            return "📋 暂无对话 session 记录。\n发送消息到 Chat 模式开始对话。";
        }

        StringBuilder sb = new StringBuilder("📋 对话 Session 列表：\n\n");
        int index = 1;
        for (ClaudeSession s : sessions) {
            String shortId = s.getSessionId().length() > 8
                    ? s.getSessionId().substring(0, 8) + "..."
                    : s.getSessionId();
            String time = s.getUpdatedAt() != null ? s.getUpdatedAt().format(TIME_FMT) : "?";
            sb.append(String.format("%d. %s (用户:%s, 目录:%s, %s)\n",
                    index++, shortId,
                    s.getOpenId().substring(0, Math.min(8, s.getOpenId().length())) + "...",
                    s.getWorkingDir().isBlank() ? "默认" : new File(s.getWorkingDir()).getName(),
                    time));
        }
        sb.append("\n共 ").append(sessions.size()).append(" 个 session");
        return sb.toString();
    }

    // ========== 内部方法 ==========

    private List<String> buildCommand(String message, String resumeSessionId) {
        List<String> cmd = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            cmd.add("cmd.exe");
            cmd.add("/c");
        }
        cmd.add("claude");
        cmd.add("-p");
        cmd.add(message);
        cmd.add("--output-format");
        cmd.add("json");
        cmd.add("--bare");

        if (resumeSessionId != null) {
            cmd.add("--resume");
            cmd.add(resumeSessionId);
        }

        return cmd;
    }

    @Transactional
    protected String parseResponse(String stdout, String openId) {
        if (stdout.isBlank()) {
            return "Claude Code 返回了空响应。";
        }

        try {
            JsonObject json = JsonParser.parseString(stdout).getAsJsonObject();

            if (json.has("is_error") && json.get("is_error").getAsBoolean()) {
                String error = json.has("result") ? json.get("result").getAsString() : "unknown error";
                log.error("[ClaudeCode] API 错误: {}", error);
                return "❌ Claude Code 返回错误: " + error;
            }

            // 提取并持久化 session_id
            if (json.has("session_id")) {
                String sessionId = json.get("session_id").getAsString();
                saveSession(openId, sessionId);
            }

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

    private void saveSession(String openId, String sessionId) {
        Optional<ClaudeSession> existing = sessionRepo.findByOpenIdAndWorkingDir(openId, workingDir);
        if (existing.isPresent()) {
            ClaudeSession session = existing.get();
            session.setSessionId(sessionId);
            session.setUpdatedAt(LocalDateTime.now());
            sessionRepo.save(session);
            log.info("[ClaudeCode] 更新 session: {} (openId={})", sessionId, openId);
        } else {
            ClaudeSession session = new ClaudeSession();
            session.setOpenId(openId);
            session.setWorkingDir(workingDir);
            session.setSessionId(sessionId);
            session.setUpdatedAt(LocalDateTime.now());
            sessionRepo.save(session);
            log.info("[ClaudeCode] 新建 session: {} (openId={}, dir={})", sessionId, openId, workingDir);
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
