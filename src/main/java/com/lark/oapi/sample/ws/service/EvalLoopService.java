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
import java.util.Set;
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
            // 结果行同样归位（MVP-1.6：RUNNING 行先落库拿 resultId，重启会把它们悬住）
            for (EvalCaseResult r : results.findByVerdict(EvalRunner.RUNNING)) {
                r.setVerdict(EvalRunner.ERROR);
                r.setReason("服务重启导致执行中断（未完成），请重新试跑");
                results.save(r);
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
     * 启动一条 eval 的试跑：立即返回（含<b>本次新建的 resultId</b>——前端与后续 loop 一律按它
     * 轮询 {@link #resultJson} 单次结果，<b>绝不靠「读列表第一条」</b>：那样会把上一轮的旧结果
     * 当成本轮的，旧红当新红、旧输出当新输出，MVP-1.6 实测缺陷）。
     * <p>
     * 异步执行时结果行以 RUNNING 先行落库（id 即返回的 resultId），执行完在<b>同一行</b>上更新终态；
     * 同步终态（只读安全闸拦截 FAIL / 缺沙箱凭据 SKIPPED）也返回该行 id。
     * 未落地（target 空）拒绝执行；同任务已有执行中条目拒绝（串行闸）。
     *
     * @return {ok, taskId, caseId, resultId, verdict}（verdict=RUNNING 表示异步执行中）
     * @throws IllegalArgumentException 未落地/不存在
     * @throws IllegalStateException    正在执行中（本条或同任务其他条）
     */
    public JsonObject trial(Long taskId, Long caseId) {
        EvalTask task = requireTask(taskId);
        EvalCase c = requireCase(taskId, caseId);
        if (!c.isEnabled()) throw new IllegalStateException("该 eval 已停用，先启用再试跑");
        if (!EvalRunner.isLanded(c.getTarget())) {
            throw new IllegalArgumentException("该 eval 还没填可执行 target（未落地）：先补出命令行再试跑，未落地条目不计入通过率");
        }
        // 只读安全闸（MVP-1.6 追加）：http 类 target 指向写操作端点 → 同步记 FAIL，进程根本不起。
        // 不是 SKIPPED、不是抛异常——静默跳过的安全闸等于没有闸，必须留下诚实的 FAIL 记录。
        EvalReadOnlyGuard.GuardResult guard = EvalReadOnlyGuard.check(c.getKind(), c.getTarget());
        if (guard.blocked()) {
            EvalCaseResult blocked = recordResult(task, c, EvalRunner.FAIL, null, 0,
                    "eval 禁止写操作端点（只读安全闸，进程未启动）: " + guard.matched(), "", "");
            return trialJson(taskId, caseId, blocked.getId(), EvalRunner.FAIL);
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
                EvalCaseResult skipped = recordResult(task, c, EvalRunner.SKIPPED, null, 0,
                        "需要沙箱凭据（环境变量 " + SANDBOX_CRED_ENV + " 未配置），本次跳过；不计入通过率", "", "");
                return trialJson(taskId, caseId, skipped.getId(), EvalRunner.SKIPPED);
            }
            c.setLastVerdict(EvalRunner.RUNNING);
            c.setLastReason("");
            cases.save(c);
            // 先落一行 RUNNING（拿到本次 resultId 同步返回）；执行线程结束时在同一行更新终态。
            // 「从未跑过」（没有任何结果行）与「正在跑」（存在 RUNNING 行）因此在执行记录里天然可分。
            final EvalCaseResult row = new EvalCaseResult();
            row.setTaskId(task.getId());
            row.setCaseId(c.getId());
            row.setTriggerMode("TRIAL");
            row.setVerdict(EvalRunner.RUNNING);
            row.setExitCode(null);
            row.setElapsedMs(0);
            row.setReason("");
            row.setOutputSummary("");
            row.setOutputFile("");
            results.save(row);
            File workDir = resolveWorkDir(task);
            final Long cid = c.getId();
            Thread.ofVirtual().name("eval-trial-" + cid).start(() -> {
                try {
                    runOne(task.getId(), cid, row, workDir);
                } catch (Exception e) {
                    log.error("[Eval] 试跑异常 task={} case={} result={}", task.getId(), cid, row.getId(), e);
                    try {
                        EvalCase cc = cases.findById(cid).orElse(null);
                        if (cc != null) {
                            finishResult(row, task, cc, EvalRunner.ERROR, null, 0,
                                    "执行异常: " + e.getMessage(), "", "");
                        }
                    } catch (Exception ignored) {}
                } finally {
                    lock.release();
                }
            });
            handedOff = true;
            return trialJson(taskId, caseId, row.getId(), EvalRunner.RUNNING);
        } finally {
            if (!handedOff) lock.release();
        }
    }

    /** trial 返回体：本次执行的定位三要素（taskId/caseId/resultId）+ 当前 verdict */
    static JsonObject trialJson(Long taskId, Long caseId, Long resultId, String verdict) {
        JsonObject o = new JsonObject();
        o.addProperty("ok", true);
        o.addProperty("taskId", taskId);
        o.addProperty("caseId", caseId);
        if (resultId != null) o.addProperty("resultId", resultId);
        else o.add("resultId", com.google.gson.JsonNull.INSTANCE);
        o.addProperty("verdict", verdict);
        return o;
    }

    /** 真正跑一条：命令构造 → 进程执行（超时强杀）→ 机器判定 → 输出落盘 + 更新本次结果行（trial 预建的那行） */
    void runOne(Long taskId, Long caseId, EvalCaseResult row, File workDir) throws Exception {
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
        finishResult(row, task, c, verdict, exec.exitCode(), exec.elapsedMs(), reason,
                EvalRunner.summarize(exec.output()), outputFile);
    }

    /** 同步终态（安全闸拦截/沙箱跳过）：新建结果行并直接写终值，返回该行（含生成的 id） */
    private EvalCaseResult recordResult(EvalTask task, EvalCase c, String verdict, Integer exitCode, long elapsedMs,
                                        String reason, String outputSummary, String outputFile) {
        EvalCaseResult r = new EvalCaseResult();
        r.setTaskId(task.getId());
        r.setCaseId(c.getId());
        r.setTriggerMode("TRIAL");
        finishResult(r, task, c, verdict, exitCode, elapsedMs, reason, outputSummary, outputFile);
        return r;
    }

    /** 在给定结果行上写终值 + 同步 case 快照（判定内核不在此处——verdict 全部来自 EvalRunner.judge/闸门） */
    private void finishResult(EvalCaseResult r, EvalTask task, EvalCase c, String verdict, Integer exitCode,
                              long elapsedMs, String reason, String outputSummary, String outputFile) {
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
        log.info("[Eval] 试跑完成 task={} case={} result={}「{}」 verdict={} exit={} elapsed={}ms",
                task.getId(), c.getId(), r.getId(), c.getTitle(), verdict, exitCode, elapsedMs);
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

    /**
     * 单条 eval 的最近执行记录列表。
     * 排序契约：按 id 倒序（最新在前，见 {@link EvalCaseResultRepository#findTop20ByCaseIdOrderByIdDesc}）。
     * 正在执行的一轮以 verdict=RUNNING 的行出现——「从未跑过」（空列表）与「正在跑」由此可分。
     */
    public JsonArray caseResults(Long taskId, Long caseId) {
        requireCase(taskId, caseId);
        JsonArray arr = new JsonArray();
        for (EvalCaseResult r : results.findTop20ByCaseIdOrderByIdDesc(caseId)) {
            arr.add(singleResultJson(r));
        }
        return arr;
    }

    /**
     * 按 resultId 精确取单次执行结果（轮询契约：一律用 trial 返回的 resultId 调这里，
     * <b>不许靠「读列表第一条」</b>——那会把上一轮的旧结果当成本轮的，MVP-1.6 实测缺陷回归锁）。
     */
    public JsonObject resultJson(Long taskId, Long caseId, Long resultId) {
        requireCase(taskId, caseId);
        EvalCaseResult r = results.findById(resultId)
                .filter(x -> caseId.equals(x.getCaseId()) && taskId.equals(x.getTaskId()))
                .orElseThrow(() -> new IllegalArgumentException("执行记录不存在: " + resultId));
        return singleResultJson(r);
    }

    private static JsonObject singleResultJson(EvalCaseResult r) {
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
        return o;
    }

    /**
     * 单次执行记录的输出全文（分页读取，MVP-1.6 追加 2：「错误了要看执行的输出是什么，这样才能排查」）。
     * <p>
     * 安全纪律：文件路径<b>只取自库里该条 result 记录的 outputFile 字段</b>，接口绝不接受调用方传路径
     *（否则变成任意文件读取漏洞）。按行分页（offsetLine + maxLines，maxLines 钳制 1..2000），
     * 一次绝不吐几 MB；同时返回 totalLines/truncated 与完整输出文件路径，用户可自己打开全文。
     */
    public JsonObject resultOutput(Long taskId, Long caseId, Long resultId, int offsetLine, int maxLines) {
        requireCase(taskId, caseId);
        EvalCaseResult r = results.findById(resultId)
                .filter(x -> caseId.equals(x.getCaseId()) && taskId.equals(x.getTaskId()))
                .orElseThrow(() -> new IllegalArgumentException("执行记录不存在: " + resultId));
        JsonObject o = new JsonObject();
        o.addProperty("id", r.getId());
        o.addProperty("verdict", r.getVerdict());
        if (r.getExitCode() != null) o.addProperty("exitCode", r.getExitCode());
        else o.add("exitCode", com.google.gson.JsonNull.INSTANCE);
        o.addProperty("elapsedMs", r.getElapsedMs());
        o.addProperty("reason", r.getReason());
        o.addProperty("outputSummary", r.getOutputSummary());
        o.addProperty("outputFile", r.getOutputFile());
        o.addProperty("ranAt", str(r.getRanAt()));
        o.addProperty("triggerMode", r.getTriggerMode());

        String file = r.getOutputFile();
        int offset = Math.max(0, offsetLine);
        int limit = Math.min(Math.max(1, maxLines), 2000);
        o.addProperty("offsetLine", offset);
        o.addProperty("maxLines", limit);
        if (file == null || file.isBlank() || !Files.isRegularFile(Path.of(file))) {
            // SKIPPED / 只读安全闸拦截（没起进程）或落盘失败 → 没有全文可给，如实说明，不留空白
            o.addProperty("content", "");
            o.addProperty("totalLines", 0);
            o.addProperty("truncated", false);
            o.addProperty("note", "该次执行没有落盘输出文件（未起进程——如 SKIPPED/只读安全闸拦截，或当时落盘失败）；"
                    + "判定依据见 reason，摘要见 outputSummary");
            return o;
        }
        try {
            long totalBytes = Files.size(Path.of(file));
            StringBuilder sb = new StringBuilder();
            int lineNo = 0;
            boolean truncated = false;
            try (var br = Files.newBufferedReader(Path.of(file), StandardCharsets.UTF_8)) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (lineNo >= offset && lineNo < offset + limit) {
                        sb.append(line).append('\n');
                    }
                    lineNo++;
                }
            }
            truncated = lineNo > offset + limit;
            o.addProperty("content", sb.toString());
            o.addProperty("totalLines", lineNo);
            o.addProperty("totalBytes", totalBytes);
            o.addProperty("truncated", truncated);
            if (truncated) {
                o.addProperty("note", "输出较长已按行分页（本页 " + offset + ".." + Math.min(lineNo, offset + limit)
                        + " / 共 " + lineNo + " 行）；完整输出文件: " + file);
            }
        } catch (java.io.IOException e) {
            o.addProperty("content", "");
            o.addProperty("totalLines", 0);
            o.addProperty("truncated", false);
            o.addProperty("note", "读取输出文件失败: " + e.getMessage() + "（文件: " + file + "）");
        }
        return o;
    }

    // ===== 通过率统计（分母诚实：未落地/跳过/未跑/探针 一律不进分母） =====

    /**
     * executed（分母）= enabled 且已落地 且 <b>计入评分</b>（countsTowardScore=true，MVP-1.7）
     * 且 最近一次执行出了客观结果（PASS/FAIL/TIMEOUT）。
     * notLanded（target 空）、skipped（缺沙箱凭据）、notRun（落地了还没跑过）单列，绝不混进分母；
     * 探针（countsTowardScore=false，如直连供应商网关的连通检查）的真实执行结果单独进
     * probeExecuted/probePassed——它只回答「外部服务活着吗」，不证明交付代码正确，
     * <b>既不许虚增通过率（探针 PASS 不进 passed/executed），也不许算成失败（探针 FAIL 不进 failed）</b>。
     */
    public record Stats(int total, int enabled, int landed, int notLanded, int executed,
                        int passed, int failed, int timeout, int error, int skipped,
                        int running, int notRun, int probeExecuted, int probePassed) {}

    static Stats computeStats(List<EvalCase> all) {
        int total = all.size(), enabled = 0, landed = 0, notLanded = 0, executed = 0, passed = 0,
                failed = 0, timeout = 0, error = 0, skipped = 0, running = 0, notRun = 0,
                probeExecuted = 0, probePassed = 0;
        for (EvalCase c : all) {
            if (!c.isEnabled()) continue;
            enabled++;
            if (!EvalRunner.isLanded(c.getTarget())) { notLanded++; continue; }
            landed++;
            String v = c.getLastVerdict();
            if (v == null) { notRun++; continue; }
            boolean scored = c.isCountsTowardScore();
            switch (v) {
                case EvalRunner.PASS -> {
                    if (scored) { executed++; passed++; } else { probeExecuted++; probePassed++; }
                }
                case EvalRunner.FAIL -> {
                    if (scored) { executed++; failed++; } else { probeExecuted++; }
                }
                case EvalRunner.TIMEOUT -> {
                    if (scored) { executed++; timeout++; } else { probeExecuted++; }
                }
                case EvalRunner.SKIPPED -> skipped++;      // 单列，不进分母
                case EvalRunner.ERROR -> error++;           // 环境/启动问题，单列，不进分母（跑都没跑起来）
                case EvalRunner.RUNNING -> running++;
                default -> notRun++;
            }
        }
        return new Stats(total, enabled, landed, notLanded, executed, passed, failed, timeout, error,
                skipped, running, notRun, probeExecuted, probePassed);
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
        o.addProperty("probeExecuted", s.probeExecuted());
        o.addProperty("probePassed", s.probePassed());
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
        o.addProperty("datePolicy", lib.datePolicy());
        o.add("accountOptions", accountOptionsJson(vars));
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
            to.addProperty("countsTowardScore", tpl.countsTowardScore());
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
     * accountId 下拉候选（MVP-1.7 追加①，用户原话「编辑的时候给个框给我我能填用哪个账号」）：
     * 任务已填 configJsonPath 时，读那份配置 JSON 的 AccountDic 键枚举候选，附 AccountName/ConfirmMode
     * 作辅助说明（例：2996 · instantConfirm · INSTANT）。
     * <p>
     * 安全纪律：只回显账号 ID 与名称/确认模式，<b>绝不回显 appid/secret/customerCode 等任何凭据值</b>
     *（枚举副本里 secret 是占位符也不透出）。读不到文件 / AccountDic 缺失或为空 / JSON 坏 →
     * accounts 空数组 + problem 写明原因（前端据此退化成文本输入，不报错、不空白）。
     */
    static JsonObject accountOptionsJson(Map<String, String> vars) {
        JsonObject o = new JsonObject();
        JsonArray arr = new JsonArray();
        o.add("accounts", arr);
        String cfgPath = vars.get("configJsonPath");
        if (cfgPath == null || cfgPath.isBlank()) {
            o.addProperty("problem", "未填 configJsonPath，无法从配置枚举账号候选");
            return o;
        }
        Path p;
        try {
            p = Path.of(cfgPath.trim());
        } catch (Exception e) {
            o.addProperty("problem", "configJsonPath 不是合法路径（" + cfgPath + "），无法枚举账号候选");
            return o;
        }
        if (!Files.isRegularFile(p)) {
            o.addProperty("problem", "configJsonPath 指向的文件读不到（" + cfgPath + "），无法枚举账号候选");
            return o;
        }
        try {
            JsonObject cfg = com.google.gson.JsonParser
                    .parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!cfg.has("AccountDic") || !cfg.get("AccountDic").isJsonObject()) {
                o.addProperty("problem", "配置 JSON 里没有 AccountDic 对象，无法枚举账号候选");
                return o;
            }
            JsonObject dic = cfg.getAsJsonObject("AccountDic");
            List<String> ids = new java.util.ArrayList<>(dic.keySet());
            java.util.Collections.sort(ids);
            for (String id : ids) {
                JsonObject a = new JsonObject();
                a.addProperty("id", id);
                // 白名单式取字段：只取 AccountName/ConfirmMode 作辅助说明，凭据字段一律不碰
                if (dic.get(id) != null && dic.get(id).isJsonObject()) {
                    JsonObject acct = dic.get(id).getAsJsonObject();
                    for (String field : List.of("AccountName", "ConfirmMode")) {
                        if (acct.has(field) && acct.get(field).isJsonPrimitive()) {
                            a.addProperty(Character.toLowerCase(field.charAt(0)) + field.substring(1),
                                    acct.get(field).getAsString());
                        }
                    }
                }
                arr.add(a);
            }
            if (arr.isEmpty()) {
                o.addProperty("problem", "配置 AccountDic 里没有任何账号，无法枚举候选");
            }
        } catch (Exception e) {
            o.addProperty("problem", "解析配置 JSON 失败（" + e.getMessage() + "），无法枚举账号候选");
        }
        return o;
    }

    /**
     * 导入勾选的模板 → 批量建 eval（AC13′/AC14′）：
     * 变量填齐 → 落地为可执行 case（target/expect 已展开）；变量缺失 → target 留空 =
     * 「未落地」（{@link EvalRunner#isLanded} 同一口径，不进通过率分母），notes 写清缺哪个变量。
     * MVP-1.6：supplierDir/slnPath 由 {@link #templateVars} 自动解析（探测不到唯一候选 = 视同缺变量，
     * notes 写明歧义原因）；http 类展开后 target 指向写操作端点 → {@link EvalReadOnlyGuard} 只读安全闸
     * 拒绝落地（notes 写明命中片段）。导入不做任何判定——verdict 仍只能来自之后的真实试跑。
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
            // 只读安全闸（MVP-1.6 追加）：http 类 target 指向写操作端点 → 拒绝落地（导入时就拦，试跑前还会再拦一道）
            EvalReadOnlyGuard.GuardResult guard = EvalReadOnlyGuard.check(c.getKind(), target.text());
            boolean blocked = guard.blocked();
            boolean missing = !target.missingVariables().isEmpty() || blocked;
            if (missing) {
                c.setTarget("");                       // 未落地：不可执行、不进分母（复用 isLanded 口径）
                c.setExpect(!blocked && expect.missingVariables().isEmpty() ? expect.text() : "");
                notLanded++;
            } else {
                c.setTarget(target.text());
                c.setExpect(expect.text());
                landed++;
            }
            StringBuilder notes = new StringBuilder(tpl.description() == null ? "" : tpl.description());
            if (!tpl.verified()) notes.append("\n【该模板未真实验证过，首跑结果出来前请勿当作可信基线】");
            if (blocked) {
                notes.append("\n【只读安全闸·拒绝导入】eval 禁止写操作端点：").append(guard.matched())
                        .append("。对外只允许查询类请求（已知只读目标类别: ")
                        .append(EvalReadOnlyGuard.describeAllowed())
                        .append("）；修正上下文变量后重新导入。");
            }
            if (!target.missingVariables().isEmpty()) {
                notes.append("\n缺少上下文变量：").append(String.join("、", target.missingVariables()))
                        .append("——在任务设置「上下文变量」里补齐后重新导入，或手动编辑补出可执行 target。")
                        .append("\n模板 target：").append(tpl.targetTemplate());
                if (target.missingVariables().contains("repoPath")) {
                    // repoPath 缺省由绑定实例的工作目录自动填充（templateVars）；能缺 = 自动填充也失败了
                    notes.append("\nrepoPath = 仓库根目录（build 找 .sln、类注册引用全仓检索用），缺省自动取绑定实例的工作目录（claudeWorkingDir）。")
                            .append("当前未绑定实例或实例无工作目录——请先在任务设置绑定指向供应商仓库的实例，")
                            .append("或在「上下文变量」里显式填 repoPath 后重新导入；不会退化成在后端进程目录里扫（那会恒红且理由错误）。");
                }
                if (target.missingVariables().contains("supplierDir")) {
                    PathResolution sd = resolveSupplierDir(t, vars);
                    notes.append("\nsupplierDir = 供应商目录（「只管本次交付」的静态检查扫描根，约定位置 ")
                            .append(SUPPLIER_SUBDIR).append("/<供应商>）。当前自动解析未成功：")
                            .append(sd.problem() == null ? "原因未知" : sd.problem())
                            .append("。绝不瞎猜、绝不回退到仓库根（那会把全仓历史债算到本次交付头上，通过率恒 0 并诱导乱改无关代码）。");
                }
                if (target.missingVariables().contains("slnPath")) {
                    PathResolution sln = resolveSlnPath(vars);
                    notes.append("\nslnPath = build 模板的解决方案路径，缺省解析 repoPath 下唯一的 .sln。当前解析未成功：")
                            .append(sln.problem() == null ? "原因未知" : sln.problem())
                            .append("。不会跑一条缺解决方案路径、必失败的命令。");
                }
                if (target.missingVariables().contains("accountId")) {
                    // MVP-1.7 追加①：端到端跑哪个供应商账号必须由人指定，不猜默认账号
                    notes.append("\n缺 accountId：请选择用哪个供应商账号跑本次测评（AccountDic 里往往有多个账号，")
                            .append("确认模式与价格行为不同，选错账号 = 测的不是想要的行为）。");
                    JsonObject acc = accountOptionsJson(vars);
                    if (acc.getAsJsonArray("accounts").size() > 0) {
                        StringBuilder cand = new StringBuilder();
                        for (var el : acc.getAsJsonArray("accounts")) {
                            JsonObject a = el.getAsJsonObject();
                            if (cand.length() > 0) cand.append("；");
                            cand.append(a.get("id").getAsString());
                            if (a.has("accountName")) cand.append(" · ").append(a.get("accountName").getAsString());
                            if (a.has("confirmMode")) cand.append(" · ").append(a.get("confirmMode").getAsString());
                        }
                        notes.append("当前配置里的候选账号：").append(cand).append("。");
                    } else if (acc.has("problem")) {
                        notes.append("（").append(acc.get("problem").getAsString()).append("，可手动填写账号 ID。）");
                    }
                    notes.append("accountId 经环境变量 EVAL_ACCOUNT_ID 传给 dotnet test——测试代码必须从 EVAL_ACCOUNT_ID 读取账号，")
                            .append("缺省时才回落默认账号（契约见 .trellis/spec/backend/supplier-eval.md）。");
                }
                if (target.missingVariables().contains("testProjectPath")) {
                    // MVP-1.7：端到端 = dotnet test 走被测代码；没有可执行测试工程就落未落地并给下一步，绝不瞎猜
                    PathResolution tp = resolveTestProjectPath(vars);
                    String hint = vars.get("supplierClassHint");
                    String who = hint != null && !hint.isBlank() ? hint.trim() : "该供应商实现类";
                    notes.append("\ntestProjectPath = 单元测试工程（引用 Microsoft.NET.Test.Sdk / xunit / nunit 的 .csproj）。当前解析未成功：")
                            .append(tp.problem() == null ? "原因未知" : tp.problem())
                            .append("。该供应商还没有可执行的单元测试工程（实勘：仓库现有 SSTest 是 WinExe 工具工程、")
                            .append("RegressionTest 是依赖 C:\\ProjectInstance 部署目录的 console 批跑器，本机跑不起来——都不算单元测试工程）。")
                            .append("下一步：在开发节点为 ").append(who)
                            .append(" 补一个 xunit 测试——构造供应商 Business 类、调查价、断言返回结构与价格语义；补完再重新导入/重跑本 eval。")
                            .append("绝不代跑一条必失败或恒绿的 dotnet test 命令。");
                }
            }
            if (!tpl.countsTowardScore()) {
                notes.append("\n【探针·不计入通过率】它只回答「外部服务活着吗/请求自带签名对不对」，不证明本次交付的代码正确；")
                        .append("执行结果单独统计（probeExecuted/probePassed），既不进分母也不算失败。");
            }
            notes.append("\n（来源：内置通用模板 ").append(tpl.key()).append("）");
            c.setNotes(notes.toString());
            c.setTimeoutSec(tpl.timeoutSec() > 0 ? tpl.timeoutSec() : EvalRunner.defaultTimeoutSec(c.getKind()));
            c.setRequiresSandbox(tpl.requiresSandbox());
            c.setCountsTowardScore(tpl.countsTowardScore());
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

    /**
     * 模板展开用变量表 = 任务 context_json + 系统变量 evalScriptDir / repoPath / supplierDir / slnPath。
     * <p>
     * repoPath（<b>仓库根</b>：build 找 .sln、class-registered 全仓引用检索用）：上下文变量显式给了 →
     * 显式值优先；缺省 → 复用 {@link #resolveWorkDir} 取绑定实例的工作目录（claudeWorkingDir → shellWorkingDir）。
     * <p>
     * supplierDir（<b>供应商目录</b>：no-empty-catch 等「只管本次交付」的检查用，MVP-1.6 与 repoPath 拆分——
     * 两条静态 eval 的扫描根需求天然冲突）：显式给了 → 显式值优先；留空 → 在
     * {@code repoPath/src/Business/Supplier/} 下按任务 supplierCode 探测（code 为空则取全部子目录），
     * <b>唯一命中才自动填</b>；0 个或多个候选 → 不放值（模板落「未落地」，notes 写明歧义），
     * <b>绝不瞎猜、绝不回退到仓库根</b>（全仓历史债会让通过率恒 0 并诱导乱改无关代码）。
     * <p>
     * slnPath（build 模板的解决方案路径）：显式给了 → 优先；缺省 → 解析 repoPath 下<b>唯一</b>的 *.sln；
     * 0 个或多个 → 不放值（未落地），绝不跑一条缺 sln 必失败的命令。
     * <p>
     * testProjectPath（端到端 dotnet test 模板的测试工程，MVP-1.7）：显式给了 → 优先；缺省 → 探测
     * repoPath 下引用 Microsoft.NET.Test.Sdk / xunit / nunit 的<b>唯一</b> .csproj；0 个或多个 → 不放值
     *（未落地，notes 写明原因与「补一个 xunit 测试」的下一步），绝不瞎猜。
     * <p>
     * 未绑定实例或实例无工作目录 → repoPath 不放值：{{repoPath}} 展不开 → 该模板导入时落「未落地」，
     * <b>绝不静默退化成后端进程 cwd 去扫</b>（实测缺陷：那样静态扫描恒红且红的理由是错的）。
     * 路径统一正斜杠（与 evalScriptDir 同规，node 在 Windows 也认，且避开 cmd 引号内尾部反斜杠转义坑）。
     */
    Map<String, String> templateVars(EvalTask t) {
        Map<String, String> vars = EvalTemplates.contextVars(t.getContextJson());
        vars.put("evalScriptDir", EvalTemplates.resolveScriptDir(scriptDirConfig));
        String explicit = vars.get("repoPath");
        if (explicit != null && !explicit.isBlank()) {
            vars.put("repoPath", explicit.trim().replace('\\', '/'));
        } else {
            File workDir = resolveWorkDir(t);
            if (workDir != null) {
                vars.put("repoPath", workDir.getAbsolutePath().replace('\\', '/'));
            } else {
                vars.remove("repoPath");   // 空白显式值也移除 → expand 统一报「缺 repoPath」→ 未落地
            }
        }
        PathResolution sd = resolveSupplierDir(t, vars);
        if (sd.path() != null) vars.put("supplierDir", sd.path());
        else vars.remove("supplierDir");   // 探测不到唯一目录 → 展不开 → 未落地 + notes 写明歧义（AC19′）
        PathResolution sln = resolveSlnPath(vars);
        if (sln.path() != null) vars.put("slnPath", sln.path());
        else vars.remove("slnPath");       // 无 sln / 多个 sln → 未落地，不跑必失败命令（AC20′）
        PathResolution tp = resolveTestProjectPath(vars);
        if (tp.path() != null) vars.put("testProjectPath", tp.path());
        else vars.remove("testProjectPath");   // 0 个或多个测试工程 → 展不开 → 未落地 + notes 写明原因与下一步（MVP-1.7）
        return vars;
    }

    /** 路径解析结果：path=null 时 problem 写明原因（导入 notes / 排查用，绝不静默） */
    record PathResolution(String path, String problem) {
        static PathResolution of(String path) { return new PathResolution(path, null); }
        static PathResolution fail(String problem) { return new PathResolution(null, problem); }
    }

    /** 供应商代码目录约定（探测 supplierDir 用） */
    static final String SUPPLIER_SUBDIR = "src/Business/Supplier";

    /** 目录扫描跳过项（与 scripts/eval/*.js 的 SKIP_DIRS 同口径） */
    private static final Set<String> SCAN_SKIP_DIRS = Set.of(
            "bin", "obj", ".git", "node_modules", ".vs", ".idea", "packages", "TestResults");

    /**
     * supplierDir 解析（AC19′）：显式上下文变量优先（指向不存在目录也算失败，写明原因）；
     * 缺省在 repoPath/src/Business/Supplier/ 下探测——supplierCode 非空按名字（大小写不敏感）匹配，
     * 为空则全部子目录都是候选；<b>唯一命中才自动填</b>，0 个或多个 → 失败并写明歧义。
     */
    PathResolution resolveSupplierDir(EvalTask t, Map<String, String> vars) {
        String explicit = vars.get("supplierDir");
        if (explicit != null && !explicit.isBlank()) {
            String norm = explicit.trim().replace('\\', '/');
            if (!Files.isDirectory(Path.of(norm))) {
                return PathResolution.fail("上下文变量 supplierDir 显式指向的目录不存在: " + norm);
            }
            return PathResolution.of(norm);
        }
        String repo = vars.get("repoPath");
        if (repo == null || repo.isBlank()) {
            return PathResolution.fail("缺 repoPath（未绑定实例或实例无工作目录，且未在上下文变量显式填写），无法探测供应商目录");
        }
        Path base = Path.of(repo).resolve(SUPPLIER_SUBDIR);
        if (!Files.isDirectory(base)) {
            return PathResolution.fail(repo + " 下不存在 " + SUPPLIER_SUBDIR + "/ 目录，无法自动探测供应商目录");
        }
        List<String> names;
        try (var s = Files.list(base)) {
            names = s.filter(Files::isDirectory).map(p -> p.getFileName().toString()).sorted().toList();
        } catch (java.io.IOException e) {
            return PathResolution.fail("枚举 " + base + " 失败: " + e.getMessage());
        }
        String code = t.getSupplierCode() == null ? "" : t.getSupplierCode().trim();
        List<String> hits = code.isEmpty() ? names
                : names.stream().filter(n -> n.equalsIgnoreCase(code)).toList();
        if (hits.size() == 1) {
            return PathResolution.of(base.resolve(hits.get(0)).toString().replace('\\', '/'));
        }
        if (hits.isEmpty()) {
            return PathResolution.fail("供应商目录探测 0 个候选：" + base + " 下"
                    + (code.isEmpty() ? "没有任何子目录" : "没有名字匹配 supplierCode=" + code + " 的目录")
                    + "（现有: " + (names.isEmpty() ? "无" : String.join("、", names)) + "）——请在上下文变量显式填 supplierDir");
        }
        return PathResolution.fail("供应商目录探测有歧义（" + hits.size() + " 个候选: " + String.join("、", hits)
                + "），唯一命中才自动填——请在上下文变量显式填 supplierDir");
    }

    /**
     * slnPath 解析（AC20′）：显式上下文变量优先；缺省在 repoPath 下找<b>唯一</b>的 *.sln
     *（跳过 bin/obj/.git 等目录）；0 个或多个 → 失败并写明原因，模板落「未落地」。
     */
    PathResolution resolveSlnPath(Map<String, String> vars) {
        String explicit = vars.get("slnPath");
        if (explicit != null && !explicit.isBlank()) {
            String norm = explicit.trim().replace('\\', '/');
            if (!Files.isRegularFile(Path.of(norm))) {
                return PathResolution.fail("上下文变量 slnPath 显式指向的文件不存在: " + norm);
            }
            return PathResolution.of(norm);
        }
        String repo = vars.get("repoPath");
        if (repo == null || repo.isBlank()) {
            return PathResolution.fail("缺 repoPath（未绑定实例或实例无工作目录，且未在上下文变量显式填写），无法解析 .sln");
        }
        Path root = Path.of(repo);
        if (!Files.isDirectory(root)) {
            return PathResolution.fail("repoPath 指向的目录不存在: " + repo);
        }
        List<Path> slns = findSlnFiles(root);
        if (slns.size() == 1) {
            return PathResolution.of(slns.get(0).toString().replace('\\', '/'));
        }
        if (slns.isEmpty()) {
            return PathResolution.fail("repoPath（" + repo + "）下没找到任何 .sln——请确认仓库根是否正确，或在上下文变量显式填 slnPath");
        }
        List<String> names = slns.stream().map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
        return PathResolution.fail("repoPath（" + repo + "）下找到 " + slns.size() + " 个 .sln（"
                + String.join("、", names) + "），有歧义——请在上下文变量显式填 slnPath");
    }

    /**
     * testProjectPath 解析（MVP-1.7 端到端 = dotnet test）：显式上下文变量优先（指向不存在的文件也算失败，写明原因）；
     * 缺省在 repoPath 下探测引用 Microsoft.NET.Test.Sdk / xunit / nunit 的 .csproj（跳过 bin/obj 等），
     * <b>唯一命中才自动填</b>；0 个或多个 → 失败并写明原因/歧义，模板落「未落地」，
     * 绝不瞎猜、绝不跑一条必失败或恒绿的 dotnet test 命令（探测纪律与 supplierDir/slnPath 同款）。
     */
    PathResolution resolveTestProjectPath(Map<String, String> vars) {
        String explicit = vars.get("testProjectPath");
        if (explicit != null && !explicit.isBlank()) {
            String norm = explicit.trim().replace('\\', '/');
            if (!Files.isRegularFile(Path.of(norm))) {
                return PathResolution.fail("上下文变量 testProjectPath 显式指向的文件不存在: " + norm);
            }
            return PathResolution.of(norm);
        }
        String repo = vars.get("repoPath");
        if (repo == null || repo.isBlank()) {
            return PathResolution.fail("缺 repoPath（未绑定实例或实例无工作目录，且未在上下文变量显式填写），无法探测单元测试工程");
        }
        Path root = Path.of(repo);
        if (!Files.isDirectory(root)) {
            return PathResolution.fail("repoPath 指向的目录不存在: " + repo);
        }
        List<Path> hits = findTestCsprojFiles(root);
        if (hits.size() == 1) {
            return PathResolution.of(hits.get(0).toString().replace('\\', '/'));
        }
        if (hits.isEmpty()) {
            return PathResolution.fail("repoPath（" + repo + "）下没找到任何单元测试工程"
                    + "（探测引用 Microsoft.NET.Test.Sdk / xunit / nunit 的 .csproj，命中 0 个）"
                    + "——请确认仓库根，或在上下文变量显式填 testProjectPath");
        }
        List<String> names = hits.stream().map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
        return PathResolution.fail("repoPath（" + repo + "）下找到 " + hits.size() + " 个单元测试工程，有歧义（"
                + String.join("、", names) + "），唯一命中才自动填——请在上下文变量显式填 testProjectPath");
    }

    /** 测试工程标记（.csproj 内容小写化后做确定性文本判定，不是模型判断） */
    private static final List<String> TEST_CSPROJ_MARKERS = List.of("microsoft.net.test.sdk", "xunit", "nunit");

    /** repoPath 下递归找测试工程 .csproj（跳过 bin/obj/.git/node_modules 等，与 findSlnFiles 同口径） */
    static List<Path> findTestCsprojFiles(Path root) {
        List<Path> out = new java.util.ArrayList<>();
        try {
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(root) && SCAN_SKIP_DIRS.contains(n)) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (!file.getFileName().toString().toLowerCase().endsWith(".csproj")) {
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    try {
                        String content = Files.readString(file, StandardCharsets.UTF_8).toLowerCase();
                        if (TEST_CSPROJ_MARKERS.stream().anyMatch(content::contains)) out.add(file);
                    } catch (java.io.IOException ignored) {
                        // 读不到的 .csproj 跳过，不让整次探测失败（剩余候选仍然诚实）
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path file, java.io.IOException exc) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (java.io.IOException e) {
            log.warn("[Eval] 探测单元测试工程失败 root={}: {}", root, e.getMessage());
        }
        return out;
    }

    /** repoPath 下递归找 *.sln（跳过 bin/obj/.git/node_modules/.vs/.idea/packages/TestResults） */
    static List<Path> findSlnFiles(Path root) {
        List<Path> out = new java.util.ArrayList<>();
        try {
            Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(Path dir,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    String n = dir.getFileName() == null ? "" : dir.getFileName().toString();
                    if (!dir.equals(root) && SCAN_SKIP_DIRS.contains(n)) {
                        return java.nio.file.FileVisitResult.SKIP_SUBTREE;
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                        java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (file.getFileName().toString().toLowerCase().endsWith(".sln")) out.add(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFileFailed(Path file, java.io.IOException exc) {
                    return java.nio.file.FileVisitResult.CONTINUE;   // 读不到的文件跳过，不让整次解析失败
                }
            });
        } catch (java.io.IOException e) {
            log.warn("[Eval] 解析 .sln 失败 root={}: {}", root, e.getMessage());
        }
        return out;
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
        o.addProperty("countsTowardScore", c.isCountsTowardScore());
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
