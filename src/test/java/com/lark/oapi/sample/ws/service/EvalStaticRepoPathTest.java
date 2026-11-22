package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonObject;
import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.model.entity.EvalCase;
import com.lark.oapi.sample.ws.model.entity.EvalCaseResult;
import com.lark.oapi.sample.ws.model.entity.EvalTask;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.EvalCaseRepository;
import com.lark.oapi.sample.ws.repository.EvalCaseResultRepository;
import com.lark.oapi.sample.ws.repository.EvalTaskRepository;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 静态扫描模板「扫描根目录（repoPath）」缺陷修复回归测试（11-21-supplier-eval-loop MVP-1.5 实测缺陷）。
 * <p>
 * 缺陷：static-no-empty-catch / static-class-registered 导入后的 target 没传扫描根参数，
 * 脚本退化成在后端 JVM 进程工作目录里扫 .cs → 无论供应商代码什么样都恒红，且红的理由是错的。
 * <p>
 * 修复语义（本测试钉死）：扫描根 = 绑定实例的工作目录（复用 {@link EvalLoopService#resolveWorkDir}，
 * claudeWorkingDir → shellWorkingDir）；上下文变量显式给了 repoPath 则以显式值优先；
 * 两者都拿不到 → 模板落「未落地」，notes 写清"未绑定实例或实例无工作目录"，绝不退化成后端 cwd。
 * <p>
 * 判定链真实执行：fixture 一律生成在 JUnit 临时目录（不碰任何真实仓库），走
 * importTemplates → trial → EvalRunner.execute/judge 全链路，断言真实退出码与脚本输出。
 */
class EvalStaticRepoPathTest {

    @TempDir
    Path tmp;

    private EvalTaskRepository taskRepo;
    private EvalCaseRepository caseRepo;
    private EvalCaseResultRepository resultRepo;
    private BotInstanceRepository instanceRepo;
    private EvalLoopService service;
    private String scriptDir;

    /** 反例 fixture：空 catch 吞异常（必须 FAIL） */
    static final String SWALLOW_CS = """
            using System;
            public class Swallow {
                public void Run() {
                    try { DoWork(); }
                    catch (Exception) { }
                }
                void DoWork() {}
            }
            """;

    /** 正例 fixture：catch 里记日志再抛（必须 PASS） */
    static final String CLEAN_CS = """
            using System;
            public class Clean {
                public void Run() {
                    try { DoWork(); }
                    catch (Exception ex) { Log.Error(ex); throw; }
                }
                void DoWork() {}
            }
            """;

    @BeforeAll
    static void requireNode() {
        Assumptions.assumeTrue(nodeAvailable(), "本机没有 node，跳过静态模板真实执行测试");
    }

    static boolean nodeAvailable() {
        try {
            Process p = new ProcessBuilder("node", "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        taskRepo = mock(EvalTaskRepository.class);
        caseRepo = mock(EvalCaseRepository.class);
        resultRepo = mock(EvalCaseResultRepository.class);
        instanceRepo = mock(BotInstanceRepository.class);
        Path f = Path.of(EvalTemplates.DEV_FILE).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(f), "模板库文件必须存在（测试在仓库根运行）: " + f);
        scriptDir = EvalTemplates.resolveScriptDir("");
        service = new EvalLoopService(taskRepo, caseRepo, resultRepo, instanceRepo,
                tmp.resolve("eval-logs").toString(), f.toString(), scriptDir);
    }

    // ===== 辅助 =====

    /** 任务 id=1，绑定实例 id=9（claudeWorkingDir = instanceWorkDir），带上下文变量 */
    private EvalTask task(Path instanceWorkDir, String contextJson) {
        EvalTask t = new EvalTask();
        t.setId(1L);
        t.setName("repo-path-test");
        t.setInstanceId(instanceWorkDir == null ? null : 9L);
        t.setContextJson(contextJson);
        when(taskRepo.findById(1L)).thenReturn(Optional.of(t));
        when(caseRepo.findByTaskIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());
        if (instanceWorkDir != null) {
            BotInstance inst = new BotInstance();
            inst.setId(9L);
            inst.setClaudeWorkingDir(instanceWorkDir.toString());
            inst.setShellWorkingDir("");
            when(instanceRepo.findById(9L)).thenReturn(Optional.of(inst));
        }
        return t;
    }

    /** 导入单条模板并断言落地，返回入库的 case（未设 id） */
    private EvalCase importLanded(EvalTask t, String templateKey) throws Exception {
        JsonObject r = service.importTemplates(t.getId(), List.of(templateKey));
        assertEquals(1, r.get("landed").getAsInt(), templateKey + " 变量齐全必须落地: " + r);
        ArgumentCaptor<EvalCase> captor = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseRepo).save(captor.capture());
        return captor.getValue();
    }

    /** 挂上 id 并真实试跑，返回落库结果 */
    private EvalCaseResult trial(EvalCase c, long caseId) throws Exception {
        c.setId(caseId);
        when(caseRepo.findById(caseId)).thenReturn(Optional.of(c));
        service.trial(1L, caseId);
        return waitForNthResult(1);
    }

    /** 路径 → 正斜杠形式（templateVars 归一后的形态；JSON 上下文变量里也必须用它） */
    static String fwd(Path p) {
        return p.toString().replace('\\', '/');
    }

    private EvalCaseResult waitForNthResult(int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            ArgumentCaptor<EvalCaseResult> captor = ArgumentCaptor.forClass(EvalCaseResult.class);
            try {
                verify(resultRepo, atLeast(n)).save(captor.capture());
                if (captor.getAllValues().size() >= n) return captor.getAllValues().get(n - 1);
            } catch (AssertionError ignore) { /* 还没到第 n 次 save */ }
            Thread.sleep(100);
        }
        return fail("等待第 " + n + " 条试跑结果超时（30s 内 resultRepo.save 不足 " + n + " 次）");
    }

    // ===== ① 绑定实例 → repoPath 自动填进 target；空 catch fixture 真实 FAIL =====

    @Test
    void boundInstanceWorkDirFillsRepoPathAndBadFixtureReallyFails() throws Exception {
        Path badRepo = tmp.resolve("bad-repo");
        Files.createDirectories(badRepo);
        Files.writeString(badRepo.resolve("Swallow.cs"), SWALLOW_CS, StandardCharsets.UTF_8);

        EvalCase c = importLanded(task(badRepo, ""), "static-no-empty-catch");

        // 防回归：target 必须包含扫描根参数（= 绑定实例工作目录，正斜杠归一）
        assertEquals("node \"" + scriptDir + "/check-no-empty-catch.js\" \"" + fwd(badRepo) + "\"",
                c.getTarget(), "扫描根目录必须作为参数写进 target");
        assertFalse(c.getTarget().contains("{{"), "不残留未展开占位符");

        EvalCaseResult r = trial(c, 61L);
        assertEquals(EvalRunner.FAIL, r.getVerdict(), "空 catch fixture 必须真跑 FAIL");
        assertEquals(1, r.getExitCode(), "脚本真实退出码 = 1");
        assertTrue(r.getOutputSummary().contains("Swallow.cs"),
                "失败输出必须定位到违规文件: " + r.getOutputSummary());
        assertEquals(EvalRunner.FAIL, c.getLastVerdict());
    }

    // ===== ② 干净 fixture 真实 PASS；显式 repoPath 优先于实例工作目录（进程 cwd ≠ 扫描根） =====

    @Test
    void explicitRepoPathWinsAndCleanFixtureReallyPasses() throws Exception {
        Path cleanRepo = tmp.resolve("clean-repo");
        Files.createDirectories(cleanRepo);
        Files.writeString(cleanRepo.resolve("Clean.cs"), CLEAN_CS, StandardCharsets.UTF_8);
        Path elsewhere = tmp.resolve("elsewhere");   // 实例工作目录：空目录、无任何 .cs（进程 cwd 指向这里）
        Files.createDirectories(elsewhere);

        EvalCase c = importLanded(task(elsewhere, "{\"repoPath\":\"" + fwd(cleanRepo) + "\"}"),
                "static-no-empty-catch");

        assertTrue(c.getTarget().endsWith("\"" + fwd(cleanRepo) + "\""),
                "显式 repoPath 优先进 target: " + c.getTarget());
        assertFalse(c.getTarget().contains(fwd(elsewhere)), "实例工作目录不得出现在 target 里");

        EvalCaseResult r = trial(c, 62L);
        assertEquals(EvalRunner.PASS, r.getVerdict(),
                "进程 cwd 是空目录，仍按 target 里的 repoPath 扫到干净 fixture → 必须 PASS（证明根参数真实生效，"
                        + "修复前这里会因 cwd 零 .cs 而 FAIL）: reason=" + r.getReason() + " out=" + r.getOutputSummary());
        assertEquals(0, r.getExitCode());
        assertTrue(r.getOutputSummary().contains("OK"), r.getOutputSummary());
    }

    // ===== ③ static-class-registered 同样带扫描根参数并真实跑通 =====

    @Test
    void classRegisteredTargetCarriesScanRootAndReallyPasses() throws Exception {
        Path repo = tmp.resolve("supplier-repo");
        Files.createDirectories(repo.resolve("impl"));
        Files.createDirectories(repo.resolve("factory"));
        Files.writeString(repo.resolve("impl/DorakSupplierService.cs"),
                "namespace Supplier.Dorak { public class DorakSupplierService : ISupplierService { } }",
                StandardCharsets.UTF_8);
        Files.writeString(repo.resolve("factory/SupplierFactory.cs"),
                "public static class SupplierFactory { static object Get(int id) => new Dorak.DorakSupplierService(); }",
                StandardCharsets.UTF_8);

        EvalCase c = importLanded(task(repo, "{\"supplierClassHint\":\"DorakSupplierService\"}"),
                "static-class-registered");
        assertEquals("node \"" + scriptDir + "/check-class-registered.js\" \"DorakSupplierService\" \""
                        + fwd(repo) + "\"",
                c.getTarget(), "类名与扫描根都必须作为参数写进 target");

        EvalCaseResult r = trial(c, 63L);
        assertEquals(EvalRunner.PASS, r.getVerdict(),
                "有定义 + 被工厂引用 → 真实 PASS: reason=" + r.getReason() + " out=" + r.getOutputSummary());
        assertEquals(0, r.getExitCode());
    }

    // ===== ④ 未绑定实例 → 两条静态模板都落「未落地」，不进分母，notes 写清原因 =====

    @Test
    void unboundInstanceLandsStaticTemplatesNotLandedOutOfDenominator() throws Exception {
        // 就算其他变量填齐，缺扫描根也不能落地（更不许悄悄用后端 cwd 顶替）
        EvalTask t = task(null, "{\"supplierClassHint\":\"DorakSupplierService\"}");

        JsonObject r = service.importTemplates(t.getId(),
                List.of("static-no-empty-catch", "static-class-registered"));
        assertEquals(2, r.get("imported").getAsInt());
        assertEquals(0, r.get("landed").getAsInt());
        assertEquals(2, r.get("notLanded").getAsInt());

        ArgumentCaptor<EvalCase> captor = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseRepo, times(2)).save(captor.capture());
        for (EvalCase c : captor.getAllValues()) {
            assertEquals("", c.getTarget(), "未绑定实例 → 未落地（target 空），绝不退化成后端 cwd");
            assertFalse(EvalRunner.isLanded(c.getTarget()));
            assertTrue(c.getNotes().contains("repoPath"), "notes 写清缺的变量: " + c.getNotes());
            assertTrue(c.getNotes().contains("未绑定实例或实例无工作目录"),
                    "notes 必须写清未落地原因: " + c.getNotes());
        }
        var s = EvalLoopService.computeStats(captor.getAllValues());
        assertEquals(2, s.notLanded(), "未落地单列");
        assertEquals(0, s.executed(), "未落地绝不混进分母");
        assertEquals(0, s.landed());

        // 模板面板同步标注缺 repoPath（页面据此提示）
        JsonObject panel = service.templatesJson(1L);
        for (var el : panel.getAsJsonArray("templates")) {
            JsonObject to = el.getAsJsonObject();
            String key = to.get("key").getAsString();
            if (key.equals("static-no-empty-catch") || key.equals("static-class-registered")) {
                List<String> missing = new java.util.ArrayList<>();
                to.getAsJsonArray("missingVariables").forEach(m -> missing.add(m.getAsString()));
                assertTrue(missing.contains("repoPath"), key + " 面板必须标注缺 repoPath: " + missing);
            }
        }
    }

    // ===== ⑤ 绑定了实例但实例没有任何工作目录 → 同样未落地（同 ④ 口径） =====

    @Test
    void boundInstanceWithoutWorkDirAlsoLandsNotLanded() throws Exception {
        EvalTask t = new EvalTask();
        t.setId(1L);
        t.setName("blank-dir");
        t.setInstanceId(9L);
        t.setContextJson("");
        when(taskRepo.findById(1L)).thenReturn(Optional.of(t));
        when(caseRepo.findByTaskIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());
        BotInstance inst = new BotInstance();
        inst.setId(9L);
        inst.setClaudeWorkingDir("");
        inst.setShellWorkingDir("");
        when(instanceRepo.findById(9L)).thenReturn(Optional.of(inst));

        JsonObject r = service.importTemplates(1L, List.of("static-no-empty-catch"));
        assertEquals(0, r.get("landed").getAsInt());
        assertEquals(1, r.get("notLanded").getAsInt());
        ArgumentCaptor<EvalCase> captor = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseRepo).save(captor.capture());
        assertEquals("", captor.getValue().getTarget());
        assertTrue(captor.getValue().getNotes().contains("未绑定实例或实例无工作目录"),
                captor.getValue().getNotes());
    }
}
