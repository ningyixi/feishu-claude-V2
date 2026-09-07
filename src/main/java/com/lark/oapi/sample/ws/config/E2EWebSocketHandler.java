package com.lark.oapi.sample.ws.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.service.E2ETaskService;
import com.lark.oapi.sample.ws.service.ToolStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * E2E 测试工作台 WebSocket — /ws/e2e?taskId=xxx
 * <p>
 * 浏览器 ⇄ 任务的实时通道：
 * <ul>
 *   <li>连接建立 → 注册为该任务的观众 → 立即推送 snapshot（任务信息 + 完整历史 transcript），刷新/断线重连即恢复现场</li>
 *   <li>浏览器发 {"type":"message","text":"..."} → E2ETaskService.sendMessage（虚拟线程执行，忙碌自动排队）</li>
 *   <li>任务事件（thinking/delta/tool/complete/error/queued）→ 广播给该任务的所有观众（支持多人围观）</li>
 * </ul>
 * 发送用 ConcurrentWebSocketSessionDecorator 保证并发安全（广播线程与快照线程可能同时写同一 session）。
 */
@Component
public class E2EWebSocketHandler extends TextWebSocketHandler implements E2ETaskService.Broadcaster {

    private static final Logger log = LoggerFactory.getLogger(E2EWebSocketHandler.class);
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    private final E2ETaskService taskService;
    /** taskId → 观众会话集合 */
    private final Map<String, Set<WebSocketSession>> viewers = new ConcurrentHashMap<>();

    public E2EWebSocketHandler(E2ETaskService taskService) {
        this.taskService = taskService;
        taskService.setBroadcaster(this);   // 自注册，打破循环依赖
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) throws Exception {
        String taskId = queryParam(rawSession.getUri(), "taskId");
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(rawSession, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);

        E2ETaskService.E2ETask task = taskId != null ? taskService.getTask(taskId) : null;
        if (task == null) {
            JsonObject err = new JsonObject();
            err.addProperty("type", "error");
            err.addProperty("message", "任务不存在: " + taskId);
            session.sendMessage(new TextMessage(err.toString()));
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        viewers.computeIfAbsent(task.id, k -> ConcurrentHashMap.newKeySet()).add(session);
        session.sendMessage(new TextMessage(buildSnapshot(task).toString()));
        log.info("[E2E WS] 观众接入: task={}, sessionId={}, 当前观众数={}",
                task.id, session.getId(), viewers.get(task.id).size());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String taskId = queryParam(session.getUri(), "taskId");
        try {
            JsonObject msg = JsonParser.parseString(message.getPayload()).getAsJsonObject();
            String type = msg.has("type") ? msg.get("type").getAsString() : "";
            if ("message".equals(type) && msg.has("text")) {
                String text = msg.get("text").getAsString();
                if (!text.isBlank() && taskId != null) {
                    taskService.sendMessage(taskId, text);
                }
            }
        } catch (IllegalArgumentException e) {
            sendError(session, e.getMessage());
        } catch (Exception e) {
            log.error("[E2E WS] 消息处理异常: task={}", taskId, e);
            sendError(session, "消息处理异常: " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String taskId = queryParam(session.getUri(), "taskId");
        if (taskId != null) {
            Set<WebSocketSession> set = viewers.get(taskId);
            if (set != null) {
                set.removeIf(s -> s.getId().equals(session.getId()));
                log.info("[E2E WS] 观众离开: task={}, 剩余={}", taskId, set.size());
            }
        }
    }

    /** 广播任务事件给所有观众（实现 E2ETaskService.Broadcaster） */
    @Override
    public void broadcast(String taskId, JsonObject event) {
        Set<WebSocketSession> set = viewers.get(taskId);
        if (set == null || set.isEmpty()) return;
        String payload = event.toString();
        for (WebSocketSession s : set) {
            try {
                if (s.isOpen()) s.sendMessage(new TextMessage(payload));
            } catch (Exception e) {
                log.warn("[E2E WS] 推送失败，移除观众: {}", e.getMessage());
                set.remove(s);
            }
        }
    }

    // ===== 快照序列化 =====

    private JsonObject buildSnapshot(E2ETaskService.E2ETask task) {
        JsonObject snap = new JsonObject();
        snap.addProperty("type", "snapshot");
        snap.add("task", taskJson(task));
        JsonArray turns = new JsonArray();
        for (E2ETaskService.TurnRecord rec : task.transcript) {
            turns.add(turnJson(rec));
        }
        snap.add("turns", turns);
        return snap;
    }

    private JsonObject taskJson(E2ETaskService.E2ETask task) {
        JsonObject t = new JsonObject();
        t.addProperty("id", task.id);
        t.addProperty("testerInstanceId", task.testerInstanceId);
        t.addProperty("targetInstanceId", task.targetInstanceId);
        t.addProperty("testerName", task.testerName);
        t.addProperty("targetName", task.targetName);
        t.addProperty("workingDir", task.workingDir);
        t.addProperty("model", task.model);
        t.addProperty("createdAt", task.createdAt);
        t.addProperty("busy", task.busy);
        return t;
    }

    /** 单轮记录 → JSON（与 BrowserTurnListener 的实时事件字段对齐，前端可共用渲染逻辑） */
    public static JsonObject turnJson(E2ETaskService.TurnRecord rec) {
        JsonObject t = new JsonObject();
        t.addProperty("userText", rec.userText);
        t.addProperty("startMs", rec.startMs);
        synchronized (rec) {
            t.addProperty("thinking", rec.thinking.toString());
            t.addProperty("answer", rec.answer.toString());
        }
        JsonArray tools = new JsonArray();
        for (ToolStep s : rec.tools) {
            JsonObject so = new JsonObject();
            so.addProperty("name", s.name);
            so.addProperty("detail", s.detail != null ? s.detail : "");
            so.addProperty("status", s.status.name().toLowerCase());
            so.addProperty("elapsedMs", s.elapsedMs());
            tools.add(so);
        }
        t.add("tools", tools);
        if (rec.result != null) {
            JsonObject r = new JsonObject();
            r.addProperty("text", rec.result.text != null ? rec.result.text : "");
            r.addProperty("isError", rec.result.isError);
            r.addProperty("aborted", rec.result.aborted);
            r.addProperty("durationMs", rec.result.durationMs);
            r.addProperty("thinkingMs", rec.result.thinkingMs);
            r.addProperty("inputTokens", rec.result.inputTokens);
            r.addProperty("outputTokens", rec.result.outputTokens);
            r.addProperty("costUsd", rec.result.costUsd);
            r.addProperty("model", rec.result.model != null ? rec.result.model : "");
            t.add("result", r);
        }
        if (rec.errorMessage != null) {
            t.addProperty("errorMessage", rec.errorMessage);
            t.addProperty("aborted", rec.aborted);
        }
        return t;
    }

    private void sendError(WebSocketSession session, String msg) {
        try {
            JsonObject err = new JsonObject();
            err.addProperty("type", "error");
            err.addProperty("message", msg != null ? msg : "未知错误");
            if (session.isOpen()) session.sendMessage(new TextMessage(err.toString()));
        } catch (Exception ignored) {}
    }

    private static String queryParam(URI uri, String name) {
        if (uri == null || uri.getQuery() == null) return null;
        for (String pair : uri.getQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), java.nio.charset.StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
