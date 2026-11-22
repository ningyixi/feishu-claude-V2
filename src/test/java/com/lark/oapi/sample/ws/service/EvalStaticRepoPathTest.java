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
 * 静态/构建模板「扫描根拆分（repoPath vs supplierDir vs slnPath）」回归测试
 *（11-21-supplier-eval-loop MVP-1.5 缺陷修复 + MVP-1.6 拆根目录缺陷，AC18′/AC19′/AC20′）。
 * <p>
 * MVP-1.5 缺陷：静态模板 target 没传扫描根 → 退化成扫后端进程 cwd，恒红且红的理由错误。
 * MVP-1.6 缺陷（用户真任务 id=2 实测）：两条静态 eval 共用一个 repoPath——收窄到供应商目录后
 * class-registered 假红（注册点天生在供应商目录之外）；不收窄则 no-empty-catch 把全仓历史债算到本次交付。
 * <p>
 * 修复语义（本测试钉死）：
 * ① no-empty-catch 根 = supplierDir（供应商目录）；class-registered 双根 = 定义 supplierDir + 引用 repoPath 全仓；
 * ② supplierDir 缺省按 repoPath/src/Business/Supplier/&lt;supplierCode&gt; 探测，唯一命中才自动填，
 *    0 个或多个 → 未落地并写明歧义，绝不瞎猜、绝不回退仓库根（AC19′）；
 * ③ build-dotnet target 动态解析 repoPath 下唯一 .sln；0 个或多个 → 未落地（AC20′）；
 * ④ 未绑定实例且未显式填 repoPath → 一律未落地，绝不退化成后端 cwd。
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
        resultIds.set(100);
        when(resultRepo.save(any())).thenAnswer(inv -> {
            EvalCaseResult r = inv.getArgument(0);
            if (r.getId() == null) r.setId(resultIds.incrementAndGet());
            return r;
        });
        Path f = Path.of(EvalTemplates.DEV_FILE).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(f), "模板库文件必须存在（测试在仓库根运行）: " + f);
        scriptDir = EvalTemplates.resolveScriptDir("");
        service = new EvalLoopService(taskRepo, caseRepo, resultRepo, instanceRepo,
                tmp.resolve("eval-logs").toString(), f.toString(), scriptDir);
    }

    /** 结果行 id 发号器（真实环境由 JPA 生成；mock 下 save 时补 id） */
    private final java.util.concurrent.atomic.AtomicLong resultIds = new java.util.concurrent.atomic.AtomicLong(100);

    // ===== 辅助 =====

    /** 任务 id=1，绑定实例 id=9（claudeWorkingDir = instanceWorkDir），带上下文变量与供应商代码 */
    private EvalTask task(Path instanceWorkDir, String contextJson) {
        return task(instanceWorkDir, contextJson, "");
    }

    private EvalTask task(Path instanceWorkDir, String contextJson, String supplierCode) {
        EvalTask t = new EvalTask();
        t.setId(1L);
        t.setName("repo-path-test");
        t.setSupplierCode(supplierCode);
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

    /** 导入 n 条模板并断言全部落地，返回入库的 case 列表（未设 id） */
    private List<EvalCase> importAllLanded(EvalTask t, String... templateKeys) throws Exception {
        JsonObject r = service.importTemplates(t.getId(), List.of(templateKeys));
        assertEquals(templateKeys.length, r.get("landed").getAsInt(),
                String.join(",", templateKeys) + " 变量齐全必须落地: " + r);
        ArgumentCaptor<EvalCase> captor = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseRepo, times(templateKeys.length)).save(captor.capture());
        return captor.getAllValues();
    }

    /** 导入单条模板并断言落地，返回入库的 case（未设 id） */
    private EvalCase importLanded(EvalTask t, String templateKey) throws Exception {
        return importAllLanded(t, templateKey).get(0);
    }

    /** 挂上 id 并真实试跑：按本次 resultId 精确等待结果（绝不读"列表第一条"） */
    private EvalCaseResult trial(EvalCase c, long caseId) throws Exception {
        c.setId(caseId);
        when(caseRepo.findById(caseId)).thenReturn(Optional.of(c));
        JsonObject resp = service.trial(1L, caseId);
        assertFalse(resp.get("resultId").isJsonNull(), "trial 必须返回本次 resultId: " + resp);
        return awaitResult(resp.get("resultId").getAsLong());
    }

    /** 路径 → 正斜杠形式（templateVars 归一后的形态；JSON 上下文变量里也必须用它） */
    static String fwd(Path p) {
        return p.toString().replace('\\', '/');
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

    /** 复刻真仓结构：repo/src/Business/Supplier/<name>/（可放 .cs），返回供应商目录 */
    Path supplierDir(Path repo, String name) throws Exception {
        Path d = repo.resolve("src/Business/Supplier/" + name);
        Files.createDirectories(d);
        return d;
    }

    // ===== ① 绑定实例 → repoPath 自动取实例工作目录，supplierDir 按约定探测；空 catch fixture 真实 FAIL =====

    @Test
    void boundInstanceWorkDirFillsRootsAndBadFixtureReallyFails() throws Exception {
        Path badRepo = tmp.resolve("bad-repo");
        Path sup = supplierDir(badRepo, "TestSup");
        Files.writeString(sup.resolve("Swallow.cs"), SWALLOW_CS, StandardCharsets.UTF_8);

        // supplierCode 命中唯一目录 → supplierDir 自动填（唯一命中才自动填）
        EvalCase c = importLanded(task(badRepo, "", "TestSup"), "static-no-empty-catch");

        // 防回归（AC18′ 前半）：no-empty-catch 的根必须是【供应商目录】，不是仓库根
        assertEquals("node \"" + scriptDir + "/check-no-empty-catch.js\" \"" + fwd(sup) + "\"",
                c.getTarget(), "扫描根必须 = supplierDir（供应商目录）并作为参数写进 target");
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
        Path sup = supplierDir(cleanRepo, "OnlySup");   // 唯一候选 → supplierCode 留空也自动命中
        Files.writeString(sup.resolve("Clean.cs"), CLEAN_CS, StandardCharsets.UTF_8);
        Path elsewhere = tmp.resolve("elsewhere");   // 实例工作目录：空目录、无任何 .cs（进程 cwd 指向这里）
        Files.createDirectories(elsewhere);

        EvalCase c = importLanded(task(elsewhere, "{\"repoPath\":\"" + fwd(cleanRepo) + "\"}"),
                "static-no-empty-catch");

        assertEquals("node \"" + scriptDir + "/check-no-empty-catch.js\" \"" + fwd(sup) + "\"",
                c.getTarget(), "显式 repoPath 探测出的 supplierDir 进 target");
        assertFalse(c.getTarget().contains(fwd(elsewhere)), "实例工作目录不得出现在 target 里");

        EvalCaseResult r = trial(c, 62L);
        assertEquals(EvalRunner.PASS, r.getVerdict(),
                "进程 cwd 是空目录，仍按 target 里的 supplierDir 扫到干净 fixture → 必须 PASS: reason="
                        + r.getReason() + " out=" + r.getOutputSummary());
        assertEquals(0, r.getExitCode());
        assertTrue(r.getOutputSummary().contains("OK"), r.getOutputSummary());
    }

    // ===== ③ AC18′ 核心：同一任务下两条静态 eval 各用各的根，注册点在供应商目录外不再假红 =====

    @Test
    void sameTaskStaticPairUsesCorrectRootsAndClassRegisteredNoLongerFalseRed() throws Exception {
        Path repo = tmp.resolve("supplier-repo");
        Path sup = supplierDir(repo, "Dorak");
        Files.createDirectories(repo.resolve("factory"));
        Files.writeString(sup.resolve("DorakSupplierService.cs"),
                "namespace Supplier.Dorak { public class DorakSupplierService : ISupplierService { } }",
                StandardCharsets.UTF_8);
        // 注册点（工厂）在供应商目录之外——现网 id=2 case41 假红的根因场景
        Files.writeString(repo.resolve("factory/SupplierFactory.cs"),
                "public static class SupplierFactory { static object Get(int id) => new Dorak.DorakSupplierService(); }",
                StandardCharsets.UTF_8);

        EvalTask t = task(repo, "{\"supplierClassHint\":\"DorakSupplierService\"}", "Dorak");
        List<EvalCase> cs = importAllLanded(t, "static-no-empty-catch", "static-class-registered");
        EvalCase noCatch = cs.get(0);
        EvalCase registered = cs.get(1);

        // AC18′：两者的 target 分别含各自正确的根参数（精确等值断言）
        assertEquals("node \"" + scriptDir + "/check-no-empty-catch.js\" \"" + fwd(sup) + "\"",
                noCatch.getTarget(), "no-empty-catch 根 = 供应商目录");
        assertEquals("node \"" + scriptDir + "/check-class-registered.js\" \"DorakSupplierService\" \""
                        + fwd(sup) + "\" \"" + fwd(repo) + "\"",
                registered.getTarget(), "class-registered 双根 = 定义 supplierDir + 引用 repoPath 全仓");

        // 真实执行：定义在供应商目录、引用只在仓库根 → 必须 PASS（修复前单根扫描在此假红「只有定义」）
        EvalCaseResult r2 = trial(registered, 63L);
        assertEquals(EvalRunner.PASS, r2.getVerdict(),
                "注册点在供应商目录外也必须 PASS（假红回归）: reason=" + r2.getReason()
                        + " out=" + r2.getOutputSummary());
        assertEquals(0, r2.getExitCode());
        assertTrue(r2.getOutputSummary().contains("代理判据"), "输出必须声明代理判据性质: " + r2.getOutputSummary());

        EvalCaseResult r1 = trial(noCatch, 64L);
        assertEquals(EvalRunner.PASS, r1.getVerdict(),
                "供应商目录内代码干净 → PASS（根收窄不误伤）: reason=" + r1.getReason() + " out=" + r1.getOutputSummary());
        assertEquals(0, r1.getExitCode());
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
            assertTrue(c.getNotes().contains("supplierDir"), "notes 写清缺的变量: " + c.getNotes());
            assertTrue(c.getNotes().contains("未绑定实例或实例无工作目录"),
                    "notes 必须写清未落地原因: " + c.getNotes());
        }
        assertTrue(captor.getAllValues().get(1).getNotes().contains("repoPath"),
                "class-registered 还缺 repoPath（全仓引用检索根）: " + captor.getAllValues().get(1).getNotes());
        var s = EvalLoopService.computeStats(captor.getAllValues());
        assertEquals(2, s.notLanded(), "未落地单列");
        assertEquals(0, s.executed(), "未落地绝不混进分母");
        assertEquals(0, s.landed());

        // 模板面板同步标注缺的变量（页面据此提示；不硬编码变量名）
        JsonObject panel = service.templatesJson(1L);
        for (var el : panel.getAsJsonArray("templates")) {
            JsonObject to = el.getAsJsonObject();
            String key = to.get("key").getAsString();
            List<String> missing = new java.util.ArrayList<>();
            to.getAsJsonArray("missingVariables").forEach(m -> missing.add(m.getAsString()));
            if (key.equals("static-no-empty-catch")) {
                assertEquals(List.of("supplierDir"), missing, "面板缺变量标注跟随新变量");
            } else if (key.equals("static-class-registered")) {
                assertEquals(List.of("supplierDir", "repoPath"), missing,
                        "supplierClassHint 已填，仍缺双根: " + missing);
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

    // ===== ⑥ AC19′：supplierDir 探测不到唯一目录（0 个或多个）→ 未落地 + notes 写明歧义 =====

    @Test
    void ambiguousOrMissingSupplierDirLandsNotLandedWithReason() throws Exception {
        // 多个候选：src/Business/Supplier/ 下两个目录、supplierCode 留空 → 歧义，绝不瞎猜
        Path repo = tmp.resolve("multi-repo");
        Files.writeString(supplierDir(repo, "Alpha").resolve("A.cs"), CLEAN_CS, StandardCharsets.UTF_8);
        Files.writeString(supplierDir(repo, "Beta").resolve("B.cs"), CLEAN_CS, StandardCharsets.UTF_8);

        EvalTask t = task(null, "{\"repoPath\":\"" + fwd(repo) + "\"}");
        JsonObject r = service.importTemplates(1L, List.of("static-no-empty-catch"));
        assertEquals(0, r.get("landed").getAsInt());
        assertEquals(1, r.get("notLanded").getAsInt());
        ArgumentCaptor<EvalCase> captor = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseRepo).save(captor.capture());
        EvalCase c = captor.getValue();
        assertEquals("", c.getTarget(), "歧义 → 未落地，绝不瞎猜、绝不回退仓库根");
        assertTrue(c.getNotes().contains("歧义"), "notes 写明歧义: " + c.getNotes());
        assertTrue(c.getNotes().contains("Alpha") && c.getNotes().contains("Beta"),
                "notes 列出全部候选: " + c.getNotes());
        var s = EvalLoopService.computeStats(List.of(c));
        assertEquals(0, s.executed(), "未落地不进分母");
        assertEquals(1, s.notLanded());

        // 0 个候选：supplierCode 不匹配任何目录
        reset(caseRepo);
        when(caseRepo.findByTaskIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());
        EvalTask t2 = task(null, "{\"repoPath\":\"" + fwd(repo) + "\"}", "Gamma");
        when(taskRepo.findById(1L)).thenReturn(Optional.of(t2));
        JsonObject r2 = service.importTemplates(1L, List.of("static-no-empty-catch"));
        assertEquals(0, r2.get("landed").getAsInt());
        ArgumentCaptor<EvalCase> captor2 = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseRepo).save(captor2.capture());
        assertTrue(captor2.getValue().getNotes().contains("0 个候选"),
                "notes 写明 0 候选: " + captor2.getValue().getNotes());
        assertTrue(captor2.getValue().getNotes().contains("Gamma"), captor2.getValue().getNotes());

        // 显式 supplierDir 优先于探测（歧义也能被显式值化解）
        reset(caseRepo);
        when(caseRepo.findByTaskIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());
        EvalTask t3 = task(null, "{\"repoPath\":\"" + fwd(repo) + "\",\"supplierDir\":\""
                + fwd(repo.resolve("src/Business/Supplier/Beta")) + "\"}");
        when(taskRepo.findById(1L)).thenReturn(Optional.of(t3));
        EvalCase c3 = importLanded(t3, "static-no-empty-catch");
        assertEquals("node \"" + scriptDir + "/check-no-empty-catch.js\" \""
                + fwd(repo.resolve("src/Business/Supplier/Beta")) + "\"", c3.getTarget());
    }

    // ===== ⑦ AC20′：build-dotnet target 解析 repoPath 下唯一 .sln；0/多个 → 未落地 =====

    @Test
    void buildTemplateResolvesUniqueSlnOrLandsNotLanded() throws Exception {
        // 唯一 .sln（复刻真仓 D:/dida/SupplierV2 → Dida.Supplier.Core.sln 的结构）
        Path repo = tmp.resolve("sln-repo");
        Files.createDirectories(repo);
        Files.writeString(repo.resolve("Fake.Supplier.Core.sln"), "Microsoft Visual Studio Solution File\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(repo.resolve("obj"));   // bin/obj 里的 .sln 不参与解析
        Files.writeString(repo.resolve("obj/Ignored.sln"), "x", StandardCharsets.UTF_8);

        EvalCase c = importLanded(task(null, "{\"repoPath\":\"" + fwd(repo) + "\"}"), "build-dotnet");
        assertEquals("dotnet build \"" + fwd(repo.resolve("Fake.Supplier.Core.sln")) + "\" --nologo",
                c.getTarget(), "target 必须带真实 .sln 路径（旧模板不带 sln，任何 cwd 下必失败）");
        assertEquals("exit=0", c.getExpect());
        assertTrue(c.getNotes().contains("未真实验证"), "verified=false 的「未真实验证」语义保留: " + c.getNotes());

        // 多个 .sln → 歧义，未落地（绝不跑一条赌运气的命令）
        Path repo2 = tmp.resolve("multi-sln");
        Files.createDirectories(repo2.resolve("sub"));
        Files.writeString(repo2.resolve("A.sln"), "x", StandardCharsets.UTF_8);
        Files.writeString(repo2.resolve("sub/B.sln"), "x", StandardCharsets.UTF_8);
        reset(caseRepo);
        when(caseRepo.findByTaskIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());
        EvalTask t2 = task(null, "{\"repoPath\":\"" + fwd(repo2) + "\"}");
        when(taskRepo.findById(1L)).thenReturn(Optional.of(t2));
        JsonObject r2 = service.importTemplates(1L, List.of("build-dotnet"));
        assertEquals(0, r2.get("landed").getAsInt());
        ArgumentCaptor<EvalCase> cap2 = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseRepo).save(cap2.capture());
        assertEquals("", cap2.getValue().getTarget(), "多个 .sln → 未落地");
        assertTrue(cap2.getValue().getNotes().contains("歧义"), cap2.getValue().getNotes());
        assertTrue(cap2.getValue().getNotes().contains("A.sln") && cap2.getValue().getNotes().contains("sub/B.sln"),
                "notes 列出全部候选: " + cap2.getValue().getNotes());

        // 没有 .sln → 未落地而非"跑一条必失败的命令"
        Path repo3 = tmp.resolve("no-sln");
        Files.createDirectories(repo3);
        reset(caseRepo);
        when(caseRepo.findByTaskIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());
        EvalTask t3 = task(null, "{\"repoPath\":\"" + fwd(repo3) + "\"}");
        when(taskRepo.findById(1L)).thenReturn(Optional.of(t3));
        JsonObject r3 = service.importTemplates(1L, List.of("build-dotnet"));
        assertEquals(0, r3.get("landed").getAsInt());
        ArgumentCaptor<EvalCase> cap3 = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseRepo).save(cap3.capture());
        assertEquals("", cap3.getValue().getTarget());
        assertTrue(cap3.getValue().getNotes().contains("没找到任何 .sln"), cap3.getValue().getNotes());

        // 显式 slnPath 优先于解析（歧义也能被显式值化解）
        reset(caseRepo);
        when(caseRepo.findByTaskIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());
        EvalTask t4 = task(null, "{\"slnPath\":\"" + fwd(repo2.resolve("A.sln")) + "\"}");
        when(taskRepo.findById(1L)).thenReturn(Optional.of(t4));
        EvalCase c4 = importLanded(t4, "build-dotnet");
        assertEquals("dotnet build \"" + fwd(repo2.resolve("A.sln")) + "\" --nologo", c4.getTarget());
    }
}
