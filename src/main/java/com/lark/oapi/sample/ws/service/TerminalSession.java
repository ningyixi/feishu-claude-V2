package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 常驻终端会话 — 包装一个长运行的 Claude CLI 进程。
 * <p>
 * 使用 {@code --input-format stream-json --output-format stream-json --include-partial-messages}，
 * 通过 stdin 发送 NDJSON user 消息，通过 stdout 接收 NDJSON 事件流。
 * <p>
 * <b>完成判定是事件驱动的，不靠超时猜测</b>：协议的 {@code result} 事件是每轮唯一且确定的终局信号
 * （实测含子 Agent 场景也是每轮恰好一个 result），收到即完成、零等待。
 * 增量文本来自 {@code stream_event} 的 {@code content_block_delta}（text_delta / thinking_delta），
 * 工具步骤来自 {@code assistant} 的 tool_use 块与 {@code user} 的 tool_result 块。
 * <p>
 * 超时只作为进程卡死的兜底（{@link #NO_EVENT_IDLE_MS} 无任何输出 / {@link #HARD_TURN_TIMEOUT_MS} 硬上限），
 * 不参与正常完成判定。轮次之间用 {@link ReentrantLock} 串行化，杜绝两轮事件串写同一 buffer 的竞态。
 * <p>
 * 生命周期回调全部在 reader 虚拟线程上触发（见 {@link TurnListener}），实现方（卡片控制器）不得阻塞。
 */
public class TerminalSession {

    private static final Logger log = LoggerFactory.getLogger(TerminalSession.class);

    /** 单轮硬性最大时长兜底：超过判定进程失控，强制终止并重启。
     *  默认 60 分钟（真实开发任务可以很长），可用 -Dterminal.hardTurnTimeoutMinutes=90 调整 */
    private static final long HARD_TURN_TIMEOUT_MS = Long.getLong("terminal.hardTurnTimeoutMinutes", 60L) * 60_000L;
    /** stdout 完全无输出的兜底：thinking/tool/status 事件都会刷新它。
     *  工具执行期间（如长编译）stdout 可能静默，此时豁免——只有"无工具在跑且完全静默"才判定卡死 */
    private static final long NO_EVENT_IDLE_MS = 120_000L;
    /** 看门狗检查周期 */
    private static final long WATCHDOG_PERIOD_MS = 10_000L;
    /** future.get 相对硬上限的额外宽限，确保看门狗先触发 */
    private static final long FUTURE_GRACE_MS = 60_000L;

    private final String sessionKey;   // 会话标识（用于日志）
    private final String workingDir;
    private final String model;
    private final int sandboxLevel;    // 沙箱档位（SandboxPolicy.LEVEL_*），进程启动旗标的一部分
    /** stdin 写入互斥：常规轮次写入 vs 中途注入（steer）——保证 NDJSON 行完整与顺序 */
    private final Object stdinLock = new Object();

    private volatile Process process;
    private volatile BufferedWriter writer;
    private volatile BufferedReader reader;
    private volatile String claudeSessionId;
    private volatile String detectedModel;
    private volatile boolean alive;
    private volatile long lastEventTime;

    private Thread readerThread;

    /** 轮次串行化锁 — 同一时刻只有一轮在跑 */
    private final ReentrantLock turnLock = new ReentrantLock();
    /** 当前活跃轮次（reader 线程路由事件的目标），受 turnLock 保护 */
    private volatile Turn currentTurn;

    /** 轮次活动阶段 — 供实例状态展示（思考中/工具执行/生成回复） */
    public enum ActivityPhase { IDLE, THINKING, TOOL_RUNNING, GENERATING }
    private volatile ActivityPhase activityPhase = ActivityPhase.IDLE;
    private volatile String currentToolName;
    private volatile long lastTurnEndTime;

    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "terminal-watchdog");
        t.setDaemon(true);
        return t;
    });

    public TerminalSession(String sessionKey, String workingDir, String model) {
        this(sessionKey, workingDir, model, SandboxPolicy.LEVEL_FULL);
    }

    /** @param sandboxLevel 沙箱档位（SandboxPolicy.LEVEL_*）——决定 start() 拼哪些权限旗标 */
    public TerminalSession(String sessionKey, String workingDir, String model, int sandboxLevel) {
        this.sessionKey = sessionKey;
        this.workingDir = workingDir != null ? workingDir : "";
        this.model = model != null ? model : "";
        this.sandboxLevel = sandboxLevel;
    }

    public int getSandboxLevel() { return sandboxLevel; }

    // ===== 生命周期 =====

    /** 启动 Claude CLI 常驻进程 */
    public synchronized void start() throws IOException {
        if (alive && process != null && process.isAlive()) {
            return;
        }

        List<String> command = buildCommand();
        log.info("[Terminal] 启动常驻进程: {}", String.join(" ", command));

        ProcessBuilder pb = new ProcessBuilder(command);
        if (!workingDir.isBlank()) {
            File dir = new File(workingDir);
            if (dir.isDirectory()) {
                pb.directory(dir);
            }
        }
        pb.redirectErrorStream(false);

        // 严格沙箱档（--restricted）会忽略 user/project/local settings 文件（防供应链提权），
        // 网关凭证（ANTHROPIC_AUTH_TOKEN/BASE_URL 等，通常配在 ~/.claude/settings.json 的 env 块）
        // 会随之丢失 → CLI 报 "Invalid API key · Please run /login"。
        // 进程环境变量不受 restricted 影响：这里把 settings 的 env 块显式注入子进程环境兜底（所有档位统一注入）。
        injectClaudeEnv(pb);

        try {
            process = pb.start();
        } catch (IOException e) {
            log.error("[Terminal] 进程启动失败: command={}", command, e);
            throw e;
        }
        writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        lastEventTime = System.currentTimeMillis();

        Thread.ofVirtual().name("stderr-" + sessionKey).start(this::readStderr);
        readerThread = Thread.ofVirtual().name("reader-" + sessionKey).start(this::readerLoop);

        alive = true;
        log.info("[Terminal] 常驻进程已启动: key={}, pid={}", sessionKey, process.pid());
    }

    /** 销毁进程，释放资源。若有活跃轮次则以中止收尾。 */
    public synchronized void destroy() {
        alive = false;
        Turn t = currentTurn;
        if (t != null && !t.future.isDone()) {
            t.listener.onTurnError("会话已销毁", true);
            t.future.complete(errorResult(t, "会话已销毁", true));
        }
        closeQuietly(writer);
        if (process != null) {
            process.destroy();
            try {
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
        watchdog.shutdownNow();
        log.info("[Terminal] 常驻进程已销毁: key={}", sessionKey);
    }

    /**
     * 中途注入（steering）— 把一条 user 消息直接写进<b>正在执行中的轮次</b>：
     * CLI 会在下一个工具边界把它呈给模型，模型当场调整并并入同一个 result 回复。
     * <p>
     * 已实测验证（verify/steer/probe2.py，CLI 2.1.267）：Bash 工具飞行中注入，
     * 注入指令出现在同一轮 result 里——与桌面交互式 Claude 的"排队消息中途送达"行为一致。
     * <p>
     * 不新建轮次、不经过 turnLock（正是为了绕开排队）；与常规写入共用 stdinLock 保证行完整。
     *
     * @return true=已注入进行中轮次；false=无活跃轮次/进程未就绪（调用方应回退排队 sendMessage）
     */
    public boolean steer(String text) {
        if (text == null || text.isBlank()) return false;
        synchronized (stdinLock) {
            Turn t = currentTurn;
            if (!isAlive() || t == null || t.future.isDone()) return false;
            try {
                JsonObject msg = new JsonObject();
                msg.addProperty("type", "user");
                msg.addProperty("session_id", "");
                JsonObject messageObj = new JsonObject();
                messageObj.addProperty("role", "user");
                messageObj.addProperty("content", text);
                msg.add("message", messageObj);
                msg.add("parent_tool_use_id", JsonNull.INSTANCE);
                writer.write(msg.toString());
                writer.newLine();
                writer.flush();
                log.info("[Terminal] >> [steer 中途注入] {} ({}字)",
                        text.substring(0, Math.min(80, text.length())), text.length());
                return true;
            } catch (Exception e) {
                log.warn("[Terminal] steer 写入失败（调用方将回退排队）: {}", e.getMessage());
                return false;
            }
        }
    }

    /** 仅中止当前轮次（/new、/stop），不销毁进程。对照 openclaw-lark 的 abortCard 快路径。 */
    public void abortCurrentTurn() {
        Turn t = currentTurn;
        if (t != null && !t.future.isDone()) {
            log.info("[Terminal] 主动中止当前轮次: key={}", sessionKey);
            t.listener.onTurnError("已停止", true);
            t.future.complete(errorResult(t, "已停止", true));
        }
    }

    public boolean isAlive() {
        return alive && process != null && process.isAlive();
    }

    public String getClaudeSessionId() { return claudeSessionId; }
    public String getWorkingDir() { return workingDir; }
    public String getModel() { return model != null && !model.isBlank() ? model : detectedModel; }

    // ===== 活动状态（供实例状态服务读取，全部 volatile 无锁） =====

    /** 当前活动阶段：IDLE / THINKING / TOOL_RUNNING / GENERATING */
    public ActivityPhase getActivityPhase() { return activityPhase; }

    /** 正在执行的工具名（TOOL_RUNNING 时非空） */
    public String getCurrentToolName() { return currentToolName; }

    /** 是否有活跃轮次（已写入 stdin、尚未 result） */
    public boolean isTurnActive() {
        Turn t = currentTurn;
        return t != null && !t.future.isDone();
    }

    /** 当前轮次已进行毫秒数（无活跃轮次为 0） */
    public long getTurnElapsedMs() {
        Turn t = currentTurn;
        return isTurnActive() ? System.currentTimeMillis() - t.startTime : 0;
    }

    /** 排队等待的轮次数（turnLock 队列长度） */
    public int getQueuedCount() { return turnLock.getQueueLength(); }

    /** 上一轮结束时间戳（0 = 本进程还没完成过任何轮次） */
    public long getLastTurnEndTime() { return lastTurnEndTime; }

    /** stdout 最后一次有输出的时间戳（看门狗同源数据） */
    public long getLastEventTime() { return lastEventTime; }

    // ===== 消息发送 =====

    /**
     * 图片附件 — base64 + mediaType，随 user 消息的 content 数组发给 CLI。
     * 实测 stream-json 输入原生支持 Anthropic image content block（网关视觉模型直接读取）。
     */
    public static class ImageAttachment {
        public final String mediaType;   // image/png, image/jpeg, image/gif, image/webp
        public final String base64Data;

        public ImageAttachment(String mediaType, String base64Data) {
            this.mediaType = mediaType;
            this.base64Data = base64Data;
        }
    }

    /**
     * 向常驻进程发送消息并等待本轮完成（阻塞至 result 事件）。
     * 流式增量、工具步骤、终局都通过 {@code listener} 回调推送。
     * 同一会话的多轮消息串行化：上一轮未完成时，本轮先触发 {@link TurnListener#onQueued()} 再排队等待。
     *
     * @return 本轮最终文本（出错时以 ❌/⏰ 前缀）
     */
    public String sendMessage(String message, TurnListener listener) {
        return sendMessage(message, null, listener);
    }

    /**
     * 带图片附件的消息发送 — content 变成 [text块, image块...] 数组（Anthropic 消息格式）。
     */
    public String sendMessage(String message, List<ImageAttachment> images, TurnListener listener) {
        if (listener == null) listener = new NullTurnListener();

        // 排队提示：若锁被占用说明上一轮还在跑
        if (!turnLock.tryLock()) {
            listener.onQueued();
            turnLock.lock();
        }
        try {
            if (!isAlive()) {
                try {
                    log.info("[Terminal] 进程已死，重启中...");
                    start();
                } catch (IOException e) {
                    listener.onTurnError("无法重启终端: " + e.getMessage(), false);
                    return "❌ 无法重启终端: " + e.getMessage();
                }
            }

            // turn 装配 + stdin 写入 与 steer() 的检查+写入 用同一把锁互斥，保证消息顺序
            final Turn turn = new Turn(listener);
            synchronized (stdinLock) {
            currentTurn = turn;
            activityPhase = ActivityPhase.THINKING;
            currentToolName = null;
            listener.onTurnStart();

            // 写入 stdin（NDJSON user 消息；有图片时 content 为块数组）
            try {
                JsonObject msg = new JsonObject();
                msg.addProperty("type", "user");
                msg.addProperty("session_id", "");
                JsonObject messageObj = new JsonObject();
                messageObj.addProperty("role", "user");
                if (images == null || images.isEmpty()) {
                    messageObj.addProperty("content", message);
                } else {
                    JsonArray content = new JsonArray();
                    if (message != null && !message.isBlank()) {
                        JsonObject textBlock = new JsonObject();
                        textBlock.addProperty("type", "text");
                        textBlock.addProperty("text", message);
                        content.add(textBlock);
                    }
                    for (ImageAttachment img : images) {
                        JsonObject imageBlock = new JsonObject();
                        imageBlock.addProperty("type", "image");
                        JsonObject source = new JsonObject();
                        source.addProperty("type", "base64");
                        source.addProperty("media_type", img.mediaType);
                        source.addProperty("data", img.base64Data);
                        imageBlock.add("source", source);
                        content.add(imageBlock);
                    }
                    messageObj.add("content", content);
                }
                msg.add("message", messageObj);
                msg.add("parent_tool_use_id", JsonNull.INSTANCE);

                String json = msg.toString();
                log.info("[Terminal] >> {} ({}字, 图片{}张)",
                        json.substring(0, Math.min(200, json.length())), message != null ? message.length() : 0,
                        images != null ? images.size() : 0);
                writer.write(json);
                writer.newLine();
                writer.flush();
            } catch (Exception e) {
                log.error("[Terminal] 写入 stdin 失败", e);
                currentTurn = null;
                listener.onTurnError("发送失败: " + e.getMessage(), false);
                return "❌ 发送失败: " + e.getMessage();
            }
            }   // end synchronized (stdinLock)

            // 看门狗：仅兜底进程卡死/失控，不参与正常完成判定
            ScheduledFuture<?> wd = watchdog.scheduleAtFixedRate(
                    () -> checkWatchdog(turn), WATCHDOG_PERIOD_MS, WATCHDOG_PERIOD_MS, TimeUnit.MILLISECONDS);

            try {
                TurnResult result = turn.future.get(HARD_TURN_TIMEOUT_MS + FUTURE_GRACE_MS, TimeUnit.MILLISECONDS);
                return result.isError || result.aborted
                        ? (result.text != null && !result.text.isBlank() ? result.text : "❌ 本轮未成功")
                        : result.text;
            } catch (TimeoutException e) {
                log.error("[Terminal] future 等待超时（看门狗应已先触发）: key={}", sessionKey);
                listener.onTurnError("⏰ Claude 响应超时", false);
                restartProcess();
                return "⏰ Claude 响应超时，已重启终端，请重试";
            } catch (Exception e) {
                log.error("[Terminal] 等待回复失败", e);
                listener.onTurnError("等待回复失败: " + e.getMessage(), false);
                return "❌ 等待回复失败: " + e.getMessage();
            } finally {
                wd.cancel(false);
                currentTurn = null;
                activityPhase = ActivityPhase.IDLE;
                currentToolName = null;
                lastTurnEndTime = System.currentTimeMillis();
            }
        } finally {
            turnLock.unlock();
        }
    }

    // ===== 内部：构建启动命令 =====

    /**
     * 把 ~/.claude/settings.json 的 env 块注入 CLI 子进程环境。
     * <p>
     * 背景：--restricted（严格沙箱档）忽略 settings 文件，而本网关的认证恰好配置在
     * settings 的 env 块里（ANTHROPIC_AUTH_TOKEN/ANTHROPIC_BASE_URL/...），不注入则严格档必然认证失败。
     * 规则：已存在的进程环境变量优先（putIfAbsent 不覆盖 OS 级配置）；文件缺失/解析失败静默跳过；
     * 只记数量不打值（凭证不落日志）。
     */
    private static void injectClaudeEnv(ProcessBuilder pb) {
        try {
            java.nio.file.Path settings = java.nio.file.Path.of(
                    System.getProperty("user.home"), ".claude", "settings.json");
            if (!java.nio.file.Files.exists(settings)) return;
            String json = java.nio.file.Files.readString(settings, java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
            if (!root.has("env") || !root.get("env").isJsonObject()) return;
            int n = 0;
            for (var e : root.getAsJsonObject("env").entrySet()) {
                if (e.getValue().isJsonPrimitive()
                        && pb.environment().putIfAbsent(e.getKey(), e.getValue().getAsString()) == null) {
                    n++;
                }
            }
            if (n > 0) {
                log.info("[Terminal] 已注入 settings.json env 块 {} 个环境变量到 CLI 进程（值不落日志）", n);
            }
        } catch (Exception ex) {
            log.warn("[Terminal] settings.json env 注入失败（CLI 将按自身认证逻辑运行）: {}", ex.getMessage());
        }
    }

    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>();
        String os = System.getProperty("os.name", "").toLowerCase();
        String resolved = resolveCommand("claude");

        if (os.contains("win") && resolved != null
                && (resolved.endsWith(".cmd") || resolved.endsWith(".bat"))) {
            cmd.add("cmd.exe");
            cmd.add("/c");
            cmd.add(resolved);
            log.info("[Terminal] 使用 cmd.exe 运行: {}", resolved);
        } else {
            cmd.add(resolved != null ? resolved : "claude");
        }

        cmd.add("-p");
        cmd.add("--input-format");  cmd.add("stream-json");
        cmd.add("--output-format"); cmd.add("stream-json");
        cmd.add("--include-partial-messages");   // ★ token 级增量流（text_delta / thinking_delta）
        cmd.add("--verbose");
        // 权限旗标按沙箱档位拼装（L1 策略沙箱，旗标组合经真实 CLI 实测验证）：
        // 0=全权限 bypassPermissions（现状默认）；1=宽松（白名单+自动拒绝）；2=严格（--restricted 结构圈禁）。
        // 详见 SandboxPolicy 与 docs/沙箱方案与威胁模型.md。
        SandboxPolicy.appendFlags(cmd, sandboxLevel);
        if (!model.isBlank()) {
            cmd.add("--model");
            cmd.add(model);
        }
        return cmd;
    }

    private String resolveCommand(String command) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            String path = System.getenv("PATH");
            if (path == null) return command;
            for (String dir : path.split(";")) {
                if (dir.isBlank()) continue;
                for (String ext : new String[]{".exe", ".cmd", ".bat"}) {
                    File f = new File(dir, command + ext);
                    if (f.exists()) {
                        log.info("[Terminal] 解析命令路径: {} -> {}", command, f.getAbsolutePath());
                        return f.getAbsolutePath();
                    }
                }
            }
        }
        return command;
    }

    // ===== 内部：stdout 事件读取循环 =====

    private void readerLoop() {
        try {
            String line;
            // 阻塞 readLine 跑在专用虚拟线程上（不再用 supplyAsync + commonPool，杜绝并发读流）
            while (alive && process != null && process.isAlive() && (line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                lastEventTime = System.currentTimeMillis();   // 任何输出都刷新看门狗

                // 高频噪声：thinking_tokens 计数、task_progress 只刷新时间不打日志
                boolean noisy = line.contains("\"thinking_tokens\"") || line.contains("\"task_progress\"");
                if (!noisy) {
                    log.info("[Terminal] << {}", line.substring(0, Math.min(400, line.length())));
                }

                try {
                    processEvent(JsonParser.parseString(line).getAsJsonObject());
                } catch (Exception e) {
                    log.debug("[Terminal] [非JSON] {}", line.substring(0, Math.min(200, line.length())));
                }
            }
        } catch (IOException e) {
            log.info("[Terminal] reader 读取结束: {}", e.getMessage());
        } finally {
            alive = false;
            // 进程退出：未完成轮次以错误收尾（不阻塞 future）
            Turn t = currentTurn;
            if (t != null && !t.future.isDone()) {
                String partial = t.answerText.toString();
                log.info("[Terminal] 进程退出，收尾未完成轮次 ({}字)", partial.length());
                t.listener.onTurnError("终端进程意外退出", false);
                t.future.complete(errorResult(t, partial.isBlank() ? "⚠️ 终端进程意外退出" : partial, false));
            }
            log.info("[Terminal] reader 线程退出: key={}", sessionKey);
        }
    }

    private void processEvent(JsonObject event) {
        String type = event.has("type") ? event.get("type").getAsString() : "";
        String subtype = event.has("subtype") ? event.get("subtype").getAsString() : "";
        boolean isSubAgent = event.has("parent_tool_use_id") && !event.get("parent_tool_use_id").isJsonNull();

        switch (type) {
            case "system" -> handleSystem(event, subtype);
            case "stream_event" -> { if (!isSubAgent) handleStreamEvent(event); }
            case "assistant" -> { if (!isSubAgent) handleAssistant(event); }
            case "user" -> handleUser(event);   // tool_result 反馈（含子 Agent 的，用 tool_use_id 匹配）
            case "result" -> handleResult(event);
            case "control_response" -> log.debug("[Terminal] [control_response] {}", subtype);
            default -> log.debug("[Terminal] [{}]", type);
        }
    }

    private void handleSystem(JsonObject event, String subtype) {
        switch (subtype) {
            case "init" -> {
                if (event.has("session_id")) claudeSessionId = event.get("session_id").getAsString();
                if (event.has("model") && !event.get("model").isJsonNull()) detectedModel = event.get("model").getAsString();
                log.info("[Terminal] [init] session_id={}, model={}", claudeSessionId, detectedModel);
            }
            case "task_started", "task_updated", "task_notification" ->
                log.debug("[Terminal] [task:{}] {}", subtype, event.has("status") ? event.get("status").getAsString() : "");
            case "thinking_tokens", "status", "hook_started", "hook_response" -> { /* 忽略 */ }
            default -> log.debug("[Terminal] [system:{}] {}", subtype, event);
        }
    }

    /** stream_event 携带原始 Anthropic 流事件；只取主 Agent 的 text_delta / thinking_delta。 */
    private void handleStreamEvent(JsonObject event) {
        Turn turn = currentTurn;
        if (turn == null || !event.has("event")) return;
        JsonObject inner = event.getAsJsonObject("event");
        String innerType = inner.has("type") ? inner.get("type").getAsString() : "";
        if (!"content_block_delta".equals(innerType) || !inner.has("delta")) return;

        JsonObject delta = inner.getAsJsonObject("delta");
        String dType = delta.has("type") ? delta.get("type").getAsString() : "";
        long now = System.currentTimeMillis();

        if ("text_delta".equals(dType) && delta.has("text")) {
            String text = delta.get("text").getAsString();
            if (text.isEmpty()) return;
            if (turn.thinkingStartMs > 0 && turn.thinkingEndMs == 0) turn.thinkingEndMs = now;  // 思考结束
            turn.answerText.append(text);
            activityPhase = ActivityPhase.GENERATING;
            turn.listener.onTextDelta(text);
        } else if ("thinking_delta".equals(dType) && delta.has("thinking")) {
            String th = delta.get("thinking").getAsString();
            if (th.isEmpty()) return;
            if (turn.thinkingStartMs == 0) turn.thinkingStartMs = now;
            turn.thinkingText.append(th);
            if (activityPhase != ActivityPhase.TOOL_RUNNING) activityPhase = ActivityPhase.THINKING;
            turn.listener.onThinkingDelta(th);
        }
        // input_json_delta / signature_delta 忽略
    }

    /** assistant 完整块 — 只用于提取主 Agent 的 tool_use 步骤（文本增量已由 stream_event 处理）。 */
    private void handleAssistant(JsonObject event) {
        Turn turn = currentTurn;
        if (turn == null || !event.has("message")) return;
        JsonObject msg = event.getAsJsonObject("message");
        if (!msg.has("content")) return;
        for (JsonElement item : msg.getAsJsonArray("content")) {
            if (!item.isJsonObject()) continue;
            JsonObject obj = item.getAsJsonObject();
            String ct = obj.has("type") ? obj.get("type").getAsString() : "";
            if ("tool_use".equals(ct)) {
                String id = obj.has("id") ? obj.get("id").getAsString() : ("anon-" + System.nanoTime());
                String name = obj.has("name") ? obj.get("name").getAsString() : "?";
                String detail = extractToolDetail(name, obj.has("input") ? obj.getAsJsonObject("input") : null);
                ToolStep step = new ToolStep(id, name, detail, "Task".equalsIgnoreCase(name));
                turn.toolSteps.add(step);
                turn.stepsById.put(id, step);
                activityPhase = ActivityPhase.TOOL_RUNNING;
                currentToolName = name;
                log.info("[Terminal] [tool_use] {} {}", name, detail);
                turn.listener.onToolStart(step);
            }
        }
    }

    /** user 事件的 tool_result — 按 tool_use_id 匹配步骤并标记完成/失败。 */
    private void handleUser(JsonObject event) {
        Turn turn = currentTurn;
        if (turn == null || !event.has("message")) return;
        JsonObject msg = event.getAsJsonObject("message");
        if (!msg.has("content") || !msg.get("content").isJsonArray()) return;
        for (JsonElement item : msg.getAsJsonArray("content")) {
            if (!item.isJsonObject()) continue;
            JsonObject obj = item.getAsJsonObject();
            if (!"tool_result".equals(obj.has("type") ? obj.get("type").getAsString() : "")) continue;
            String toolUseId = obj.has("tool_use_id") ? obj.get("tool_use_id").getAsString() : null;
            if (toolUseId == null) continue;
            ToolStep step = turn.stepsById.get(toolUseId);
            if (step == null || step.status != ToolStep.Status.RUNNING) continue;
            boolean isError = obj.has("is_error") && obj.get("is_error").getAsBoolean();
            step.markEnd(isError ? ToolStep.Status.ERROR : ToolStep.Status.SUCCESS);
            turn.listener.onToolEnd(step);
            // 所有工具都结束了 → 回到"等模型下一步动作"的思考态
            String stillRunning = turn.toolSteps.stream()
                    .filter(s -> s.status == ToolStep.Status.RUNNING)
                    .map(s -> s.name).findFirst().orElse(null);
            if (stillRunning == null) {
                activityPhase = ActivityPhase.THINKING;
                currentToolName = null;
            } else {
                currentToolName = stillRunning;
            }
        }
    }

    /** result 事件 = 本轮终局（确定性完成信号，零等待）。每轮恰好一个。 */
    private void handleResult(JsonObject event) {
        Turn turn = currentTurn;
        if (turn == null || turn.future.isDone()) {
            log.warn("[Terminal] 收到 result 但无活跃轮次（或已完成），忽略");
            return;
        }

        boolean isError = event.has("is_error") && event.get("is_error").getAsBoolean();
        String resultText = event.has("result") && !event.get("result").isJsonNull()
                ? event.get("result").getAsString() : "";
        String finalText = resultText.isBlank() ? turn.answerText.toString() : resultText;

        TurnResult r = new TurnResult(finalText, isError);
        r.thinkingText = turn.thinkingText.toString();
        r.thinkingMs = (turn.thinkingStartMs > 0)
                ? ((turn.thinkingEndMs > 0 ? turn.thinkingEndMs : System.currentTimeMillis()) - turn.thinkingStartMs)
                : 0;
        r.toolSteps.addAll(turn.toolSteps);
        r.toolElapsedMs = computeToolElapsed(turn.toolSteps);
        r.model = getModel() != null ? getModel() : "";

        // result 事件携带的 metrics
        if (event.has("duration_ms") && !event.get("duration_ms").isJsonNull()) {
            r.durationMs = event.get("duration_ms").getAsLong();
        } else {
            r.durationMs = System.currentTimeMillis() - turn.startTime;
        }
        if (event.has("total_cost_usd") && !event.get("total_cost_usd").isJsonNull()) {
            r.costUsd = event.get("total_cost_usd").getAsDouble();
        }
        if (event.has("usage") && event.get("usage").isJsonObject()) {
            JsonObject u = event.getAsJsonObject("usage");
            r.inputTokens = optLong(u, "input_tokens");
            r.outputTokens = optLong(u, "output_tokens");
            r.cacheReadTokens = optLong(u, "cache_read_input_tokens");
            r.cacheWriteTokens = optLong(u, "cache_creation_input_tokens");
        }
        if ((r.model == null || r.model.isBlank()) && event.has("modelUsage") && event.get("modelUsage").isJsonObject()) {
            var keys = event.getAsJsonObject("modelUsage").keySet();
            if (!keys.isEmpty()) r.model = keys.iterator().next();
        }

        log.info("[Terminal] [result] 完成: isError={}, {}字, {}ms, tools={}",
                isError, finalText.length(), r.durationMs, r.toolSteps.size());

        turn.future.complete(r);          // 先 complete，解锁 sendMessage 等待线程
        turn.listener.onTurnComplete(r);  // 再回调收尾卡片
    }

    // ===== 内部：看门狗（仅兜底进程卡死/失控） =====

    private void checkWatchdog(Turn turn) {
        if (turn.future.isDone()) return;
        long now = System.currentTimeMillis();

        if (now - turn.startTime > HARD_TURN_TIMEOUT_MS) {
            log.error("[Terminal] 硬上限超时 ({}min)，强制终止并重启进程", HARD_TURN_TIMEOUT_MS / 60_000);
            turn.listener.onTurnError("⏰ Claude 处理超时（" + HARD_TURN_TIMEOUT_MS / 60_000 + "分钟）", false);
            turn.future.complete(errorResult(turn, "⏰ Claude 处理超时，请重试", false));
            restartProcess();
            return;
        }
        // 工具执行期间 stdout 可能长时间静默（编译/测试/长命令），豁免空闲判定防误杀
        boolean toolRunning = false;
        for (ToolStep s : turn.toolSteps) {
            if (s.status == ToolStep.Status.RUNNING) { toolRunning = true; break; }
        }
        if (!toolRunning && now - lastEventTime > NO_EVENT_IDLE_MS) {
            log.error("[Terminal] stdout 静默 {}s 且无工具在执行，判定进程卡死，终止并重启", NO_EVENT_IDLE_MS / 1000);
            String partial = turn.answerText.toString();
            turn.listener.onTurnError("⏰ Claude 无响应，可能进程卡住", false);
            turn.future.complete(errorResult(turn,
                    partial.isBlank() ? "⏰ Claude 无响应，已重启终端，请重试" : partial, false));
            restartProcess();
        } else if (toolRunning && now - lastEventTime > NO_EVENT_IDLE_MS) {
            log.debug("[Terminal] stdout 静默但有工具在执行，豁免空闲判定");
        }
    }

    private synchronized void restartProcess() {
        log.info("[Terminal] 重启进程: key={}", sessionKey);
        alive = false;
        closeQuietly(writer);
        if (process != null) {
            process.destroyForcibly();
            try { process.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        }
        try {
            start();
        } catch (IOException e) {
            log.error("[Terminal] 重启失败: key={}", sessionKey, e);
        }
    }

    // ===== 工具方法 =====

    private TurnResult errorResult(Turn turn, String text, boolean aborted) {
        TurnResult r = new TurnResult(text, !aborted);
        r.aborted = aborted;
        r.thinkingText = turn.thinkingText.toString();
        r.toolSteps.addAll(turn.toolSteps);
        r.toolElapsedMs = computeToolElapsed(turn.toolSteps);
        r.durationMs = System.currentTimeMillis() - turn.startTime;
        r.model = getModel() != null ? getModel() : "";
        return r;
    }

    private static long computeToolElapsed(List<ToolStep> steps) {
        if (steps.isEmpty()) return 0;
        long min = Long.MAX_VALUE, max = 0;
        for (ToolStep s : steps) {
            min = Math.min(min, s.startMs);
            long end = s.endMs > 0 ? s.endMs : System.currentTimeMillis();
            max = Math.max(max, end);
        }
        return min == Long.MAX_VALUE ? 0 : Math.max(0, max - min);
    }

    private static long optLong(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsLong() : -1;
    }

    /** 从 tool_use 的 input 提取一行摘要（命令/路径/查询等，脱敏截断）。 */
    private static String extractToolDetail(String toolName, JsonObject input) {
        if (input == null) return "";
        String n = toolName == null ? "" : toolName.toLowerCase();
        String raw = null;
        switch (n) {
            case "bash", "exec" -> raw = firstPresent(input, "command", "description");
            case "read", "edit", "write", "multiedit", "notebookedit" -> raw = firstPresent(input, "file_path", "path", "notebook_path");
            case "grep" -> raw = firstPresent(input, "pattern");
            case "glob" -> raw = firstPresent(input, "pattern");
            case "websearch", "web_search" -> raw = firstPresent(input, "query", "q");
            case "webfetch", "web_fetch" -> raw = firstPresent(input, "url");
            case "task", "agent" -> raw = firstPresent(input, "description", "prompt", "task");
            default -> raw = firstPresent(input, "description", "path", "command", "query");
        }
        if (raw == null) return "";
        raw = raw.replaceAll("\\s+", " ").trim();
        return raw.length() > 100 ? raw.substring(0, 100) + "…" : raw;
    }

    private static String firstPresent(JsonObject o, String... keys) {
        for (String k : keys) {
            if (o.has(k) && !o.get(k).isJsonNull()) {
                JsonElement e = o.get(k);
                return e.isJsonPrimitive() ? e.getAsString() : e.toString();
            }
        }
        return null;
    }

    private void readStderr() {
        if (process == null) return;
        try (BufferedReader errReader = new BufferedReader(
                new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = errReader.readLine()) != null) {
                log.info("[Terminal] stderr: {}", line);
            }
        } catch (IOException ignored) {}
    }

    private static void closeQuietly(Closeable c) {
        if (c != null) { try { c.close(); } catch (IOException ignored) {} }
    }

    // ===== 内部数据结构 =====

    /** 一轮对话的状态容器 — 每条用户消息一个，reader 线程按此路由事件。 */
    private static class Turn {
        final TurnListener listener;
        final CompletableFuture<TurnResult> future = new CompletableFuture<>();
        final StringBuilder answerText = new StringBuilder();
        final StringBuilder thinkingText = new StringBuilder();
        final List<ToolStep> toolSteps = new ArrayList<>();
        final ConcurrentMap<String, ToolStep> stepsById = new ConcurrentHashMap<>();
        final long startTime = System.currentTimeMillis();
        volatile long thinkingStartMs;
        volatile long thinkingEndMs;

        Turn(TurnListener listener) {
            this.listener = listener;
        }
    }

    /** 无 UI 回调场景（测试/兼容）用的空监听器。 */
    private static class NullTurnListener implements TurnListener {
        public void onTurnStart() {}
        public void onThinkingDelta(String delta) {}
        public void onTextDelta(String delta) {}
        public void onToolStart(ToolStep step) {}
        public void onToolEnd(ToolStep step) {}
        public void onTurnComplete(TurnResult result) {}
        public void onTurnError(String message, boolean aborted) {}
    }
}
