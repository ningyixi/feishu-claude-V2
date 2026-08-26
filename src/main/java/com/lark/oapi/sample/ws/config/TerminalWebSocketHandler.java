package com.lark.oapi.sample.ws.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TerminalWebSocketHandler.class);

    // sessionId -> PTY session
    private final Map<String, PtySession> ptySessions = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("[Terminal WS] 客户端连接: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonObject msg = JsonParser.parseString(message.getPayload()).getAsJsonObject();
            String type = msg.has("type") ? msg.get("type").getAsString() : "";
            String wsId = session.getId();

            switch (type) {
                case "open" -> {
                    long instanceId = msg.get("instanceId").getAsLong();
                    String workDir = msg.has("workDir") ? msg.get("workDir").getAsString() : "";
                    openPty(wsId, session, instanceId, workDir);
                }
                case "input" -> {
                    String data = msg.get("data").getAsString();
                    PtySession pty = ptySessions.get(wsId);
                    if (pty != null && pty.stdin != null) {
                        pty.stdin.write(data);
                        pty.stdin.flush();
                    }
                }
                case "resize" -> {
                    // 目前不支持 resize，但接受消息不报错
                }
                case "close" -> closePty(wsId);
            }
        } catch (Exception e) {
            log.error("[Terminal WS] 消息处理异常", e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        closePty(session.getId());
        log.info("[Terminal WS] 客户端断开: {}", session.getId());
    }

    private void openPty(String wsId, WebSocketSession session, long instanceId, String workDir) {
        // 关闭旧的 PTY
        closePty(wsId);

        try {
            // 启动交互式 shell
            ProcessBuilder pb;
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                pb = new ProcessBuilder("cmd.exe", "/Q");
            } else {
                pb = new ProcessBuilder("/bin/bash", "--norc", "--noprofile");
            }
            pb.redirectErrorStream(true);

            if (workDir != null && !workDir.isBlank()) {
                File dir = new File(workDir);
                if (dir.isDirectory()) {
                    pb.directory(dir);
                }
            }

            Process process = pb.start();
            BufferedWriter stdin = new BufferedWriter(
                    new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            BufferedReader stdout = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));

            PtySession pty = new PtySession(process, stdin, instanceId);
            ptySessions.put(wsId, pty);

            log.info("[Terminal] PTY 已启动: instance={}, dir={}", instanceId, workDir);

            // 发送已连接标记
            sendJson(session, "connected", "instanceId", instanceId);

            // 启动读取线程，持续读取 PTY 输出并推送给前端
            Thread.ofVirtual().name("pty-reader-" + wsId).start(() -> {
                try {
                    char[] buf = new char[4096];
                    int len;
                    while ((len = stdout.read(buf)) != -1) {
                        String chunk = new String(buf, 0, len);
                        sendJson(session, "output", "data", chunk);
                    }
                } catch (IOException e) {
                    // PTY 已关闭
                } finally {
                    sendJson(session, "exit", "code", 0);
                }
            });

        } catch (IOException e) {
            log.error("[Terminal] 启动 PTY 失败", e);
            sendJson(session, "error", "message", "启动终端失败: " + e.getMessage());
        }
    }

    private void closePty(String wsId) {
        PtySession pty = ptySessions.remove(wsId);
        if (pty != null) {
            try { pty.stdin.close(); } catch (IOException ignored) {}
            if (pty.process.isAlive()) {
                pty.process.destroyForcibly();
            }
            log.info("[Terminal] PTY 已关闭: instance={}", pty.instanceId);
        }
    }

    private void sendJson(WebSocketSession session, String type, Object... kv) {
        if (!session.isOpen()) return;
        try {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", type);
            for (int i = 0; i + 1 < kv.length; i += 2) {
                String key = (String) kv[i];
                Object val = kv[i + 1];
                if (val instanceof String s) obj.addProperty(key, s);
                else if (val instanceof Number n) obj.addProperty(key, n);
            }
            synchronized (session) {
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(obj.toString()));
                }
            }
        } catch (Exception ignored) {}
    }

    private static class PtySession {
        final Process process;
        final BufferedWriter stdin;
        final long instanceId;

        PtySession(Process process, BufferedWriter stdin, long instanceId) {
            this.process = process;
            this.stdin = stdin;
            this.instanceId = instanceId;
        }
    }
}
