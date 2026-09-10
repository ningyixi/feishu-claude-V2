package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.model.entity.ChatMessage;
import com.lark.oapi.sample.ws.model.entity.E2ETaskEntity;
import com.lark.oapi.sample.ws.model.entity.E2ETurnEntity;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.ChatMessageRepository;
import com.lark.oapi.sample.ws.repository.E2ETaskEntityRepository;
import com.lark.oapi.sample.ws.repository.E2ETurnEntityRepository;
import jakarta.annotation.PostConstruct;
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
    private final E2ETaskEntityRepository taskEntityRepo;
    private final E2ETurnEntityRepository turnEntityRepo;
    private final Map<String, E2ETask> tasks = new ConcurrentHashMap<>();
    private volatile Broadcaster broadcaster;

    public E2ETaskService(BotInstanceRepository instanceRepo, ChatMessageRepository chatMessageRepo,
                          E2ETaskEntityRepository taskEntityRepo, E2ETurnEntityRepository turnEntityRepo) {
        this.instanceRepo = instanceRepo;
        this.chatMessageRepo = chatMessageRepo;
        this.taskEntityRepo = taskEntityRepo;
        this.turnEntityRepo = turnEntityRepo;
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

    /** 广播一条群聊系统消息（阶段横幅/结果通告），同时写入群聊记录并落库 */
    public void postSystem(E2ETask task, String text) {
        TurnRecord rec = beginTurn(task, text, "system", "system", "");
        JsonObject e = new JsonObject();
        e.addProperty("type", "system");
        e.addProperty("turnIndex", task.transcript.indexOf(rec));
        e.addProperty("text", text);
        broadcast(task.id, e);
    }

    // ===== 任务生命周期 =====

    /**
     * 创建群聊任务。
     * @param members 角色分配：role ∈ programmer/designer/executor，允许同一实例兼任多个角色
     */
    public E2ETask createTask(List<MemberSpec> members, String requirement, boolean autoFix, int maxRounds) throws Exception {
        return createTask(members, requirement, autoFix, maxRounds, "filesystem");
    }

    public E2ETask createTask(List<MemberSpec> members, String requirement, boolean autoFix, int maxRounds, String commMode) throws Exception {
        if ("a2a".equalsIgnoreCase(commMode)) {
            throw new IllegalArgumentException("A2A（Agent2Agent 协议）通信模式开发中，敬请期待；当前请使用共享文件系统模式");
        }
        if (members == null || members.isEmpty()) throw new IllegalArgumentException("至少需要一个成员实例");
        if (requirement == null || requirement.isBlank()) throw new IllegalArgumentException("请填写要测试的需求描述");

        E2ETask task = new E2ETask(UUID.randomUUID().toString().substring(0, 8));
        task.requirement = requirement.trim();
        task.autoFix = autoFix;
        task.maxRounds = Math.max(1, Math.min(maxRounds, 5));
        task.commMode = commMode != null && !commMode.isBlank() ? commMode : "filesystem";

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
        persistTask(task);
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
            if (task.answerFuture != null) task.answerFuture.complete("（任务已删除）");
            // 用户点 × = 彻底删除：任务 + 流水线状态 + 全部群聊记录
            try {
                turnEntityRepo.deleteByTaskId(taskId);
                taskEntityRepo.deleteById(taskId);
            } catch (Exception e) {
                log.warn("[E2E] 任务数据删除失败: id={}, {}", taskId, e.getMessage());
            }
            log.info("[E2E] 任务已删除(含落库数据): id={}", taskId);
        }
    }

    // ===== 持久化（重启读档） =====

    /** 任务元数据 + 流水线状态整体落库（阶段流转/用例更新/轮次报告/终局时调用） */
    public void persistTask(E2ETask task) {
        try {
            E2ETaskEntity e = taskEntityRepo.findById(task.id).orElseGet(() -> {
                E2ETaskEntity n = new E2ETaskEntity();
                n.setId(task.id);
                n.setCreatedAt(task.createdAt);
                return n;
            });
            e.setMembersJson(membersToJson(task));
            e.setRequirement(task.requirement);
            e.setAutoFix(task.autoFix);
            e.setMaxRounds(task.maxRounds);
            e.setSharedDir(task.sharedDir);
            e.setCommMode(task.commMode);
            e.setPipelineJson(pipelineToJson(task.pipeline));
            e.setPipelineStarted(task.pipelineStarted);
            e.setPipelineDone(task.pipelineDone);
            e.setUpdatedAt(System.currentTimeMillis());
            taskEntityRepo.save(e);
        } catch (Exception ex) {
            log.warn("[E2E] 任务落库失败（不影响运行）: id={}, {}", task.id, ex.getMessage());
        }
    }

    /** 群聊消息终态回填（agent 轮次完成/出错、user 消息补 addressedTo） */
    public void persistTurn(E2ETask task, TurnRecord rec) {
        if (rec.dbId == null) return;
        try {
            turnEntityRepo.findById(rec.dbId).ifPresent(e -> {
                e.setAddressedTo(rec.addressedTo != null ? rec.addressedTo : "");
                synchronized (rec) {
                    e.setThinking(rec.thinking.toString());
                    e.setAnswer(rec.answer.toString());
                }
                e.setToolsJson(toolsToJson(rec.tools));
                e.setResultJson(resultToJson(rec.result));
                e.setErrorMessage(rec.errorMessage);
                e.setAborted(rec.aborted);
                turnEntityRepo.save(e);
            });
        } catch (Exception ex) {
            log.warn("[E2E] 群聊消息回填失败: task={}, {}", task.id, ex.getMessage());
        }
    }

    /** 启动时从 H2 读档：恢复任务、成员、流水线状态与全部群聊记录 */
    @PostConstruct
    public void reconstructAll() {
        try {
            List<E2ETaskEntity> entities = taskEntityRepo.findAll();
            for (E2ETaskEntity e : entities) {
                E2ETask task = new E2ETask(e.getId());
                task.requirement = e.getRequirement() != null ? e.getRequirement() : "";
                task.autoFix = e.isAutoFix();
                task.maxRounds = e.getMaxRounds();
                task.sharedDir = e.getSharedDir();
                task.commMode = e.getCommMode() != null ? e.getCommMode() : "filesystem";
                task.pipelineStarted = e.isPipelineStarted();
                task.pipelineDone = e.isPipelineDone();
                membersFromJson(e.getMembersJson(), task);
                pipelineFromJson(e.getPipelineJson(), task.pipeline);
                // 重启前流水线在跑 → 标记中断（前端显示续跑按钮）
                task.interrupted = task.pipelineStarted && !task.pipelineDone;

                for (E2ETurnEntity te : turnEntityRepo.findByTaskIdOrderByTurnIndexAsc(e.getId())) {
                    TurnRecord rec = new TurnRecord(te.getUserText() != null ? te.getUserText() : "");
                    rec.kind = te.getKind();
                    rec.author = te.getAuthor();
                    rec.authorName = te.getAuthorName() != null ? te.getAuthorName() : "";
                    rec.addressedTo = te.getAddressedTo() != null ? te.getAddressedTo() : "";
                    rec.dbId = te.getId();
                    synchronized (rec) {
                        if (te.getThinking() != null) rec.thinking.append(te.getThinking());
                        if (te.getAnswer() != null) rec.answer.append(te.getAnswer());
                    }
                    toolsFromJson(te.getToolsJson(), rec.tools);
                    rec.result = resultFromJson(te.getResultJson());
                    rec.errorMessage = te.getErrorMessage();
                    rec.aborted = te.isAborted();
                    if ("agent".equals(rec.kind) && rec.result == null && rec.errorMessage == null) {
                        rec.errorMessage = "应用重启，此发言被中断";
                    }
                    task.transcript.add(rec);
                }
                tasks.put(task.id, task);
                log.info("[E2E] 读档恢复: id={}, 成员{}个, 消息{}条, 用例{}条, 轮次{}次, interrupted={}",
                        task.id, task.members.size(), task.transcript.size(),
                        task.pipeline.cases.size(), task.pipeline.rounds.size(), task.interrupted);
            }
        } catch (Exception ex) {
            log.error("[E2E] 读档失败（不影响新任务）", ex);
        }
    }

    // ===== 序列化 =====

    private String membersToJson(E2ETask task) {
        JsonArray arr = new JsonArray();
        for (Member m : task.members) {
            JsonObject o = new JsonObject();
            o.addProperty("instanceId", m.instanceId);
            o.addProperty("name", m.name);
            o.addProperty("role", m.role);
            o.addProperty("workingDir", m.workingDir);
            o.addProperty("model", m.model);
            o.addProperty("authorizedOpenId", m.authorizedOpenId);
            arr.add(o);
        }
        return arr.toString();
    }

    private void membersFromJson(String json, E2ETask task) {
        try {
            for (JsonElement el : JsonParser.parseString(json != null ? json : "[]").getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                Member m = new Member();
                m.instanceId = o.has("instanceId") ? o.get("instanceId").getAsLong() : 0;
                m.name = o.has("name") ? o.get("name").getAsString() : "";
                m.role = o.has("role") ? o.get("role").getAsString() : "";
                m.workingDir = o.has("workingDir") ? o.get("workingDir").getAsString() : "";
                m.model = o.has("model") ? o.get("model").getAsString() : "";
                m.authorizedOpenId = o.has("authorizedOpenId") ? o.get("authorizedOpenId").getAsString() : "";
                task.members.add(m);
            }
        } catch (Exception ignored) {}
    }

    private String pipelineToJson(PipelineState ps) {
        JsonObject o = new JsonObject();
        o.addProperty("stage", ps.stage);
        o.addProperty("stageDetail", ps.stageDetail);
        o.addProperty("round", ps.round);
        o.addProperty("failReason", ps.failReason);
        JsonArray cases = new JsonArray();
        for (TestCase c : ps.cases) {
            JsonObject co = new JsonObject();
            co.addProperty("id", c.id);
            co.addProperty("title", c.title);
            co.addProperty("steps", c.steps);
            co.addProperty("expected", c.expected);
            co.addProperty("priority", c.priority);
            co.addProperty("status", c.status);
            co.addProperty("evidence", c.evidence);
            co.addProperty("failReason", c.failReason);
            co.addProperty("lastRound", c.lastRound);
            cases.add(co);
        }
        o.add("cases", cases);
        JsonArray rounds = new JsonArray();
        for (RoundReport r : ps.rounds) {
            JsonObject ro = new JsonObject();
            ro.addProperty("round", r.round);
            ro.addProperty("passed", r.passed);
            ro.addProperty("failed", r.failed);
            ro.addProperty("blocked", r.blocked);
            ro.addProperty("total", r.total);
            ro.addProperty("atMs", r.atMs);
            ro.addProperty("fixSummary", r.fixSummary);
            JsonArray results = new JsonArray();
            for (CaseResult cr : r.results) {
                JsonObject cro = new JsonObject();
                cro.addProperty("id", cr.id);
                cro.addProperty("title", cr.title);
                cro.addProperty("status", cr.status);
                cro.addProperty("evidence", cr.evidence);
                cro.addProperty("failReason", cr.failReason);
                results.add(cro);
            }
            ro.add("results", results);
            rounds.add(ro);
        }
        o.add("rounds", rounds);
        return o.toString();
    }

    private void pipelineFromJson(String json, PipelineState ps) {
        try {
            JsonObject o = JsonParser.parseString(json != null && !json.isBlank() ? json : "{}").getAsJsonObject();
            if (o.has("stage") && !o.get("stage").isJsonNull()) ps.stage = o.get("stage").getAsString();
            if (o.has("stageDetail") && !o.get("stageDetail").isJsonNull()) ps.stageDetail = o.get("stageDetail").getAsString();
            if (o.has("round")) ps.round = o.get("round").getAsInt();
            if (o.has("failReason") && !o.get("failReason").isJsonNull()) ps.failReason = o.get("failReason").getAsString();
            if (o.has("cases")) {
                for (JsonElement el : o.getAsJsonArray("cases")) {
                    JsonObject co = el.getAsJsonObject();
                    TestCase c = new TestCase();
                    c.id = co.has("id") ? co.get("id").getAsString() : "";
                    c.title = co.has("title") ? co.get("title").getAsString() : "";
                    c.steps = co.has("steps") ? co.get("steps").getAsString() : "";
                    c.expected = co.has("expected") ? co.get("expected").getAsString() : "";
                    c.priority = co.has("priority") ? co.get("priority").getAsString() : "medium";
                    c.status = co.has("status") ? co.get("status").getAsString() : "pending";
                    c.evidence = co.has("evidence") ? co.get("evidence").getAsString() : "";
                    c.failReason = co.has("failReason") ? co.get("failReason").getAsString() : "";
                    c.lastRound = co.has("lastRound") ? co.get("lastRound").getAsInt() : 0;
                    ps.cases.add(c);
                }
            }
            if (o.has("rounds")) {
                for (JsonElement el : o.getAsJsonArray("rounds")) {
                    JsonObject ro = el.getAsJsonObject();
                    RoundReport r = new RoundReport();
                    r.round = ro.get("round").getAsInt();
                    r.passed = ro.get("passed").getAsInt();
                    r.failed = ro.get("failed").getAsInt();
                    r.blocked = ro.get("blocked").getAsInt();
                    r.total = ro.get("total").getAsInt();
                    r.atMs = ro.has("atMs") ? ro.get("atMs").getAsLong() : 0;
                    r.fixSummary = ro.has("fixSummary") && !ro.get("fixSummary").isJsonNull() ? ro.get("fixSummary").getAsString() : "";
                    if (ro.has("results")) {
                        for (JsonElement re : ro.getAsJsonArray("results")) {
                            JsonObject cro = re.getAsJsonObject();
                            r.results.add(new CaseResult(
                                    cro.has("id") ? cro.get("id").getAsString() : "",
                                    cro.has("title") ? cro.get("title").getAsString() : "",
                                    cro.has("status") ? cro.get("status").getAsString() : "",
                                    cro.has("evidence") ? cro.get("evidence").getAsString() : "",
                                    cro.has("failReason") ? cro.get("failReason").getAsString() : ""));
                        }
                    }
                    ps.rounds.add(r);
                }
            }
        } catch (Exception ignored) {}
    }

    private String toolsToJson(List<ToolStep> tools) {
        JsonArray arr = new JsonArray();
        for (ToolStep s : tools) {
            JsonObject o = new JsonObject();
            o.addProperty("name", s.name);
            o.addProperty("detail", s.detail);
            o.addProperty("status", s.status.name());
            o.addProperty("elapsedMs", s.elapsedMs());
            arr.add(o);
        }
        return arr.toString();
    }

    private void toolsFromJson(String json, List<ToolStep> out) {
        try {
            for (JsonElement el : JsonParser.parseString(json != null ? json : "[]").getAsJsonArray()) {
                JsonObject o = el.getAsJsonObject();
                ToolStep.Status st = ToolStep.Status.valueOf(o.get("status").getAsString());
                out.add(ToolStep.restore(o.get("name").getAsString(),
                        o.has("detail") && !o.get("detail").isJsonNull() ? o.get("detail").getAsString() : "",
                        st, o.has("elapsedMs") ? o.get("elapsedMs").getAsLong() : 0));
            }
        } catch (Exception ignored) {}
    }

    private String resultToJson(TurnResult r) {
        if (r == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("text", r.text);
        o.addProperty("isError", r.isError);
        o.addProperty("aborted", r.aborted);
        o.addProperty("durationMs", r.durationMs);
        o.addProperty("thinkingMs", r.thinkingMs);
        o.addProperty("toolElapsedMs", r.toolElapsedMs);
        o.addProperty("inputTokens", r.inputTokens);
        o.addProperty("outputTokens", r.outputTokens);
        o.addProperty("costUsd", r.costUsd);
        o.addProperty("model", r.model);
        return o.toString();
    }

    private TurnResult resultFromJson(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            JsonObject o = JsonParser.parseString(json).getAsJsonObject();
            TurnResult r = new TurnResult();
            r.text = o.has("text") && !o.get("text").isJsonNull() ? o.get("text").getAsString() : "";
            r.isError = o.has("isError") && o.get("isError").getAsBoolean();
            r.aborted = o.has("aborted") && o.get("aborted").getAsBoolean();
            r.durationMs = o.has("durationMs") ? o.get("durationMs").getAsLong() : 0;
            r.thinkingMs = o.has("thinkingMs") ? o.get("thinkingMs").getAsLong() : 0;
            r.toolElapsedMs = o.has("toolElapsedMs") ? o.get("toolElapsedMs").getAsLong() : 0;
            r.inputTokens = o.has("inputTokens") ? o.get("inputTokens").getAsLong() : -1;
            r.outputTokens = o.has("outputTokens") ? o.get("outputTokens").getAsLong() : -1;
            r.costUsd = o.has("costUsd") ? o.get("costUsd").getAsDouble() : -1;
            r.model = o.has("model") && !o.get("model").isJsonNull() ? o.get("model").getAsString() : "";
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    @PreDestroy
    public void destroyAll() {
        tasks.values().forEach(t -> t.cancelled = true);
        tasks.clear();
    }

    // ===== 群聊轮次记录 =====

    /** 开一轮群消息记录（成员发言/用户发言/系统消息共用），返回记录供 listener 绑定；即时插入 e2e_turn 行 */
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
        // 落库（agent 轮次内容在 persistTurn 完成时回填）
        try {
            E2ETurnEntity e = new E2ETurnEntity();
            e.setTaskId(task.id);
            e.setTurnIndex(task.transcript.size() - 1);
            e.setKind(kind);
            e.setAuthor(author);
            e.setAuthorName(authorName != null ? authorName : "");
            e.setUserText(displayText != null ? displayText : "");
            e.setStartMs(rec.startMs);
            turnEntityRepo.save(e);
            rec.dbId = e.getId();
        } catch (Exception ex) {
            log.warn("[E2E] 群聊消息落库失败（不影响运行）: {}", ex.getMessage());
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
        /**
         * 成员间通信方式：
         * filesystem = 共享文件系统（黑板模式 Blackboard Pattern：同工作目录经 .e2e/<taskId>/ 文件中转，当前实现）
         * a2a        = Agent2Agent 协议（agent 直连通信，开发中，创建时明确拒绝防止静默降级）
         */
        public volatile String commMode = "filesystem";
        public volatile boolean busy;
        public volatile boolean cancelled;
        public volatile boolean pipelineStarted;
        public volatile boolean pipelineDone;
        /** 应用重启导致的流水线中断（读档时计算；续跑/终局后清除） */
        public volatile boolean interrupted;
        volatile TurnRecord activeTurn;
        public final PipelineState pipeline = new PipelineState();

        /** 流水线暂停中：某成员在等玩家回答问题（非空=等待中） */
        public volatile PendingQuestion pendingQuestion;
        /** 玩家答案投递口 — postUserMessage(auto) 完成它即恢复流水线 */
        public volatile java.util.concurrent.CompletableFuture<String> answerFuture;

        E2ETask(String id) { this.id = id; }

        public Member memberByRole(String role) {
            return members.stream().filter(m -> m.role.equals(role)).findFirst().orElse(null);
        }
        /** 共享文件交换目录：<sharedDir>/.e2e/<taskId>（同目录模式下约定路径） */
        public String exchangeDirName() {
            return ".e2e" + File.separator + id;
        }
    }

    /** 流水线暂停时成员向玩家提的问题 */
    public static class PendingQuestion {
        public final String role;        // 提问成员角色
        public final String question;
        public final String why;
        public final long sinceMs = System.currentTimeMillis();

        public PendingQuestion(String role, String question, String why) {
            this.role = role;
            this.question = question;
            this.why = why != null ? why : "";
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
        /** 本轮逐用例判定痕迹（重测轮只含该轮实际执行的用例）——右侧"执行记录"数据源 */
        public final List<CaseResult> results = new CopyOnWriteArrayList<>();
    }

    /** 某轮中单条用例的判定快照（不随复测覆盖，保留各轮痕迹） */
    public static class CaseResult {
        public String id;
        public String title;
        public String status;      // pass | fail | blocked
        public String evidence;    // 截断后的关键证据
        public String failReason;  // 截断后的失败原因

        public CaseResult(String id, String title, String status, String evidence, String failReason) {
            this.id = id;
            this.title = title;
            this.status = status;
            this.evidence = evidence;
            this.failReason = failReason;
        }
    }

    /** 群聊里的一条消息记录（用户/成员发言/系统横幅统一建模；agent 发言带流式累积） */
    public static class TurnRecord {
        public final String userText;                    // user/system: 消息文本; agent: 展示用的发言摘要
        public final long startMs = System.currentTimeMillis();
        public volatile String kind = "agent";           // user | agent | system
        public volatile String author = "user";          // programmer/designer/executor/user/system
        public volatile String authorName = "";          // 实例名
        public volatile String addressedTo = "";         // user 消息的收件人: auto|programmer|designer|executor
        public volatile Long dbId;                       // e2e_turn 表主键（持久化用）
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
