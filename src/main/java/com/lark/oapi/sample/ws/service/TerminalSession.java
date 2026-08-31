package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;

/**
 * 常驻终端会话 — 包装一个长运行的 Claude CLI 进程。
 * <p>
 * 使用 {@code --input-format stream-json --output-format stream-json} 模式，
 * 通过 stdin 发送 NDJSON 消息，通过 stdout 接收 NDJSON 事件流。
 * 进程在多次对话之间保持存活，上下文自动维护在同一进程内。
 */
public class TerminalSession {

    private static final Logger log = LoggerFactory.getLogger(TerminalSession.class);
    private static final long READ_TIMEOUT_MS = 120_000; // 每行读取超时 2 分钟
    private static final int MAX_OUTPUT_CHARS = 18000;

    private final String sessionKey;   // 会话标识（用于日志）
    private final String workingDir;
    private final String model;

    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile BufferedReader reader;
    private volatile String claudeSessionId;
    private volatile boolean alive;

    // 当前轮次的结果 Future 和累积文本
    private CompletableFuture<TurnResult> currentResult;
    private StringBuilder accumulatedText;

    private Thread readerThread;

    public TerminalSession(String sessionKey, String workingDir, String model) {
        this.sessionKey = sessionKey;
        this.workingDir = workingDir != null ? workingDir : "";
        this.model = model != null ? model : "";
    }

    // ===== 生命周期 =====

    /**
     * 启动 Claude CLI 常驻进程
     */
    public synchronized void start() throws IOException {
        if (alive && process != null && process.isAlive()) {
            return;
        }

        java.util.List<String> command = buildCommand();
        log.info("[Terminal] 启动常驻进程: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        if (!workingDir.isBlank()) {
            File dir = new File(workingDir);
            if (dir.isDirectory()) {
                pb.directory(dir);
            }
        }
        pb.redirectErrorStream(false);

        try {
            process = pb.start();
        } catch (IOException e) {
            log.error("[Terminal] 进程启动失败: command={}", command, e);
            throw e;
        }
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

        // stderr 后台读取，避免阻塞
        Thread.ofVirtual().name("stderr-" + sessionKey).start(this::readStderr);

        // stdout 事件读取线程
        readerThread = Thread.ofVirtual().name("reader-" + sessionKey).start(this::readerLoop);

        alive = true;
        log.info("[Terminal] 常驻进程已启动: key={}, pid={}", sessionKey, process.pid());
    }

    /**
     * 销毁进程，释放资源
     */
    public synchronized void destroy() {
        alive = false;
        if (currentResult != null && !currentResult.isDone()) {
            currentResult.completeExceptionally(new IOException("终端已销毁"));
        }
        if (writer != null) {
            try { writer.close(); } catch (IOException ignored) {}
        }
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        log.info("[Terminal] 常驻进程已销毁: key={}", sessionKey);
    }

    public boolean isAlive() {
        return alive && process != null && process.isAlive();
    }

    public String getClaudeSessionId() {
        return claudeSessionId;
    }

    // ===== 消息发送 =====

    /**
     * 向常驻进程发送消息并等待回复。
     * 同步方法，同一时间只处理一条消息。
     */
    public synchronized String sendMessage(String message) {
        if (!isAlive()) {
            try {
                log.info("[Terminal] 进程已死，重启中...");
                start();
            } catch (IOException e) {
                return "❌ 无法重启终端: " + e.getMessage();
            }
        }

        accumulatedText = new StringBuilder();
        currentResult = new CompletableFuture<>();

        try {
            // 构造 NDJSON 消息
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "user");
            msg.addProperty("session_id", "");
            JsonObject messageObj = new JsonObject();
            messageObj.addProperty("role", "user");
            messageObj.addProperty("content", message);
            msg.add("message", messageObj);
            msg.add("parent_tool_use_id", JsonNull.INSTANCE);

            String json = msg.toString();
            log.info("[Terminal] >> {}", json.substring(0, Math.min(200, json.length())));
            writer.write(json);
            writer.newLine();
            writer.flush();

            // 等待 result 事件（由 reader 线程完成 future）
            TurnResult result = currentResult.get(15, TimeUnit.MINUTES);
            if (result.isError) {
                return "❌ " + result.text;
            }
            String text = result.text;
            if (text.isBlank()) {
                text = accumulatedText.toString();
            }
            return truncate(text.isBlank() ? "(Claude 返回了空回复)" : text);

        } catch (TimeoutException e) {
            currentResult.cancel(true);
            return "⏰ Claude 响应超时（15分钟）";
        } catch (Exception e) {
            log.error("[Terminal] 发送消息失败", e);
            return "❌ 发送消息失败: " + e.getMessage();
        }
    }

    // ===== 内部：构建启动命令 =====

    private java.util.List<String> buildCommand() {
        java.util.List<String> cmd = new java.util.ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        String resolved = resolveCommand("claude");

        if (os.contains("win") && resolved != null
                && (resolved.endsWith(".cmd") || resolved.endsWith(".bat"))) {
            // Windows batch file 必须通过 cmd.exe /c 运行（ProcessBuilder 不能直接执行 .cmd）
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add(resolved);
            log.info("[Terminal] 使用 cmd.exe 运行: {}", resolved);
        } else {
            // 直接运行可执行文件（Linux/Mac 的 claude binary 或 Windows 的 .exe）
            cmd.add(resolved != null ? resolved : "claude");
        }

        cmd.add("-p");
        cmd.add("--input-format");
        cmd.add("stream-json");
        cmd.add("--output-format");
        cmd.add("stream-json");
        cmd.add("--verbose");
        cmd.add("--permission-mode");
        cmd.add("acceptEdits");
        if (!model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }
        return cmd;
    }

    /**
     * 在 PATH 中解析命令的完整路径。
     * Windows 上 npm 安装的 claude 是 .cmd 文件，需要找到完整路径。
     */
    private String resolveCommand(String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String path = System.getenv("PATH");
            if (path == null) return command;
            for (String dir : path.split(";")) {
                if (dir.isBlank()) continue;
                for (String ext : new String[]{".exe", ".cmd", ".bat"}) {
                    java.io.File f = new java.io.File(dir, command + ext);
                    if (f.exists()) {
                        log.info("[Terminal] 解析命令路径: {} -> {}", command, f.getAbsolutePath());
                        return f.getAbsolutePath();
                    }
                }
            }
        }
        return command;
    }

    // ===== 内部：stdout 事件读取循环 =====

    private void readerLoop() {
        try {
            while (alive && process.isAlive()) {
                String line = readLineWithTimeout(reader, READ_TIMEOUT_MS);
                if (line == null) {
                    if (!process.isAlive()) {
                        log.info("[Terminal] 进程已退出, exitCode={}", getExitCode());
                        break;
                    }
                    log.debug("[Terminal] readLine 返回 null, 进程仍存活, 继续等待");
                    continue;
                }

                line = line.trim();
                if (line.isEmpty()) continue;

                log.info("[Terminal] << {}", line.substring(0, Math.min(500, line.length())));

                try {
                    processEvent(JsonParser.parseString(line).getAsJsonObject());
                } catch (Exception e) {
                    log.info("[Terminal] [非JSON] {}", line.substring(0, Math.min(200, line.length())));
                }
            }
        } finally {
            // 进程退出，确保 pending 的 future 不会永远等待
            if (currentResult != null && !currentResult.isDone()) {
                String text = accumulatedText != null ? accumulatedText.toString() : "";
                currentResult.complete(new TurnResult(
                        text.isBlank() ? "⚠️ 终端进程意外退出" : text, false));
            }
            alive = false;
            log.info("[Terminal] reader 线程退出: key={}, alive={}", sessionKey, process != null && process.isAlive());
        }
    }

    private void processEvent(JsonObject event) {
        String type = event.has("type") ? event.get("type").getAsString() : "";
        String subtype = event.has("subtype") ? event.get("subtype").getAsString() : "";

        switch (type) {
            case "system" -> {
                if (event.has("session_id")) {
                    claudeSessionId = event.get("session_id").getAsString();
                    log.info("[Terminal] session_id: {}", claudeSessionId);
                }
                if (!"thinking_tokens".equals(subtype)) {
                    log.info("[Terminal] [system] {}", subtype);
                }
            }
            case "user" -> {
                // replay-user-messages 回显，或 tool_result 反馈 — 忽略
            }
            case "assistant" -> {
                if (event.has("message")) {
                    JsonObject msg = event.getAsJsonObject("message");
                    if (msg.has("content")) {
                        for (var item : msg.getAsJsonArray("content")) {
                            JsonObject obj = item.getAsJsonObject();
                            String ct = obj.has("type") ? obj.get("type").getAsString() : "";
                            if ("text".equals(ct) && obj.has("text")) {
                                String text = obj.get("text").getAsString();
                                accumulatedText.append(text);
                                log.info("[Terminal] [assistant] {}",
                                        text.substring(0, Math.min(200, text.length())));
                            }
                            if ("tool_use".equals(ct)) {
                                log.info("[Terminal] [tool_use] {}",
                                        obj.has("name") ? obj.get("name").getAsString() : "?");
                            }
                        }
                    }
                }
            }
            case "result" -> {
                log.info("[Terminal] [result] 完成");
                String result = "";
                boolean isError = false;
                if (event.has("is_error") && event.get("is_error").getAsBoolean()) {
                    isError = true;
                    result = event.has("result") ? event.get("result").getAsString() : "unknown";
                } else if (event.has("result")) {
                    result = event.get("result").getAsString();
                }
                String finalText = result.isBlank() ? accumulatedText.toString() : result;
                if (currentResult != null && !currentResult.isDone()) {
                    currentResult.complete(new TurnResult(finalText, isError));
                }
                accumulatedText = new StringBuilder(); // 为下一轮重置
            }
            case "control_response" -> {
                log.info("[Terminal] [control_response] {}", subtype);
            }
            default -> log.info("[Terminal] [{}]", type);
        }
    }

    // ===== 内部：stderr 读取 =====

    private void readStderr() {
        try (BufferedReader errReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = errReader.readLine()) != null) {
                log.info("[Terminal] stderr: {}", line);
            }
        } catch (IOException ignored) {}
    }

    // ===== 工具方法 =====

    private String readLineWithTimeout(BufferedReader reader, long timeoutMs) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try { return reader.readLine(); }
                catch (IOException e) {
                    log.warn("[Terminal] readLine IOException: {}", e.getMessage());
                    return null;
                }
            }).get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (Exception e) {
            log.warn("[Terminal] readLineWithTimeout 异常: {}", e.getMessage());
            return null;
        }
    }

    private String truncate(String text) {
        if (text.length() <= MAX_OUTPUT_CHARS) return text;
        return text.substring(0, MAX_OUTPUT_CHARS) + "\n\n... (截断，共 " + text.length() + " 字符)";
    }

    private int getExitCode() {
        try {
            return process != null ? process.exitValue() : -1;
        } catch (IllegalThreadStateException e) {
            return -1; // 进程仍在运行
        }
    }

    // ===== 内部数据结构 =====

    private static class TurnResult {
        final String text;
        final boolean isError;

        TurnResult(String text, boolean isError) {
            this.text = text;
            this.isError = isError;
        }
    }
}
