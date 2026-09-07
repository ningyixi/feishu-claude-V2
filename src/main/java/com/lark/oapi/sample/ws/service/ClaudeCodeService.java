package com.lark.oapi.sample.ws.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
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
        try {
            TerminalSession session = getOrCreate(instanceId, workingDir, model);
            log.info("[ClaudeCode][{}] 发送消息: openId={}", instanceId, openId);
            return session.sendMessage(message, listener);
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
        TerminalSession session = sessions.get(instanceId);
        if (session != null && session.isAlive()) {
            // 检测配置是否变更（工作目录或模型）
            boolean configChanged = !nullSafeEquals(session.getWorkingDir(), workingDir)
                    || !nullSafeEquals(session.getModel(), model);
            if (!configChanged) {
                return session;
            }
            log.info("[ClaudeCode] 配置变更，重建终端: instanceId={}, dir: {} -> {}, model: {} -> {}",
                    instanceId, session.getWorkingDir(), workingDir, session.getModel(), model);
            session.destroy();
        }

        // 旧的进程可能已死，清理掉
        if (session != null) {
            session.destroy();
        }

        String sessionKey = instanceId + ":" + (workingDir != null ? workingDir : "");
        session = new TerminalSession(sessionKey, workingDir, model);
        session.start();
        sessions.put(instanceId, session);
        log.info("[ClaudeCode] 创建新终端: instanceId={}, dir={}", instanceId, workingDir);
        return session;
    }

    private static boolean nullSafeEquals(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
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
