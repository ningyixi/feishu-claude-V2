package com.lark.oapi.sample.ws.service;

/**
 * 工具调用步骤 — 对应 Claude stream-json 协议中的 tool_use 块。
 * <p>
 * 子 Agent（Task 工具）也表现为一个 ToolStep，其状态由 task_started/task_notification 事件更新。
 * 渲染样式对照 openclaw-lark 的工具面板：Running(turquoise)/Succeeded(green)/Failed(red)。
 */
public class ToolStep {

    public enum Status { RUNNING, SUCCESS, ERROR }

    /** tool_use_id，用于与 tool_result 匹配 */
    public final String id;
    /** 工具名（Bash/Read/Edit/Task/WebSearch...） */
    public final String name;
    /** 摘要信息（命令/文件路径等，已截断），可为空 */
    public final String detail;
    /** 是否为子 Agent 步骤（Task 工具） */
    public final boolean subAgent;

    public volatile Status status;
    public final long startMs;
    public volatile long endMs;

    public ToolStep(String id, String name, String detail, boolean subAgent) {
        this.id = id;
        this.name = name;
        this.detail = detail;
        this.subAgent = subAgent;
        this.status = Status.RUNNING;
        this.startMs = System.currentTimeMillis();
        this.endMs = 0;
    }

    public void markEnd(Status s) {
        this.status = s;
        this.endMs = System.currentTimeMillis();
    }

    public long elapsedMs() {
        long end = endMs > 0 ? endMs : System.currentTimeMillis();
        return Math.max(0, end - startMs);
    }

    /** 面板显示标题：友好名 + detail 摘要 */
    public String displayTitle() {
        return name;
    }
}
