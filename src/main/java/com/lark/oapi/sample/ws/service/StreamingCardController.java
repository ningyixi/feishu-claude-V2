package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonObject;
import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.v1.model.*;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.PatchMessageReq;
import com.lark.oapi.service.im.v1.model.PatchMessageReqBody;
import com.lark.oapi.service.im.v1.model.PatchMessageResp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 流式卡片控制器 — openclaw-lark StreamingCardController + FlushController 的 Java 移植。
 * <p>
 * 每轮对话一个实例，实现 {@link TurnListener} 消费 TerminalSession 的确定性事件回调：
 * <ul>
 *   <li>创建：CardKit card.create(streaming_mode:true 初始卡) → im.message.create 引用 card_id</li>
 *   <li>流式：cardElement.content 推<b>全量累积文本</b>+单调 sequence（飞书端 diff + 打字机动画）</li>
 *   <li>节流四件套：300ms 节流 + mutex/needsReflush 合并 + lastFlushedText 去重 + 230020 限频跳帧</li>
 *   <li>终态：card.settings(streaming_mode:false) → card.update(完整终态卡)；即使中间帧全部被跳过也能兜底</li>
 *   <li>降级：CardKit 任一环节失败 → im.message.patch 整卡替换（1500ms 节流），仍是原地更新不刷屏</li>
 * </ul>
 * 状态机：CREATING → STREAMING → {COMPLETED, ABORTED, FAILED}，终态后所有回调 no-op。
 * <p>
 * 回调在 TerminalSession 的 reader 线程触发，<b>绝不阻塞</b>：所有飞书 HTTP 调用都提交到
 * 专用虚拟线程 executor（{@link #HTTP}），定时器只做调度（{@link #SCHED}）。
 */
public class StreamingCardController implements TurnListener {

    private static final Logger log = LoggerFactory.getLogger(StreamingCardController.class);

    // 节流常量（对照 openclaw-lark reply-dispatcher-types.ts THROTTLE_CONSTANTS）
    private static final long CARDKIT_THROTTLE_MS = 300;      // cardElement.content（为流式设计，可较快）
    private static final long PATCH_THROTTLE_MS = 1500;       // im.message.patch 限频严格
    private static final long PANEL_THROTTLE_MS = 1500;       // 工具面板整卡更新节流
    private static final long LONG_GAP_THRESHOLD_MS = 2000;   // 超过此间隔视为长间隔（工具执行中）
    private static final long BATCH_AFTER_GAP_MS = 300;       // 长间隔后先攒这么久再发首帧
    private static final int RATE_LIMITED_CODE = 230020;      // 飞书卡片限频错误码：跳帧不重试

    /** 全局定时器线程：只跑轻量调度，绝不执行 HTTP */
    private static final ScheduledExecutorService SCHED = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "card-flush-timer");
        t.setDaemon(true);
        return t;
    });
    /** 全局 HTTP executor：虚拟线程，飞书接口再慢也不阻塞定时器/reader */
    private static final ExecutorService HTTP = Executors.newVirtualThreadPerTaskExecutor();

    enum Phase { INIT, CREATING, STREAMING, COMPLETED, ABORTED, FAILED }

    private final Client client;
    private final String receiveIdType;   // open_id / chat_id
    private final String receiveId;

    private final long startMs = System.currentTimeMillis();
    private volatile Phase phase = Phase.INIT;
    private volatile String cardId;          // CardKit 卡片实体 id（降级后置 null）
    private volatile String sentMessageId;   // 已发送的 im 消息 id（patch 目标）
    private volatile boolean patchMode;      // 降级模式
    private volatile boolean messageSent;    // 是否成功发出过任何消息（供上层兜底判断）
    private CompletableFuture<Void> creationFuture = new CompletableFuture<>();

    private final AtomicInteger sequence = new AtomicInteger(0);
    private final StringBuilder answer = new StringBuilder();
    private final StringBuilder thinking = new StringBuilder();
    private final List<ToolStep> steps = new CopyOnWriteArrayList<>();

    // flush 状态机（全部受 this 监视器保护）
    private boolean flushInProgress;
    private boolean needsReflush;
    private String lastFlushedText = "";
    private long lastFlushTime;
    private ScheduledFuture<?> pendingFlushTimer;
    private long lastPanelUpdate;
    private ScheduledFuture<?> pendingPanelTimer;

    public StreamingCardController(Client client, String receiveIdType, String receiveId) {
        this.client = client;
        this.receiveIdType = receiveIdType;
        this.receiveId = receiveId;
    }

    /** 本轮是否成功发出过消息（false 时上层应回退普通 replyText，避免用户什么都收不到） */
    public boolean isDelivered() {
        return messageSent;
    }

    // ===== TurnListener 回调（reader 线程，绝不阻塞） =====

    @Override
    public void onQueued() {
        HTTP.execute(() -> sendText("⏳ 上一条消息还在处理中，已排队等待…"));
    }

    @Override
    public void onTurnStart() {
        phase = Phase.CREATING;
        HTTP.execute(this::createCard);
    }

    @Override
    public void onThinkingDelta(String delta) {
        synchronized (this) { thinking.append(delta); }
        // 思考阶段仅在正文未开始时刷新卡片
        synchronized (this) { if (answer.length() > 0) return; }
        throttledFlush();
    }

    @Override
    public void onTextDelta(String delta) {
        synchronized (this) { answer.append(delta); }
        throttledFlush();
    }

    @Override
    public void onToolStart(ToolStep step) {
        steps.add(step);
        throttledPanelUpdate();
    }

    @Override
    public void onToolEnd(ToolStep step) {
        throttledPanelUpdate();
    }

    @Override
    public void onTurnComplete(TurnResult result) {
        HTTP.execute(() -> finalizeCard(result, false, null));
    }

    @Override
    public void onTurnError(String message, boolean aborted) {
        HTTP.execute(() -> {
            TurnResult r = snapshotResult();
            if (aborted) {
                r.aborted = true;
            } else {
                r.isError = true;
                String body = r.text != null && !r.text.isBlank() ? r.text + "\n\n" : "";
                r.text = body + "---\n**Error**: " + message;
            }
            finalizeCard(r, aborted, message);
        });
    }

    // ===== 卡片创建 =====

    private void createCard() {
        try {
            CreateCardResp resp = client.cardkit().v1().card().create(CreateCardReq.newBuilder()
                    .createCardReqBody(CreateCardReqBody.newBuilder()
                            .type("card_json")
                            .data(CardBuilder.buildStreamingInitialCard())
                            .build())
                    .build());
            if (resp.success() && resp.getData() != null && resp.getData().getCardId() != null) {
                String newCardId = resp.getData().getCardId();
                JsonObject content = new JsonObject();
                content.addProperty("type", "card");
                JsonObject data = new JsonObject();
                data.addProperty("card_id", newCardId);
                content.add("data", data);

                CreateMessageResp msgResp = sendInteractive(content.toString());
                if (msgResp != null && msgResp.success() && msgResp.getData() != null) {
                    cardId = newCardId;
                    sentMessageId = msgResp.getData().getMessageId();
                    messageSent = true;
                    phase = Phase.STREAMING;
                    creationFuture.complete(null);
                    log.info("[Card] CardKit 卡片已创建: cardId={}, msgId={}", cardId, sentMessageId);
                    flushAsync();   // 把创建期间累积的增量立即补一帧
                    return;
                }
                log.warn("[Card] 卡片消息发送失败，降级 patch 模式: {}", msgResp != null ? msgResp.getCode() + " " + msgResp.getMsg() : "null");
            } else {
                log.warn("[Card] CardKit create 失败，降级 patch 模式: code={}, msg={}（若为权限问题请在飞书开发者后台为应用添加 CardKit 权限）",
                        resp.getCode(), resp.getMsg());
            }
        } catch (Exception e) {
            log.warn("[Card] CardKit create 异常，降级 patch 模式: {}", e.getMessage());
        }
        createPatchFallback();
    }

    /** 降级：发一张普通 interactive 卡，后续用 im.message.patch 原地整卡替换 */
    private void createPatchFallback() {
        patchMode = true;
        cardId = null;
        try {
            String json = CardBuilder.buildStreamingCardJson("", "", List.of(), 0, false);
            CreateMessageResp resp = sendInteractive(json);
            if (resp != null && resp.success() && resp.getData() != null) {
                sentMessageId = resp.getData().getMessageId();
                messageSent = true;
                phase = Phase.STREAMING;
                log.info("[Card] patch 降级卡片已发送: msgId={}", sentMessageId);
            } else {
                phase = Phase.FAILED;
                log.error("[Card] patch 降级发送也失败: {}", resp != null ? resp.getCode() + " " + resp.getMsg() : "null");
            }
        } catch (Exception e) {
            phase = Phase.FAILED;
            log.error("[Card] patch 降级发送异常", e);
        }
        creationFuture.complete(null);
        flushAsync();
    }

    // ===== 流式刷新（节流四件套） =====

    /** 节流调度：窗口内合并为一次延迟 flush；长间隔后先攒 BATCH_AFTER_GAP_MS 再发 */
    private void throttledFlush() {
        synchronized (this) {
            if (isTerminal() || phase != Phase.STREAMING) return;
            long throttleMs = patchMode ? PATCH_THROTTLE_MS : CARDKIT_THROTTLE_MS;
            long now = System.currentTimeMillis();
            long elapsed = now - lastFlushTime;
            if (elapsed >= throttleMs) {
                cancelPendingFlush();
                if (elapsed > LONG_GAP_THRESHOLD_MS) {
                    lastFlushTime = now;
                    pendingFlushTimer = SCHED.schedule(this::flushAsync, BATCH_AFTER_GAP_MS, TimeUnit.MILLISECONDS);
                } else {
                    flushAsync();
                }
            } else if (pendingFlushTimer == null) {
                pendingFlushTimer = SCHED.schedule(this::flushAsync, throttleMs - elapsed, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void flushAsync() {
        HTTP.execute(this::flush);
    }

    /** mutex + reflush：同一时刻最多一个更新在飞，飞行期增量合并为一次补刷 */
    private void flush() {
        synchronized (this) {
            if (isTerminal()) return;
            if (flushInProgress) {
                needsReflush = true;
                return;
            }
            flushInProgress = true;
            lastFlushTime = System.currentTimeMillis();   // 前置时间戳，防并发再次进入
        }
        try {
            doFlush();
        } finally {
            synchronized (this) {
                flushInProgress = false;
                lastFlushTime = System.currentTimeMillis();
                notifyAll();   // 唤醒 waitForFlush
                if (needsReflush && !isTerminal() && pendingFlushTimer == null) {
                    needsReflush = false;
                    pendingFlushTimer = SCHED.schedule(this::flushAsync, 0, TimeUnit.MILLISECONDS);
                }
            }
        }
    }

    private void doFlush() {
        String text = displayText();
        synchronized (this) {
            if (text.isEmpty() || text.equals(lastFlushedText)) return;   // 去重
        }
        if (!patchMode && cardId != null) {
            try {
                ContentCardElementResp resp = client.cardkit().v1().cardElement().content(
                        ContentCardElementReq.newBuilder()
                                .cardId(cardId)
                                .elementId(CardBuilder.STREAMING_ELEMENT_ID)
                                .contentCardElementReqBody(ContentCardElementReqBody.newBuilder()
                                        .content(text)
                                        .sequence(sequence.incrementAndGet())
                                        .build())
                                .build());
                if (resp.success()) {
                    synchronized (this) { lastFlushedText = text; }
                } else if (resp.getCode() == RATE_LIMITED_CODE) {
                    log.debug("[Card] 限频(230020)，跳过本帧");   // 跳帧不重试，下个节流窗口自然重发
                } else {
                    log.warn("[Card] cardElement.content 失败 code={} msg={}，降级 patch", resp.getCode(), resp.getMsg());
                    degradeToPatch();
                }
            } catch (Exception e) {
                log.warn("[Card] cardElement.content 异常，降级 patch: {}", e.getMessage());
                degradeToPatch();
            }
        } else if (patchMode && sentMessageId != null) {
            String json = CardBuilder.buildStreamingCardJson(
                    snapshot(answer), snapshot(thinking), steps, elapsedMs(), false);
            patchMessage(json, text);
        }
    }

    private void degradeToPatch() {
        boolean wasPatch = patchMode;
        patchMode = true;
        cardId = null;
        if (!wasPatch && sentMessageId == null) {
            createPatchFallback();
        }
    }

    /** 工具面板更新：1500ms 节流整卡替换（正文一并写入防止内容回退） */
    private void throttledPanelUpdate() {
        synchronized (this) {
            if (isTerminal() || phase != Phase.STREAMING) return;
            if (pendingPanelTimer != null) return;
            long now = System.currentTimeMillis();
            long wait = Math.max(0, PANEL_THROTTLE_MS - (now - lastPanelUpdate));
            if (wait == 0) {
                lastPanelUpdate = now;
                HTTP.execute(this::doPanelUpdate);
            } else {
                pendingPanelTimer = SCHED.schedule(() -> {
                    synchronized (this) {
                        pendingPanelTimer = null;
                        lastPanelUpdate = System.currentTimeMillis();
                    }
                    HTTP.execute(this::doPanelUpdate);
                }, wait, TimeUnit.MILLISECONDS);
            }
        }
    }

    private void doPanelUpdate() {
        if (isTerminal() || phase != Phase.STREAMING) return;
        String ans = snapshot(answer), thk = snapshot(thinking);
        String text = displayText();
        if (!patchMode && cardId != null) {
            try {
                String json = CardBuilder.buildStreamingCardJson(ans, thk, steps, elapsedMs(), true);
                UpdateCardResp resp = client.cardkit().v1().card().update(UpdateCardReq.newBuilder()
                        .cardId(cardId)
                        .updateCardReqBody(UpdateCardReqBody.newBuilder()
                                .card(Card.newBuilder().type("card_json").data(json).build())
                                .sequence(sequence.incrementAndGet())
                                .build())
                        .build());
                if (resp.success()) {
                    synchronized (this) { lastFlushedText = text; }   // 整卡已含正文，避免紧随的重复 content flush
                } else if (resp.getCode() != RATE_LIMITED_CODE) {
                    log.warn("[Card] card.update(面板) 失败 code={}，降级 patch", resp.getCode());
                    degradeToPatch();
                }
            } catch (Exception e) {
                log.warn("[Card] card.update(面板) 异常，降级 patch: {}", e.getMessage());
                degradeToPatch();
            }
        } else if (patchMode && sentMessageId != null) {
            String json = CardBuilder.buildStreamingCardJson(ans, thk, steps, elapsedMs(), false);
            patchMessage(json, text);
        }
    }

    private void patchMessage(String cardJson, String displayTextForDedup) {
        if (sentMessageId == null) return;
        try {
            PatchMessageResp resp = client.im().message().patch(PatchMessageReq.newBuilder()
                    .messageId(sentMessageId)
                    .patchMessageReqBody(PatchMessageReqBody.newBuilder().content(cardJson).build())
                    .build());
            if (resp.success()) {
                synchronized (this) { lastFlushedText = displayTextForDedup; }
            } else if (resp.getCode() == RATE_LIMITED_CODE) {
                log.debug("[Card] patch 限频(230020)，跳过本帧");
            } else {
                log.warn("[Card] im.message.patch 失败 code={} msg={}", resp.getCode(), resp.getMsg());
            }
        } catch (Exception e) {
            log.warn("[Card] im.message.patch 异常: {}", e.getMessage());
        }
    }

    // ===== 终态收尾 =====

    private void finalizeCard(TurnResult r, boolean aborted, String errorMsg) {
        try {
            creationFuture.get(10, TimeUnit.SECONDS);   // 等创建落地
        } catch (Exception ignored) {}
        waitForFlush(3000);

        Phase target = aborted ? Phase.ABORTED : (r.isError ? Phase.FAILED : Phase.COMPLETED);
        synchronized (this) {
            if (isTerminal()) return;
            cancelPendingFlush();
            if (pendingPanelTimer != null) { pendingPanelTimer.cancel(false); pendingPanelTimer = null; }
            phase = target;
        }

        try {
            if (r.durationMs <= 0) r.durationMs = elapsedMs();
            List<CardBuilder.Option> options = (!r.isError && !aborted && CardBuilder.hasOptions(r.text))
                    ? CardBuilder.extractOptions(r.text) : null;
            String finalJson = CardBuilder.buildFinalCardJson(r, options);

            boolean done = false;
            if (!patchMode && cardId != null) {
                done = closeStreamingAndUpdate(finalJson);
            }
            if (!done) {
                if (sentMessageId != null) {
                    patchMessage(finalJson, " final");   // 终态兜底：即使中间帧全被限频跳过也保证最终内容正确
                    done = true;
                } else {
                    done = sendInteractive(finalJson) != null;   // 什么都没发出去过 → 直接发终态卡
                }
            }
            if (done) messageSent = true;
            log.info("[Card] 终态更新完成: phase={}, {}字, tools={}", target, r.text != null ? r.text.length() : 0,
                    r.toolSteps != null ? r.toolSteps.size() : 0);
        } catch (Exception e) {
            log.error("[Card] 终态更新失败", e);
        }
    }

    /** card.settings(streaming_mode:false) + card.update(终态卡)，两步共用递增 sequence */
    private boolean closeStreamingAndUpdate(String finalJson) {
        try {
            JsonObject settings = new JsonObject();
            settings.addProperty("streaming_mode", false);
            SettingsCardResp sResp = client.cardkit().v1().card().settings(SettingsCardReq.newBuilder()
                    .cardId(cardId)
                    .settingsCardReqBody(SettingsCardReqBody.newBuilder()
                            .settings(settings.toString())
                            .sequence(sequence.incrementAndGet())
                            .build())
                    .build());
            if (!sResp.success()) {
                log.warn("[Card] card.settings 失败 code={} msg={}", sResp.getCode(), sResp.getMsg());
                // settings 失败仍尝试 update（部分场景可恢复）
            }
            UpdateCardResp uResp = client.cardkit().v1().card().update(UpdateCardReq.newBuilder()
                    .cardId(cardId)
                    .updateCardReqBody(UpdateCardReqBody.newBuilder()
                            .card(Card.newBuilder().type("card_json").data(finalJson).build())
                            .sequence(sequence.incrementAndGet())
                            .build())
                    .build());
            if (uResp.success()) return true;
            log.warn("[Card] 终态 card.update 失败 code={} msg={}，回退 patch", uResp.getCode(), uResp.getMsg());
            return false;
        } catch (Exception e) {
            log.warn("[Card] 终态 CardKit 更新异常，回退 patch: {}", e.getMessage());
            return false;
        }
    }

    // ===== 工具方法 =====

    private String displayText() {
        synchronized (this) {
            if (answer.length() > 0) return answer.toString();
            if (thinking.length() > 0) {
                String t = thinking.toString();
                if (t.length() > 3000) t = "..." + t.substring(t.length() - 3000);
                return "💭 **思考中...**\n\n" + t;
            }
            return "";
        }
    }

    private String snapshot(StringBuilder sb) {
        synchronized (this) { return sb.toString(); }
    }

    /** 用控制器当前累积状态组装 TurnResult（onTurnError 场景，result 事件可能没来） */
    private TurnResult snapshotResult() {
        TurnResult r = new TurnResult(snapshot(answer), false);
        r.thinkingText = snapshot(thinking);
        r.toolSteps.addAll(steps);
        r.durationMs = elapsedMs();
        return r;
    }

    private long elapsedMs() {
        return System.currentTimeMillis() - startMs;
    }

    private boolean isTerminal() {
        return phase == Phase.COMPLETED || phase == Phase.ABORTED || phase == Phase.FAILED;
    }

    private void cancelPendingFlush() {
        if (pendingFlushTimer != null) {
            pendingFlushTimer.cancel(false);
            pendingFlushTimer = null;
        }
    }

    /** 等待在飞的 flush 落地（终态前调用，防止终态被中间帧覆盖） */
    private void waitForFlush(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        synchronized (this) {
            while (flushInProgress && System.currentTimeMillis() < deadline) {
                try { wait(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        }
    }

    private CreateMessageResp sendInteractive(String cardJson) {
        try {
            CreateMessageReq req = CreateMessageReq.newBuilder()
                    .receiveIdType(receiveIdType)
                    .createMessageReqBody(CreateMessageReqBody.newBuilder()
                            .receiveId(receiveId)
                            .uuid(UUID.randomUUID().toString())
                            .msgType("interactive")
                            .content(cardJson)
                            .build())
                    .build();
            return client.im().message().create(req);
        } catch (Exception e) {
            log.error("[Card] im.message.create 异常", e);
            return null;
        }
    }

    private void sendText(String text) {
        try {
            JsonObject content = new JsonObject();
            content.addProperty("text", text);
            client.im().message().create(CreateMessageReq.newBuilder()
                    .receiveIdType(receiveIdType)
                    .createMessageReqBody(CreateMessageReqBody.newBuilder()
                            .receiveId(receiveId)
                            .uuid(UUID.randomUUID().toString())
                            .msgType("text")
                            .content(content.toString())
                            .build())
                    .build());
        } catch (Exception e) {
            log.warn("[Card] 发送文本提示失败: {}", e.getMessage());
        }
    }
}
