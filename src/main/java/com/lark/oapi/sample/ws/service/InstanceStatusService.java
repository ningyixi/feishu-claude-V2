package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.dto.InstanceStatusDTO;
import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * 实例状态判定服务 — 把三个真实状态源合成一个"好玩但诚实"的状态：
 * <ul>
 *   <li>FeishuBotService：WS 连接是否在线、最后收到消息的时间</li>
 *   <li>ClaudeCodeService/TerminalSession：进程是否存活、轮次活动阶段（思考/工具/生成）、排队数、上轮结束时间</li>
 *   <li>BotInstance：enabled 开关</li>
 * </ul>
 * 判定优先级：停用 &gt; 断线 &gt; 干活中 &gt; 进程挂了 &gt; 刚完成 &gt; 空闲 &gt; 摸鱼 &gt; 睡大觉。
 * 空闲类状态的文案随机取自文案池，每次轮询都可能有新花样。
 */
@Service
public class InstanceStatusService {

    /** 刚完成任务的展示窗口 */
    private static final long DONE_WINDOW_MS = 2 * 60_000L;
    /** 空闲 → 摸鱼 的阈值 */
    private static final long FISHING_AFTER_MS = 10 * 60_000L;
    /** 摸鱼 → 睡大觉 的阈值 */
    private static final long SLEEPING_AFTER_MS = 2 * 60 * 60_000L;

    private static final String[] IDLE_LINES = {
            "随时待命，来点活干干",
            "精力满格，就等你一句话",
            "闲得发慌，快发消息来玩",
            "整装待发，油门已踩热",
    };
    private static final String[] FISHING_LINES = {
            "%s没动静，九成是在摸鱼",
            "摸鱼小能手，已经游了%s",
            "疑似在知识的海洋里…狗刨",
            "上班摸鱼中，别告诉老板（%s）",
    };
    private static final String[] SLEEPING_LINES = {
            "%s没理人了，睡得像头小猪",
            "呼呼大睡中，除非天塌了别叫醒它",
            "已进入深度睡眠，梦里还在写代码（%s）",
            "冬眠中…春天来了记得叫醒它",
    };
    private static final String[] THINKING_LINES = {
            "脑子里的齿轮转得飞起…",
            "沉思中，眉头紧锁（并没有眉头）",
            "正在脑内疯狂翻资料…",
            "思考人生…不是，思考你的问题",
    };
    private static final String[] GENERATING_LINES = {
            "键盘敲得冒星子，马上出活",
            "奋笔疾书中，笔尖起火",
            "答案正在喷射而出…",
            "文思泉涌，拦都拦不住",
    };

    private final BotInstanceRepository instanceRepo;
    private final FeishuBotService feishuBotService;
    private final ClaudeCodeService claudeCodeService;

    public InstanceStatusService(BotInstanceRepository instanceRepo,
                                 FeishuBotService feishuBotService,
                                 ClaudeCodeService claudeCodeService) {
        this.instanceRepo = instanceRepo;
        this.feishuBotService = feishuBotService;
        this.claudeCodeService = claudeCodeService;
    }

    public List<InstanceStatusDTO> resolveAll() {
        return instanceRepo.findAll().stream()
                .map(this::resolve)
                .collect(Collectors.toList());
    }

    public InstanceStatusDTO resolve(BotInstance inst) {
        Long id = inst.getId();

        // 1. 停用
        if (!inst.isEnabled()) {
            return new InstanceStatusDTO(id, "DISABLED", "🚫", "已停用", "老板说了，这个机器人放假", false);
        }
        // 2. 飞书断线（用真实连线状态，能识别"SDK 静默重连中"的假在线）
        if (!feishuBotService.isWsAlive(id)) {
            boolean everConnected = feishuBotService.isInstanceConnected(id);
            return new InstanceStatusDTO(id, "DISCONNECTED", "📡", "信号中断",
                    everConnected ? "飞书长连接断开，SDK 自动重连中（网络恢复后自愈，无需重启）"
                                  : "飞书长连接未建立，检查网络或凭证后重连",
                    false);
        }

        TerminalSession session = claudeCodeService.getSession(id);

        // 3. 干活中（轮次活跃）——优先级最高的"好玩"状态
        if (session != null && session.isTurnActive()) {
            long elapsed = session.getTurnElapsedMs();
            int queued = session.getQueuedCount();
            String queuedNote = queued > 0 ? "，身后还排着 " + queued + " 条消息" : "";
            return switch (session.getActivityPhase()) {
                case TOOL_RUNNING -> {
                    String tool = session.getCurrentToolName();
                    yield new InstanceStatusDTO(id, "WORKING", "🛠️", "工作中",
                            "正在执行 " + (tool != null ? tool : "工具") + "（已 " + fmt(elapsed) + "）" + queuedNote, true);
                }
                case GENERATING -> new InstanceStatusDTO(id, "GENERATING", "✍️", "奋笔疾书",
                        pick(GENERATING_LINES) + "（已 " + fmt(elapsed) + "）" + queuedNote, true);
                case THINKING, IDLE -> new InstanceStatusDTO(id, "THINKING", "🤔", "思考中",
                        pick(THINKING_LINES) + "（已 " + fmt(elapsed) + "）" + queuedNote, true);
            };
        }

        // 4. 进程挂了（有会话但进程死了，下一条消息会自动重启）
        if (session != null && !session.isAlive()) {
            return new InstanceStatusDTO(id, "CRASHED", "🤕", "进程挂了",
                    "Claude 进程意外退出，发条消息就能满血复活", false);
        }

        // 5. 空闲分级：刚完成 → 待命 → 摸鱼 → 睡大觉
        long lastMsg = feishuBotService.getLastMessageTime(id);
        long lastEnd = session != null ? session.getLastTurnEndTime() : 0;
        long lastActivity = Math.max(lastMsg, lastEnd);

        if (lastEnd > 0 && System.currentTimeMillis() - lastEnd < DONE_WINDOW_MS) {
            return new InstanceStatusDTO(id, "DONE", "✅", "任务完成",
                    "刚干完一票（" + fmt(System.currentTimeMillis() - lastEnd) + "前），歇口气", false);
        }
        if (session == null && lastMsg == 0) {
            return new InstanceStatusDTO(id, "IDLE", "🟢", "空闲中",
                    "还没人找它聊过天，它很矜持地在待命", false);
        }
        if (lastActivity <= 0) {
            return new InstanceStatusDTO(id, "IDLE", "🟢", "空闲中", pick(IDLE_LINES), false);
        }

        long idleFor = System.currentTimeMillis() - lastActivity;
        if (idleFor < FISHING_AFTER_MS) {
            return new InstanceStatusDTO(id, "IDLE", "🟢", "空闲中", pick(IDLE_LINES), false);
        }
        if (idleFor < SLEEPING_AFTER_MS) {
            return new InstanceStatusDTO(id, "FISHING", "🐟", "摸鱼中",
                    String.format(pick(FISHING_LINES), fmt(idleFor)), false);
        }
        return new InstanceStatusDTO(id, "SLEEPING", "😴", "睡大觉",
                String.format(pick(SLEEPING_LINES), fmt(idleFor)), false);
    }

    // ===== 工具 =====

    private static String pick(String[] lines) {
        return lines[ThreadLocalRandom.current().nextInt(lines.length)];
    }

    /** 毫秒 → 人类可读（23s / 5分02秒 / 2小时13分） */
    static String fmt(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "秒";
        long m = s / 60;
        if (m < 60) return String.format("%d分%02d秒", m, s % 60);
        long h = m / 60;
        return h + "小时" + String.format("%02d分", m % 60);
    }
}
