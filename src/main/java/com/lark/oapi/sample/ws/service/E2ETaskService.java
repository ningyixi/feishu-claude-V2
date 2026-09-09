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
 * E2E 测试群聊服务 — AgentCoder 三角色架构（Programmer / Test Designer / Test Executor）。
 * <p>
 * 一个任务 = 一个"群聊"：把若干个<b>已有实例</b>圈进来，各实例使用自己与飞书对话的
 * 同一常驻会话（ClaudeCodeService 终端池，不新建专属进程）——程序员实例天然记得
 * 自己在飞书里做过的改动。群内每个成员的发言（一轮 Claude 对话）都写入群聊记录
 * 并经 WebSocket 广播到浏览器；编排逻辑见 {@link E2EOrchestrator}。
 * <p>
 * 同目录优化：若所有成员实例的工作目录相同，agent 之间的大内容（变更说明/用例/报告）
 * 直接通过共享目录下 {@code .e2e/<taskId>/} 的文件交换，群聊只留精简摘要。
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

    /** 广播一条群聊系统消息（阶段横幅/结果通告），同时写入群聊记录 */
    public void postSystem(E2ETask task, String text) {
        TurnRecord rec = new TurnRecord(text);
        rec.kind = "system";
        rec.author = "system";
        task.transcript.add(rec);
        JsonObject e = new JsonObject();
        e.addProperty("type", "system");
        e.addProperty("turnIndex", task.transcript.size() - 1);
        e.addProperty("text", text);
        broadcast(task.id, e);
    }

    // ===== 任务生命周期 =====

    /**
     * 创建群聊任务。
     * @param members 角色分配：role ∈ programmer/designer/executor，允许同一实例兼任多个角色
     */
    public E2ETask createTask(List<MemberSpec> members, String requirement, boolean autoFix, int maxRounds) throws Exception {
        if (members == null || members.isEmpty()) throw new IllegalArgumentException("至少需要一个成员实例");
        if (requirement == null || requirement.isBlank()) throw new IllegalArgumentException("请填写要测试的需求描述");

        E2ETask task = new E2ETask(UUID.randomUUID().toString().substring(0, 8));
        task.requirement = requirement.trim();
        task.autoFix = autoFix;
        task.maxRounds = Math.max(1, Math.min(maxRounds, 5));

        Set<String> dirs = new LinkedHashSet<>();
        for (MemberSpec spec : members) {
            BotInstance inst = instanceRepo.findById(spec.instanceId)
                    .orElseThrow(() -> new IllegalArgumentException("实例不存在: " + spec.instanceId));
            Member m = new Member();
            m.instanceId = inst.getId();
            m.name = inst.getName();
            m.role = spec.role;
            m.workingDir = firstNonBlank(inst.getClaudeWorkingDir(), inst.getShellWorkingDir(), "");
            m.model = inst.getClaudeModel() != null ? inst.getClaudeModel() : "";
            m.authorizedOpenId = inst.getAuthorizedOpenId() != null ? inst.getAuthorizedOpenId() : "";
            task.members.add(m);
            if (!m.workingDir.isBlank()) dirs.add(m.workingDir.toLowerCase());
        }
        // 三个核心角色齐不齐全只提醒不拦截（允许一人分饰多角、也允许缺角色跑简化流程）
        task.sharedDir = dirs.size() == 1 ? task.members.get(0).workingDir : null;

        tasks.put(task.id, task);
        log.info("[E2E] 群聊任务已创建: id={}, members={}, sharedDir={}",
                task.id, task.members.stream().map(m -> m.role + "=" + m.name).toList(),
                task.sharedDir != null ? task.sharedDir : "(目录不同,走群聊全文传递)");
        return task;
    }

    public E2ETask getTask(String taskId) { return tasks.get(taskId); }
    public Collection<E2ETask> listTasks() { return tasks.values(); }

    public void destroyTask(String taskId) {
        E2ETask task = tasks.remove(taskId);
        if (task != null) {
            task.cancelled = true;
            log.info("[E2E] 任务已删除: id={}", taskId);
        }
    }

    @PreDestroy
    public void destroyAll() {
        tasks.values().forEach(t -> t.cancelled = true);
        tasks.clear();
    }

    // ===== 群聊轮次记录 =====

    /** 开一轮群消息记录（成员发言/用户发言/系统消息共用），返回记录供 listener 绑定 */
    TurnRecord beginTurn(E2ETask task, String displayText, String kind, String author, String authorName) {
        TurnRecord rec = new TurnRecord(displayText);
        rec.kind = kind;          // user | agent | system
        rec.author = author;      // programmer/designer/executor/user/system
        rec.authorName = authorName;
        task.transcript.add(rec);
        if ("agent".equals(kind)) {
            task.activeTurn = rec;
            task.busy = true;
        }
        return rec;
    }

    void endTurn(E2ETask task) {
        task.busy = false;
        task.activeTurn = null;
    }

    /** 最近群聊摘要（给成员补上下文用：它自己的会话里没有别人的发言） */
    String recentGroupDigest(E2ETask task, int maxMsgs, int capPerMsg) {
        List<TurnRecord> recs = task.transcript;
        int from = Math.max(0, recs.size() - maxMsgs);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < recs.size(); i++) {
            TurnRecord r = recs.get(i);
            if (r.kind.equals("user") || r.kind.equals("system")) {
                sb.append("[").append(r.authorLabel()).append("] ").append(truncate(r.userText, capPerMsg)).append("\n");
            } else {
                String ans;
                synchronized (r) { ans = r.answer.toString(); }
                if (!ans.isBlank()) {
                    sb.append("[").append(r.authorLabel()).append("] ").append(truncate(ans, capPerMsg)).append("\n");
                }
            }
        }
        return sb.toString();
    }

    // ===== git / 对话简报（给测试设计师看事实，防止只听程序员自述） =====

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

    String gitSection(String dir) {
        if (dir == null || dir.isBlank()) return "（未配置工作目录）";
        StringBuilder sb = new StringBuilder();
        sb.append("[git log --oneline -10]\n").append(runGit(dir, "log", "--oneline", "-10")).append("\n");
        sb.append("[git status --short]\n").append(runGit(dir, "status", "--short")).append("\n");
        sb.append("[git diff --stat HEAD]\n").append(runGit(dir, "diff", "--stat", "HEAD"));
        return sb.toString();
    }

    String chatSection(long instanceId) {
        List<ChatMessage> msgs = recentChats(instanceId);
        if (msgs.isEmpty()) return "（暂无对话记录）";
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : msgs) {
            sb.append("[").append("user".equals(m.getRole()) ? "用户" : "实例自述").append("] ")
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

    /** 创建任务时的成员角色分配 */
    public static class MemberSpec {
        public long instanceId;
        public String role;   // programmer | designer | executor
    }

    /** 群成员 = 一个已有实例 + 角色（会话复用该实例的飞书终端池会话） */
    public static class Member {
        public long instanceId;
        public String name;
        public String role;
        public String workingDir;
        public String model;
        public String authorizedOpenId;

        public String roleLabel() {
            return switch (role) {
                case "programmer" -> "程序员";
                case "designer" -> "测试设计师";
                case "executor" -> "执行者";
                default -> role;
            };
        }
        public String roleEmoji() {
            return switch (role) {
                case "programmer" -> "👨‍💻";
                case "designer" -> "🧪";
                case "executor" -> "⚙️";
                default -> "🤖";
            };
        }
    }

    /** 一个 E2E 群聊任务 */
    public static class E2ETask {
        public final String id;
        public final long createdAt = System.currentTimeMillis();
        public final List<Member> members = new CopyOnWriteArrayList<>();
        public final List<TurnRecord> transcript = new CopyOnWriteArrayList<>();
        public volatile String requirement = "";
        public volatile boolean autoFix = true;
        public volatile int maxRounds = 3;
        /** 所有成员目录一致时非空 —— 启用文件中转（群聊精简模式） */
        public volatile String sharedDir;
        public volatile boolean busy;
        public volatile boolean cancelled;
        public volatile boolean pipelineStarted;
        public volatile boolean pipelineDone;
        volatile TurnRecord activeTurn;
        public final PipelineState pipeline = new PipelineState();

        E2ETask(String id) { this.id = id; }

        public Member memberByRole(String role) {
            return members.stream().filter(m -> m.role.equals(role)).findFirst().orElse(null);
        }
        /** 共享文件交换目录：<sharedDir>/.e2e/<taskId>（同目录模式下约定路径） */
        public String exchangeDirName() {
            return ".e2e" + File.separator + id;
        }
    }

    /** 流水线状态 — 阶段机 + 用例集 + 轮次报告（对照 AgentCoder: designer→executor→feedback loop） */
    public static class PipelineState {
        /** IDLE | PROGRAMMER_REPORT | DESIGNING | EXECUTING | FIXING | DONE | FAILED */
        public volatile String stage = "IDLE";
        public volatile String stageDetail = "";
        public volatile int round;
        public volatile String failReason;
        public final List<TestCase> cases = new CopyOnWriteArrayList<>();
        public final List<RoundReport> rounds = new CopyOnWriteArrayList<>();
    }

    /** 结构化测试用例（Test Designer 产出，JSON 解析而来） */
    public static class TestCase {
        public String id;
        public String title;
        public String steps;
        public String expected;
        public String priority = "medium";
        /** pending | running | pass | fail | blocked */
        public volatile String status = "pending";
        public volatile String evidence = "";
        public volatile String failReason = "";
        public volatile int lastRound;
    }

    /** 单轮执行报告（Test Executor 产出聚合） */
    public static class RoundReport {
        public int round;
        public int passed, failed, blocked, total;
        public long atMs = System.currentTimeMillis();
        public String fixSummary = "";   // 程序员的修复说明（截断）
    }

    /** 群聊里的一条消息记录（用户/成员发言/系统横幅统一建模；agent 发言带流式累积） */
    public static class TurnRecord {
        public final String userText;                    // user/system: 消息文本; agent: 展示用的发言摘要
        public final long startMs = System.currentTimeMillis();
        public volatile String kind = "agent";           // user | agent | system
        public volatile String author = "user";          // programmer/designer/executor/user/system
        public volatile String authorName = "";          // 实例名
        public final StringBuilder thinking = new StringBuilder();
        public final StringBuilder answer = new StringBuilder();
        public final List<ToolStep> tools = new CopyOnWriteArrayList<>();
        public volatile TurnResult result;
        public volatile String errorMessage;
        public volatile boolean aborted;

        TurnRecord(String userText) { this.userText = userText; }

        public String authorLabel() {
            return switch (author) {
                case "programmer" -> "👨‍💻程序员" + (authorName.isBlank() ? "" : "·" + authorName);
                case "designer" -> "🧪测试设计师" + (authorName.isBlank() ? "" : "·" + authorName);
                case "executor" -> "⚙️执行者" + (authorName.isBlank() ? "" : "·" + authorName);
                case "system" -> "系统";
                default -> "用户";
            };
        }
    }
}
