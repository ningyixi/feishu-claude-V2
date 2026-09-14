package com.lark.oapi.sample.ws.a2a;

import com.google.gson.JsonObject;
import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.service.ClaudeCodeService;
import com.lark.oapi.sample.ws.service.TerminalSession;
import com.lark.oapi.sample.ws.service.ToolStep;
import com.lark.oapi.sample.ws.service.TurnListener;
import com.lark.oapi.sample.ws.service.TurnResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * A2A 任务派发器 — axocoatl TaskHandler 的 Java 移植，即"包在终端 Claude 外面的 wrapper"。
 * <p>
 * 一个 A2A 任务 == 接收实例常驻终端会话的一个 turn：
 * receiver_id → BotInstance → ClaudeCodeService.chat(...)。
 * 与飞书私聊 / E2E / 供应商工作台完全同一条执行路径（常驻进程、turnLock 串行、session 上下文共享），
 * A2A 只是同一条执行的任务信封门面 —— 对齐 axocoatl 理念
 * "A2A is the public, framework-neutral face of the same underlying agent execution"。
 * <p>
 * 移植时补齐的原实现欠账：
 * <ul>
 *   <li>timeout_secs 强制执行（服务端 future.get(timeout)，axocoatl 传而不用）；
 *       缺省值垫在 TerminalSession 看门狗之后（+120s），避免双杀竞态</li>
 *   <li>准入上限（经 {@link A2ATaskRegistry#tryAdmit}，溢出快速失败 "agent busy, queue full"）</li>
 * </ul>
 * 保留的原实现约定：宽容输入提取（input.input 字符串优先，否则整段 stringify，永不报形状错误）、
 * receiver 找不到 → Failed("Agent 'xxx' not found")、业务失败走 Failed+error 字符串而非异常。
 * <p>
 * 内部模式：仅进程内调用（E2E 编排器），不暴露 HTTP 端点。
 */
@Service
public class A2ATaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(A2ATaskDispatcher.class);

    /** 输出摘录长度（群聊信封展示用） */
    private static final int EXCERPT_CHARS = 300;

    private final ClaudeCodeService claudeCodeService;
    private final BotInstanceRepository instanceRepo;
    private final A2ATaskRegistry registry;

    public A2ATaskDispatcher(ClaudeCodeService claudeCodeService,
                             BotInstanceRepository instanceRepo,
                             A2ATaskRegistry registry) {
        this.claudeCodeService = claudeCodeService;
        this.instanceRepo = instanceRepo;
        this.registry = registry;
    }

    /** 状态流转事件 — 编排器据此更新轮次信封（rec.a2a）并广播群聊 a2a_task 事件 */
    public static class A2AStatusEvent {
        public final String taskId;
        public final A2AProtocol.TaskStatus status;
        public final long atMs = System.currentTimeMillis();
        /** 终态时非空：任务从 Running 到终态的耗时 */
        public final Long durationMs;
        /** Completed 时的输出摘录（截断 {@value A2ATaskDispatcher#EXCERPT_CHARS} 字） */
        public final String outputExcerpt;
        /** Failed 时的错误文本 */
        public final String error;

        public A2AStatusEvent(String taskId, A2AProtocol.TaskStatus status,
                              Long durationMs, String outputExcerpt, String error) {
            this.taskId = taskId;
            this.status = status;
            this.durationMs = durationMs;
            this.outputExcerpt = outputExcerpt;
            this.error = error;
        }
    }

    /**
     * 派发一个 A2A 任务并阻塞至终态。
     *
     * @param task     任务信封（input/context/timeout_secs）
     * @param listener 轮次流事件监听器（可空）——原样接到 chat 调用上，群聊流式不受影响
     * @param onStatus 状态流转回调（Running/Completed/Failed，可空）；Pending 由调用方在 dispatch 前自行广播
     */
    public A2AProtocol.A2ATaskResult dispatch(A2AProtocol.A2ATask task, TurnListener listener,
                                              Consumer<A2AStatusEvent> onStatus) {
        if (task == null || task.id == null) {
            return A2ATaskResult0.failed(task != null ? task.id : null, "malformed task");
        }
        // 1. 准入（每 receiver 非终态任务上限）
        if (!registry.tryAdmit(task)) {
            return finish(task, A2ATaskResult0.failed(task.id, "agent busy, queue full"), onStatus);
        }
        // 2. receiver 解析：数字实例 id 优先，其次实例名精确匹配（axocoatl 语义：找不到 → Failed）
        BotInstance inst = resolveReceiver(task.receiverId);
        if (inst == null) {
            return finish(task, A2ATaskResult0.failed(task.id, "Agent '" + task.receiverId + "' not found"), onStatus);
        }
        // 3. 宽容输入提取
        String prompt = extractInput(task.input);
        // 4. context 缺省回填实例配置。working_dir/model 只信调用方传入的实例配置快照值
        //    （E2E Member 即建任务时从 BotInstance 抄的），禁止任意外部值——
        //    ClaudeCodeService.getOrCreate 发现目录/模型变化会 destroy 重建会话（杀活跃轮次+丢上下文）
        A2AProtocol.TaskContext ctx = task.context != null ? task.context : new A2AProtocol.TaskContext();
        String workingDir = firstNonBlank(ctx.workingDir, inst.getClaudeWorkingDir(), inst.getShellWorkingDir(), "");
        String model = firstNonBlank(ctx.model, inst.getClaudeModel(), null);
        String openIdTag = "a2a-" + task.id;   // 仅日志标识（session 按 instanceId 路由）

        // 5. 执行：虚拟线程跑 chat + 服务端超时强制
        registry.markRunning(task.id);
        emit(onStatus, new A2AStatusEvent(task.id, A2AProtocol.TaskStatus.RUNNING, null, null, null));
        long timeoutMs = task.timeoutSecs != null && task.timeoutSecs > 0
                ? task.timeoutSecs * 1000L
                : watchdogTimeoutMs() + 120_000L;

        AtomicBoolean turnStarted = new AtomicBoolean(false);
        TurnListener wrapped = wrapListener(listener, turnStarted);
        CompletableFuture<String> fut = new CompletableFuture<>();
        Thread.ofVirtual().name("a2a-task-" + task.id).start(() -> {
            try {
                fut.complete(claudeCodeService.chat(inst.getId(), openIdTag, prompt, null,
                        workingDir, model, wrapped));
            } catch (Throwable t) {
                fut.completeExceptionally(t);
            }
        });

        String reply;
        try {
            reply = fut.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            // 超时：仅当该任务的 turn 已真正开始才 best-effort 中止（abortCurrentTurn 中止的是
            // 实例会话的"当前轮次"——若任务还在排队，当前轮次属于别的功能，绝不能误杀）
            if (turnStarted.get()) {
                TerminalSession s = claudeCodeService.getSession(inst.getId());
                if (s != null) s.abortCurrentTurn();
            }
            log.warn("[A2A] 任务超时: id={}, receiver={}, timeoutMs={}", task.id, task.receiverId, timeoutMs);
            return finish(task, A2ATaskResult0.failed(task.id, "timeout after " + (timeoutMs / 1000) + "s"), onStatus);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return finish(task, A2ATaskResult0.failed(task.id, "interrupted"), onStatus);
        } catch (Exception e) {
            Throwable root = e.getCause() != null ? e.getCause() : e;
            return finish(task, A2ATaskResult0.failed(task.id, "dispatch error: " + root.getMessage()), onStatus);
        }

        // 6. 结果封装（chat 内部已把异常翻译成 "❌ ..." 文本返回，这里按前缀识别业务失败）
        if (reply == null) reply = "";
        if (reply.startsWith("❌") || reply.startsWith("⏰")) {
            return finish(task, A2ATaskResult0.failed(task.id, reply), onStatus);
        }
        A2AProtocol.A2ATaskResult ok = new A2AProtocol.A2ATaskResult();
        ok.taskId = task.id;
        ok.status = A2AProtocol.TaskStatus.COMPLETED;
        ok.output = new JsonObject();
        ok.output.addProperty("content", reply);
        return finish(task, ok, onStatus);
    }

    // ===== 内部方法 =====

    private A2AProtocol.A2ATaskResult finish(A2AProtocol.A2ATask task, A2AProtocol.A2ATaskResult result,
                                             Consumer<A2AStatusEvent> onStatus) {
        registry.markFinished(task.id, result);
        A2ATaskRegistry.Record rec = registry.get(task.id);
        Long durationMs = rec != null && rec.startedAt > 0 ? System.currentTimeMillis() - rec.startedAt : null;
        String excerpt = null;
        if (result.status == A2AProtocol.TaskStatus.COMPLETED && result.output != null
                && result.output.has("content")) {
            String content = result.output.get("content").getAsString();
            excerpt = content.length() > EXCERPT_CHARS ? content.substring(0, EXCERPT_CHARS) + "…" : content;
        }
        emit(onStatus, new A2AStatusEvent(task.id, result.status, durationMs, excerpt, result.error));
        log.info("[A2A] 任务终态: id={}, sender={}, receiver={}, status={}, 耗时={}ms",
                task.id, task.senderId, task.receiverId, result.status.wire(), durationMs);
        return result;
    }

    private void emit(Consumer<A2AStatusEvent> onStatus, A2AStatusEvent ev) {
        if (onStatus == null) return;
        try {
            onStatus.accept(ev);
        } catch (Exception e) {
            log.warn("[A2A] 状态回调异常（吞掉不影响任务）: {}", e.getMessage());
        }
    }

    /** receiver_id → 实例：先按数字 id，再按名称精确匹配 */
    BotInstance resolveReceiver(String receiverId) {
        if (receiverId == null || receiverId.isBlank()) return null;
        String rid = receiverId.trim();
        try {
            return instanceRepo.findById(Long.parseLong(rid)).orElse(null);
        } catch (NumberFormatException ignored) {
            // 非数字 → 走名称匹配
        }
        return instanceRepo.findAll().stream()
                .filter(i -> rid.equals(i.getName()))
                .findFirst().orElse(null);
    }

    /** 宽容输入提取（axocoatl 约定）：input["input"] 字符串优先，否则整段 JSON stringify，永不报形状错误 */
    static String extractInput(JsonObject input) {
        if (input == null || input.isJsonNull()) return "";
        if (input.has("input") && !input.get("input").isJsonNull()) {
            var el = input.get("input");
            if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) return el.getAsString();
            return el.toString();
        }
        return input.toString();
    }

    /** 缺省超时 = TerminalSession 看门狗值 + 120s（严格垫在看门狗之后，让看门狗先干净收尾） */
    static long watchdogTimeoutMs() {
        return Long.getLong("terminal.hardTurnTimeoutMinutes", 60L) * 60_000L;
    }

    /** 包装监听器：记录 turn 是否真正开始（取消/超时只在此后才允许 abortCurrentTurn），其余原样转发 */
    private static TurnListener wrapListener(TurnListener delegate, AtomicBoolean turnStarted) {
        return new TurnListener() {
            @Override public void onTurnStart() {
                turnStarted.set(true);
                if (delegate != null) delegate.onTurnStart();
            }
            @Override public void onQueued() {
                if (delegate != null) delegate.onQueued();
            }
            @Override public void onThinkingDelta(String delta) {
                if (delegate != null) delegate.onThinkingDelta(delta);
            }
            @Override public void onTextDelta(String delta) {
                if (delegate != null) delegate.onTextDelta(delta);
            }
            @Override public void onToolStart(ToolStep step) {
                if (delegate != null) delegate.onToolStart(step);
            }
            @Override public void onToolEnd(ToolStep step) {
                if (delegate != null) delegate.onToolEnd(step);
            }
            @Override public void onTurnComplete(TurnResult result) {
                if (delegate != null) delegate.onTurnComplete(result);
            }
            @Override public void onTurnError(String message, boolean aborted) {
                if (delegate != null) delegate.onTurnError(message, aborted);
            }
        };
    }

    private static String firstNonBlank(String... vals) {
        if (vals == null) return null;
        for (String v : vals) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }

    /** A2ATaskResult 构建助手（Failed 终态：output=null + error 字符串，axocoatl 形状） */
    private static final class A2ATaskResult0 {
        static A2AProtocol.A2ATaskResult failed(String taskId, String error) {
            A2AProtocol.A2ATaskResult r = new A2AProtocol.A2ATaskResult();
            r.taskId = taskId;
            r.status = A2AProtocol.TaskStatus.FAILED;
            r.error = error;
            return r;
        }
    }
}
