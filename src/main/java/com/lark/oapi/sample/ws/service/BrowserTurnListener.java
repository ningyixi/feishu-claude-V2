package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 浏览器轮次监听器 — 把 TerminalSession 的确定性事件翻译成 JSON 事件，
 * ① 累积进任务的 TurnRecord（断线重连快照）② 经 E2ETaskService 广播到 /ws/e2e。
 * <p>
 * 与飞书侧 StreamingCardController 是同一契约（TurnListener）的两种 UI 实现：
 * 一个渲染成飞书卡片，一个渲染成浏览器对话流。
 */
public class BrowserTurnListener implements TurnListener {

    private static final Logger log = LoggerFactory.getLogger(BrowserTurnListener.class);

    private final E2ETaskService service;
    private final E2ETaskService.E2ETask task;
    private final E2ETaskService.TurnRecord rec;
    private final int turnIndex;
    /** 事件来源标记："A"=测试方输出，"B"=被测方修复输出（流水线模式），前端据此区分气泡样式 */
    private final String source;

    public BrowserTurnListener(E2ETaskService service, E2ETaskService.E2ETask task, E2ETaskService.TurnRecord rec, String source) {
        this.service = service;
        this.task = task;
        this.rec = rec;
        this.source = source != null ? source : "A";
        this.turnIndex = task.transcript.size() - 1;
    }

    @Override
    public void onTurnStart() {
        JsonObject e = base("turn_start");
        e.addProperty("userText", rec.userText);   // 多观众场景：其他浏览器也能看到用户输入
        service.broadcast(task.id, e);
    }

    @Override
    public void onQueued() {
        service.broadcast(task.id, base("queued"));
    }

    @Override
    public void onThinkingDelta(String delta) {
        synchronized (rec) { rec.thinking.append(delta); }
        JsonObject e = base("thinking");
        e.addProperty("text", delta);
        service.broadcast(task.id, e);
    }

    @Override
    public void onTextDelta(String delta) {
        synchronized (rec) { rec.answer.append(delta); }
        JsonObject e = base("delta");
        e.addProperty("text", delta);
        service.broadcast(task.id, e);
    }

    @Override
    public void onToolStart(ToolStep step) {
        rec.tools.add(step);
        service.broadcast(task.id, toolEvent(step));
    }

    @Override
    public void onToolEnd(ToolStep step) {
        service.broadcast(task.id, toolEvent(step));
    }

    @Override
    public void onTurnComplete(TurnResult result) {
        rec.result = result;
        JsonObject e = base("complete");
        JsonObject r = new JsonObject();
        r.addProperty("text", result.text != null ? result.text : "");
        r.addProperty("isError", result.isError);
        r.addProperty("aborted", result.aborted);
        r.addProperty("durationMs", result.durationMs);
        r.addProperty("thinkingMs", result.thinkingMs);
        r.addProperty("toolElapsedMs", result.toolElapsedMs);
        r.addProperty("inputTokens", result.inputTokens);
        r.addProperty("outputTokens", result.outputTokens);
        r.addProperty("costUsd", result.costUsd);
        r.addProperty("model", result.model != null ? result.model : "");
        // 终态权威文本（result 字段可能与流式累积有细微差异，以它为准）
        if (result.text != null && !result.text.isBlank()) {
            synchronized (rec) {
                rec.answer.setLength(0);
                rec.answer.append(result.text);
            }
        }
        e.add("result", r);
        service.broadcast(task.id, e);
        service.persistTurn(task, rec);   // 终态回填落库（重启可读档）
        log.info("[E2E] 轮次完成: task={}, turn={}, {}字", task.id, turnIndex,
                result.text != null ? result.text.length() : 0);
    }

    @Override
    public void onTurnError(String message, boolean aborted) {
        rec.errorMessage = message;
        rec.aborted = aborted;
        JsonObject e = base("error");
        e.addProperty("message", message != null ? message : "未知错误");
        e.addProperty("aborted", aborted);
        service.broadcast(task.id, e);
        service.persistTurn(task, rec);   // 终态回填落库
        log.warn("[E2E] 轮次异常: task={}, turn={}, error={}, aborted={}", task.id, turnIndex, message, aborted);
    }

    private JsonObject base(String type) {
        JsonObject e = new JsonObject();
        e.addProperty("type", type);
        e.addProperty("turnIndex", turnIndex);
        e.addProperty("source", source);
        e.addProperty("author", rec.author);
        e.addProperty("authorName", rec.authorName);
        return e;
    }

    private JsonObject toolEvent(ToolStep step) {
        JsonObject e = base("tool");
        int idx = rec.tools.indexOf(step);
        e.addProperty("toolIndex", idx >= 0 ? idx : rec.tools.size() - 1);
        e.addProperty("name", step.name);
        e.addProperty("detail", step.detail != null ? step.detail : "");
        e.addProperty("status", step.status.name().toLowerCase());   // running/success/error
        e.addProperty("elapsedMs", step.elapsedMs());
        return e;
    }
}
