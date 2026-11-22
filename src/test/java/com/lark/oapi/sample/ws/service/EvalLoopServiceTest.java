package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.model.entity.EvalCase;
import com.lark.oapi.sample.ws.model.entity.EvalCaseResult;
import com.lark.oapi.sample.ws.model.entity.EvalTask;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.EvalCaseRepository;
import com.lark.oapi.sample.ws.repository.EvalCaseResultRepository;
import com.lark.oapi.sample.ws.repository.EvalTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 测评服务单测（11-21-supplier-eval-loop MVP-1）：
 * 分母诚实（未落地/跳过不进通过率）、requiresSandbox 跳过语义（不起进程）、
 * 同任务串行闸（不并发跑多个构建）、未落地拒绝试跑、输出落盘 + 判定入库。
 */
class EvalLoopServiceTest {

    @TempDir
    Path tmp;

    private EvalTaskRepository taskRepo;
    private EvalCaseRepository caseRepo;
    private EvalCaseResultRepository resultRepo;
    private BotInstanceRepository instanceRepo;
    private EvalLoopService service;

    /** 结果行 id 发号器（真实环境由 JPA 生成；mock 下 save 时补 id，trial 才能同步返回 resultId） */
    private final java.util.concurrent.atomic.AtomicLong resultIds = new java.util.concurrent.atomic.AtomicLong(100);

    @BeforeEach
    void setUp() {
        taskRepo = mock(EvalTaskRepository.class);
        caseRepo = mock(EvalCaseRepository.class);
        resultRepo = mock(EvalCaseResultRepository.class);
        instanceRepo = mock(BotInstanceRepository.class);
        when(resultRepo.save(any())).thenAnswer(inv -> {
            EvalCaseResult r = inv.getArgument(0);
            if (r.getId() == null) r.setId(resultIds.incrementAndGet());
            return r;
        });
        service = new EvalLoopService(taskRepo, caseRepo, resultRepo, instanceRepo,
                tmp.resolve("eval-logs").toString(), "", "");
    }

    private static EvalCase evalCase(long id, boolean enabled, String target, String lastVerdict) {
        EvalCase c = new EvalCase();
        c.setId(id);
        c.setTaskId(1L);
        c.setTitle("case-" + id);
        c.setEnabled(enabled);
        c.setTarget(target);
        c.setLastVerdict(lastVerdict);
        return c;
    }

    // ===== 分母诚实：未落地 / 跳过 / 未跑 一律不进通过率分母 =====

    @Test
    void statsDenominatorExcludesNotLandedSkippedAndNotRun() {
        List<EvalCase> all = List.of(
                evalCase(1, true, "dotnet build", EvalRunner.PASS),        // 分母 + 分子
                evalCase(2, true, "dotnet test", EvalRunner.FAIL),         // 分母
                evalCase(3, true, "newman run", EvalRunner.TIMEOUT),       // 分母（真跑过、没通过）
                evalCase(4, true, "postman ...", EvalRunner.SKIPPED),      // 跳过：单列，不进分母
                evalCase(5, true, "", EvalRunner.PASS),                    // 未落地：就算残留 PASS 也不进分母
                evalCase(6, true, "grep -r xxx", null),                    // 落地未跑：不进分母
                evalCase(7, false, "dotnet build", EvalRunner.PASS),       // 停用：完全不参与
                evalCase(8, true, "dir", EvalRunner.ERROR));               // 进程没起来：单列，不进分母
        var s = EvalLoopService.computeStats(all);

        assertEquals(8, s.total());
        assertEquals(7, s.enabled());
        assertEquals(3, s.executed(), "分母 = 真跑出客观结果的（PASS/FAIL/TIMEOUT）");
        assertEquals(1, s.passed());
        assertEquals(1, s.failed());
        assertEquals(1, s.timeout());
        assertEquals(1, s.skipped(), "跳过单列");
        assertEquals(1, s.notLanded(), "未落地单列");
        assertEquals(1, s.notRun());
        assertEquals(1, s.error());
        // 通过率 = 1/3，绝不是 1/7 或 3/3 —— 未落地与跳过没有把分母做大或做小
        assertEquals(0.333, (double) s.passed() / s.executed(), 0.001);
    }

    // ===== MVP-1.7：探针（countsTowardScore=false）不进分母、单独统计、探针失败不算失败 =====

    @Test
    void statsKeepProbesOutOfDenominatorInAllVerdicts() {
        EvalCase probeTimeout = evalCase(11, true, "node probe.js", EvalRunner.TIMEOUT);
        probeTimeout.setCountsTowardScore(false);
        EvalCase probeSkip = evalCase(12, true, "node probe.js", EvalRunner.SKIPPED);
        probeSkip.setCountsTowardScore(false);
        EvalCase probeNotLanded = evalCase(13, true, "", null);
        probeNotLanded.setCountsTowardScore(false);
        EvalCase scored = evalCase(14, true, "dotnet test x", EvalRunner.PASS);
        // 存量行：加列前落库的读出来是 NULL → 一律按默认值 true（计分项）处理，
        // 绝不因加列把现网真任务（id=2）的分母悄悄缩水或把计分项变成探针
        EvalCase legacy = evalCase(15, true, "node legacy.js", EvalRunner.FAIL);
        legacy.setCountsTowardScore(null);
        assertTrue(legacy.isCountsTowardScore(), "NULL（存量行）必须按 true 处理");

        var s = EvalLoopService.computeStats(List.of(probeTimeout, probeSkip, probeNotLanded, scored, legacy));
        assertEquals(2, s.executed(), "分母只含计分项的真实执行（PASS 1 + 存量 NULL 按计分的 FAIL 1）");
        assertEquals(1, s.passed());
        assertEquals(1, s.failed(), "存量 NULL 行的 FAIL 照常计入——通过率没有被加列改变");
        assertEquals(0, s.timeout(), "探针超时不进计分 timeout（探针挂了不是代码挂了）");
        assertEquals(1, s.probeExecuted(), "探针的真实执行结果单独统计");
        assertEquals(0, s.probePassed());
        assertEquals(1, s.skipped(), "探针 SKIPPED 仍单列进 skipped（不影响通过率）");
        assertEquals(1, s.notLanded(), "探针未落地仍单列进 notLanded");
        assertEquals(0.5, (double) s.passed() / s.executed(), 0.001,
                "通过率 = 1/2 —— 探针既没虚增分子，也没把失败算进来");
    }

    // ===== requiresSandbox 跳过语义：不起进程、结果落 SKIPPED =====

    @Test
    void trialSkipsSandboxCaseWithoutCredentialsAndNeverStartsProcess() {
        EvalTask task = new EvalTask();
        task.setId(1L);
        EvalCase c = evalCase(10, true, "postman collection run sandbox.json", EvalRunner.PASS);
        c.setRequiresSandbox(true);
        when(taskRepo.findById(1L)).thenReturn(Optional.of(task));
        when(caseRepo.findById(10L)).thenReturn(Optional.of(c));
        service.sandboxCreds = () -> false;   // 无凭据

        com.google.gson.JsonObject resp = service.trial(1L, 10L);
        assertEquals(EvalRunner.SKIPPED, resp.get("verdict").getAsString(), "同步终态直接返回 SKIPPED");
        assertFalse(resp.get("resultId").isJsonNull(), "同步终态也返回本次 resultId");
        assertEquals(10L, resp.get("caseId").getAsLong());

        ArgumentCaptor<EvalCaseResult> saved = ArgumentCaptor.forClass(EvalCaseResult.class);
        verify(resultRepo).save(saved.capture());
        assertEquals(EvalRunner.SKIPPED, saved.getValue().getVerdict(), "无凭据 → SKIPPED（单列，不进分母）");
        assertEquals(resp.get("resultId").getAsLong(), saved.getValue().getId(), "返回的 resultId 就是落库那行");
        assertTrue(saved.getValue().getReason().contains(EvalLoopService.SANDBOX_CRED_ENV),
                "跳过原因写明凭据探测方式: " + saved.getValue().getReason());
        assertNull(saved.getValue().getExitCode(), "跳过没有退出码——没起进程");
        assertEquals(0L, saved.getValue().getElapsedMs());
        assertEquals(EvalRunner.SKIPPED, c.getLastVerdict(), "case 快照同步为 SKIPPED");
    }

    @Test
    void sandboxCaseRunsWhenCredentialsPresent() throws Exception {
        // 有凭据 → 不跳过，真实执行（Windows 上用无害 echo 命令验证走到判定）
        EvalTask task = new EvalTask();
        task.setId(1L);
        EvalCase c = evalCase(11, true,
                EvalRunner.isWindows() ? "echo SANDBOX_OK" : "echo SANDBOX_OK", null);
        c.setKind("script");
        c.setRequiresSandbox(true);
        c.setExpect("stdout~SANDBOX_OK");
        when(taskRepo.findById(1L)).thenReturn(Optional.of(task));
        when(caseRepo.findById(11L)).thenReturn(Optional.of(c));
        service.sandboxCreds = () -> true;

        com.google.gson.JsonObject resp = service.trial(1L, 11L);
        assertEquals(EvalRunner.RUNNING, resp.get("verdict").getAsString(), "异步执行：先返回 RUNNING + resultId");
        // trial 是异步的：按本次 resultId 等结果写完（最多 30s，实际秒级）
        EvalCaseResult r = awaitResult(resp.get("resultId").getAsLong());
        assertEquals(EvalRunner.PASS, r.getVerdict(), "有凭据正常执行并按断言判定");
    }

    // ===== 未落地 / 停用 拒绝试跑 =====

    @Test
    void trialRejectsNotLandedCase() {
        EvalTask task = new EvalTask();
        task.setId(1L);
        EvalCase c = evalCase(12, true, "   ", null);
        when(taskRepo.findById(1L)).thenReturn(Optional.of(task));
        when(caseRepo.findById(12L)).thenReturn(Optional.of(c));

        var e = assertThrows(IllegalArgumentException.class, () -> service.trial(1L, 12L));
        assertTrue(e.getMessage().contains("未落地"), "未落地拒绝执行且文案可懂: " + e.getMessage());
        assertTrue(e.getMessage().contains("不计入通过率"));
        verify(resultRepo, never()).save(any());
    }

    @Test
    void trialRejectsDisabledCase() {
        EvalTask task = new EvalTask();
        task.setId(1L);
        EvalCase c = evalCase(13, false, "echo x", null);
        when(taskRepo.findById(1L)).thenReturn(Optional.of(task));
        when(caseRepo.findById(13L)).thenReturn(Optional.of(c));
        assertThrows(IllegalStateException.class, () -> service.trial(1L, 13L));
    }

    // ===== 同任务串行闸：不并发跑多个构建 =====

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void secondTrialInSameTaskRejectedWhileFirstRunning() throws Exception {
        EvalTask task = new EvalTask();
        task.setId(1L);
        // 第一条：真实慢命令（ping ≈ 4s），占住串行闸
        EvalCase slow = evalCase(20, true, "ping -n 5 127.0.0.1 > nul", null);
        slow.setKind("script");
        slow.setTimeoutSec(30);
        // 第二条：同任务另一 eval
        EvalCase other = evalCase(21, true, "echo second", null);
        other.setKind("script");
        when(taskRepo.findById(1L)).thenReturn(Optional.of(task));
        when(caseRepo.findById(20L)).thenReturn(Optional.of(slow));
        when(caseRepo.findById(21L)).thenReturn(Optional.of(other));
        when(caseRepo.findByTaskIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());
        service.sandboxCreds = () -> true;

        com.google.gson.JsonObject respA = service.trial(1L, 20L);
        var e = assertThrows(IllegalStateException.class, () -> service.trial(1L, 21L),
                "同任务第二条必须被串行闸拒绝（避免并发 dotnet 互锁 obj/）");
        assertTrue(e.getMessage().contains("串行"), e.getMessage());

        // 同一条正在 RUNNING 时再点自己 → 也被拒
        assertEquals(EvalRunner.RUNNING, slow.getLastVerdict());
        var e2 = assertThrows(IllegalStateException.class, () -> service.trial(1L, 20L));
        assertTrue(e2.getMessage().contains("正在执行中"));

        awaitResult(respA.get("resultId").getAsLong());   // 等第一条跑完释放闸
        // 释放后第二条可跑
        com.google.gson.JsonObject respB = assertDoesNotThrow(() -> service.trial(1L, 21L));
        assertNotEquals(respA.get("resultId").getAsLong(), respB.get("resultId").getAsLong(),
                "两次执行必须是两个不同的 resultId");
        EvalCaseResult second = awaitResult(respB.get("resultId").getAsLong());
        assertEquals(EvalRunner.PASS, second.getVerdict(), "串行闸释放后第二条正常执行");
    }

    // ===== 执行结果入库：verdict/退出码/耗时/摘要/落盘文件 =====

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void trialRecordsMachineVerdictWithOutputFile() throws Exception {
        EvalTask task = new EvalTask();
        task.setId(1L);
        EvalCase c = evalCase(30, true, "echo BUILD_OK_MARK", null);
        c.setKind("build");
        c.setExpect("stdout~BUILD_OK_MARK");
        c.setTimeoutSec(30);
        when(taskRepo.findById(1L)).thenReturn(Optional.of(task));
        when(caseRepo.findById(30L)).thenReturn(Optional.of(c));
        service.sandboxCreds = () -> true;

        com.google.gson.JsonObject resp = service.trial(1L, 30L);
        EvalCaseResult r = awaitResult(resp.get("resultId").getAsLong());

        assertEquals(EvalRunner.PASS, r.getVerdict(), "退出码 0 + 断言匹配 = PASS（机器判定）");
        assertEquals(0, r.getExitCode());
        assertTrue(r.getElapsedMs() >= 0);
        assertTrue(r.getOutputSummary().contains("BUILD_OK_MARK"), "摘要含输出");
        assertFalse(r.getOutputFile().isBlank(), "完整输出落盘");
        assertTrue(java.nio.file.Files.exists(Path.of(r.getOutputFile())), "落盘文件真实存在");
        assertTrue(r.getOutputFile().contains("task-1"), "落盘路径含任务目录");
        assertEquals("TRIAL", r.getTriggerMode());
        assertEquals(EvalRunner.PASS, c.getLastVerdict(), "case 快照同步");
        assertEquals("", c.getLastReason(), "PASS 无失败原因");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void trialRecordsTimeoutAsTimeoutVerdict() throws Exception {
        EvalTask task = new EvalTask();
        task.setId(1L);
        EvalCase c = evalCase(31, true, "ping -n 8 127.0.0.1 > nul", null);   // ≈7s
        c.setKind("script");
        c.setTimeoutSec(1);                                                    // 1s 强杀
        when(taskRepo.findById(1L)).thenReturn(Optional.of(task));
        when(caseRepo.findById(31L)).thenReturn(Optional.of(c));
        service.sandboxCreds = () -> true;

        long t0 = System.currentTimeMillis();
        com.google.gson.JsonObject resp = service.trial(1L, 31L);
        EvalCaseResult r = awaitResult(resp.get("resultId").getAsLong());
        long waited = System.currentTimeMillis() - t0;

        assertEquals(EvalRunner.TIMEOUT, r.getVerdict(), "超时 = TIMEOUT，绝不判 PASS");
        assertNull(r.getExitCode(), "强杀无退出码");
        assertTrue(r.getReason().contains("强制终止"), r.getReason());
        assertTrue(waited < 6000, "超时即停不傻等（实际 " + waited + "ms）");
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void trialRecordsFailReasonFromAssertion() throws Exception {
        EvalTask task = new EvalTask();
        task.setId(1L);
        EvalCase c = evalCase(32, true, "echo WRONG_OUTPUT", null);
        c.setKind("script");
        c.setExpect("stdout~EXPECTED_MARK");
        c.setTimeoutSec(30);
        when(taskRepo.findById(1L)).thenReturn(Optional.of(task));
        when(caseRepo.findById(32L)).thenReturn(Optional.of(c));
        service.sandboxCreds = () -> true;

        com.google.gson.JsonObject resp = service.trial(1L, 32L);
        EvalCaseResult r = awaitResult(resp.get("resultId").getAsLong());

        assertEquals(EvalRunner.FAIL, r.getVerdict(), "退出码 0 但断言不匹配 = FAIL");
        assertTrue(r.getReason().contains("EXPECTED_MARK") || r.getReason().contains("未匹配"),
                "失败原因可定位: " + r.getReason());
    }

    // ===== 工作目录解析：绑定实例的 claudeWorkingDir → shellWorkingDir =====

    @Test
    void workDirComesFromBoundInstance() {
        EvalTask task = new EvalTask();
        task.setId(1L);
        task.setInstanceId(9L);
        BotInstance inst = new BotInstance();
        inst.setId(9L);
        inst.setClaudeWorkingDir("D:/work/supplier");
        inst.setShellWorkingDir("D:/fallback");
        when(instanceRepo.findById(9L)).thenReturn(Optional.of(inst));
        assertEquals(Path.of("D:/work/supplier"), service.resolveWorkDir(task).toPath());

        inst.setClaudeWorkingDir("");
        assertEquals(Path.of("D:/fallback"), service.resolveWorkDir(task).toPath());

        task.setInstanceId(null);
        assertNull(service.resolveWorkDir(task), "未绑定实例 → 继承后端进程目录");
    }

    // ===== 草稿导入：落库即未落地 case，不带任何判定 =====

    @Test
    void importDraftsCreatesNotLandedCases() {
        EvalTask task = new EvalTask();
        task.setId(1L);
        when(taskRepo.findById(1L)).thenReturn(Optional.of(task));
        when(caseRepo.findByTaskIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());

        int n = service.importDrafts(1L, List.of(
                new EvalDrafts.Draft("[场景] 总价100", "script", "", "", 120, false, false,
                        "关键预期：逐晚累加精确等于100", "report-and-validation.md · 有针对性的验证", true),
                new EvalDrafts.Draft("[core §1] RP ID", "static", "", "", 60, false, false,
                        "bullet 全文", "core-review § 1", true)));

        assertEquals(2, n);
        ArgumentCaptor<EvalCase> captor = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseRepo, times(2)).save(captor.capture());
        for (EvalCase saved : captor.getAllValues()) {
            assertEquals("", saved.getTarget(), "导入的草稿仍未落地");
            assertNull(saved.getLastVerdict(), "导入不带任何判定——verdict 只能来自真实执行");
            assertTrue(saved.getNotes().contains("来源"), "notes 附来源可追溯");
        }
        assertEquals(1, captor.getAllValues().get(0).getSortOrder());
        assertEquals(2, captor.getAllValues().get(1).getSortOrder());
    }

    // ===== MVP-1.6 追加 3 回归锁：连续两次 trial 必须拿到不同 resultId，按 id 轮询精确取到本次结果 =====

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void consecutiveTrialsReturnDistinctResultIdsAndPollingByIdGetsExactlyThatRun() throws Exception {
        EvalTask task = new EvalTask();
        task.setId(1L);
        EvalCase c = evalCase(40, true, "echo FIRST_RUN", null);
        c.setKind("script");
        c.setExpect("stdout~FIRST_RUN");
        c.setTimeoutSec(30);
        when(taskRepo.findById(1L)).thenReturn(Optional.of(task));
        when(caseRepo.findById(40L)).thenReturn(Optional.of(c));
        service.sandboxCreds = () -> true;

        // 第一次试跑
        com.google.gson.JsonObject resp1 = service.trial(1L, 40L);
        long id1 = resp1.get("resultId").getAsLong();
        EvalCaseResult row1 = awaitResult(id1);
        assertEquals(EvalRunner.PASS, row1.getVerdict());
        assertTrue(row1.getOutputSummary().contains("FIRST_RUN"), row1.getOutputSummary());

        // 第二次试跑（换了命令——实测缺陷场景：换实现后按"列表第一条"轮询会读到上一轮旧结果）
        c.setTarget("echo SECOND_RUN");
        c.setExpect("stdout~SECOND_RUN");
        com.google.gson.JsonObject resp2 = trialWhenIdle(1L, 40L);
        long id2 = resp2.get("resultId").getAsLong();
        assertNotEquals(id1, id2, "两次试跑必须返回不同的 resultId");

        EvalCaseResult row2 = awaitResult(id2);
        assertEquals(EvalRunner.PASS, row2.getVerdict());
        assertTrue(row2.getOutputSummary().contains("SECOND_RUN"),
                "按 resultId 精确取到的是【本次】的输出: " + row2.getOutputSummary());
        assertFalse(row2.getOutputSummary().contains("FIRST_RUN"), "绝不是上一轮的旧输出");
        // 上一轮那行原样保留，按 id 仍可精确回看（执行记录逐次可追溯）
        assertTrue(row1.getOutputSummary().contains("FIRST_RUN"));
        // 服务端 resultJson 同样按 id 精确取
        when(resultRepo.findById(id1)).thenReturn(Optional.of(row1));
        when(resultRepo.findById(id2)).thenReturn(Optional.of(row2));
        assertTrue(service.resultJson(1L, 40L, id1).get("outputSummary").getAsString().contains("FIRST_RUN"),
                "按 id1 取到第一轮结果");
        assertTrue(service.resultJson(1L, 40L, id2).get("outputSummary").getAsString().contains("SECOND_RUN"),
                "按 id2 取到第二轮结果");
    }

    // ===== 等待异步试跑落库（按 resultId 精确匹配，直到该行离开 RUNNING 终态为止） =====

    /**
     * 与 awaitResult 的竞态修复：awaitResult 在结果行写成终态时即返回，但执行线程释放同任务串行闸
     * （finally lock.release()）发生在其后一瞬——紧接着的下一次 trial 可能撞上尚未释放的闸而被拒。
     * 这是测试侧的时序问题（生产逻辑正确：串行闸本就该拒绝并发试跑），用短重试吸收释放延迟，
     * 让「连续两次试跑」稳定复现而非偶发红。仅重试「正在执行」这一种可恢复拒绝。
     */
    private com.google.gson.JsonObject trialWhenIdle(Long taskId, Long caseId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (true) {
            try {
                return service.trial(taskId, caseId);
            } catch (IllegalStateException e) {
                if (e.getMessage() != null && e.getMessage().contains("正在执行")
                        && System.currentTimeMillis() < deadline) {
                    Thread.sleep(50);   // 等上一轮执行线程释放串行闸
                    continue;
                }
                throw e;
            }
        }
    }

    private EvalCaseResult awaitResult(long resultId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            ArgumentCaptor<EvalCaseResult> captor = ArgumentCaptor.forClass(EvalCaseResult.class);
            try {
                verify(resultRepo, atLeastOnce()).save(captor.capture());
                for (EvalCaseResult r : captor.getAllValues()) {
                    // 同一行对象会被执行线程原地更新成终态（RUNNING → PASS/FAIL/TIMEOUT/ERROR）
                    if (r.getId() != null && r.getId() == resultId && !EvalRunner.RUNNING.equals(r.getVerdict())) {
                        return r;
                    }
                }
            } catch (AssertionError ignore) { /* 还没有任何 save */ }
            Thread.sleep(100);
        }
        return fail("等待 resultId=" + resultId + " 的试跑结果超时（30s）");
    }
}
