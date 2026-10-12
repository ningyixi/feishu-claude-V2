package com.lark.oapi.sample.ws.service;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 机器人互 @ 死循环刹车（参考 larksuite/openclaw-lark src/messaging/inbound/bot-loop-guard.ts）。
 * <p>
 * 两个机器人在群里互相 @，每条回复都会唤醒对方 → 无限对聊（哪怕只是互回"⛔ 无权访问"）。
 * 按会话（实例 + chat + thread）统计"连续来自机器人的入站消息数"，超过上限后停止回复，
 * 直到有真人发言（{@link #reset}）。会话空闲超过 idleResetMs 视为新一轮，计数归零。
 */
public class BotLoopGuard {

    public record Verdict(boolean allowed, int count, int limit, boolean justTripped) {}

    private static final class State {
        int count;
        long updatedAt;
        boolean tripNotified;
    }

    private final int maxConsecutiveBotTurns;
    private final long idleResetMs;
    private final Map<String, State> states = new LinkedHashMap<>();
    private long lastSweepAt;

    public BotLoopGuard(int maxConsecutiveBotTurns, long idleResetMs) {
        this.maxConsecutiveBotTurns = Math.max(1, maxConsecutiveBotTurns);
        this.idleResetMs = Math.max(1_000, idleResetMs);
    }

    public static String key(Object instanceId, String chatId, String threadId) {
        return instanceId + ":" + (chatId != null ? chatId : "") + ":" + (threadId != null ? threadId : "");
    }

    /** 记一次机器人发来的消息并判定是否还允许回复；justTripped=本次刚越线（用于只发一次提示） */
    public synchronized Verdict noteBotTurn(String key, long now) {
        sweep(now);
        State s = states.get(key);
        if (s == null || now - s.updatedAt > idleResetMs) {
            s = new State();
            states.put(key, s);
        }
        s.count++;
        s.updatedAt = now;
        boolean allowed = s.count <= maxConsecutiveBotTurns;
        boolean justTripped = false;
        if (!allowed && !s.tripNotified) {
            s.tripNotified = true;
            justTripped = true;
        }
        return new Verdict(allowed, s.count, maxConsecutiveBotTurns, justTripped);
    }

    /** 真人发言 → 该会话计数清零、恢复回复 */
    public synchronized void reset(String key) {
        states.remove(key);
    }

    public synchronized int size() { return states.size(); }

    private void sweep(long now) {
        if (now - lastSweepAt < idleResetMs) return;
        lastSweepAt = now;
        Iterator<State> it = states.values().iterator();
        while (it.hasNext()) if (now - it.next().updatedAt > idleResetMs) it.remove();
    }
}
