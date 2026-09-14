package com.lark.oapi.sample.ws.a2a;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A2A 任务内存注册表 — 状态流转记录（Pending→Running→终态）+ 准入上限 + 按前缀清理。
 * <p>
 * 补齐 axocoatl 原实现的两个欠账：
 * <ul>
 *   <li>无任务注册表/状态追踪 → 这里记录每个任务的状态与时间戳，供编排器广播与前端展示</li>
 *   <li>同 agent 并发任务无界排队 → {@link #tryAdmit} 给每个 receiver 设非终态任务上限，
 *       溢出快速返回失败（"agent busy"）比无限排队安全</li>
 * </ul>
 * 内部模式：生命周期跟随 E2E 任务——destroyTask 时 {@link #purgeByPrefix} 清理防泄漏；
 * 终态记录总量封顶 {@value #MAX_FINISHED}，FIFO 淘汰。
 */
@Service
public class A2ATaskRegistry {

    private static final Logger log = LoggerFactory.getLogger(A2ATaskRegistry.class);

    /** 每个 receiver 的非终态（Pending+Running）任务上限。会话本身串行，>1 即在排队 */
    private static final int MAX_PER_RECEIVER = 4;
    /** 终态记录保留上限，超出 FIFO 淘汰 */
    private static final int MAX_FINISHED = 500;

    public static class Record {
        public final A2AProtocol.A2ATask task;
        public volatile A2AProtocol.TaskStatus status = A2AProtocol.TaskStatus.PENDING;
        public final long createdAt = System.currentTimeMillis();
        public volatile long startedAt;
        public volatile long finishedAt;
        public volatile A2AProtocol.A2ATaskResult result;

        Record(A2AProtocol.A2ATask task) { this.task = task; }
    }

    private final Map<String, Record> records = new ConcurrentHashMap<>();
    private final Deque<String> finishedOrder = new ArrayDeque<>();

    /**
     * 准入：登记任务为 Pending。receiver 的非终态任务数达到上限时返回 false（不登记），
     * dispatcher 将其转为 Failed("agent busy, queue full")。
     */
    public synchronized boolean tryAdmit(A2AProtocol.A2ATask task) {
        if (task == null || task.id == null) return false;
        long load = 0;
        for (Record r : records.values()) {
            if ((r.status == A2AProtocol.TaskStatus.PENDING || r.status == A2AProtocol.TaskStatus.RUNNING)
                    && Objects.equals(r.task.receiverId, task.receiverId)) {
                load++;
            }
        }
        if (load >= MAX_PER_RECEIVER) {
            log.warn("[A2A] 准入拒绝: receiver={} 非终态任务已达上限 {}", task.receiverId, MAX_PER_RECEIVER);
            return false;
        }
        records.put(task.id, new Record(task));
        return true;
    }

    public void markRunning(String taskId) {
        Record r = records.get(taskId);
        if (r != null && r.status == A2AProtocol.TaskStatus.PENDING) {
            r.status = A2AProtocol.TaskStatus.RUNNING;
            r.startedAt = System.currentTimeMillis();
        }
    }

    /** 落终态并登记 FIFO 淘汰队列（synchronized 与 tryAdmit/淘汰互斥） */
    public synchronized void markFinished(String taskId, A2AProtocol.A2ATaskResult result) {
        Record r = records.get(taskId);
        if (r == null) return;
        r.status = result != null ? result.status : A2AProtocol.TaskStatus.FAILED;
        r.result = result;
        r.finishedAt = System.currentTimeMillis();
        finishedOrder.addLast(taskId);
        while (finishedOrder.size() > MAX_FINISHED) {
            String oldest = finishedOrder.pollFirst();
            if (oldest != null) records.remove(oldest);
        }
    }

    public Record get(String taskId) {
        return taskId != null ? records.get(taskId) : null;
    }

    /** 按任务 id 前缀清理（E2E destroyTask 时调用，防止内存泄漏）；返回清理条数 */
    public synchronized int purgeByPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return 0;
        int n = 0;
        var it = records.keySet().iterator();
        while (it.hasNext()) {
            if (it.next().startsWith(prefix)) { it.remove(); n++; }
        }
        finishedOrder.removeIf(id -> id.startsWith(prefix));
        if (n > 0) log.info("[A2A] 注册表清理: prefix={}, 移除 {} 条", prefix, n);
        return n;
    }

    public int size() { return records.size(); }
}
