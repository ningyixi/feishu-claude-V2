package com.lark.oapi.sample.ws.service;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮对话的最终结果 — 由 stream-json 协议的 result 事件组装（确定性完成信号）。
 * metrics 字段直接来自 result 事件（duration_ms/usage/total_cost_usd/modelUsage），
 * 用于终态卡片 footer（对照 openclaw-lark 的 FooterSessionMetrics）。
 */
public class TurnResult {

    /** 最终回复全文（result 事件的 result 字段，空白时回退累积文本） */
    public String text = "";
    /** 本轮是否出错（result.is_error 或看门狗/进程死亡兜底） */
    public boolean isError;
    /** 是否被用户中止（/new、/stop） */
    public boolean aborted;

    /** 思考全文（thinking_delta 累积），可为空 */
    public String thinkingText = "";
    /** 思考阶段耗时 ms（首个 thinking_delta → 首个 text_delta），无思考为 0 */
    public long thinkingMs;

    /** result.duration_ms，缺失时用轮次墙钟时间 */
    public long durationMs;
    public long inputTokens = -1;
    public long outputTokens = -1;
    public long cacheReadTokens = -1;
    public long cacheWriteTokens = -1;
    public double costUsd = -1;
    public String model = "";

    /** 本轮工具调用步骤（含子 Agent） */
    public final List<ToolStep> toolSteps = new ArrayList<>();
    /** 工具阶段总耗时 ms（首步 start → 末步 end），无工具为 0 */
    public long toolElapsedMs;

    public TurnResult() {}

    public TurnResult(String text, boolean isError) {
        this.text = text;
        this.isError = isError;
    }
}
