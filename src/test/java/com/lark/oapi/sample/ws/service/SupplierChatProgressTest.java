package com.lark.oapi.sample.ws.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 对话区轮次进度文案（SupplierJobService.chatProgressText 纯函数）— 11-05 修复 */
class SupplierChatProgressTest {

    @Test
    void queuePhaseShowsPositionOrFallback() {
        String withPos = SupplierJobService.chatProgressText("QUEUE", null, null, 8_000, 2);
        assertTrue(withPos.startsWith("⏳"), "进度文案统一 ⏳ 前缀: " + withPos);
        assertTrue(withPos.contains("排队中"));
        assertTrue(withPos.contains("前面还有 2 条"));

        String noPos = SupplierJobService.chatProgressText("QUEUE", null, null, 8_000, -1);
        assertTrue(noPos.contains("排队/执行中"), "拿不到队列位用合并文案: " + noPos);
    }

    @Test
    void toolPhaseIncludesToolNameDetailAndElapsed() {
        String text = SupplierJobService.chatProgressText("TOOL", "Bash", "postman collection run supplier.json", 135_000, -1);
        assertTrue(text.startsWith("⏳ 正在执行 🔧 Bash"), text);
        assertTrue(text.contains("postman collection run supplier.json"), text);
        assertTrue(text.contains("已 2分15秒"), "时长格式化复用 InstanceStatusService.fmt: " + text);
    }

    @Test
    void toolPhaseTruncatesLongDetail() {
        String text = SupplierJobService.chatProgressText("TOOL", "Bash", "x".repeat(200), 1_000, -1);
        assertTrue(text.length() < 130, "长命令应截断: " + text.length());
        assertTrue(text.contains("…"));
    }

    @Test
    void toolPhaseWithoutNameFallsBackToGenericTool() {
        String text = SupplierJobService.chatProgressText("TOOL", null, null, 3_000, -1);
        assertTrue(text.contains("正在执行 🔧 工具"), text);
    }

    @Test
    void thinkingAndGeneratingPhases() {
        String thinking = SupplierJobService.chatProgressText("THINKING", null, null, 40_000, -1);
        assertTrue(thinking.startsWith("⏳"), thinking);
        assertTrue(thinking.contains("思考中"));
        assertTrue(thinking.contains("已 40秒"), thinking);

        String generating = SupplierJobService.chatProgressText("GENERATING", null, null, 5_000, -1);
        assertTrue(generating.contains("正在生成回复"), generating);
    }

    @Test
    void unknownPhaseFallsBackToThinking() {
        assertTrue(SupplierJobService.chatProgressText(null, null, null, 1_000, -1).contains("思考中"));
        assertTrue(SupplierJobService.chatProgressText("WEIRD", null, null, 1_000, -1).contains("思考中"));
    }
}
