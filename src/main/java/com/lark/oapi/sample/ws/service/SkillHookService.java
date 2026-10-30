package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.model.entity.ChatMessage;
import com.lark.oapi.sample.ws.model.entity.HookRun;
import com.lark.oapi.sample.ws.model.entity.SkillFileChange;
import com.lark.oapi.sample.ws.model.entity.SkillHook;
import com.lark.oapi.sample.ws.repository.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Skill 自进化 Hook 服务 — 从实战对话反向维护 skill 文件。
 * <p>
 * 核心机制：每个 hook 一个<b>专属维护者会话</b>（{@link TerminalSession}，workingDir=skill目录，
 * 模型取自执行者实例）。会话有记忆 → 维护者记得自己沉淀过什么，天然避免重复写入。
 * <p>
 * 三条触发路径：
 * <ul>
 *   <li>AFTER_CHAT：{@link #onTurnCompleted} 由 FeishuBotService 在轮次完成后调用（异步），
 *       先发判定 prompt（LLM 判断本轮对话是否值得沉淀），trigger=true 才执行完整维护</li>
 *   <li>CRON：{@link #cronTick()} 每分钟扫描，Spring {@link CronExpression} 判到期</li>
 *   <li>MANUAL：REST 手动触发（补跑/测试）</li>
 * </ul>
 * 执行流程：拉窗口对话 → skill 目录快照 → 维护 prompt（模型直接编辑 skill 文件）→
 * 快照对比得真实变更（hash 为准）+ 模型自报摘要 → 落 HookRun / SkillFileChange。
 */
@Service
public class SkillHookService {

    private static final Logger log = LoggerFactory.getLogger(SkillHookService.class);
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final int MSG_CAP = 500;          // 单条对话注入截断
    private static final int TOTAL_CAP = 30000;      // 对话总量截断
    private static final int SNAPSHOT_MAX_FILES = 200;
    private static final long SNAPSHOT_MAX_FILE_SIZE = 5 * 1024 * 1024;
    private static final Pattern FENCE = Pattern.compile("```(?:json)?\\s*([\\s\\S]*?)```");

    private final SkillHookRepository hookRepo;
    private final HookRunRepository runRepo;
    private final SkillFileChangeRepository changeRepo;
    private final ChatMessageRepository chatRepo;
    private final BotInstanceRepository instanceRepo;

    /** hookId → 专属维护者会话 */
    private final Map<Long, TerminalSession> sessions = new ConcurrentHashMap<>();
    /** hookId → 执行锁（防同一 hook 并发重入） */
    private final Map<Long, ReentrantLock> runLocks = new ConcurrentHashMap<>();
    /** hookId → cron 下次触发时间（内存态，重启重算） */
    private final Map<Long, LocalDateTime> nextFireAt = new ConcurrentHashMap<>();

    public SkillHookService(SkillHookRepository hookRepo, HookRunRepository runRepo,
                            SkillFileChangeRepository changeRepo, ChatMessageRepository chatRepo,
                            BotInstanceRepository instanceRepo) {
        this.hookRepo = hookRepo;
        this.runRepo = runRepo;
        this.changeRepo = changeRepo;
        this.chatRepo = chatRepo;
        this.instanceRepo = instanceRepo;
    }

    /** 旧 hook 回填默认维护意图（10-30-hook-maintenance-intent），不炸存量 */
    @jakarta.annotation.PostConstruct
    public void backfillIntents() {
        try {
            for (SkillHook h : hookRepo.findAll()) {
                if (h.getIntent().isBlank()) {
                    h.setIntent("通用经验沉淀（review建议/踩坑修复/最佳实践，未特别限定意图）");
                    hookRepo.save(h);
                }
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(SkillHookService.class)
                    .warn("[Hook] 意图回填失败（不影响启动）: {}", e.getMessage());
        }
    }

    // ===== 触发入口 ①：对话完成后（FeishuBotService 接线，异步不阻塞） =====

    public void onTurnCompleted(Long instanceId, String userText, String botReply) {
        Thread.ofVirtual().name("hook-judge-" + instanceId).start(() -> {
            try {
                for (SkillHook hook : hookRepo.findByEnabledTrue()) {
                    if (!"AFTER_CHAT".equals(hook.getTriggerType())) continue;
                    if (!parseIds(hook.getInstanceIds()).contains(instanceId)) continue;
                    judgeAndMaybeRun(hook, userText, botReply);
                }
            } catch (Exception e) {
                log.warn("[Hook] 对话后判定异常（不影响对话）: {}", e.getMessage());
            }
        });
    }

    private void judgeAndMaybeRun(SkillHook hook, String userText, String botReply) {
        ReentrantLock lock = runLocks.computeIfAbsent(hook.getId(), k -> new ReentrantLock());
        if (!lock.tryLock()) {
            log.info("[Hook] {} 正在执行中，跳过本次判定", hook.getName());
            return;
        }
        HookRun run = newRun(hook, "AFTER_CHAT");
        try {
            runRepo.save(run);
            String prompt = buildJudgePrompt(hook, userText, botReply);
            String out = maintainerSession(hook).sendMessage(prompt, new NoopListener());
            JsonObject verdict = extractJsonObject(out);
            boolean trigger = verdict != null && verdict.has("trigger") && verdict.get("trigger").getAsBoolean();
            String reason = verdict != null && verdict.has("reason") ? verdict.get("reason").getAsString() : "（判定结果无法解析: " + brief(out) + "）";

            if (!trigger) {
                run.setStatus("JUDGED_SKIP");
                run.setJudgeReason(trunc(reason, 1000));
                finish(run);
                log.info("[Hook] {} 判定无需触发: {}", hook.getName(), reason);
                return;
            }
            // 判定通过 → 同一把锁内直接执行完整维护
            log.info("[Hook] {} 判定触发: {}", hook.getName(), reason);
            run.setJudgeReason(trunc(reason, 1000));
            doMaintain(hook, run);
        } catch (Exception e) {
            log.error("[Hook] {} 判定/执行异常", hook.getName(), e);
            run.setStatus("FAILED");
            run.setJudgeReason(trunc("异常: " + e.getMessage(), 1000));
            finish(run);
        } finally {
            lock.unlock();
        }
    }

    // ===== 触发入口 ②：cron 定时 =====

    @Scheduled(fixedDelay = 60_000)
    public void cronTick() {
        LocalDateTime now = LocalDateTime.now();
        for (SkillHook hook : hookRepo.findByEnabledTrue()) {
            if (!"CRON".equals(hook.getTriggerType())) continue;
            try {
                CronExpression cron = CronExpression.parse(normalizeCron(hook.getCronExpr()));
                LocalDateTime next = nextFireAt.computeIfAbsent(hook.getId(), k -> cron.next(now));
                if (!now.isBefore(next)) {
                    nextFireAt.put(hook.getId(), cron.next(now));
                    log.info("[Hook] {} cron 到期触发", hook.getName());
                    runAsync(hook, "CRON");
                }
            } catch (Exception e) {
                log.warn("[Hook] {} cron 表达式无效: {} ({})", hook.getName(), hook.getCronExpr(), e.getMessage());
            }
        }
    }

    // ===== 触发入口 ③：手动 =====

    public void runManually(Long hookId) {
        SkillHook hook = hookRepo.findById(hookId)
                .orElseThrow(() -> new IllegalArgumentException("Hook 不存在: " + hookId));
        runAsync(hook, "MANUAL");
    }

    private void runAsync(SkillHook hook, String triggerType) {
        Thread.ofVirtual().name("hook-run-" + hook.getId()).start(() -> {
            ReentrantLock lock = runLocks.computeIfAbsent(hook.getId(), k -> new ReentrantLock());
            if (!lock.tryLock()) {
                log.info("[Hook] {} 已在执行中，忽略本次 {} 触发", hook.getName(), triggerType);
                return;
            }
            HookRun run = newRun(hook, triggerType);
            runRepo.save(run);
            try {
                doMaintain(hook, run);
            } catch (Exception e) {
                log.error("[Hook] {} 执行异常", hook.getName(), e);
                run.setStatus("FAILED");
                run.setSummary(trunc("执行异常: " + e.getMessage(), 4000));
                finish(run);
            } finally {
                lock.unlock();
            }
        });
    }

    // ===== 维护主流程 =====

    private void doMaintain(SkillHook hook, HookRun run) throws Exception {
        LocalDateTime from = run.getWindowFrom();
        LocalDateTime to = run.getWindowTo();
        List<Long> ids = parseIds(hook.getInstanceIds());
        List<ChatMessage> msgs = chatRepo.findByInstanceIdInAndCreatedAtBetweenOrderByCreatedAtAsc(ids, from, to);
        run.setMessageCount(msgs.size());

        if (msgs.isEmpty()) {
            run.setStatus("NO_MSG");
            run.setSummary("时间窗内无新对话，跳过维护");
            finish(run);
            touchLastRun(hook, to);
            return;
        }

        // skill 目录执行前快照
        Map<String, FileState> before = snapshot(hook.getSkillDir());

        String prompt = buildMaintainPrompt(hook, msgs, from, to);
        String out = maintainerSession(hook).sendMessage(prompt, new NoopListener());

        // 执行后快照对比 → 真实变更
        Map<String, FileState> after = snapshot(hook.getSkillDir());
        JsonObject result = extractJsonObject(out);
        Map<String, String> modelSummaries = modelChangeSummaries(result);

        List<SkillFileChange> changes = new ArrayList<>();
        for (Map.Entry<String, FileState> e : after.entrySet()) {
            FileState b = before.get(e.getKey());
            if (b != null && b.hash.equals(e.getValue().hash)) continue;   // 未变
            SkillFileChange c = new SkillFileChange();
            c.setRunId(run.getId());
            c.setHookId(hook.getId());
            c.setFilePath(e.getKey());
            c.setChangeSummary(trunc(modelSummaries.getOrDefault(e.getKey(),
                    b == null ? "新增文件" : "文件内容变化"), 2000));
            c.setSizeBefore(b != null ? b.size : 0L);
            c.setSizeAfter(e.getValue().size);
            changes.add(c);
        }
        // 模型自报改了但 hash 没变的（声称未落实）不落账；被删除的文件也记一笔
        for (Map.Entry<String, FileState> e : before.entrySet()) {
            if (!after.containsKey(e.getKey())) {
                SkillFileChange c = new SkillFileChange();
                c.setRunId(run.getId());
                c.setHookId(hook.getId());
                c.setFilePath(e.getKey());
                c.setChangeSummary("文件被删除");
                c.setSizeBefore(e.getValue().size);
                c.setSizeAfter(0L);
                changes.add(c);
            }
        }
        changeRepo.saveAll(changes);

        boolean isError = out != null && out.startsWith("❌");
        run.setStatus(isError ? "FAILED" : "SUCCESS");
        String lesson = result != null && result.has("lesson") ? result.get("lesson").getAsString() : "";
        boolean updated = result != null && result.has("updated") && result.get("updated").getAsBoolean();
        StringBuilder summary = new StringBuilder();
        if (!lesson.isBlank()) summary.append(lesson);
        summary.append(summary.length() > 0 ? " | " : "").append("实际变更 ").append(changes.size()).append(" 个文件");
        if (!updated && changes.isEmpty() && !isError) {
            summary.append("（模型判断本窗口无需沉淀）");
        }
        if (isError) summary.append(" | 维护会话返回错误: ").append(brief(out));
        run.setSummary(trunc(summary.toString(), 4000));
        finish(run);
        touchLastRun(hook, to);
        log.info("[Hook] {} 维护完成: {}条对话 → {}个文件变更, lesson={}",
                hook.getName(), msgs.size(), changes.size(), brief(lesson));
    }

    private void touchLastRun(SkillHook hook, LocalDateTime windowTo) {
        hook.setLastRunAt(windowTo);
        hookRepo.save(hook);
    }

    private HookRun newRun(SkillHook hook, String triggerType) {
        HookRun run = new HookRun();
        run.setHookId(hook.getId());
        run.setTriggerType(triggerType);
        run.setStatus("RUNNING");
        run.setStartedAt(LocalDateTime.now());
        run.setWindowFrom(hook.getLastRunAt() != null ? hook.getLastRunAt() : hook.getCreatedAt());
        run.setWindowTo(LocalDateTime.now());
        return run;
    }

    private void finish(HookRun run) {
        run.setFinishedAt(LocalDateTime.now());
        run.setDurationMs(java.time.Duration.between(run.getStartedAt(), run.getFinishedAt()).toMillis());
        runRepo.save(run);
    }

    // ===== 维护者会话 =====

    private TerminalSession maintainerSession(SkillHook hook) throws Exception {
        String model = instanceRepo.findById(hook.getMaintainerInstanceId() != null ? hook.getMaintainerInstanceId() : 0L)
                .map(BotInstance::getClaudeModel).orElse("");
        if (model == null) model = "";
        String dir = hook.getSkillDir();

        TerminalSession s = sessions.get(hook.getId());
        if (s != null && s.isAlive() && dir.equals(s.getWorkingDir()) && model.equals(nullToEmpty(s.getModel()))) {
            return s;
        }
        if (s != null) {
            s.destroy();
        }
        s = new TerminalSession("hook:" + hook.getId(), dir, model);
        s.start();
        sessions.put(hook.getId(), s);
        log.info("[Hook] 维护者会话已启动: hook={}, dir={}, model={}", hook.getName(), dir, model.isEmpty() ? "默认" : model);
        return s;
    }

    /** hook 配置变更/删除时调用：销毁专属会话（配置下次使用时按新值重建） */
    public void invalidateSession(Long hookId) {
        TerminalSession s = sessions.remove(hookId);
        if (s != null) s.destroy();
        nextFireAt.remove(hookId);
    }

    @Transactional
    public void deleteHook(Long hookId) {
        invalidateSession(hookId);
        runRepo.deleteByHookId(hookId);
        changeRepo.deleteByHookId(hookId);
        hookRepo.deleteById(hookId);
    }

    @PreDestroy
    public void destroyAll() {
        sessions.values().forEach(TerminalSession::destroy);
        sessions.clear();
    }

    // ===== Prompt =====

    String buildJudgePrompt(SkillHook hook, String userText, String botReply) {
        return "【Skill 自进化 · 触发判定】\n"
                + "你是 skill「" + hook.getName() + "」（目录: " + hook.getSkillDir() + "）的进化守门员。\n"
                + "本 skill 的维护意图：" + intentOf(hook) + "\n"
                + "判断下面这轮飞书对话是否包含值得沉淀进 skill 的经验——**以维护意图为准**：\n"
                + "- 值得：与本意图直接相关的 review 修改建议、踩坑与修复方法、可复用最佳实践、流程/规范改进\n"
                + "- 不值得：闲聊、一次性查询、与该 skill 主题或本意图无关的内容、单纯的代码生成请求\n"
                + "- 对话有价值但不属于本意图范围 → 也判 false（由对应意图的 hook 负责）\n"
                + "只输出一个 ```json 代码块：{\"trigger\":true或false,\"reason\":\"一句话理由\"}\n\n"
                + "【本轮对话】\n"
                + "用户: " + trunc(userText, 800) + "\n"
                + "Agent 回复(截断): " + trunc(botReply, 2000);
    }

    /** 意图展示（旧 hook 无意图时的默认口径与 backfillIntents 一致） */
    static String intentOf(SkillHook hook) {
        return hook.getIntent().isBlank() ? "通用经验沉淀（未特别限定意图）" : hook.getIntent();
    }

    String buildMaintainPrompt(SkillHook hook, List<ChatMessage> msgs, LocalDateTime from, LocalDateTime to) {
        Map<Long, String> names = new HashMap<>();
        instanceRepo.findAll().forEach(i -> names.put(i.getId(), i.getName()));

        StringBuilder convo = new StringBuilder();
        int total = 0;
        for (ChatMessage m : msgs) {
            String line = "[" + (m.getCreatedAt() != null ? m.getCreatedAt().format(FMT) : "?") + "]"
                    + "[" + names.getOrDefault(m.getInstanceId(), "实例" + m.getInstanceId()) + "]"
                    + "[" + ("user".equals(m.getRole()) ? "用户" : "Agent") + "] "
                    + trunc(m.getContent(), MSG_CAP).replaceAll("\\s+", " ") + "\n";
            if (total + line.length() > TOTAL_CAP) {
                convo.append("...(对话过多已截断，剩余留给下次窗口)\n");
                break;
            }
            convo.append(line);
            total += line.length();
        }

        return "【Skill 自进化 · 定期维护】\n"
                + "你是 skill「" + hook.getName() + "」的维护者，skill 目录（也是你当前工作目录）: " + hook.getSkillDir() + "\n"
                + "本 skill 的维护意图：" + intentOf(hook) + " —— **只提炼与该意图相关的经验**，"
                + "意图之外的内容（哪怕本身有价值）留给对应意图的 hook 处理，不要越界写入。\n"
                + "下面是 " + from.format(FMT) + " ~ " + to.format(FMT) + " 期间用户与开发实例的真实对话，"
                + "请从中提炼值得沉淀的经验并维护 skill 文件。\n\n"
                + "沉淀标准：符合维护意图、可泛化、会重复出现、skill 尚未覆盖；排除一次性偶然操作和与 skill 无关的内容。\n"
                + "落笔格式按意图选择（如 review 要点→检查清单条目；业务开发细节→坑与解法条目；提示词写法→模式与反例）。\n"
                + "步骤：\n"
                + "1. 先读 skill 目录现有文件（SKILL.md 等），理解结构与风格\n"
                + "2. 从对话中提炼候选经验（特别注意：review 修改建议、踩坑修复、'要记住/以后都要'类表述）\n"
                + "3. 值得沉淀的：直接编辑 skill 文件融入——追加或修订到合适章节，保持原有格式风格；你之前维护过这个 skill，已沉淀过的不要重复写\n"
                + "4. 完成后只输出一个 ```json 代码块：\n"
                + "{\"updated\":true或false,\"changes\":[{\"file\":\"相对路径\",\"summary\":\"改了什么、为什么\"}],\"lesson\":\"本次沉淀一句话总结\"}\n"
                + "没有值得沉淀的就 updated=false 并在 lesson 说明理由，不要为了改而改。\n\n"
                + "== 对话记录（" + msgs.size() + " 条，旧→新） ==\n" + convo;
    }

    // ===== 快照与 JSON 工具 =====

    private record FileState(long size, String hash) {}

    private Map<String, FileState> snapshot(String dir) {
        Map<String, FileState> out = new LinkedHashMap<>();
        Path root = Paths.get(dir);
        if (!Files.isDirectory(root)) return out;
        try (Stream<Path> walk = Files.walk(root, 4)) {
            walk.filter(Files::isRegularFile)
                    .filter(p -> !p.toString().contains(".git"))
                    .limit(SNAPSHOT_MAX_FILES)
                    .forEach(p -> {
                        try {
                            long size = Files.size(p);
                            if (size > SNAPSHOT_MAX_FILE_SIZE) {
                                out.put(root.relativize(p).toString().replace('\\', '/'), new FileState(size, "size-only"));
                                return;
                            }
                            MessageDigest md = MessageDigest.getInstance("SHA-256");
                            byte[] digest = md.digest(Files.readAllBytes(p));
                            StringBuilder hex = new StringBuilder();
                            for (byte b : digest) hex.append(String.format("%02x", b));
                            out.put(root.relativize(p).toString().replace('\\', '/'), new FileState(size, hex.toString()));
                        } catch (Exception ignored) {}
                    });
        } catch (Exception e) {
            log.warn("[Hook] skill 目录快照失败: {}", e.getMessage());
        }
        return out;
    }

    private Map<String, String> modelChangeSummaries(JsonObject result) {
        Map<String, String> out = new HashMap<>();
        if (result == null || !result.has("changes") || !result.get("changes").isJsonArray()) return out;
        JsonArray arr = result.getAsJsonArray("changes");
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String file = o.has("file") ? o.get("file").getAsString() : "";
            String sum = o.has("summary") ? o.get("summary").getAsString() : "";
            if (!file.isBlank()) out.put(normalizePath(file), sum);
        }
        return out;
    }

    private static String normalizePath(String p) {
        return p.replace('\\', '/').replaceAll("^\\./", "");
    }

    static JsonObject extractJsonObject(String text) {
        if (text == null || text.isBlank()) return null;
        Matcher m = FENCE.matcher(text);
        JsonObject fenced = null;
        while (m.find()) {
            JsonObject o = tryParseObject(m.group(1).trim());
            if (o != null) fenced = o;   // 取最后一个可解析的（最终答案通常在末尾）
        }
        if (fenced != null) return fenced;
        int start = text.indexOf('{');
        int attempts = 0;
        while (start >= 0 && attempts++ < 10) {
            int end = matchBrace(text, start);
            if (end > start) {
                JsonObject o = tryParseObject(text.substring(start, end + 1));
                if (o != null && o.size() > 0) return o;
            }
            start = text.indexOf('{', start + 1);
        }
        return null;
    }

    private static JsonObject tryParseObject(String s) {
        try {
            JsonElement el = JsonParser.parseString(s);
            if (el.isJsonObject()) return el.getAsJsonObject();
        } catch (Exception ignored) {}
        return null;
    }

    private static int matchBrace(String s, int start) {
        int depth = 0;
        boolean inStr = false, esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return i; }
        }
        return -1;
    }

    private static List<Long> parseIds(String csv) {
        List<Long> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String s : csv.split(",")) {
            try { out.add(Long.parseLong(s.trim())); } catch (NumberFormatException ignored) {}
        }
        return out;
    }

    private static String normalizeCron(String expr) {
        String e = expr != null ? expr.trim() : "";
        // Quartz 风格 "?" 日/周占位 Spring 也支持；5 位表达式补秒
        if (e.split("\\s+").length == 5) e = "0 " + e;
        return e;
    }

    private static String trunc(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…(截断)";
    }

    private static String brief(String s) {
        return trunc(s != null ? s.replaceAll("\\s+", " ") : "", 160);
    }

    private static String nullToEmpty(String s) { return s != null ? s : ""; }

    /** 维护/判定轮不需要 UI 流式，收结果即可 */
    private static class NoopListener implements TurnListener {
        public void onTurnStart() {}
        public void onThinkingDelta(String delta) {}
        public void onTextDelta(String delta) {}
        public void onToolStart(ToolStep step) {}
        public void onToolEnd(ToolStep step) {}
        public void onTurnComplete(TurnResult result) {}
        public void onTurnError(String message, boolean aborted) {}
    }
}
