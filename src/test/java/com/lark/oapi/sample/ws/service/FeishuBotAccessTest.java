package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.ChatMessageRepository;
import com.lark.oapi.sample.ws.repository.UserModeRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.Set;

import static com.lark.oapi.sample.ws.service.FeishuBotService.AccessDecision.*;
import static com.lark.oapi.sample.ws.service.FeishuBotService.decideAccess;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * S1 首个私聊者自动绑定（11-17-unblock-instance-chat）— 纯逻辑单测，不起连接、不进 Spring 上下文。
 * <p>
 * 覆盖 PRD 三态：空绑定+p2p → 放行并绑定（AC1 前半）；空绑定+group → 拒（AC2）；非空 → 原严格匹配语义
 * （AC1 后半：另一个 openId 私聊仍被拒）。绑定动作只允许由已鉴权的飞书事件回调触发（本类直测其唯一实现
 * {@link FeishuBotService#autoBindFirstPrivateChatter}，控制器层没有、也禁止有任何 HTTP 入口）。
 */
class FeishuBotAccessTest {

    private static final Set<String> WHITELIST = Set.of("ou_global_admin");
    private static final String FIRST = "ou_first_private_chatter";
    private static final String OTHER = "ou_someone_else";

    private final BotInstanceRepository instanceRepo = mock(BotInstanceRepository.class);

    private FeishuBotService service() throws Exception {
        FeishuBotService s = new FeishuBotService(
                mock(UserModeRepository.class), mock(ChatMessageRepository.class),
                mock(ShellService.class), mock(ClaudeCodeService.class), mock(SkillHookService.class));
        Field f = FeishuBotService.class.getDeclaredField("botInstanceRepo");
        f.setAccessible(true);
        f.set(s, instanceRepo);
        return s;
    }

    private BotInstance instance(String authorizedOpenId) {
        BotInstance b = new BotInstance();
        b.setId(106L);
        b.setName("测试实例");
        b.setAuthorizedOpenId(authorizedOpenId);
        return b;
    }

    // ===== decideAccess：权限判定三态矩阵 =====

    @Test
    void emptyBindingP2pTriggersAutoBind() {
        // AC1 前半：出厂实例（authorized_open_id 空）收到第一条私聊 → 走自动绑定分支
        assertEquals(AUTO_BIND, decideAccess(WHITELIST, "", FIRST, "p2p"));
        assertEquals(AUTO_BIND, decideAccess(WHITELIST, null, FIRST, "p2p"));
        assertEquals(AUTO_BIND, decideAccess(WHITELIST, "   ", FIRST, "p2p"));   // 空白按未绑定归一
    }

    @Test
    void emptyBindingGroupChatNeverAutoBinds() {
        // AC2：群聊绝不自动绑定，空绑定的群消息维持现有拒止
        assertEquals(DENY, decideAccess(WHITELIST, "", FIRST, "group"));
        assertEquals(DENY, decideAccess(WHITELIST, null, FIRST, "group"));
    }

    @Test
    void emptyBindingUnknownChatTypeOrBlankOpenIdDenies() {
        // 会话类型未知 / 发送者 openId 缺失（机器人事件判空路径）→ 保守拒止，绝不绑定空值
        assertEquals(DENY, decideAccess(WHITELIST, "", FIRST, null));
        assertEquals(DENY, decideAccess(WHITELIST, "", null, "p2p"));
        assertEquals(DENY, decideAccess(WHITELIST, "", "  ", "p2p"));
    }

    @Test
    void boundInstanceKeepsStrictMatchSemantics() {
        // 硬约束3：已有绑定 = 严格匹配，行为与改动前完全一致
        assertEquals(ALLOW, decideAccess(WHITELIST, FIRST, FIRST, "p2p"));
        assertEquals(ALLOW, decideAccess(WHITELIST, FIRST, FIRST, "group"));
        // AC1 后半：绑定后另一个 openId 私聊仍被拒（不因 p2p 放行）
        assertEquals(DENY, decideAccess(WHITELIST, FIRST, OTHER, "p2p"));
        assertEquals(DENY, decideAccess(WHITELIST, FIRST, OTHER, "group"));
        assertEquals(DENY, decideAccess(WHITELIST, FIRST, null, "p2p"));
    }

    @Test
    void globalWhitelistStillOverridesEverything() {
        // 硬约束3：全局白名单照旧有效（不做配置化，不改语义）
        assertEquals(ALLOW, decideAccess(WHITELIST, "", "ou_global_admin", "group"));
        assertEquals(ALLOW, decideAccess(WHITELIST, OTHER, "ou_global_admin", "p2p"));
    }

    // ===== autoBindFirstPrivateChatter：落库动作 =====

    @Test
    void bindWritesOnlyOpenIdAndSyncsLiveSnapshot() throws Exception {
        BotInstance stored = instance("");
        stored.setClaudeModel("sonnet");
        stored.setSandboxLevel(2);
        stored.setClaudeWorkingDir("D:/work");
        stored.setShellWorkingDir("D:/shell");
        when(instanceRepo.findById(106L)).thenReturn(Optional.of(stored));
        BotInstance live = instance("");   // conn.instance 内存快照

        assertTrue(service().autoBindFirstPrivateChatter(live, FIRST));

        ArgumentCaptor<BotInstance> cap = ArgumentCaptor.forClass(BotInstance.class);
        verify(instanceRepo).save(cap.capture());
        assertEquals(FIRST, cap.getValue().getAuthorizedOpenId());
        // 硬约束5：沙箱/模型/目录等其他实例字段不得被顺手改写
        assertEquals("sonnet", cap.getValue().getClaudeModel());
        assertEquals(2, cap.getValue().getSandboxLevel());
        assertEquals("D:/work", cap.getValue().getClaudeWorkingDir());
        assertEquals("D:/shell", cap.getValue().getShellWorkingDir());
        // 内存快照同步：同一连接上的后续消息直接按已绑定放行
        assertEquals(FIRST, live.getAuthorizedOpenId());
    }

    @Test
    void bindLosesRaceToConcurrentBinderAndFallsBackToStrictMatch() throws Exception {
        // 并发窗口：处理本条消息期间别人已绑定 → 不覆盖，回到严格匹配（AC1 后半语义）
        when(instanceRepo.findById(106L)).thenReturn(Optional.of(instance(OTHER)));
        BotInstance live = instance("");

        assertFalse(service().autoBindFirstPrivateChatter(live, FIRST));

        verify(instanceRepo, never()).save(any());
        assertEquals(OTHER, live.getAuthorizedOpenId());   // 快照刷新为已绑定值
    }

    @Test
    void bindConcurrentSameUserStillAllowed() throws Exception {
        // 并发窗口的另一半：抢先绑定的恰好就是本发送者 → 放行
        when(instanceRepo.findById(106L)).thenReturn(Optional.of(instance(FIRST)));

        assertTrue(service().autoBindFirstPrivateChatter(instance(""), FIRST));
        verify(instanceRepo, never()).save(any());
    }

    @Test
    void bindFailsClosedOnDbErrorOrMissingRow() throws Exception {
        // DB 故障 / 实例行缺失 → 拒止本轮（fail-closed），绝不放行未落库的绑定
        when(instanceRepo.findById(106L)).thenThrow(new RuntimeException("db down"));
        assertFalse(service().autoBindFirstPrivateChatter(instance(""), FIRST));

        reset(instanceRepo);
        when(instanceRepo.findById(106L)).thenReturn(Optional.empty());
        assertFalse(service().autoBindFirstPrivateChatter(instance(""), FIRST));
        verify(instanceRepo, never()).save(any());
    }
}
