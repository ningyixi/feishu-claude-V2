package com.lark.oapi.sample.ws.service;

import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * S5 排队边界（11-17-unblock-instance-chat AC6/AC7）— TerminalSession 层实跑断言，不起真实 CLI 进程。
 * <p>
 * 用反射装配"假进程 + 手工轮次"（沿用 {@code ResultHandoffTest} 的 Turn 反射模式）：
 * <ul>
 *   <li><b>AC6 反向断言（防过度修复）</b>：存在活跃轮次时，第二条消息必须进 pendingQueue 排队——
 *       getQueuedCount()/queuedSnapshot() 行为不变、onQueued 触发、<b>不注入运行中轮次的 stdin、
 *       不 abort、不换轮</b>；前一轮正常结束后排队消息按序开新轮（排队就是排队，不丢弃不插队）。</li>
 *   <li><b>AC7 卡点态直通</b>：会话空闲（卡点态的物理事实）时，消息立即开新轮——
 *       不走排队分支（onQueued 不触发、pendingQueue 恒空）、零等待写入 stdin。</li>
 * </ul>
 * 本任务代码不新增任何 steer() 调用；"不注入"由 stdin 零字节断言覆盖（steer 与常规写入共用同一 writer）。
 */
class TerminalQueueBoundaryTest {

    // ===== 反射装配工具 =====

    private static void set(TerminalSession s, String field, Object value) throws Exception {
        Field f = TerminalSession.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(s, value);
    }

    private static Object get(TerminalSession s, String field) {
        try {
            Field f = TerminalSession.class.getDeclaredField(field);
            f.setAccessible(true);
            return f.get(s);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    private static Object newTurn(TurnListener listener) throws Exception {
        Class<?> c = Class.forName(TerminalSession.class.getName() + "$Turn");
        Constructor<?> ctor = c.getDeclaredConstructor(TurnListener.class);
        ctor.setAccessible(true);
        return ctor.newInstance(listener);
    }

    @SuppressWarnings("unchecked")
    private static CompletableFuture<TurnResult> futureOf(Object turn) {
        try {
            Field f = turn.getClass().getDeclaredField("future");
            f.setAccessible(true);
            return (CompletableFuture<TurnResult>) f.get(turn);
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }

    /** 假进程会话：alive=true + mock Process + stdin 捕获 writer（不拉起真实 claude CLI） */
    private static TerminalSession fakeSession(ByteArrayOutputStream stdin) throws Exception {
        TerminalSession s = new TerminalSession("queue-boundary-test", "", "");
        Process p = mock(Process.class);
        when(p.isAlive()).thenReturn(true);
        set(s, "process", p);
        set(s, "alive", true);
        set(s, "writer", new BufferedWriter(new OutputStreamWriter(stdin, StandardCharsets.UTF_8)));
        set(s, "lastEventTime", System.currentTimeMillis());   // 看门狗静默（测试远短于 120s 空闲阈值）
        return s;
    }

    private static void awaitTrue(BooleanSupplier cond, String what) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) fail("等待超时: " + what);
            Thread.sleep(20);
        }
    }

    private static String stdinText(ByteArrayOutputStream stdin) {
        return stdin.toString(StandardCharsets.UTF_8);
    }

    /** 记录型监听器：onQueued/onTurnStart 事件到点可断言 */
    private static class RecListener implements TurnListener {
        final CountDownLatch queued = new CountDownLatch(1);
        final CountDownLatch started = new CountDownLatch(1);
        volatile boolean queuedFired;
        public void onTurnStart() { started.countDown(); }
        public void onQueued() { queuedFired = true; queued.countDown(); }
        public void onThinkingDelta(String d) {}
        public void onTextDelta(String d) {}
        public void onToolStart(ToolStep step) {}
        public void onToolEnd(ToolStep step) {}
        public void onTurnComplete(TurnResult r) {}
        public void onTurnError(String m, boolean a) {}
    }

    private static class NoopListener implements TurnListener {
        public void onTurnStart() {}
        public void onThinkingDelta(String d) {}
        public void onTextDelta(String d) {}
        public void onToolStart(ToolStep step) {}
        public void onToolEnd(ToolStep step) {}
        public void onTurnComplete(TurnResult r) {}
        public void onTurnError(String m, boolean a) {}
    }

    // ===== AC6：活跃轮次在场 → 第二条消息必须排队，不注入、不打断、不提前执行 =====

    @Test
    void activeTurnForcesQueueingWithoutInjectionOrAbort() throws Exception {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        TerminalSession s = fakeSession(stdin);
        try {
            // 第一轮"在飞"：currentTurn 未完成 + turnLock 被占用（等价于真实 sendMessage 持锁状态）
            Object turn1 = newTurn(new NoopListener());
            set(s, "currentTurn", turn1);
            ReentrantLock turnLock = (ReentrantLock) get(s, "turnLock");
            turnLock.lock();
            assertTrue(s.isTurnActive());

            RecListener second = new RecListener();
            AtomicReference<String> reply = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread.ofVirtual().name("ac6-second-sender").start(() -> {
                try { reply.set(s.sendMessage("第二条消息", null, second, "工作台")); }
                finally { done.countDown(); }
            });

            // —— 排队侧断言（第二条被挡在 turnLock 外）——
            assertTrue(second.queued.await(10, TimeUnit.SECONDS), "活跃轮次在场必须触发 onQueued（排队提示）");
            // pendingQueue 登记先于 onQueued（latch 同步点可靠）；turnLock 等待队列在 onQueued 之后才进入，轮询等它到位
            var snapshot = s.queuedSnapshot();
            assertEquals(1, snapshot.size());
            assertTrue(snapshot.get(0).preview().contains("第二条消息"));
            assertEquals("工作台", snapshot.get(0).kind());
            awaitTrue(() -> s.getQueuedCount() == 1, "turnLock 等待队列必须登记第二条");
            // 不得注入运行中的轮次：steer 与常规写入共用同一 writer，stdin 零字节 = 什么都没插进去
            assertEquals("", stdinText(stdin), "活跃轮次期间绝不允许向 stdin 注入任何内容");
            // 不得 abort / 提前换轮：第一轮 future 未完成、currentTurn 未变、onTurnStart 未触发
            assertFalse(futureOf(turn1).isDone(), "排队绝不允许中止运行中的轮次");
            assertSame(turn1, get(s, "currentTurn"));
            assertEquals(1, second.started.getCount(), "第二条不得被提前执行");

            // —— 第一轮正常结束 → 排队消息依序拿到锁、作为新轮次执行（不丢弃）——
            futureOf(turn1).complete(new TurnResult("第一轮完成", false));
            turnLock.unlock();

            awaitTrue(() -> stdinText(stdin).contains("第二条消息"), "解锁后第二条应写入 stdin 开新轮");
            Object turn2 = get(s, "currentTurn");
            assertNotSame(turn1, turn2);
            assertTrue(s.isTurnActive());
            assertEquals(0, s.getQueuedCount(), "拿到锁即从排队面板摘除（既有语义不变）");
            assertTrue(s.queuedSnapshot().isEmpty());

            futureOf(turn2).complete(new TurnResult("第二条回答", false));
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals("第二条回答", reply.get());
            assertTrue(second.queuedFired, "排队路径必须留下 onQueued 痕迹");
        } finally {
            s.destroy();
        }
    }

    // ===== AC7：会话空闲（卡点态物理事实）→ 直通开新轮，绝不走排队分支 =====

    @Test
    void idleSessionStartsNewTurnImmediatelyWithoutQueueBranch() throws Exception {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        TerminalSession s = fakeSession(stdin);
        try {
            assertFalse(s.isTurnActive());   // 卡点态：CLI 轮次已结束，会话必然空闲

            RecListener rec = new RecListener();
            AtomicReference<String> reply = new AtomicReference<>();
            CountDownLatch done = new CountDownLatch(1);
            Thread.ofVirtual().name("ac7-stall-chat").start(() -> {
                try { reply.set(s.sendMessage("卡点期间的问题", null, rec, "飞书")); }
                finally { done.countDown(); }
            });

            // 立即开新轮：onTurnStart 触发、消息零等待写入 stdin
            assertTrue(rec.started.await(10, TimeUnit.SECONDS), "空闲会话必须立即开新轮（onTurnStart）");
            awaitTrue(() -> stdinText(stdin).contains("卡点期间的问题"), "消息应立即写入 stdin");
            // 未走排队分支：onQueued 不触发、pendingQueue 恒空、锁队列无人等待
            assertFalse(rec.queuedFired, "空闲直通绝不允许触发排队分支");
            assertEquals(0, s.getQueuedCount());
            assertTrue(s.queuedSnapshot().isEmpty());
            assertTrue(s.isTurnActive());

            Object turn = get(s, "currentTurn");
            assertNotNull(turn);
            futureOf(turn).complete(new TurnResult("立即回答", false));
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals("立即回答", reply.get());
        } finally {
            s.destroy();
        }
    }
}
