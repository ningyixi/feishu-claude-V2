package com.lark.oapi.sample.ws.service;

/**
 * 轮次生命周期监听器 — TerminalSession 把 Claude CLI stream-json 事件
 * 翻译成确定性回调，由 UI 层（{@link StreamingCardController}）消费。
 * <p>
 * 对照 openclaw-lark 的 dispatcher 回调契约：
 * onPartialReply → onTextDelta，onReasoningStream → onThinkingDelta，
 * onToolStart → onToolStart/onToolEnd，onIdle(markFullyComplete) → onTurnComplete，
 * onError/abortCard → onTurnError。
 * <p>
 * 完成判定唯一来源是协议的 result 事件（每轮恰好一个，实测含子 Agent 场景），
 * 回调全部在 reader 虚拟线程上触发，实现方不得阻塞（HTTP 调用需自行异步化）。
 */
public interface TurnListener {

    /** 轮次开始（stdin 已写入），实现方应立即创建"思考中"卡片 */
    void onTurnStart();

    /** 排队提示：上一条消息还在处理时触发（可选实现） */
    default void onQueued() {}

    /** 思考增量（thinking_delta） */
    void onThinkingDelta(String delta);

    /** 回复正文增量（text_delta） */
    void onTextDelta(String delta);

    /** 工具开始执行（tool_use 块 / 子 Agent task_started） */
    void onToolStart(ToolStep step);

    /** 工具执行结束（tool_result / task_notification completed） */
    void onToolEnd(ToolStep step);

    /** 轮次完成 — result 事件驱动，零等待，携带完整 metrics */
    void onTurnComplete(TurnResult result);

    /** 轮次异常终止（协议错误/看门狗/进程死亡/用户中止） */
    void onTurnError(String message, boolean aborted);
}
