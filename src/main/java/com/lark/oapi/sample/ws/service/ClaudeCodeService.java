package com.lark.oapi.sample.ws.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.Map;
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
            return session.sendMessage(message, images, listener);
        } catch (Exception e) {
            log.error("[ClaudeCode][{}] 对话失败", instanceId, e);
            if (listener != null) listener.onTurnError("Claude 对话失败: " + e.getMessage(), false);
            return "❌ Claude 对话失败: " + e.getMessage();
        }
    }

    /**
     * 重置会话 — 销毁旧终端，创建新的。
     */
    public void newSession(Long instanceId, String workingDir, String model) {
        destroySession(instanceId);
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
        int sandboxLevel = instanceRepo.findById(instanceId)
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
        session.inheritSessionId(inherit);
        session.start();
        sessions.put(instanceId, session);
        log.info("[ClaudeCode] 创建新终端: instanceId={}, dir={}, 沙箱={}", instanceId, workingDir, SandboxPolicy.label(sandboxLevel));
        return session;
    }

    private static boolean nullSafeEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    // ===== 独立通道（lane）：数字员工需求/聊天专用会话，不与实例主会话（飞书私聊/E2E/供应商）共用 =====

    /** 空闲 lane 保留时长：需求完成后仍可带上下文追问 */
    private static final long LANE_IDLE_TTL_MS = Long.getLong("claude.laneIdleTtlMinutes", 30L) * 60_000L;
    /** 空闲 lane 最多保留个数（超出按最久未用淘汰，防进程堆积） */
    private static final int LANE_IDLE_CAP = Integer.getInteger("claude.laneIdleCap", 6);

    private final Map<String, TerminalSession> lanes = new ConcurrentHashMap<>();
    private final Map<String, Long> laneLastUsed = new ConcurrentHashMap<>();

    /**
     * 在独立 lane 上对话：lane 首次使用时以实例的工作目录/模型/沙箱档位起一个新的 CLI 进程（全新上下文），
     * 后续同 lane 复用。与实例主会话互不排队、互不污染上下文。
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
                && s.getSandboxLevel() == sandboxLevel && s.getExtraDirs().equals(extraDirs) && s.getExtraEnv().equals(env)) {
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
        if (s != null) s.destroy();
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
     * 销毁指定实例的终端
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
