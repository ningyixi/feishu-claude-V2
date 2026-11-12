package com.lark.oapi.sample.ws.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 2026-11 消息链路插桩（"157 行后零痕迹"事故）新增纯函数的单测。
 * senderType 判空加固内联在匿名 handler 中无法独立测，由整体 try/catch 兜底可见化。
 */
class FeishuBotServiceInstrumentationTest {

    @Test
    void previewNullSafeAndTruncates() {
        assertNull(FeishuBotService.preview(null, 60));
        assertEquals("", FeishuBotService.preview("", 60));
        assertEquals("abc", FeishuBotService.preview("abc", 3));      // 恰等长不截
        assertEquals("ab…", FeishuBotService.preview("abc", 2));      // 超长截断补省略号
        assertEquals("x".repeat(60), FeishuBotService.preview("x".repeat(60), 60));
        assertEquals("x".repeat(60) + "…", FeishuBotService.preview("x".repeat(61), 60));
    }

    @Test
    void isExpiredToleratesGarbageInput() {
        // 空值/脏数据安全：一律按未过期处理（宁可放行也不误杀正常消息）
        assertFalse(FeishuBotService.isExpired(null));
        assertFalse(FeishuBotService.isExpired(""));
        assertFalse(FeishuBotService.isExpired("not-a-number"));
        assertFalse(FeishuBotService.isExpired(String.valueOf(System.currentTimeMillis())));
        assertTrue(FeishuBotService.isExpired(String.valueOf(System.currentTimeMillis() - 31 * 60 * 1000L)));
    }
}
