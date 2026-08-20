package com.lark.oapi.sample.ws;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 本地 Shell 终端服务
 * <p>
 * 管理一个持久的 shell 进程，支持命令执行、会话状态持久化（cd/export 等）、
 * 超时控制和输出截断。
 */
public class ShellService {

    private static final Logger log = LoggerFactory.getLogger(ShellService.class);

    private static final int MAX_OUTPUT_CHARS = 18000;
    private static final long DEFAULT_TIMEOUT_MS = 30_000;

    private Process shellProcess;
    private BufferedWriter shellInput;
    private BufferedReader shellOutput;
    private final ReentrantLock execLock = new ReentrantLock();
    private volatile boolean alive = false;

    /**
     * 执行 shell 命令并返回输出
     */
    public String execute(String command) {
        return execute(command, DEFAULT_TIMEOUT_MS);
    }

    /**
     * 执行 shell 命令并返回输出，支持自定义超时
     */
    public String execute(String command, long timeoutMs) {
        execLock.lock();
        try {
            ensureShell();

            String marker = "__END_" + UUID.randomUUID().toString().replace("-", "") + "__";

            // 写入命令 + 结束标记
            shellInput.write(command);
            shellInput.newLine();
            shellInput.write("echo " + marker);
            shellInput.newLine();
            shellInput.flush();

            // 读取输出直到标记
            StringBuilder output = new StringBuilder();
            long deadline = System.currentTimeMillis() + timeoutMs;

            boolean markerFound = readUntilMarker(output, marker, deadline);

            if (!markerFound) {
                log.warn("[Shell] 命令超时 ({}ms): {}", timeoutMs, command);
                killShell();
                return "⏰ 命令超时 (" + (timeoutMs / 1000) + "s)，Shell 已重启。\n\n"
                        + truncateOutput(output.toString());
            }

            String result = output.toString().stripTrailing();
            return result.isEmpty() ? "(无输出)" : truncateOutput(result);

        } catch (IOException e) {
            log.error("[Shell] 执行异常: {}", command, e);
            killShell();
            return "Shell 执行出错: " + e.getMessage();
        } finally {
            execLock.unlock();
        }
    }

    /**
     * 终止 shell 进程
     */
    public void destroy() {
        execLock.lock();
        try {
            killShell();
            log.info("[Shell] 已终止");
        } finally {
            execLock.unlock();
        }
    }

    // ========== 内部方法 ==========

    private void ensureShell() {
        if (alive && shellProcess != null && shellProcess.isAlive()) {
            return;
        }

        try {
            List<String> shellCmd = detectShell();
            ProcessBuilder pb = new ProcessBuilder(shellCmd);
            pb.redirectErrorStream(true); // 合并 stdout/stderr

            shellProcess = pb.start();
            shellInput = new BufferedWriter(
                    new OutputStreamWriter(shellProcess.getOutputStream(), StandardCharsets.UTF_8));
            shellOutput = new BufferedReader(
                    new InputStreamReader(shellProcess.getInputStream(), StandardCharsets.UTF_8));
            alive = true;

            log.info("[Shell] 启动: {}", String.join(" ", shellCmd));
        } catch (IOException e) {
            alive = false;
            throw new RuntimeException("无法启动 Shell 进程: " + e.getMessage(), e);
        }
    }

    private List<String> detectShell() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            return List.of("cmd.exe", "/Q");
        } else {
            Path bash = Path.of("/bin/bash");
            if (Files.exists(bash)) {
                return List.of("/bin/bash", "--norc", "--noprofile");
            }
            return List.of("/bin/sh");
        }
    }

    private boolean readUntilMarker(StringBuilder buf, String marker, long deadline) {
        while (true) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return false;
            }

            try {
                String line = readLineWithTimeout(remaining);
                if (line == null) {
                    return false; // EOF = shell 进程退出
                }
                if (line.contains(marker)) {
                    return true;
                }
                buf.append(line).append('\n');
            } catch (Exception e) {
                log.warn("[Shell] 读取输出异常", e);
                return false;
            }
        }
    }

    private String readLineWithTimeout(long timeoutMs) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return shellOutput.readLine();
            } catch (IOException e) {
                return null;
            }
        }).get(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private void killShell() {
        if (shellProcess != null && shellProcess.isAlive()) {
            shellProcess.destroyForcibly();
        }
        alive = false;
    }

    private String truncateOutput(String output) {
        if (output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        return output.substring(0, MAX_OUTPUT_CHARS)
                + "\n\n... (输出已截断，共 " + output.length() + " 字符)";
    }
}
