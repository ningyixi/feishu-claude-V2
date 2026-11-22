package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.model.entity.EvalCase;
import com.lark.oapi.sample.ws.model.entity.EvalCaseResult;
import com.lark.oapi.sample.ws.model.entity.EvalTask;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.EvalCaseRepository;
import com.lark.oapi.sample.ws.repository.EvalCaseResultRepository;
import com.lark.oapi.sample.ws.repository.EvalTaskRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;

/**
 * 代码质量测评（11-21-supplier-eval-loop MVP-1）：任务/eval 条目 CRUD + 单条试跑 + 草稿生成导入。
 * <p>
 * 判定纪律（PRD 用户硬约束）：PASS/FAIL 全部由 {@link EvalRunner} 依进程退出码 + 输出断言裁决，
 * 本服务<b>不调用 ClaudeCodeService 的任何通道</b>（chat / chatInLane / getSession 一律不碰）；
 * 改代码循环属 MVP-2。草稿生成（{@link EvalDrafts}）只做确定性翻译，产物须经用户确认才入库。
 * <p>
 * 并发纪律：同一任务内串行执行（Semaphore tryAcquire，忙则拒绝）——不并发跑多个 dotnet，
 * 避免互相锁 obj/。每条执行都有超时强杀与输出落盘 + 截断。
 */
@Service
public class EvalLoopService {

    private static final Logger log = LoggerFactory.getLogger(EvalLoopService.class);

    /**
     * 默认基线命令提示（PRD Q1 未定项，<b>待真实验证</b>）：仅作前端 target 预填占位，
     * 服务不会自动执行它们。本轮纪律：不在 D:/dida/SupplierV2 里执行任何 dotnet 命令
     * （会往该仓库写 bin/obj，且可能有实例正在里面干活）。
     */
    public static final String BASELINE_BUILD_HINT = "dotnet build <解决方案或项目路径> --nologo";
    public static final String BASELINE_TEST_HINT = "dotnet test <测试项目路径> --filter \"FullyQualifiedName~<供应商命名空间>\" --nologo";

    /** requiresSandbox 的凭据探测：该环境变量非空 = 有沙箱凭据；无凭据的 sandbox eval 判 SKIPPED（不进分母） */
    static final String SANDBOX_CRED_ENV = "EVAL_SANDBOX_CREDENTIALS";

    private final EvalTaskRepository tasks;
    private final EvalCaseRepository cases;
    private final EvalCaseResultRepository results;
    private final BotInstanceRepository instances;

    /** 输出落盘目录（空 = 系统临时目录下 feishu-eval-logs） */
    private final String logDirConfig;

    /** 模板库文件覆盖入口（feishu.eval-templates；空 = EvalTemplates 默认解析顺序，AC16′ 改文件即生效） */
    private final String templatesFileConfig;

    /** 通用脚本目录覆盖入口（feishu.eval-script-dir；空 = 进程目录下 scripts/eval） */
    private final String scriptDirConfig;

    /** 每任务一把串行闸（Semaphore 而非 ReentrantLock：加锁在 HTTP 线程、释放在执行线程） */
    private final Map<Long, Semaphore> taskLocks = new ConcurrentHashMap<>();

    /** 沙箱凭据探测（单测可替换） */
    BooleanSupplier sandboxCreds = () -> {
        String v = System.getenv(SANDBOX_CRED_ENV);
        return v != null && !v.isBlank();
    };

    public EvalLoopService(EvalTaskRepository tasks, EvalCaseRepository cases, EvalCaseResultRepository results,
                           BotInstanceRepository instances,
                           @Value("${feishu.eval-log-dir:}") String logDirConfig,
                           @Value("${feishu.eval-templates:}") String templatesFileConfig,
                           @Value("${feishu.eval-script-dir:}") String scriptDirConfig) {
        this.tasks = tasks;
        this.cases = cases;
        this.results = results;
        this.instances = instances;
        this.logDirConfig = logDirConfig == null ? "" : logDirConfig.trim();
        this.templatesFileConfig = templatesFileConfig == null ? "" : templatesFileConfig.trim();
        this.scriptDirConfig = scriptDirConfig == null ? "" : scriptDirConfig.trim();
    }

    /** 后端重启会把执行中 eval 悬在 RUNNING（虚拟线程已死）：启动时归位成 ERROR，防页面永远转圈 */
    @PostConstruct
    void resetStaleRunning() {
        try {
            for (EvalCase c : cases.findByLastVerdict(EvalRunner.RUNNING)) {
                c.setLastVerdict(EvalRunner.ERROR);
                c.setLastReason("服务重启导致执行中断（未完成），请重新试跑");
                cases.save(c);
            }
        } catch (Exception e) {
            log.warn("[Eval] 启动归位 RUNNING eval 失败（忽略）: {}", e.getMessage());
        }
    }

    // ===== 任务 CRUD =====

    public EvalTask createTask(String name, String supplierCode, Long instanceId,
                               String reviewSkillPath, String createSkillPath) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("请填写任务名");
        EvalTask t = new EvalTask();
        t.setName(name.trim());
        t.setSupplierCode(supplierCode == null ? "" : supplierCode.trim());
        t.setInstanceId(instanceId);
        t.setReviewSkillPath(reviewSkillPath == null ? "" : reviewSkillPath.trim());
        t.setCreateSkillPath(createSkillPath == null ? "" : createSkillPath.trim());
        return tasks.save(t);
    }

    public EvalTask requireTask(Long id) {
        return tasks.findById(id).orElseThrow(() -> new IllegalArgumentException("测评任务不存在: " + id));
    }

    /**
     * 任务设置更新（patch 语义：null = 保持原值）。
     * 例外是 instanceId——前端总是显式携带该键，{@code setInstance=true} 时 null 表示解绑
     * （解绑后 eval 在后端进程目录执行）。
     * contextJson（MVP-1.5）：任务级上下文变量，null = 保持原值；非 null 必须是扁平 JSON 对象。
     */
    public void updateTask(Long id, String name, String supplierCode, boolean setInstance, Long instanceId,
                           String reviewSkillPath, String createSkillPath, String contextJson) {
        EvalTask t = requireTask(id);
        if (name != null && !name.isBlank()) t.setName(name.trim());
        else if (name != null) throw new IllegalArgumentException("任务名不能为空");
        if (supplierCode != null) t.setSupplierCode(supplierCode.trim());
        if (setInstance) t.setInstanceId(instanceId);
        if (reviewSkillPath != null) t.setReviewSkillPath(reviewSkillPath.trim());
        if (createSkillPath != null) t.setCreateSkillPath(createSkillPath.trim());
        if (contextJson != null) t.setContextJson(validateContextJson(contextJson));
        tasks.save(t);
    }

    /** 上下文变量必须是"扁平 JSON 对象"（键 → 字符串/数字/布尔），坏格式 fail-loud 拒存 */
    static String validateContextJson(String contextJson) {
        String s = contextJson == null ? "" : contextJson.trim();
        if (s.isEmpty()) return "";
        JsonObject o;
        try {
            o = com.google.gson.JsonParser.parseString(s).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalArgumentException("上下文变量必须是 JSON 对象（如 {\"configJsonPath\":\"D:/x.json\"}）: " + e.getMessage());
        }
        for (Map.Entry<String, com.google.gson.JsonElement> e : o.entrySet()) {
            com.google.gson.JsonElement v = e.getValue();
            if (v != null && !v.isJsonPrimitive() && !v.isJsonNull()) {
                throw new IllegalArgumentException("上下文变量 " + e.getKey() + " 的值必须是字符串/数字，不能嵌套对象或数组");
            }
        }
        return o.toString();
    }

    @Transactional
    public void deleteTask(Long id) {
        EvalTask t = requireTask(id);
        Semaphore lock = taskLocks.get(t.getId());
        if (lock != null && lock.availablePermits() == 0) {
            throw new IllegalStateException("该任务有 eval 正在执行，等它结束再删除");
        }
        results.deleteByTaskId(t.getId());
        cases.deleteByTaskId(t.getId());
        tasks.delete(t);
        taskLocks.remove(t.getId());
    }

    public JsonArray listJson() {
        JsonArray arr = new JsonArray();
        for (EvalTask t : tasks.findAllByOrderByIdDesc()) {
            List<EvalCase> cs = cases.findByTaskIdOrderBySortOrderAscIdAsc(t.getId());
            JsonObject o = taskJson(t);
            Stats s = computeStats(cs);
            o.add("stats", statsJson(s));
            o.addProperty("caseCount", cs.size());
            arr.add(o);
        }
        return arr;
    }

    public JsonObject detail(Long taskId) {
        EvalTask t = requireTask(taskId);
        List<EvalCase> cs = cases.findByTaskIdOrderBySortOrderAscIdAsc(taskId);
        JsonObject o = taskJson(t);
        String instName = "";
        if (t.getInstanceId() != null) {
            instName = instances.findById(t.getInstanceId()).map(BotInstance::getName).orElse("#" + t.getInstanceId());
        }
        o.addProperty("instanceName", instName);
        o.add("stats", statsJson(computeStats(cs)));
        JsonArray arr = new JsonArray();
        for (EvalCase c : cs) arr.add(caseJson(c));
        o.add("cases", arr);
        return o;
    }

    private JsonObject taskJson(EvalTask t) {
        JsonObject o = new JsonObject();
        o.addProperty("id", t.getId());
        o.addProperty("name", t.getName());
        o.addProperty("supplierCode", t.getSupplierCode());
        if (t.getInstanceId() != null) o.addProperty("instanceId", t.getInstanceId());
        else o.add("instanceId", com.google.gson.JsonNull.INSTANCE);
        o.addProperty("reviewSkillPath", t.getReviewSkillPath());
        o.addProperty("createSkillPath", t.getCreateSkillPath());
        o.addProperty("contextJson", t.getContextJson());
        o.addProperty("createdAt", str(t.getCreatedAt()));
        o.addProperty("updatedAt", str(t.getUpdatedAt()));
        return o;
    }

    // ===== eval 条目 CRUD =====

    public EvalCase createCase(Long taskId, String title, String kind, String target, String expect,
                               String notes, Integer timeoutSec, boolean requiresSandbox, boolean enabled) {
        EvalTask t = requireTask(taskId);
        String k = validateKind(kind);
        EvalCase c = new EvalCase();
        c.setTaskId(t.getId());
        applyCaseFields(c, title, k, target, expect, notes, timeoutSec, requiresSandbox, enabled);
        List<EvalCase> existing = cases.findByTaskIdOrderBySortOrderAscIdAsc(t.getId());
        c.setSortOrder(existing.stream().mapToInt(EvalCase::getSortOrder).max().orElse(0) + 1);
        return cases.save(c);
    }

    public EvalCase updateCase(Long taskId, Long caseId, String title, String kind, String target, String expect,
                               String notes, Integer timeoutSec, boolean requiresSandbox, boolean enabled) {
        EvalCase c = requireCase(taskId, caseId);
        if (EvalRunner.RUNNING.equals(c.getLastVerdict())) {
            throw new IllegalStateException("该 eval 正在执行中，等本轮结束再编辑");
        }
        applyCaseFields(c, title, validateKind(kind), target, expect, notes, timeoutSec, requiresSandbox, enabled);
        return cases.save(c);
    }

    @Transactional
    public void deleteCase(Long taskId, Long caseId) {
        EvalCase c = requireCase(taskId, caseId);
        if (EvalRunner.RUNNING.equals(c.getLastVerdict())) {
            throw new IllegalStateException("该 eval 正在执行中，等本轮结束再删除");
        }
        results.deleteByCaseId(c.getId());
        cases.delete(c);
    }

    public EvalCase requireCase(Long taskId, Long caseId) {
        EvalCase c = cases.findById(caseId)
                .orElseThrow(() -> new IllegalArgumentException("eval 条目不存在: " + caseId));
        if (!c.getTaskId().equals(taskId)) throw new IllegalArgumentException("eval 条目不属于该任务");
        return c;
    }

    private void applyCaseFields(EvalCase c, String title, String kind, String target, String expect,
                                 String notes, Integer timeoutSec, boolean requiresSandbox, boolean enabled) {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("请填写 eval 标题");
        c.setTitle(title.trim());
        c.setKind(kind);
        c.setTarget(target == null ? "" : target);
        c.setExpect(expect == null ? "" : expect);
        // expect 语法预检：配置坏了立刻告诉用户（判定时也会 fail-loud 判 FAIL，双保险）
        if (!c.getExpect().isBlank()) {
            List<String> errs = EvalRunner.parseExpect(c.getExpect()).errors();
            if (!errs.isEmpty()) throw new IllegalArgumentException("expect 语法有误: " + String.join("; ", errs));
        }
        c.setNotes(notes == null ? "" : notes);
        c.setTimeoutSec(timeoutSec == null || timeoutSec <= 0 ? EvalRunner.defaultTimeoutSec(kind) : timeoutSec);
        if (c.getTimeoutSec() > 86_400) throw new IllegalArgumentException("超时不能超过 86400 秒");
        c.setRequiresSandbox(requiresSandbox);
        c.setEnabled(enabled);
    }

    static String validateKind(String kind) {
        if (kind == null || !EvalRunner.KINDS.contains(kind)) {
            throw new IllegalArgumentException("未知 eval kind: " + kind + "（合法值 build/test/script/http/static）");
        }
        return kind;
    }

    // ===== 单条试跑（异步执行 + 同任务串行） =====

    /**
     * 启动一条 eval 的试跑：立即返回（页面轮询 detail 拿结果）。
     * 未落地（target 空）拒绝执行；同任务已有执行中条目拒绝（串行闸）。
     * requiresSandbox 且无凭据 → 同步记 SKIPPED（不起进程）。
     *
     * @throws IllegalArgumentException 未落地/不存在
     * @throws IllegalStateException    正在执行中（本条或同任务其他条）
     */
    public void trial(Long taskId, Long caseId) {
        EvalTask task = requireTask(taskId);
        EvalCase c = requireCase(taskId, caseId);
        if (!c.isEnabled()) throw new IllegalStateException("该 eval 已停用，先启用再试跑");
        if (!EvalRunner.isLanded(c.getTarget())) {
            throw new IllegalArgumentException("该 eval 还没填可执行 target（未落地）：先补出命令行再试跑，未落地条目不计入通过率");
        }
        if (EvalRunner.RUNNING.equals(c.getLastVerdict())) {
            throw new IllegalStateException("这条 eval 正在执行中，等本轮结束");
        }
        Semaphore lock = taskLocks.computeIfAbsent(taskId, k -> new Semaphore(1));
        if (!lock.tryAcquire()) {
            throw new IllegalStateException("该任务已有一条 eval 正在执行（同任务串行，避免多个构建互相锁 obj/），等它结束再试跑");
        }
        boolean handedOff = false;
        try {
            if (EvalRunner.shouldSkip(c.isRequiresSandbox(), sandboxCreds.getAsBoolean())) {
                recordResult(task, c, EvalRunner.SKIPPED, null, 0,
                        "需要沙箱凭据（环境变量 " + SANDBOX_CRED_ENV + " 未配置），本次跳过；不计入通过率", "", "");
                return;
            }
            c.setLastVerdict(EvalRunner.RUNNING);
            c.setLastReason("");
            cases.save(c);
            File workDir = resolveWorkDir(task);
            final Long cid = c.getId();
            Thread.ofVirtual().name("eval-trial-" + cid).start(() -> {
                try {
                    runOne(task.getId(), cid, workDir);
                } catch (Exception e) {
                    log.error("[Eval] 试跑异常 task={} case={}", task.getId(), cid, e);
                    try {
                        EvalCase cc = cases.findById(cid).orElse(null);
                        if (cc != null) {
                            recordResult(task, cc, EvalRunner.ERROR, null, 0, "执行异常: " + e.getMessage(), "", "");
                        }
                    } catch (Exception ignored) {}
                } finally {
                    lock.release();
                }
            });
            handedOff = true;
        } finally {
            if (!handedOff) lock.release();
        }
    }

    /** 真正跑一条：命令构造 → 进程执行（超时强杀）→ 机器判定 → 输出落盘 + 入库 */
    void runOne(Long taskId, Long caseId, File workDir) throws Exception {
        EvalTask task = requireTask(taskId);
        EvalCase c = requireCase(taskId, caseId);
        List<String> cmd = EvalRunner.buildCommand(c.getKind(), c.getTarget(), EvalRunner.isWindows());
        EvalRunner.Execution exec = EvalRunner.execute(cmd, workDir, c.getTimeoutSec());

        String verdict;
        String reason;
        if (!exec.finished()) {
            // 超时强杀（含子进程树）；命令不存在等启动失败走 IOException → 调用方记 ERROR
            verdict = EvalRunner.TIMEOUT;
            reason = "超过 " + c.getTimeoutSec() + " 秒未结束，已强制终止（含子进程树）";
        } else {
            EvalRunner.Judgment j = EvalRunner.judge(exec.exitCode(), exec.output(), c.getExpect());
            verdict = j.verdict();
            reason = j.reason();
        }
        String outputFile = writeOutputFile(taskId, caseId, exec.output());
        recordResult(task, c, verdict, exec.exitCode(), exec.elapsedMs(), reason,
                EvalRunner.summarize(exec.output()), outputFile);
    }

    private void recordResult(EvalTask task, EvalCase c, String verdict, Integer exitCode, long elapsedMs,
                              String reason, String outputSummary, String outputFile) {
        EvalCaseResult r = new EvalCaseResult();
        r.setTaskId(task.getId());
        r.setCaseId(c.getId());
        r.setTriggerMode("TRIAL");
        r.setVerdict(verdict);
        r.setExitCode(exitCode);
        r.setElapsedMs(elapsedMs);
        r.setReason(reason);
        r.setOutputSummary(outputSummary);
        r.setOutputFile(outputFile);
        results.save(r);

        c.setLastVerdict(verdict);
        c.setLastExitCode(exitCode);
        c.setLastElapsedMs(elapsedMs);
        c.setLastRunAt(LocalDateTime.now());
        c.setLastReason(reason);
        c.setLastOutputSummary(outputSummary);
        c.setLastOutputFile(outputFile);
        cases.save(c);
        log.info("[Eval] 试跑完成 task={} case={}「{}」 verdict={} exit={} elapsed={}ms",
                task.getId(), c.getId(), c.getTitle(), verdict, exitCode, elapsedMs);
    }

    /** 执行工作目录：绑定实例的 claudeWorkingDir → shellWorkingDir → null（继承后端进程目录） */
    File resolveWorkDir(EvalTask task) {
        if (task.getInstanceId() == null) return null;
        return instances.findById(task.getInstanceId())
                .map(inst -> {
                    String dir = !inst.getClaudeWorkingDir().isBlank() ? inst.getClaudeWorkingDir()
                            : inst.getShellWorkingDir();
                    return dir == null || dir.isBlank() ? null : new File(dir);
                })
                .orElse(null);
    }

    /** 完整（截断上限内）输出落盘：&lt;logDir&gt;/task-&lt;id&gt;/case-&lt;id&gt;-&lt;epochMilli&gt;.log */
    String writeOutputFile(Long taskId, Long caseId, String output) {
        try {
            Path dir = logDir().resolve("task-" + taskId);
            Files.createDirectories(dir);
            Path f = dir.resolve("case-" + caseId + "-" + System.currentTimeMillis() + ".log");
            Files.writeString(f, output == null ? "" : output, StandardCharsets.UTF_8);
            return f.toAbsolutePath().toString();
        } catch (Exception e) {
            log.warn("[Eval] 输出落盘失败（不影响判定，摘要仍入库）: task={} case={} {}", taskId, caseId, e.getMessage());
            return "";
        }
    }

    Path logDir() {
        if (!logDirConfig.isBlank()) return Path.of(logDirConfig);
        return Path.of(System.getProperty("java.io.tmpdir"), "feishu-eval-logs");
    }

    public JsonArray caseResults(Long taskId, Long caseId) {
        requireCase(taskId, caseId);
        JsonArray arr = new JsonArray();
        for (EvalCaseResult r : results.findTop20ByCaseIdOrderByIdDesc(caseId)) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.getId());
            o.addProperty("verdict", r.getVerdict());
            if (r.getExitCode() != null) o.addProperty("exitCode", r.getExitCode());
            else o.add("exitCode", com.google.gson.JsonNull.INSTANCE);
            o.addProperty("elapsedMs", r.getElapsedMs());
            o.addProperty("reason", r.getReason());
            o.addProperty("outputSummary", r.getOutputSummary());
            o.addProperty("outputFile", r.getOutputFile());
            o.addProperty("triggerMode", r.getTriggerMode());
            o.addProperty("ranAt", str(r.getRanAt()));
            arr.add(o);
        }
        return arr;
    }

    // ===== 通过率统计（分母诚实：未落地/跳过/未跑 一律不进分母） =====

    /**
     * executed（分母）= enabled 且已落地 且 最近一次执行出了客观结果（PASS/FAIL/TIMEOUT）。
     * notLanded（target 空）、skipped（缺沙箱凭据）、notRun（落地了还没跑过）单列，绝不混进分母。
     */
    public record Stats(int total, int enabled, int landed, int notLanded, int executed,
                        int passed, int failed, int timeout, int error, int skipped,
                        int running, int notRun) {}

    static Stats computeStats(List<EvalCase> all) {
        int total = all.size(), enabled = 0, landed = 0, notLanded = 0, executed = 0, passed = 0,
                failed = 0, timeout = 0, error = 0, skipped = 0, running = 0, notRun = 0;
        for (EvalCase c : all) {
            if (!c.isEnabled()) continue;
            enabled++;
            if (!EvalRunner.isLanded(c.getTarget())) { notLanded++; continue; }
            landed++;
            String v = c.getLastVerdict();
            if (v == null) { notRun++; continue; }
            switch (v) {
                case EvalRunner.PASS -> { executed++; passed++; }
                case EvalRunner.FAIL -> { executed++; failed++; }
                case EvalRunner.TIMEOUT -> { executed++; timeout++; }
                case EvalRunner.SKIPPED -> skipped++;      // 单列，不进分母
                case EvalRunner.ERROR -> error++;           // 环境/启动问题，单列，不进分母（跑都没跑起来）
                case EvalRunner.RUNNING -> running++;
                default -> notRun++;
            }
        }
        return new Stats(total, enabled, landed, notLanded, executed, passed, failed, timeout, error,
                skipped, running, notRun);
    }

    static JsonObject statsJson(Stats s) {
        JsonObject o = new JsonObject();
        o.addProperty("total", s.total());
        o.addProperty("enabled", s.enabled());
        o.addProperty("landed", s.landed());
        o.addProperty("notLanded", s.notLanded());
        o.addProperty("executed", s.executed());
        o.addProperty("passed", s.passed());
        o.addProperty("failed", s.failed());
        o.addProperty("timeout", s.timeout());
        o.addProperty("error", s.error());
        o.addProperty("skipped", s.skipped());
        o.addProperty("running", s.running());
        o.addProperty("notRun", s.notRun());
        return o;
    }

    // ===== 草稿生成 / 导入 =====

    /** 「从 review skill 生成初版 eval」：只读 skill 目录、只做确定性翻译，产出草稿（不落库、不判分） */
    public JsonArray generateDrafts(Long taskId) throws java.io.IOException {
        EvalTask t = requireTask(taskId);
        if (t.getReviewSkillPath().isBlank()) {
            throw new IllegalArgumentException("该任务还没填 review-supplier skill 路径，先在任务设置里补上");
        }
        List<EvalDrafts.Draft> drafts = EvalDrafts.fromReviewSkillDir(Path.of(t.getReviewSkillPath()));
        JsonArray arr = new JsonArray();
        for (EvalDrafts.Draft d : drafts) arr.add(draftJson(d));
        return arr;
    }

    static JsonObject draftJson(EvalDrafts.Draft d) {
        JsonObject o = new JsonObject();
        o.addProperty("title", d.title());
        o.addProperty("kind", d.kind());
        o.addProperty("target", d.target());
        o.addProperty("expect", d.expect());
        o.addProperty("timeoutSec", d.timeoutSec());
        o.addProperty("requiresSandbox", d.requiresSandbox());
        o.addProperty("enabled", d.enabled());
        o.addProperty("notes", d.notes());
        o.addProperty("source", d.source());
        o.addProperty("selected", d.selected());
        return o;
    }

    /** 用户确认后导入草稿：逐条按普通 case 落库（target 空 = 未落地，等用户补可执行形式） */
    @Transactional
    public int importDrafts(Long taskId, List<EvalDrafts.Draft> drafts) {
        EvalTask t = requireTask(taskId);
        List<EvalCase> existing = cases.findByTaskIdOrderBySortOrderAscIdAsc(taskId);
        int sort = existing.stream().mapToInt(EvalCase::getSortOrder).max().orElse(0);
        int n = 0;
        for (EvalDrafts.Draft d : drafts) {
            EvalCase c = new EvalCase();
            c.setTaskId(t.getId());
            c.setTitle(d.title());
            c.setKind(EvalRunner.KINDS.contains(d.kind()) ? d.kind() : "script");
            c.setTarget(d.target() == null ? "" : d.target());
            c.setExpect(d.expect() == null ? "" : d.expect());
            c.setNotes((d.notes() == null ? "" : d.notes())
                    + (d.source() == null || d.source().isBlank() ? "" : "\n（来源：" + d.source() + "）"));
            c.setTimeoutSec(d.timeoutSec() > 0 ? d.timeoutSec() : EvalRunner.defaultTimeoutSec(c.getKind()));
            c.setRequiresSandbox(d.requiresSandbox());
            c.setEnabled(d.enabled());
            c.setSortOrder(++sort);
            cases.save(c);
            n++;
        }
        return n;
    }

    // ===== 内置通用模板（MVP-1.5）：列表（含缺变量提示）+ 导入 =====

    /**
     * 模板库列表（供「导入通用模板」面板）：模板清单实时读文件（{@link EvalTemplates#load}，
     * Java 不硬编码，改文件即生效），并按该任务当前上下文变量算出每条的 missingVariables，
     * 页面直接告诉用户缺哪个变量。
     */
    public JsonObject templatesJson(Long taskId) throws java.io.IOException {
        EvalTask t = requireTask(taskId);
        EvalTemplates.Library lib = EvalTemplates.load(templatesFileConfig);
        Map<String, String> vars = templateVars(t);
        JsonObject o = new JsonObject();
        o.addProperty("scriptDir", vars.get("evalScriptDir"));
        o.addProperty("httpEvidenceWarning", lib.httpEvidenceWarning());
        JsonArray vs = new JsonArray();
        for (EvalTemplates.VariableSpec v : lib.variables()) {
            JsonObject vo = new JsonObject();
            vo.addProperty("name", v.name());
            vo.addProperty("label", v.label());
            vo.addProperty("placeholder", v.placeholder());
            vo.addProperty("value", vars.getOrDefault(v.name(), ""));
            vs.add(vo);
        }
        o.add("variables", vs);
        JsonArray ts = new JsonArray();
        for (EvalTemplates.Template tpl : lib.templates()) {
            JsonObject to = new JsonObject();
            to.addProperty("key", tpl.key());
            to.addProperty("group", tpl.group());
            to.addProperty("title", tpl.title());
            to.addProperty("kind", tpl.kind());
            to.addProperty("timeoutSec", tpl.timeoutSec() > 0 ? tpl.timeoutSec() : EvalRunner.defaultTimeoutSec(tpl.kind()));
            to.addProperty("requiresSandbox", tpl.requiresSandbox());
            to.addProperty("verified", tpl.verified());
            to.addProperty("description", tpl.description());
            to.addProperty("targetTemplate", tpl.targetTemplate());
            to.addProperty("expectTemplate", tpl.expectTemplate());
            JsonArray nv = new JsonArray();
            tpl.needsVariables().forEach(nv::add);
            to.add("needsVariables", nv);
            JsonArray mv = new JsonArray();
            EvalTemplates.expand(tpl.targetTemplate(), vars).missingVariables().forEach(mv::add);
            to.add("missingVariables", mv);
            ts.add(to);
        }
        o.add("templates", ts);
        return o;
    }

    /**
     * 导入勾选的模板 → 批量建 eval（AC13′/AC14′）：
     * 变量填齐 → 落地为可执行 case（target/expect 已展开）；变量缺失 → target 留空 =
     * 「未落地」（{@link EvalRunner#isLanded} 同一口径，不进通过率分母），notes 写清缺哪个变量。
     * 导入不做任何判定——verdict 仍只能来自之后的真实试跑。
     *
     * @return {imported, landed, notLanded}
     */
    @Transactional
    public JsonObject importTemplates(Long taskId, List<String> keys) throws java.io.IOException {
        EvalTask t = requireTask(taskId);
        if (keys == null || keys.isEmpty()) throw new IllegalArgumentException("没有勾选任何模板");
        EvalTemplates.Library lib = EvalTemplates.load(templatesFileConfig);
        Map<String, String> vars = templateVars(t);
        List<EvalCase> existing = cases.findByTaskIdOrderBySortOrderAscIdAsc(taskId);
        int sort = existing.stream().mapToInt(EvalCase::getSortOrder).max().orElse(0);
        int landed = 0, notLanded = 0;
        java.util.LinkedHashSet<String> unique = new java.util.LinkedHashSet<>(keys);
        for (String key : unique) {
            EvalTemplates.Template tpl = lib.templates().stream()
                    .filter(x -> x.key().equals(key)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("未知的模板 key: " + key + "（模板库以文件为准，可能已被修改）"));
            EvalTemplates.Expansion target = EvalTemplates.expand(tpl.targetTemplate(), vars);
            EvalTemplates.Expansion expect = EvalTemplates.expand(tpl.expectTemplate(), vars);
            EvalCase c = new EvalCase();
            c.setTaskId(t.getId());
            c.setTitle(tpl.title());
            c.setKind(EvalRunner.KINDS.contains(tpl.kind()) ? tpl.kind() : "script");
            boolean missing = !target.missingVariables().isEmpty();
            if (missing) {
                c.setTarget("");                       // 未落地：不可执行、不进分母（复用 isLanded 口径）
                c.setExpect(expect.missingVariables().isEmpty() ? expect.text() : "");
                notLanded++;
            } else {
                c.setTarget(target.text());
                c.setExpect(expect.text());
                landed++;
            }
            StringBuilder notes = new StringBuilder(tpl.description() == null ? "" : tpl.description());
            if (!tpl.verified()) notes.append("\n【该模板未真实验证过，首跑结果出来前请勿当作可信基线】");
            if (missing) {
                notes.append("\n缺少上下文变量：").append(String.join("、", target.missingVariables()))
                        .append("——在任务设置「上下文变量」里补齐后重新导入，或手动编辑补出可执行 target。")
                        .append("\n模板 target：").append(tpl.targetTemplate());
            }
            notes.append("\n（来源：内置通用模板 ").append(tpl.key()).append("）");
            c.setNotes(notes.toString());
            c.setTimeoutSec(tpl.timeoutSec() > 0 ? tpl.timeoutSec() : EvalRunner.defaultTimeoutSec(c.getKind()));
            c.setRequiresSandbox(tpl.requiresSandbox());
            c.setEnabled(true);
            c.setSortOrder(++sort);
            // expect 语法预检（落地条）：模板文件被改坏 → fail-loud，绝不入库一个判不了的断言
            if (!c.getExpect().isBlank()) {
                List<String> errs = EvalRunner.parseExpect(c.getExpect()).errors();
                if (!errs.isEmpty()) {
                    throw new IllegalStateException("模板 " + tpl.key() + " 的 expect 展开后语法有误（请修模板文件）: "
                            + String.join("; ", errs));
                }
            }
            cases.save(c);
        }
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("imported", landed + notLanded);
        o.addProperty("landed", landed);
        o.addProperty("notLanded", notLanded);
        return o;
    }

    /** 模板展开用变量表 = 任务 context_json + 系统变量 evalScriptDir（自动解析，不需要用户填） */
    Map<String, String> templateVars(EvalTask t) {
        Map<String, String> vars = EvalTemplates.contextVars(t.getContextJson());
        vars.put("evalScriptDir", EvalTemplates.resolveScriptDir(scriptDirConfig));
        return vars;
    }

    // ===== 小工具 =====

    private static String str(LocalDateTime t) {
        return t == null ? "" : t.toString();
    }

    private JsonObject caseJson(EvalCase c) {
        JsonObject o = new JsonObject();
        o.addProperty("id", c.getId());
        o.addProperty("title", c.getTitle());
        o.addProperty("kind", c.getKind());
        o.addProperty("target", c.getTarget());
        o.addProperty("expect", c.getExpect());
        o.addProperty("notes", c.getNotes());
        o.addProperty("timeoutSec", c.getTimeoutSec());
        o.addProperty("requiresSandbox", c.isRequiresSandbox());
        o.addProperty("enabled", c.isEnabled());
        o.addProperty("sortOrder", c.getSortOrder());
        o.addProperty("landed", EvalRunner.isLanded(c.getTarget()));
        if (c.getLastVerdict() != null) o.addProperty("lastVerdict", c.getLastVerdict());
        else o.add("lastVerdict", com.google.gson.JsonNull.INSTANCE);
        if (c.getLastExitCode() != null) o.addProperty("lastExitCode", c.getLastExitCode());
        else o.add("lastExitCode", com.google.gson.JsonNull.INSTANCE);
        if (c.getLastElapsedMs() != null) o.addProperty("lastElapsedMs", c.getLastElapsedMs());
        o.addProperty("lastRunAt", str(c.getLastRunAt()));
        o.addProperty("lastReason", c.getLastReason());
        o.addProperty("lastOutputSummary", c.getLastOutputSummary());
        o.addProperty("lastOutputFile", c.getLastOutputFile());
        o.addProperty("updatedAt", str(c.getUpdatedAt()));
        return o;
    }
}
