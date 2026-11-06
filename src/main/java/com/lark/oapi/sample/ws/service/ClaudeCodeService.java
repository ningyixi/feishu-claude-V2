package com.lark.oapi.sample.ws.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Claude 对话服务 — 管理常驻终端池。
 * <p>
 * 每个飞书机器人实例对应一个常驻的 Claude CLI 进程（{@link TerminalSession}），
 * 消息通过 stdin/stdout 的 NDJSON 流进行双向通信，
 * 上下文在同一个进程内自动维护，无需每次都新建进程。
 */
@Service
public class ClaudeCodeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeCodeService.class);

    /** instanceId → 常驻终端会话 */
    private final Map<Long, TerminalSession> sessions = new ConcurrentHashMap<>();

    private final com.lark.oapi.sample.ws.repository.BotInstanceRepository instanceRepo;

    public ClaudeCodeService(com.lark.oapi.sample.ws.repository.BotInstanceRepository instanceRepo) {
        this.instanceRepo = instanceRepo;
    }

    /**
     * 与 Claude 对话 — 路由到对应实例的常驻终端。
     * 如果终端不存在则自动创建，如果终端已崩溃则自动重启。
     * <p>
     * 阻塞至本轮 result 事件（确定性完成信号）；流式增量/工具步骤/终局
     * 全部通过 {@code listener} 回调推送（通常绑定一个流式卡片控制器）。
     *
     * @param instanceId 飞书机器人实例 ID
     * @param openId     用户飞书 openId（用于日志）
     * @param message    用户消息文本
     * @param workingDir Claude 工作目录
     * @param model      Claude 模型名称（可为空）
     * @param listener   轮次生命周期监听器，可为 null（纯文本等待模式）
     * @return 本轮最终文本
     */
    public String chat(Long instanceId, String openId, String message, String workingDir, String model,
                       TurnListener listener) {
        return chat(instanceId, openId, message, null, workingDir, model, listener);
    }

    /**
     * 带图片附件的对话 — 图片以 base64 image content block 随消息发给 CLI。
     */
    public String chat(Long instanceId, String openId, String message,
                       java.util.List<TerminalSession.ImageAttachment> images,
                       String workingDir, String model, TurnListener listener) {
        try {
            TerminalSession session = getOrCreate(instanceId, workingDir, model);
            log.info("[ClaudeCode][{}] 发送消息: openId={}, 图片={}张", instanceId, openId, images != null ? images.size() : 0);
            return session.sendMessage(message, images, listener, sourceKindOf(openId));
        } catch (Exception e) {
            log.error("[ClaudeCode][{}] 对话失败", instanceId, e);
            if (listener != null) listener.onTurnError("Claude 对话失败: " + e.getMessage(), false);
            return "❌ Claude 对话失败: " + e.getMessage();
        }
    }

    /** 排队面板的来源渠道标签（按会话 key 形态启发判断） */
    private static String sourceKindOf(String openId) {
        if (openId == null) return "其他";
        if (openId.startsWith("supplier-job-")) return "工作台";
        if (openId.startsWith("ou_")) return "飞书";
        if (openId.startsWith("chatbot") || openId.startsWith("oc_")) return "群聊";
        if (openId.startsWith("a2a") || openId.startsWith("employee")) return "数字员工";
        return "其他";
    }

    /** 实例当前排队快照（10-30-instance-queue-panel）；会话未建立=空闲 */
    public java.util.List<TerminalSession.QueuedMsg> queueOf(Long instanceId) {
        TerminalSession s = sessions.get(instanceId);
        return s == null ? java.util.List.of() : s.queuedSnapshot();
    }

    /**
     * 重置会话 — 销毁旧终端，创建新的。
     * <p>
     * ②b：重建前先清空持久化 sessionID，防止用户手动 /new 重置掉的会话在服务重启后被 --resume 复活；
     * 随后新建会话的 init 监听器会把新 id 落库。
     */
    public void newSession(Long instanceId, String workingDir, String model) {
        destroySession(instanceId);
        clearPersistedSessionId(instanceId);
        try {
            getOrCreate(instanceId, workingDir, model);
            log.info("[ClaudeCode] 已重置 session (instanceId={})", instanceId);
        } catch (IOException e) {
            log.error("[ClaudeCode] 重置 session 失败", e);
        }
    }

    /**
     * 获取或创建终端会话。
     * 如果会话已存在且进程存活且配置未变，直接复用；否则创建新的。
     */
    private TerminalSession getOrCreate(Long instanceId, String workingDir, String model) throws IOException {
        // 沙箱档位是进程旗标的一部分：从实例配置读取，变更时随 dir/model 一起触发会话重建
        Optional<com.lark.oapi.sample.ws.model.entity.BotInstance> instanceOpt = instanceRepo.findById(instanceId);
        int sandboxLevel = instanceOpt
                .map(com.lark.oapi.sample.ws.model.entity.BotInstance::getSandboxLevel)
                .orElse(SandboxPolicy.LEVEL_FULL);
        TerminalSession session = sessions.get(instanceId);
        if (session != null && session.isAlive()) {
            // 检测配置是否变更（工作目录、模型或沙箱档位）
            boolean configChanged = !nullSafeEquals(session.getWorkingDir(), workingDir)
                    || !nullSafeEquals(session.getModel(), model)
                    || session.getSandboxLevel() != sandboxLevel;
            if (!configChanged) {
                return session;
            }
            log.info("[ClaudeCode] 配置变更，重建终端: instanceId={}, dir: {} -> {}, model: {} -> {}, 沙箱: {} -> {}",
                    instanceId, session.getWorkingDir(), workingDir, session.getModel(), model,
                    SandboxPolicy.label(session.getSandboxLevel()), SandboxPolicy.label(sandboxLevel));
            session.destroy();
        }

        // 旧的进程可能已死，清理掉；配置没变（只是进程崩溃）→ 继承 session_id，新进程 --resume 接回上下文
        String inherit = null;
        if (session != null) {
            boolean sameConfig = nullSafeEquals(session.getWorkingDir(), workingDir == null ? "" : workingDir)
                    && session.getSandboxLevel() == sandboxLevel;
            if (sameConfig && !session.isAlive()) inherit = session.getClaudeSessionId();
            session.destroy();
        }

        String sessionKey = instanceId + ":" + (workingDir != null ? workingDir : "");
        session = new TerminalSession(sessionKey, workingDir, model, sandboxLevel);
        // ②b 服务重启续接：内存会话没了（sessions map 随 JVM 重启清空），但实例行持久化了同目录的最新
        // sessionID → --resume 接回上下文。内存 inherit（同 JVM 进程崩溃）优先，它总是最新或相等。
        if (inherit == null) {
            var saved = instanceOpt.orElse(null);
            String resumeId = saved == null ? null
                    : pickResumeId(saved.getLastClaudeSessionId(), saved.getLastClaudeSessionDir(), workingDir);
            if (resumeId != null) {
                inherit = resumeId;
                log.info("[ClaudeCode] 重启续接主会话: instanceId={}, --resume {}（DB 持久化）", instanceId, resumeId);
            }
        }
        session.inheritSessionId(inherit);
        // 每轮 init 事件把最新 sessionID 落库（--resume 会派生新 id）；写库失败只 warn，不影响对话
        session.setSessionIdListener(id -> persistSessionId(instanceId, id, workingDir));
        session.start();
        sessions.put(instanceId, session);
        log.info("[ClaudeCode] 创建新终端: instanceId={}, dir={}, 沙箱={}", instanceId, workingDir, SandboxPolicy.label(sandboxLevel));
        return session;
    }

    /**
     * ②b resume 决策（纯函数，可单测）：持久化 id 非空、且持久化目录与本次工作目录一致才接回。
     * dir 不一致 = 配置变更，拒绝跨目录 resume（CLI 会话上下文与工作目录强相关）；null 目录按空串归一。
     *
     * @return 可 --resume 的 sessionID；不满足条件返回 null（起全新会话）
     */
    static String pickResumeId(String savedId, String savedDir, String currentDir) {
        if (savedId == null || savedId.isBlank()) return null;
        String a = savedDir == null ? "" : savedDir;
        String b = currentDir == null ? "" : currentDir;
        return a.equals(b) ? savedId : null;
    }

    /**
     * ②b 持久化最新 sessionID（TerminalSession init 回调触发，每轮一次）。
     * 实例行不存在则跳过；任何异常只 warn——写库失败绝不影响对话/协议解析线程。包级可见供单测。
     */
    void persistSessionId(Long instanceId, String id, String dir) {
        try {
            instanceRepo.findById(instanceId).ifPresent(inst -> {
                inst.setLastClaudeSessionId(id == null ? "" : id);
                inst.setLastClaudeSessionDir(dir == null ? "" : dir);
                instanceRepo.save(inst);
            });
        } catch (Exception e) {
            log.warn("[ClaudeCode] sessionID 持久化失败（不影响对话）: instanceId={}, {}", instanceId, e.getMessage());
        }
    }

    /** ②b 清空持久化 sessionID（/new 手动重置用）：防服务重启后 --resume 复活用户已重置掉的会话 */
    void clearPersistedSessionId(Long instanceId) {
        try {
            instanceRepo.findById(instanceId).ifPresent(inst -> {
                inst.setLastClaudeSessionId("");
                inst.setLastClaudeSessionDir("");
                instanceRepo.save(inst);
            });
        } catch (Exception e) {
            log.warn("[ClaudeCode] 清空持久化 sessionID 失败: instanceId={}, {}", instanceId, e.getMessage());
        }
    }

    private static boolean nullSafeEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    // ===== 独立通道（lane）：Wiki 编译/数字员工等目录或身份不同的场景专用会话，不与实例主会话（飞书私聊/供应商工作台/E2E）共用 =====

    /** 空闲 lane 保留时长：需求完成后仍可带上下文追问 */
    private static final long LANE_IDLE_TTL_MS = Long.getLong("claude.laneIdleTtlMinutes", 30L) * 60_000L;
    /** 空闲 lane 最多保留个数（超出按最久未用淘汰，防进程堆积） */
    private static final int LANE_IDLE_CAP = Integer.getInteger("claude.laneIdleCap", 6);

    private final Map<String, TerminalSession> lanes = new ConcurrentHashMap<>();
    private final Map<String, Long> laneLastUsed = new ConcurrentHashMap<>();
    /** lane → 归属实例 ID（getOrCreateLane 登记，destroyLane 清除）——让实例状态合成能看见 lane 上的后台任务 */
    private final Map<String, Long> laneOwner = new ConcurrentHashMap<>();

    /**
     * 在独立 lane 上对话：lane 首次使用时以实例的工作目录/模型/沙箱档位起一个新的 CLI 进程（全新上下文），
     * 后续同 lane 复用。与实例主会话互不排队、互不污染上下文。
     * <p>
     * 供应商工作台任务已改用实例主会话（{@link #chat}）（11-05 起，与飞书对话同 sessionID/同队列/同上下文）；
     * lane 机制保留给 Wiki 编译、数字员工、E2E 等工作目录或身份不同的场景。
     */
    public String chatInLane(String lane, Long instanceId, String message, String workingDir, String model,
                             TurnListener listener) {
        return chatInLane(lane, instanceId, message, workingDir, List.of(), model, listener);
    }

    /** @param extraDirs 额外授权目录（--add-dir），多工作区需求用 */
    public String chatInLane(String lane, Long instanceId, String message, String workingDir, List<String> extraDirs,
                             String model, TurnListener listener) {
        return chatInLane(lane, instanceId, message, workingDir, extraDirs, Map.of(), model, listener);
    }

    /** @param env 额外环境变量（数字员工身份令牌等），lane 首次创建时注入；变化会重建进程 */
    public String chatInLane(String lane, Long instanceId, String message, String workingDir, List<String> extraDirs,
                             Map<String, String> env, String model, TurnListener listener) {
        try {
            TerminalSession s = getOrCreateLane(lane, instanceId, workingDir, extraDirs != null ? extraDirs : List.of(),
                    env != null ? env : Map.of(), model);
            laneLastUsed.put(lane, System.currentTimeMillis());
            log.info("[ClaudeCode][lane {}] 发送消息 (instance={})", lane, instanceId);
            try {
                return s.sendMessage(message, null, listener);
            } finally {
                laneLastUsed.put(lane, System.currentTimeMillis());
            }
        } catch (Exception e) {
            log.error("[ClaudeCode][lane {}] 对话失败", lane, e);
            if (listener != null) listener.onTurnError("Claude 对话失败: " + e.getMessage(), false);
            return "❌ Claude 对话失败: " + e.getMessage();
        }
    }

    private synchronized TerminalSession getOrCreateLane(String lane, Long instanceId, String workingDir,
                                                         List<String> extraDirs, Map<String, String> env, String model) throws IOException {
        int sandboxLevel = instanceRepo.findById(instanceId)
                .map(com.lark.oapi.sample.ws.model.entity.BotInstance::getSandboxLevel)
                .orElse(SandboxPolicy.LEVEL_FULL);
        TerminalSession s = lanes.get(lane);
        if (s != null && s.isAlive() && nullSafeEquals(s.getWorkingDir(), workingDir == null ? "" : workingDir)
                && nullSafeEquals(s.getModel(), model == null ? "" : model)
                && s.getSandboxLevel() == sandboxLevel && s.getExtraDirs().equals(extraDirs) && s.getExtraEnv().equals(env)) {
            laneOwner.put(lane, instanceId);   // 复用路径同样登记归属（实例可能中途换绑）
            return s;
        }
        String inherit = null;
        if (s != null) {
            if (!s.isAlive() && s.getSandboxLevel() == sandboxLevel && s.getExtraDirs().equals(extraDirs)
                    && nullSafeEquals(s.getWorkingDir(), workingDir == null ? "" : workingDir)) {
                inherit = s.getClaudeSessionId();   // 进程崩溃 → 新进程 --resume 接回上下文
            }
            s.destroy();
        }
        evictIdleLanes(LANE_IDLE_CAP - 1);
        s = new TerminalSession("lane:" + lane, workingDir, model, sandboxLevel);
        s.inheritSessionId(inherit);
        s.setExtraDirs(extraDirs);
        s.setExtraEnv(env);
        s.start();
        lanes.put(lane, s);
        laneOwner.put(lane, instanceId);
        log.info("[ClaudeCode] 创建独立会话 lane={}, instance={}, dir={}", lane, instanceId, workingDir);
        return s;
    }

    public TerminalSession getLaneSession(String lane) {
        return lane != null ? lanes.get(lane) : null;
    }

    public boolean hasLiveLane(String lane) {
        TerminalSession s = getLaneSession(lane);
        return s != null && s.isAlive();
    }

    public void destroyLane(String lane) {
        TerminalSession s = lanes.remove(lane);
        laneLastUsed.remove(lane);
        laneOwner.remove(lane);
        if (s != null) s.destroy();
    }

    /** 某实例名下 lane 会话的活跃快照（{@link #busiestLaneActivity} 返回值） */
    public record LaneActivity(TerminalSession session, String lane) {}

    /**
     * 该实例名下所有 lane 会话中"正在跑轮次"的进度快照；无活跃 lane 时 empty。
     * 多个 lane 同时活跃时取轮次已进行时间最长者（最忙的那个），供实例状态合成显示"后台任务中"。
     */
    public Optional<LaneActivity> busiestLaneActivity(Long instanceId) {
        if (instanceId == null) return Optional.empty();
        LaneActivity best = null;
        long bestElapsed = -1;
        for (var e : lanes.entrySet()) {
            if (!instanceId.equals(laneOwner.get(e.getKey()))) continue;
            TerminalSession s = e.getValue();
            if (!s.isTurnActive()) continue;
            long elapsed = s.getTurnElapsedMs();
            if (elapsed > bestElapsed) {
                bestElapsed = elapsed;
                best = new LaneActivity(s, e.getKey());
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * 该实例所有 lane 的最近活动时间：max(轮次结束时间, lane 最后使用时间)；无 lane 返回 0。
     * 供实例状态的空闲分级（刚完成/摸鱼/睡大觉）把后台任务算进"最近干过活"。
     */
    public long lastLaneActivityMs(Long instanceId) {
        if (instanceId == null) return 0;
        long best = 0;
        for (var e : lanes.entrySet()) {
            if (!instanceId.equals(laneOwner.get(e.getKey()))) continue;
            best = Math.max(best, Math.max(e.getValue().getLastTurnEndTime(),
                    laneLastUsed.getOrDefault(e.getKey(), 0L)));
        }
        return best;
    }

    /** 只保留 keep 个空闲 lane（进行中的轮次永不淘汰） */
    private void evictIdleLanes(int keep) {
        var idle = lanes.entrySet().stream()
                .filter(e -> !e.getValue().isTurnActive() && e.getValue().getQueuedCount() == 0)
                .sorted(java.util.Comparator.comparingLong(e -> laneLastUsed.getOrDefault(e.getKey(), 0L)))
                .map(Map.Entry::getKey).toList();
        for (int i = 0; i < idle.size() - Math.max(0, keep); i++) {
            log.info("[ClaudeCode] 淘汰空闲 lane: {}", idle.get(i));
            destroyLane(idle.get(i));
        }
    }

    /** 定时回收：空闲超过 TTL 或进程已死的 lane */
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000)
    public void reapLanes() {
        long now = System.currentTimeMillis();
        for (var e : new java.util.ArrayList<>(lanes.entrySet())) {
            TerminalSession s = e.getValue();
            boolean busy = s.isTurnActive() || s.getQueuedCount() > 0;
            if (!s.isAlive() && !busy) { destroyLane(e.getKey()); continue; }
            if (!busy && now - laneLastUsed.getOrDefault(e.getKey(), 0L) > LANE_IDLE_TTL_MS) {
                log.info("[ClaudeCode] 回收空闲 lane: {}", e.getKey());
                destroyLane(e.getKey());
            }
        }
    }

    /** 获取实例的终端会话（可能为 null = 尚未创建），供状态服务读取活动阶段 */
    public TerminalSession getSession(Long instanceId) {
        return sessions.get(instanceId);
    }

    /**
     * 中途注入（steering）：把消息注入实例正在执行的轮次（下一个工具边界模型即可见，当场调整）。
     * @return true=已注入；false=该实例当前没有进行中的轮次（调用方回退为常规排队消息）
     */
    public boolean steer(Long instanceId, String text) {
        TerminalSession s = sessions.get(instanceId);
        return s != null && s.steer(text);
    }

    /**
     * 销毁指定实例的终端。
     * ②b 注意：<b>不清</b>持久化 sessionID——销毁进程 ≠ 丢弃上下文，服务重启/下次消息按语义要 --resume 续接；
     * 只有用户显式 /new（{@link #newSession}）才清库。
     */
    public void destroySession(Long instanceId) {
        TerminalSession session = sessions.remove(instanceId);
        if (session != null) {
            session.destroy();
        }
    }

    /**
     * 销毁所有终端（服务关闭时调用）
     */
    @PreDestroy
    public void destroyAll() {
        sessions.values().forEach(TerminalSession::destroy);
        sessions.clear();
        lanes.values().forEach(TerminalSession::destroy);
        lanes.clear();
        laneLastUsed.clear();
        laneOwner.clear();
    }

    /**
     * 列出当前活跃的终端会话
     */
    public String listSessions() {
        if (sessions.isEmpty()) return "📋 暂无活跃终端。";
        StringBuilder sb = new StringBuilder("📋 活跃终端列表：\n\n");
        int i = 1;
        for (var entry : sessions.entrySet()) {
            TerminalSession s = entry.getValue();
            String claudeSid = s.getClaudeSessionId();
            String sidStr = claudeSid != null && claudeSid.length() > 8
                    ? claudeSid.substring(0, 8) + "..." : (claudeSid != null ? claudeSid : "pending");
            sb.append(String.format("%d. 实例 #%d — %s (%s)\n",
                    i++, entry.getKey(), sidStr, s.isAlive() ? "运行中" : "已停止"));
        }
        return sb.toString();
    }
}
