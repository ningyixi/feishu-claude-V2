package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * ②b 服务重启后主会话续接"最新 sessionID" — mockito 纯单测，不起进程、无 Spring 上下文。
 * <p>
 * 覆盖边界说明（与方案取舍一致）：{@code getOrCreate} 真正新建会话时会调 {@code TerminalSession.start()}
 * 拉起真实 claude CLI 进程，单测不可 mock，故不直测 getOrCreate 的种入动作；改为把它拆出的两个纯决策/落库
 * 单元在此测全：{@link ClaudeCodeService#pickResumeId}（resume 选择）、{@link ClaudeCodeService#persistSessionId}
 * （监听器落库），并直测 {@link TerminalSession} 的监听器契约与 {@link BotInstance} 新列。
 * getOrCreate 里"inherit==null 时用 pickResumeId、随后 setSessionIdListener(id->persistSessionId)"的接线
 * 由代码走查确认（见交付回报时序走查）。TerminalSession 构造不起进程，故可安全 new 出来直测 fireSessionId。
 */
class SessionResumeTest {

    private final BotInstanceRepository repo = mock(BotInstanceRepository.class);

    private ClaudeCodeService svc() { return new ClaudeCodeService(repo); }

    private BotInstance instance(long id) {
        BotInstance b = new BotInstance();
        b.setId(id);
        return b;
    }

    // ===== pickResumeId：resume 选择纯函数，全分支 =====

    @Test
    void pickResumeIdReturnsIdWhenDirMatches() {
        assertEquals("sid-1", ClaudeCodeService.pickResumeId("sid-1", "D:/work", "D:/work"));
    }

    @Test
    void pickResumeIdRejectsDifferentDir() {
        assertNull(ClaudeCodeService.pickResumeId("sid-1", "D:/work", "D:/other"));
    }

    @Test
    void pickResumeIdRejectsBlankOrNullId() {
        assertNull(ClaudeCodeService.pickResumeId("", "D:/work", "D:/work"));
        assertNull(ClaudeCodeService.pickResumeId("   ", "D:/work", "D:/work"));
        assertNull(ClaudeCodeService.pickResumeId(null, "D:/work", "D:/work"));
    }

    @Test
    void pickResumeIdTreatsNullDirsAsEmpty() {
        assertEquals("sid-1", ClaudeCodeService.pickResumeId("sid-1", null, null));
        assertEquals("sid-1", ClaudeCodeService.pickResumeId("sid-1", "", null));
        assertEquals("sid-1", ClaudeCodeService.pickResumeId("sid-1", null, ""));
        // 一边有目录一边空 → 视为不同，拒绝 resume
        assertNull(ClaudeCodeService.pickResumeId("sid-1", "", "D:/work"));
        assertNull(ClaudeCodeService.pickResumeId("sid-1", "D:/work", ""));
    }

    // ===== persistSessionId：监听器落库，mock repo verify =====

    @Test
    void persistSessionIdWritesBothColumns() {
        when(repo.findById(9L)).thenReturn(Optional.of(instance(9L)));

        svc().persistSessionId(9L, "sid-new", "D:/work");

        ArgumentCaptor<BotInstance> cap = ArgumentCaptor.forClass(BotInstance.class);
        verify(repo).save(cap.capture());
        assertEquals("sid-new", cap.getValue().getLastClaudeSessionId());
        assertEquals("D:/work", cap.getValue().getLastClaudeSessionDir());
    }

    @Test
    void persistSessionIdNormalizesNullsToEmpty() {
        when(repo.findById(9L)).thenReturn(Optional.of(instance(9L)));

        svc().persistSessionId(9L, null, null);

        ArgumentCaptor<BotInstance> cap = ArgumentCaptor.forClass(BotInstance.class);
        verify(repo).save(cap.capture());
        assertEquals("", cap.getValue().getLastClaudeSessionId());
        assertEquals("", cap.getValue().getLastClaudeSessionDir());
    }

    @Test
    void persistSessionIdSkipsWhenInstanceMissing() {
        when(repo.findById(9L)).thenReturn(Optional.empty());

        svc().persistSessionId(9L, "sid", "D:/work");

        verify(repo, never()).save(any());
    }

    @Test
    void persistSessionIdSwallowsDbFailure() {
        when(repo.findById(9L)).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> svc().persistSessionId(9L, "sid", "D:/work"));
    }

    // ===== clearPersistedSessionId：/new 清库，destroySession 不清（语义由 getOrCreate 走查覆盖） =====

    @Test
    void clearPersistedSessionIdEmptiesBothColumns() {
        BotInstance inst = instance(9L);
        inst.setLastClaudeSessionId("sid-old");
        inst.setLastClaudeSessionDir("D:/work");
        when(repo.findById(9L)).thenReturn(Optional.of(inst));

        svc().clearPersistedSessionId(9L);

        ArgumentCaptor<BotInstance> cap = ArgumentCaptor.forClass(BotInstance.class);
        verify(repo).save(cap.capture());
        assertEquals("", cap.getValue().getLastClaudeSessionId());
        assertEquals("", cap.getValue().getLastClaudeSessionDir());
    }

    @Test
    void clearPersistedSessionIdSwallowsDbFailure() {
        when(repo.findById(9L)).thenThrow(new RuntimeException("db down"));

        assertDoesNotThrow(() -> svc().clearPersistedSessionId(9L));
    }

    // ===== TerminalSession 监听器契约：触发、null-safe、异常吞掉、每轮最新 =====

    private TerminalSession session() {
        return new TerminalSession("k", "D:/work", "model", SandboxPolicy.LEVEL_FULL);
    }

    @Test
    void sessionIdListenerFiresOnEachNewId() {
        TerminalSession s = session();
        AtomicReference<String> got = new AtomicReference<>();
        s.setSessionIdListener(got::set);

        s.fireSessionId("sid-1");
        assertEquals("sid-1", got.get());
        s.fireSessionId("sid-2");   // --resume 派生新 id：持久化"每轮最新"
        assertEquals("sid-2", got.get());
    }

    @Test
    void sessionIdListenerIgnoresBlankId() {
        TerminalSession s = session();
        AtomicInteger calls = new AtomicInteger();
        s.setSessionIdListener(id -> calls.incrementAndGet());

        s.fireSessionId(null);
        s.fireSessionId("");
        s.fireSessionId("   ");
        assertEquals(0, calls.get());
    }

    @Test
    void sessionIdListenerSwallowsCallbackException() {
        TerminalSession s = session();
        s.setSessionIdListener(id -> { throw new RuntimeException("listener boom"); });

        assertDoesNotThrow(() -> s.fireSessionId("sid-1"));   // 回调异常绝不影响协议解析线程
    }

    @Test
    void fireSessionIdIsSafeWithoutListener() {
        assertDoesNotThrow(() -> session().fireSessionId("sid-1"));   // 未注册监听器：null-safe
    }

    @Test
    void inheritSessionIdOnlySeedsWhenFieldEmpty() {
        TerminalSession s = session();
        s.inheritSessionId("seed-1");
        assertEquals("seed-1", s.getClaudeSessionId());
        s.inheritSessionId("seed-2");   // 已非空 → 不覆盖：正好适配 getOrCreate 重启接回最新持久化 id
        assertEquals("seed-1", s.getClaudeSessionId());
    }

    // ===== BotInstance 新列 null 归一 =====

    @Test
    void botInstanceNormalizesNullSessionColumns() {
        BotInstance b = new BotInstance();
        assertEquals("", b.getLastClaudeSessionId());
        assertEquals("", b.getLastClaudeSessionDir());
        b.setLastClaudeSessionId(null);
        b.setLastClaudeSessionDir(null);
        assertEquals("", b.getLastClaudeSessionId());
        assertEquals("", b.getLastClaudeSessionDir());
        b.setLastClaudeSessionId("sid");
        b.setLastClaudeSessionDir("D:/x");
        assertEquals("sid", b.getLastClaudeSessionId());
        assertEquals("D:/x", b.getLastClaudeSessionDir());
    }
}
