package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonObject;
import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.model.entity.ChatMessage;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.ChatMessageRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * E2E 测试任务服务 — 「实例 A 对实例 B 做端到端测试」工作台的后端核心。
 * <p>
 * 每个任务绑定一个<b>独立的专属 TerminalSession</b>（用 A 实例的目录+模型配置快照），
 * 与 A/B 的飞书会话完全隔离；任务内多轮对话上下文由专属进程维护。
 * <p>
 * 首条消息自动注入 B 的背景简报（B 目录的 git 变更 + B 最近飞书对话），
 * 解决"B 改了什么对用户是黑盒"的问题。事件流经 {@link BrowserTurnListener}
 * 广播到 WebSocket（/ws/e2e）供浏览器流式渲染。
 */
@Service
public class E2ETaskService {

    private static final Logger log = LoggerFactory.getLogger(E2ETaskService.class);
    private static final int GIT_TIMEOUT_SEC = 5;
    private static final int GIT_OUTPUT_CAP = 2000;
    private static final int BRIEF_CHAT_COUNT = 12;
    private static final int BRIEF_CHAT_MSG_CAP = 150;

    /** 事件广播出口 — 由 E2EWebSocketHandler 实现并注册（打破循环依赖） */
    public interface Broadcaster {
        void broadcast(String taskId, JsonObject event);
    }

    private final BotInstanceRepository instanceRepo;
    private final ChatMessageRepository chatMessageRepo;
    private final Map<String, E2ETask> tasks = new ConcurrentHashMap<>();
    private volatile Broadcaster broadcaster;

    public E2ETaskService(BotInstanceRepository instanceRepo, ChatMessageRepository chatMessageRepo) {
        this.instanceRepo = instanceRepo;
        this.chatMessageRepo = chatMessageRepo;
    }

    public void setBroadcaster(Broadcaster b) {
        this.broadcaster = b;
    }

    void broadcast(String taskId, JsonObject event) {
        Broadcaster b = broadcaster;
        if (b != null) {
            try { b.broadcast(taskId, event); }
            catch (Exception e) { log.warn("[E2E] 广播失败: taskId={}, {}", taskId, e.getMessage()); }
        }
    }

    // ===== 任务生命周期 =====

    public E2ETask createTask(long testerInstanceId, long targetInstanceId) throws Exception {
        BotInstance tester = instanceRepo.findById(testerInstanceId)
                .orElseThrow(() -> new IllegalArgumentException("测试实例不存在: " + testerInstanceId));
        BotInstance target = instanceRepo.findById(targetInstanceId)
                .orElseThrow(() -> new IllegalArgumentException("被测实例不存在: " + targetInstanceId));

        String id = UUID.randomUUID().toString().substring(0, 8);
        String dir = firstNonBlank(tester.getClaudeWorkingDir(), tester.getShellWorkingDir(), "");
        String model = tester.getClaudeModel() != null ? tester.getClaudeModel() : "";

        E2ETask task = new E2ETask(id, tester, target, dir, model);
        task.session = new TerminalSession("e2e:" + id, dir, model);
        task.session.start();   // 失败抛 IOException，由 controller 转 4xx/5xx

        tasks.put(id, task);
        log.info("[E2E] 任务已创建: id={}, {}(A) -> {}(B), dir={}, model={}",
                id, tester.getName(), target.getName(), dir, model);
        return task;
    }

    public void sendMessage(String taskId, String text) {
        E2ETask task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("任务不存在: " + taskId);
        if (text == null || text.isBlank()) return;

        TurnRecord rec = new TurnRecord(text.trim());
        task.transcript.add(rec);
        task.activeTurn = rec;
        task.busy = true;

        boolean first = !task.firstMessageSent;
        task.firstMessageSent = true;
        String composed = first
                ? buildBriefing(task) + "\n\n== 用户的测试要求 ==\n" + text.trim()
                : text.trim();

        BrowserTurnListener listener = new BrowserTurnListener(this, task, rec);
        // 虚拟线程执行阻塞的 sendMessage；事件经 listener 实时广播。
        // 任务内连发消息由 TerminalSession 的 turnLock 自动串行化（排队时触发 onQueued）。
        Thread.ofVirtual().name("e2e-turn-" + taskId).start(() -> {
            try {
                task.session.sendMessage(composed, listener);
            } finally {
                task.busy = false;
                task.activeTurn = null;
            }
        });
    }

    public E2ETask getTask(String taskId) { return tasks.get(taskId); }
    public Collection<E2ETask> listTasks() { return tasks.values(); }

    public void destroyTask(String taskId) {
        E2ETask task = tasks.remove(taskId);
        if (task != null) {
            task.session.destroy();
            log.info("[E2E] 任务已销毁: id={}", taskId);
        }
    }

    @PreDestroy
    public void destroyAll() {
        tasks.values().forEach(t -> t.session.destroy());
        tasks.clear();
    }

    // ===== B 的背景简报（黑盒问题的解药） =====

    /** 组装注入首条消息的背景简报 */
    String buildBriefing(E2ETask task) {
        BotInstance target = instanceRepo.findById(task.targetInstanceId).orElse(null);
        String targetName = target != null ? target.getName() : ("#" + task.targetInstanceId);
        String targetDir = target != null
                ? firstNonBlank(target.getClaudeWorkingDir(), target.getShellWorkingDir(), "") : "";

        StringBuilder sb = new StringBuilder();
        sb.append("【端到端测试任务背景】\n");
        sb.append("你是测试执行方（实例「").append(task.testerName).append("」，模型 ")
                .append(task.model.isBlank() ? "默认" : task.model)
                .append("），被测方是实例「").append(targetName).append("」（工作目录: ")
                .append(targetDir.isBlank() ? "（未配置，与当前目录一致）" : targetDir).append("）。\n");
        sb.append("你的职责：理解被测方最近的改动，设计并执行端到端测试，如实报告结果（通过/失败/风险），不要粉饰。\n\n");

        sb.append("== 被测方目录的最近 git 变更 ==\n");
        String gitDir = targetDir.isBlank() ? task.workingDir : targetDir;
        sb.append(gitSection(gitDir)).append("\n\n");

        sb.append("== 被测方最近的飞书对话（它自述做了什么，旧→新） ==\n");
        sb.append(chatSection(task.targetInstanceId));
        return sb.toString();
    }

    /** 供前端"📋 B 的最近变更"面板预览 */
    public Map<String, Object> getBrief(long instanceId) {
        BotInstance inst = instanceRepo.findById(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("实例不存在: " + instanceId));
        String dir = firstNonBlank(inst.getClaudeWorkingDir(), inst.getShellWorkingDir(), "");
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("instanceId", instanceId);
        brief.put("instanceName", inst.getName());
        brief.put("workingDir", dir);
        brief.put("gitLog", runGit(dir, "log", "--oneline", "-10"));
        brief.put("gitStatus", runGit(dir, "status", "--short"));
        brief.put("gitDiffStat", runGit(dir, "diff", "--stat", "HEAD"));
        List<Map<String, String>> chats = new ArrayList<>();
        for (ChatMessage m : recentChats(instanceId)) {
            Map<String, String> c = new LinkedHashMap<>();
            c.put("role", m.getRole());
            c.put("content", truncate(m.getContent(), BRIEF_CHAT_MSG_CAP));
            c.put("time", m.getCreatedAt() != null ? m.getCreatedAt().toString() : "");
            chats.add(c);
        }
        brief.put("recentChats", chats);
        return brief;
    }

    private String gitSection(String dir) {
        if (dir == null || dir.isBlank()) return "（未配置工作目录）";
        StringBuilder sb = new StringBuilder();
        sb.append("[git log --oneline -10]\n").append(runGit(dir, "log", "--oneline", "-10")).append("\n");
        sb.append("[git status --short]\n").append(runGit(dir, "status", "--short")).append("\n");
        sb.append("[git diff --stat HEAD]\n").append(runGit(dir, "diff", "--stat", "HEAD"));
        return sb.toString();
    }

    private String chatSection(long instanceId) {
        List<ChatMessage> msgs = recentChats(instanceId);
        if (msgs.isEmpty()) return "（暂无对话记录）";
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : msgs) {
            sb.append("[").append("user".equals(m.getRole()) ? "用户" : "B自述").append("] ")
                    .append(truncate(m.getContent(), BRIEF_CHAT_MSG_CAP).replaceAll("\\s+", " ")).append("\n");
        }
        return sb.toString();
    }

    private List<ChatMessage> recentChats(long instanceId) {
        List<ChatMessage> desc = chatMessageRepo.findTop100ByInstanceIdOrderByCreatedAtDesc(instanceId);
        List<ChatMessage> recent = new ArrayList<>(desc.subList(0, Math.min(BRIEF_CHAT_COUNT, desc.size())));
        Collections.reverse(recent);   // 旧→新
        return recent;
    }

    /** 在指定目录跑 git 命令，5s 超时，输出截断，失败静默降级 */
    static String runGit(String dir, String... args) {
        if (dir == null || dir.isBlank() || !new File(dir).isDirectory()) return "（目录不存在）";
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("git");
            cmd.add("-C");
            cmd.add(dir);
            cmd.addAll(Arrays.asList(args));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null && out.length() < GIT_OUTPUT_CAP) {
                    out.append(line).append("\n");
                }
            }
            if (!p.waitFor(GIT_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return "（git 命令超时）";
            }
            String s = out.toString().trim();
            if (s.isEmpty()) return "（无输出" + (p.exitValue() != 0 ? "，可能不是 git 仓库，exit=" + p.exitValue() : "") + "）";
            return truncate(s, GIT_OUTPUT_CAP);
        } catch (Exception e) {
            return "（无法获取 git 信息: " + e.getMessage() + "）";
        }
    }

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n...(截断)";
    }

    // ===== 数据结构 =====

    /** 一个 E2E 测试任务 */
    public static class E2ETask {
        public final String id;
        public final long testerInstanceId;
        public final long targetInstanceId;
        public final String testerName;
        public final String targetName;
        public final String workingDir;   // A 的目录快照
        public final String model;        // A 的模型快照
        public final long createdAt = System.currentTimeMillis();
        public TerminalSession session;   // createTask 构造后立即赋值并 start
        public final List<TurnRecord> transcript = new CopyOnWriteArrayList<>();
        public volatile boolean firstMessageSent;
        public volatile boolean busy;
        volatile TurnRecord activeTurn;

        E2ETask(String id, BotInstance tester, BotInstance target, String workingDir, String model) {
            this.id = id;
            this.testerInstanceId = tester.getId();
            this.targetInstanceId = target.getId();
            this.testerName = tester.getName();
            this.targetName = target.getName();
            this.workingDir = workingDir;
            this.model = model;
        }
    }

    /** 一轮对话的完整记录（快照/重连恢复用） */
    public static class TurnRecord {
        public final String userText;
        public final long startMs = System.currentTimeMillis();
        public final StringBuilder thinking = new StringBuilder();
        public final StringBuilder answer = new StringBuilder();
        public final List<ToolStep> tools = new CopyOnWriteArrayList<>();
        public volatile TurnResult result;          // 完成时非空
        public volatile String errorMessage;        // 异常收尾时的信息
        public volatile boolean aborted;

        TurnRecord(String userText) { this.userText = userText; }
    }
}
