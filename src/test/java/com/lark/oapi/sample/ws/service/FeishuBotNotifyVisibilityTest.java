package com.lark.oapi.sample.ws.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 卡点通知的"失败可见"契约（11-17-adhoc-blocker-notify 决策 4 / AC2 / AC5）。
 * <p>
 * {@link FeishuBotService#notifyUserChecked} 与 {@link FeishuBotService#notifyUser} 走同一条投递路径、
 * 同样尽力而为、同样不抛异常不阻塞，区别只在于把"为什么没发出去"返回给调用方落库展示；
 * notifyUser 保持 void 与静默跳过语义 → 两个既有调用方（EmployeeService#pushFeishu、
 * E2EOrchestrator 终报推送）无需改动、行为一字未变。
 * <p>
 * 纯逻辑断言：不建长连接、不发网络请求（connections 为空即等价于"实例未连接"），不测时钟、不加 sleep。
 */
class FeishuBotNotifyVisibilityTest {

    private final FeishuBotService service = new FeishuBotService(null, null, null, null, null);

    @Test
    void notifyUserStaysBestEffortAndNeverThrows() {
        assertDoesNotThrow(() -> service.notifyUser(1L, "", "内容"), "openId 空 → 静默跳过");
        assertDoesNotThrow(() -> service.notifyUser(1L, null, "内容"), "openId null → 静默跳过");
        assertDoesNotThrow(() -> service.notifyUser(null, "ou_x", "内容"), "实例为空也不炸调用方主流程");
        assertDoesNotThrow(() -> service.notifyUser(99L, "ou_x", "内容"), "实例未连接 → 静默跳过");
    }

    @Test
    void notifyUserCheckedReturnsHumanReadableReasons() {
        var notBound = service.notifyUserChecked(1L, "   ", "内容");
        assertFalse(notBound.ok(), "openId 空 = 没发出去");
        assertTrue(notBound.reason().contains("尚未绑定飞书私聊用户"), notBound.reason());

        var noInstance = service.notifyUserChecked(null, "ou_x", "内容");
        assertFalse(noInstance.ok());
        assertTrue(noInstance.reason().contains("未绑定飞书实例"), noInstance.reason());

        var disconnected = service.notifyUserChecked(99L, "ou_x", "内容");
        assertFalse(disconnected.ok());
        assertTrue(disconnected.reason().contains("未连接"), disconnected.reason());
    }

    @Test
    void okResultCarriesNoReason() {
        assertTrue(FeishuBotService.NotifyResult.OK.ok());
        assertEquals("", FeishuBotService.NotifyResult.OK.reason());
    }
}
