package com.lark.oapi.sample.ws.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.service.E2EOrchestrator;
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
 * E2E 测试群聊 WebSocket — /ws/e2e?taskId=xxx
 * <p>
 * 浏览器 ⇄ 群聊任务的实时通道：
 * <ul>
 *   <li>连接建立 → 注册为该任务的观众 → 立即推送 snapshot（任务+成员+流水线状态+完整群聊记录），刷新/断线重连即恢复现场</li>
 *   <li>浏览器发 {"type":"message","text":"...","to":"auto|programmer|designer|executor"} → 群聊发言（auto 驱动流水线，@角色直聊）</li>
 *   <li>任务事件（成员发言流式增量/工具/完成、阶段流转、用例更新、轮次报告、系统横幅）→ 广播给所有观众</li>
 * </ul>
 */
@Component
public class E2EWebSocketHandler extends TextWebSocketHandler implements E2ETaskService.Broadcaster {

    private static final Logger log = LoggerFactory.getLogger(E2EWebSocketHandler.class);
    private static final int SEND_TIME_LIMIT_MS = 5_000;
    private static final int BUFFER_SIZE_LIMIT = 512 * 1024;

    private final E2ETaskService taskService;
    private final E2EOrchestrator orchestrator;
    /** taskId → 观众会话集合 */
    private final Map<String, Set<WebSocketSession>> viewers = new ConcurrentHashMap<>();
    /** 列表页（/ws/e2e?lobby=1）订阅者：任何群聊有新消息/状态变化都推一条精简 task_activity，供侧栏排序与未读计数 */
    private final Set<WebSocketSession> lobby = ConcurrentHashMap.newKeySet();
    /** 会产生"新消息"或状态变化的事件；delta/thinking/tool 这类高频增量不推给列表 */
    private static final Set<String> LOBBY_EVENTS = Set.of("user_message", "system", "turn_start", "complete", "error",
            "stage", "pipeline_reset", "ask", "ask_resolved", "a2a_task", "round_report");

    public E2EWebSocketHandler(E2ETaskService taskService, E2EOrchestrator orchestrator) {
        this.taskService = taskService;
        this.orchestrator = orchestrator;
        taskService.setBroadcaster(this);   // 自注册，打破循环依赖
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession rawSession) throws Exception {
        String taskId = queryParam(rawSession.getUri(), "taskId");
        WebSocketSession session = new ConcurrentWebSocketSessionDecorator(rawSession, SEND_TIME_LIMIT_MS, BUFFER_SIZE_LIMIT);
        if (taskId == null && "1".equals(queryParam(rawSession.getUri(), "lobby"))) {
            lobby.add(session);
            return;
        }

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
                String to = msg.has("to") ? msg.get("to").getAsString() : "auto";
                if (!text.isBlank() && taskId != null) {
                    E2ETaskService.E2ETask task = taskService.getTask(taskId);
                    if (task != null) {
                        orchestrator.postUserMessage(task, text, to);
                    }
                }
            } else if ("start".equals(type) && taskId != null) {
                E2ETaskService.E2ETask task = taskService.getTask(taskId);
                if (task != null) orchestrator.start(task);
            }
        } catch (Exception e) {
            log.error("[E2E WS] 消息处理异常: task={}", taskId, e);
            sendError(session, "消息处理异常: " + e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        lobby.removeIf(s -> s.getId().equals(session.getId()));
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
        String type = event.has("type") ? event.get("type").getAsString() : "";
        if (!lobby.isEmpty() && LOBBY_EVENTS.contains(type)) notifyLobby(taskId);
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

    /** 推给列表订阅者：该群聊的最新摘要（排序时间、消息数、最后一条预览、状态） */
    public void notifyLobby(String taskId) {
        E2ETaskService.E2ETask task = taskService.getTask(taskId);
        JsonObject ev = new JsonObject();
        ev.addProperty("type", task != null ? "task_activity" : "task_removed");
        ev.addProperty("taskId", taskId);
        if (task != null) ev.add("task", taskJson(task));
        String payload = ev.toString();
        for (WebSocketSession s : lobby) {
            try {
                if (s.isOpen()) s.sendMessage(new TextMessage(payload));
                else lobby.remove(s);
            } catch (Exception e) {
                lobby.remove(s);
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

    public static JsonObject taskJson(E2ETaskService.E2ETask task) {
        JsonObject t = new JsonObject();
        t.addProperty("id", task.id);
        t.addProperty("requirement", task.requirement);
        t.addProperty("autoFix", task.autoFix);
        t.addProperty("maxRounds", task.maxRounds);
        t.addProperty("sharedDir", task.sharedDir != null ? task.sharedDir : "");
        t.addProperty("commMode", task.commMode != null ? task.commMode : "filesystem");
        // 创建者标签：数字员工（👔 名字）或管理员（👑 admin）
        if (task.creatorEmployeeId != null) t.addProperty("creatorEmployeeId", task.creatorEmployeeId);
        t.addProperty("creatorEmployeeName", task.creatorEmployeeName != null ? task.creatorEmployeeName : "");
        t.addProperty("createdAt", task.createdAt);
        // 侧栏：消息数（未读计数基准）、最后活动时间、最后一条预览
        java.util.List<E2ETaskService.TurnRecord> tr = task.transcript;
        t.addProperty("messageCount", tr.size());
        E2ETaskService.TurnRecord last = tr.isEmpty() ? null : tr.get(tr.size() - 1);
        t.addProperty("lastActivityAt", last != null ? Math.max(last.startMs, task.createdAt) : task.createdAt);
        if (last != null) {
            String body = "agent".equals(last.kind)
                    ? (last.errorMessage != null ? "❌ " + last.errorMessage : last.answer.length() > 0 ? last.answer.toString() : "正在回复…")
                    : last.userText;
            String who = "system".equals(last.kind) ? "" : "user".equals(last.kind)
                    ? (last.authorName != null && !last.authorName.isBlank() ? last.authorName : "用户") : last.authorLabel();
            String pv = (who.isEmpty() ? "" : who + "：") + (body == null ? "" : body.replaceAll("\\s+", " ").trim());
            t.addProperty("lastPreview", pv.length() > 80 ? pv.substring(0, 80) + "…" : pv);
        }
        t.addProperty("busy", task.busy);
        // active = 整条流水线进行中（含阶段间隙/成员长执行），黄点闪到 DONE/FAILED 为止
        t.addProperty("active", task.busy || (task.pipelineStarted && !task.pipelineDone && !task.cancelled));
        t.addProperty("pipelineStarted", task.pipelineStarted);
        t.addProperty("pipelineDone", task.pipelineDone);
        t.addProperty("interrupted", task.interrupted);   // 应用重启打断，前端显示"继续执行"按钮
        // 流水线暂停中：成员在等玩家回答（前端渲染提问横幅）
        E2ETaskService.PendingQuestion pq = task.pendingQuestion;
        if (pq != null) {
            E2ETaskService.Member asker = task.memberByRole(pq.role);
            JsonObject pqo = new JsonObject();
            pqo.addProperty("role", pq.role);
            pqo.addProperty("roleLabel", asker != null ? asker.roleLabel() : pq.role);
            pqo.addProperty("roleEmoji", asker != null ? asker.roleEmoji() : "🤖");
            pqo.addProperty("memberName", asker != null ? asker.name : "");
            pqo.addProperty("question", pq.question);
            pqo.addProperty("why", pq.why);
            pqo.addProperty("sinceMs", pq.sinceMs);
            t.add("pendingQuestion", pqo);
        }

        JsonArray members = new JsonArray();
        for (E2ETaskService.Member m : task.members) {
            JsonObject mo = new JsonObject();
            mo.addProperty("instanceId", m.instanceId);
            mo.addProperty("name", m.name);
            mo.addProperty("role", m.role);
            mo.addProperty("roleLabel", m.roleLabel());
            mo.addProperty("roleEmoji", m.roleEmoji());
            mo.addProperty("workingDir", m.workingDir);
            mo.addProperty("model", m.model);
            members.add(mo);
        }
        t.add("members", members);

        t.add("pipeline", pipelineJsonOf(task));
        return t;
    }

    /**
     * 流水线 JSON（taskJson 与编排器的 pipeline_reset 广播共用）：
     * 当前轮全量字段 + history[]（同群续派任务时旧轮归档，右侧执行记录向下递增保留）。
     */
    public static JsonObject pipelineJsonOf(E2ETaskService.E2ETask task) {
        JsonObject p = runJson(task.pipeline);
        JsonArray history = new JsonArray();
        for (E2ETaskService.PipelineState h : task.runsHistory) history.add(runJson(h));
        p.add("history", history);
        return p;
    }

    private static JsonObject runJson(E2ETaskService.PipelineState ps) {
        JsonObject p = new JsonObject();
        p.addProperty("stage", ps.stage);
        p.addProperty("stageDetail", ps.stageDetail);
        p.addProperty("round", ps.round);
        p.addProperty("failReason", ps.failReason != null ? ps.failReason : "");
        p.addProperty("runIndex", ps.runIndex);
        p.addProperty("requirement", ps.requirement != null ? ps.requirement : "");
        p.addProperty("startedAt", ps.startedAt);
        p.addProperty("endedAt", ps.endedAt);
        JsonArray cases = new JsonArray();
        for (E2ETaskService.TestCase c : ps.cases) cases.add(E2EOrchestrator.caseJson(c));
        p.add("cases", cases);
        JsonArray rounds = new JsonArray();
        for (E2ETaskService.RoundReport r : ps.rounds) {
            JsonObject ro = new JsonObject();
            ro.addProperty("round", r.round);
            ro.addProperty("passed", r.passed);
            ro.addProperty("failed", r.failed);
            ro.addProperty("blocked", r.blocked);
            ro.addProperty("total", r.total);
            ro.addProperty("atMs", r.atMs);
            ro.addProperty("fixSummary", r.fixSummary != null ? r.fixSummary : "");
            JsonArray results = new JsonArray();
            for (E2ETaskService.CaseResult cr : r.results) {
                JsonObject co = new JsonObject();
                co.addProperty("id", cr.id);
                co.addProperty("title", cr.title);
                co.addProperty("status", cr.status);
                co.addProperty("evidence", cr.evidence != null ? cr.evidence : "");
                co.addProperty("failReason", cr.failReason != null ? cr.failReason : "");
                results.add(co);
            }
            ro.add("results", results);
            rounds.add(ro);
        }
        p.add("rounds", rounds);
        return p;
    }

    /** 单条群聊记录 → JSON（与实时事件字段对齐，前端共用渲染逻辑） */
    public static JsonObject turnJson(E2ETaskService.TurnRecord rec) {
        JsonObject t = new JsonObject();
        t.addProperty("kind", rec.kind);
        t.addProperty("author", rec.author);
        t.addProperty("authorName", rec.authorName);
        t.addProperty("addressedTo", rec.addressedTo != null ? rec.addressedTo : "");
        t.addProperty("userText", rec.userText);
        t.addProperty("startMs", rec.startMs);
        synchronized (rec) {
            t.addProperty("thinking", rec.thinking.toString());
            t.addProperty("answer", rec.answer.toString());
            // A2A 任务信封（commMode=a2a 的 agent 轮次；快照/读档后原样恢复）
            if (rec.a2a != null) t.add("a2a", rec.a2a.deepCopy());
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
