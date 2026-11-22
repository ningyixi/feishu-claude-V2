package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.model.entity.EvalCase;
import com.lark.oapi.sample.ws.model.entity.EvalCaseResult;
import com.lark.oapi.sample.ws.model.entity.EvalTask;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.EvalCaseRepository;
import com.lark.oapi.sample.ws.repository.EvalCaseResultRepository;
import com.lark.oapi.sample.ws.repository.EvalTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 「导入通用模板」服务层单测（11-21-supplier-eval-loop MVP-1.5）。
 * <p>
 * AC14′：变量没填齐 → 落成「未落地」（target 空，与 {@link EvalRunner#isLanded} 同一口径），
 * notes 写清缺哪个变量，stats.notLanded 计入、executed（分母）不含它；
 * AC15′：requiresSandbox 模板导入后无凭据试跑 → SKIPPED 且不起进程（退出码 null、耗时 0，MVP-1 语义不回归）；
 * 导入本身不做任何判定（lastVerdict 一律 null——verdict 只能来自真实执行）。
 */
class EvalTemplateImportTest {

    @TempDir
    Path tmp;

    private EvalTaskRepository taskRepo;
    private EvalCaseRepository caseRepo;
    private EvalCaseResultRepository resultRepo;
    private EvalLoopService service;
    private String templatesFile;
    private String scriptDir;

    @BeforeEach
    void setUp() {
        taskRepo = mock(EvalTaskRepository.class);
        caseRepo = mock(EvalCaseRepository.class);
        resultRepo = mock(EvalCaseResultRepository.class);
        BotInstanceRepository instanceRepo = mock(BotInstanceRepository.class);
        Path f = Path.of(EvalTemplates.DEV_FILE).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(f), "模板库文件必须存在（测试在仓库根运行）: " + f);
        templatesFile = f.toString();
        scriptDir = EvalTemplates.resolveScriptDir("");
        service = new EvalLoopService(taskRepo, caseRepo, resultRepo, instanceRepo,
                tmp.resolve("eval-logs").toString(), templatesFile, scriptDir);
    }

    private EvalTask task(String contextJson) {
        EvalTask t = new EvalTask();
        t.setId(1L);
        t.setName("tpl-test");
        t.setContextJson(contextJson);
        when(taskRepo.findById(1L)).thenReturn(Optional.of(t));
        when(caseRepo.findByTaskIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());
        return t;
    }

    // ===== 变量填齐 → 落地为可执行 eval（展开后的绝对命令 + 干净的 expect） =====

    @Test
    void importLandsExecutableCaseWhenVariablesComplete() throws Exception {
        task("{\"configJsonPath\":\"D:/cfg/SS.Test.Config.json\"}");

        JsonObject r = service.importTemplates(1L, List.of("config-shape"));
        assertEquals(1, r.get("imported").getAsInt());
        assertEquals(1, r.get("landed").getAsInt());
        assertEquals(0, r.get("notLanded").getAsInt());

        ArgumentCaptor<EvalCase> captor = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseRepo).save(captor.capture());
        EvalCase c = captor.getValue();
        assertEquals("node \"" + scriptDir + "/check-config-shape.js\" \"D:/cfg/SS.Test.Config.json\"",
                c.getTarget(), "{{evalScriptDir}}/{{configJsonPath}} 均已展开为可执行命令");
        assertFalse(c.getTarget().contains("{{"), "不残留未展开占位符");
        assertEquals("exit=0\nstdout=OK", c.getExpect());
        assertEquals("script", c.getKind());
        assertTrue(EvalRunner.isLanded(c.getTarget()), "落地口径复用 EvalRunner.isLanded");
        assertTrue(c.isEnabled());
        assertFalse(c.isRequiresSandbox());
        assertNull(c.getLastVerdict(), "导入不带任何判定——verdict 只能来自真实执行");
        assertTrue(c.getNotes().contains("内置通用模板 config-shape"), "notes 标来源可追溯");
    }

    // ===== AC14′：变量缺失 → 未落地 + 写清缺哪个变量 + 不进分母 =====

    @Test
    void importWithoutVariablesLandsNotLandedAndStaysOutOfDenominator() throws Exception {
        // 静态模板的扫描根（supplierDir，MVP-1.6 拆分后）显式给了；config-shape 仍缺 configJsonPath
        Path sup = tmp.resolve("sup-dir");
        java.nio.file.Files.createDirectories(sup);
        String supFwd = sup.toString().replace('\\', '/');
        task("{\"supplierDir\":\"" + supFwd + "\"}");

        JsonObject r = service.importTemplates(1L, List.of("config-shape", "static-no-empty-catch"));
        assertEquals(2, r.get("imported").getAsInt());
        assertEquals(1, r.get("landed").getAsInt(), "变量填齐的模板照常落地");
        assertEquals(1, r.get("notLanded").getAsInt());

        ArgumentCaptor<EvalCase> captor = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseRepo, times(2)).save(captor.capture());
        EvalCase missing = captor.getAllValues().get(0);
        EvalCase ready = captor.getAllValues().get(1);

        assertEquals("", missing.getTarget(), "缺变量 → target 空 = 未落地（不可执行）");
        assertFalse(EvalRunner.isLanded(missing.getTarget()));
        assertTrue(missing.getNotes().contains("configJsonPath"),
                "notes 必须写清缺哪个变量: " + missing.getNotes());
        assertTrue(missing.getNotes().contains("缺少上下文变量"), missing.getNotes());
        assertNull(missing.getLastVerdict());
        assertEquals("node \"" + scriptDir + "/check-no-empty-catch.js\" \"" + supFwd + "\"",
                ready.getTarget(), "显式 supplierDir 展开进 target 作扫描根参数");

        // stats 口径：未落地计入 notLanded，分母 executed 不含它（复用 MVP-1 computeStats，不另造）
        var s = EvalLoopService.computeStats(List.of(missing, ready));
        assertEquals(1, s.notLanded());
        assertEquals(1, s.landed());
        assertEquals(0, s.executed(), "未落地绝不混进分母");
        assertEquals(1, s.notRun(), "落地未跑的也单列，不进分母");
    }

    // ===== AC15′：requiresSandbox 模板无凭据 → SKIPPED 且不起进程（MVP-1 语义不回归） =====

    @Test
    void importedSandboxTemplateSkipsWithoutCredentialsAndNeverStartsProcess() throws Exception {
        task("{\"baseUrl\":\"https://gw.example.com\"}");

        service.importTemplates(1L, List.of("http-basic-connectivity"));
        ArgumentCaptor<EvalCase> captor = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseRepo).save(captor.capture());
        EvalCase c = captor.getValue();
        assertTrue(c.isRequiresSandbox(), "HTTP 模板必须带 requiresSandbox");
        assertTrue(EvalRunner.isLanded(c.getTarget()));

        c.setId(50L);
        when(caseRepo.findById(50L)).thenReturn(Optional.of(c));
        service.sandboxCreds = () -> false;   // 无凭据

        service.trial(1L, 50L);

        ArgumentCaptor<EvalCaseResult> saved = ArgumentCaptor.forClass(EvalCaseResult.class);
        verify(resultRepo).save(saved.capture());
        assertEquals(EvalRunner.SKIPPED, saved.getValue().getVerdict(), "无凭据 → SKIPPED，单列不进分母");
        assertNull(saved.getValue().getExitCode(), "没有退出码 = 没起进程");
        assertEquals(0L, saved.getValue().getElapsedMs());
        assertTrue(saved.getValue().getReason().contains(EvalLoopService.SANDBOX_CRED_ENV));
        assertEquals(EvalRunner.SKIPPED, c.getLastVerdict());

        var s = EvalLoopService.computeStats(List.of(c));
        assertEquals(0, s.executed(), "SKIPPED 不进分母");
        assertEquals(1, s.skipped());
    }

    // ===== 模板 key 校验 / 去重 =====

    @Test
    void importRejectsUnknownKeyAndDedupes() throws Exception {
        task("");
        var e = assertThrows(IllegalArgumentException.class,
                () -> service.importTemplates(1L, List.of("no-such-template")));
        assertTrue(e.getMessage().contains("未知的模板 key"), e.getMessage());
        verify(caseRepo, never()).save(any());

        assertThrows(IllegalArgumentException.class, () -> service.importTemplates(1L, List.of()));
    }

    // ===== 模板列表接口素材：按任务上下文标注缺失变量 + 全局警示 =====

    @Test
    void templatesJsonReportsMissingVariablesPerTemplate() throws Exception {
        task("{\"configJsonPath\":\"D:/cfg.json\"}");

        JsonObject o = service.templatesJson(1L);
        assertEquals(scriptDir, o.get("scriptDir").getAsString());
        assertTrue(o.get("httpEvidenceWarning").getAsString().contains("Postman"),
                "面板必须带 curl ≠ Postman 证据警示");
        // 变量清单来自文件且带当前值（页面据此渲染输入框）
        var vars = o.getAsJsonArray("variables");
        assertTrue(vars.size() >= 6);
        boolean foundCfg = false;
        for (var el : vars) {
            JsonObject v = el.getAsJsonObject();
            if (v.get("name").getAsString().equals("configJsonPath")) {
                foundCfg = true;
                assertEquals("D:/cfg.json", v.get("value").getAsString());
            }
        }
        assertTrue(foundCfg);
        // 每条模板带 missingVariables
        for (var el : o.getAsJsonArray("templates")) {
            JsonObject t = el.getAsJsonObject();
            String key = t.get("key").getAsString();
            List<String> missing = new java.util.ArrayList<>();
            t.getAsJsonArray("missingVariables").forEach(m -> missing.add(m.getAsString()));
            switch (key) {
                case "config-shape", "config-urls-https" -> assertTrue(missing.isEmpty(),
                        key + " 的变量已填，不该缺");
                case "e2e-supplier-unit-test" -> assertEquals(List.of("accountId", "testProjectPath", "supplierClassHint"),
                        missing, "端到端模板三个变量全缺（缺省顺序 = target 里出现顺序，accountId 必填在最前）");
                case "http-basic-connectivity" -> assertEquals(List.of("baseUrl"), missing);
                case "has-unit-test" -> assertEquals(List.of("supplierClassHint", "supplierDir"), missing);
                case "static-no-empty-catch" -> assertEquals(List.of("supplierDir"), missing,
                        "未绑定实例且探测不出 supplierDir → 缺供应商目录扫描根（绝不退化成后端进程 cwd/仓库根）");
                case "static-class-registered" -> assertEquals(List.of("supplierClassHint", "supplierDir", "repoPath"), missing);
                case "build-dotnet" -> assertEquals(List.of("slnPath"), missing,
                        "无 repoPath → 解析不出唯一 .sln → 缺 slnPath（不跑必失败命令）");
                default -> { }
            }
        }
    }

    // ===== MVP-1.7 追加①：accountId 必填 + EVAL_ACCOUNT_ID 契约 + 下拉候选不回显凭据 =====

    @Test
    void e2eTemplateLandsWithAccountIdEnvVarContract() throws Exception {
        Path csproj = tmp.resolve("Supplier.DCYiLv.Tests.csproj");
        Files.writeString(csproj, "<Project><ItemGroup><PackageReference Include=\"xunit\" /></ItemGroup></Project>");
        task("{\"accountId\":\"2996\",\"supplierClassHint\":\"DCYiLvBusiness\",\"testProjectPath\":\""
                + csproj.toString().replace('\\', '/') + "\"}");

        JsonObject r = service.importTemplates(1L, List.of("e2e-supplier-unit-test"));
        assertEquals(1, r.get("landed").getAsInt());
        ArgumentCaptor<EvalCase> captor = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseRepo).save(captor.capture());
        EvalCase c = captor.getValue();
        // target 构造精确等值：账号经环境变量 EVAL_ACCOUNT_ID 传入（cmd 形态，&& 前不留空格）
        assertEquals("set EVAL_ACCOUNT_ID=2996&& dotnet test \"" + csproj.toString().replace('\\', '/')
                + "\" --filter \"FullyQualifiedName~DCYiLvBusiness\" --nologo", c.getTarget());
        assertEquals("test", c.getKind());
        assertEquals("exit=0", c.getExpect());
        assertTrue(c.isCountsTowardScore(), "端到端是计分项（探针才不计分）");
        assertFalse(c.isRequiresSandbox(), "dotnet test 在本机跑，不依赖 EVAL_SANDBOX_CREDENTIALS");
        assertEquals(900, c.getTimeoutSec());
        assertTrue(c.getNotes().contains("未真实验证"),
                "verified=false 的模板导入时必须带未验证警示: " + c.getNotes());
    }

    @Test
    void e2eTemplateWithoutAccountIdLandsNotLandedWithPointedNotes() throws Exception {
        Path csproj = tmp.resolve("T.csproj");
        Files.writeString(csproj, "<Project><ItemGroup><PackageReference Include=\"nunit\" /></ItemGroup></Project>");
        task("{\"supplierClassHint\":\"DCYiLvBusiness\",\"testProjectPath\":\""
                + csproj.toString().replace('\\', '/') + "\"}");

        JsonObject r = service.importTemplates(1L, List.of("e2e-supplier-unit-test"));
        assertEquals(0, r.get("landed").getAsInt());
        assertEquals(1, r.get("notLanded").getAsInt(), "accountId 必填：未填 → 未落地，不进分母");
        ArgumentCaptor<EvalCase> captor = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseRepo).save(captor.capture());
        EvalCase c = captor.getValue();
        assertEquals("", c.getTarget());
        assertTrue(c.getNotes().contains("缺 accountId：请选择用哪个供应商账号跑本次测评"),
                "notes 必须点名缺 accountId 并说人话: " + c.getNotes());
        assertTrue(c.getNotes().contains("EVAL_ACCOUNT_ID"), "notes 写明契约传递方式: " + c.getNotes());
        var s = EvalLoopService.computeStats(List.of(c));
        assertEquals(1, s.notLanded());
        assertEquals(0, s.executed());
    }

    @Test
    void accountOptionsEnumerateFromConfigWithoutLeakingCredentials() throws Exception {
        // 配置副本带占位符凭据（与真实 SS.DCYiLv.Config 同构：双账号 2996/3004）
        Path cfg = tmp.resolve("SS.Test.Config.json");
        Files.writeString(cfg, """
                { "SupplierID": 2226,
                  "AccountDic": {
                    "3004": { "SupplierAccountID": 3004, "AccountName": "onRequest",
                              "appid": "<APPID>", "secret": "TOP-SECRET-VALUE", "customerCode": "<CC>",
                              "paymentType": 1, "ConfirmMode": "ONREQUEST" },
                    "2996": { "SupplierAccountID": 2996, "AccountName": "instantConfirm",
                              "appid": "<APPID>", "secret": "TOP-SECRET-VALUE", "customerCode": "<CC>",
                              "paymentType": 1, "ConfirmMode": "INSTANT" }
                  } }
                """);
        task("{\"configJsonPath\":\"" + cfg.toString().replace('\\', '/') + "\"}");

        JsonObject o = service.templatesJson(1L);
        JsonObject acc = o.getAsJsonObject("accountOptions");
        var accounts = acc.getAsJsonArray("accounts");
        assertEquals(2, accounts.size());
        assertEquals("2996", accounts.get(0).getAsJsonObject().get("id").getAsString(), "候选按账号 ID 排序");
        assertEquals("instantConfirm", accounts.get(0).getAsJsonObject().get("accountName").getAsString());
        assertEquals("INSTANT", accounts.get(0).getAsJsonObject().get("confirmMode").getAsString());
        assertEquals("3004", accounts.get(1).getAsJsonObject().get("id").getAsString());
        // 安全纪律：绝不回显任何凭据值（连字段名都不出现）
        String accJson = acc.toString();
        assertFalse(accJson.contains("TOP-SECRET-VALUE"), "凭据值绝不回显: " + accJson);
        assertFalse(accJson.contains("secret") || accJson.contains("appid") || accJson.contains("customerCode"),
                "凭据字段名也不出现: " + accJson);
        assertFalse(accJson.contains("<APPID>"), "占位符也不透出: " + accJson);

        // 读不到配置 → accounts 空 + problem 说明原因（前端退化文本框，不报错不空白）
        task("{\"configJsonPath\":\"" + tmp.resolve("nope.json").toString().replace('\\', '/') + "\"}");
        JsonObject miss = service.templatesJson(1L).getAsJsonObject("accountOptions");
        assertEquals(0, miss.getAsJsonArray("accounts").size());
        assertTrue(miss.get("problem").getAsString().contains("读不到"), miss.toString());

        task("");
        JsonObject none = service.templatesJson(1L).getAsJsonObject("accountOptions");
        assertEquals(0, none.getAsJsonArray("accounts").size());
        assertTrue(none.get("problem").getAsString().contains("configJsonPath"), none.toString());
    }

    // ===== MVP-1.7 规则 2：探针不计分——导入即标 countsTowardScore=false，stats 前后对比 =====

    @Test
    void probeTemplatesImportAsNonScoringAndStatsKeepThemOutOfDenominator() throws Exception {
        task("{\"baseUrl\":\"https://gw.example.com\",\"configJsonPath\":\"D:/cfg.json\"}");

        JsonObject r = service.importTemplates(1L, List.of("http-basic-connectivity", "config-shape"));
        assertEquals(2, r.get("landed").getAsInt());
        ArgumentCaptor<EvalCase> captor = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseRepo, times(2)).save(captor.capture());
        EvalCase probe = captor.getAllValues().get(0);
        EvalCase scored = captor.getAllValues().get(1);
        assertFalse(probe.isCountsTowardScore(), "直连供应商网关的连通检查 = 探针，不计分");
        assertTrue(probe.getTitle().startsWith("探针·"), probe.getTitle());
        assertTrue(probe.getNotes().contains("探针·不计入通过率"), probe.getNotes());
        assertTrue(scored.isCountsTowardScore(), "配置结构检查是计分项（缺省 true）");

        // stats 前后对比：探针 PASS + 计分 FAIL —— 通过率必须是 0/1，绝不被探针虚增成 1/2
        probe.setLastVerdict(EvalRunner.PASS);
        scored.setLastVerdict(EvalRunner.FAIL);
        var s = EvalLoopService.computeStats(List.of(probe, scored));
        assertEquals(1, s.executed(), "分母只含计分项");
        assertEquals(0, s.passed());
        assertEquals(1, s.failed());
        assertEquals(1, s.probeExecuted(), "探针 verdict 单独统计");
        assertEquals(1, s.probePassed());
        // 反向也不成立：探针 FAIL 不算失败、不拉低通过率（"供应商挂了"不冒充"代码有问题"）
        probe.setLastVerdict(EvalRunner.FAIL);
        scored.setLastVerdict(EvalRunner.PASS);
        var s2 = EvalLoopService.computeStats(List.of(probe, scored));
        assertEquals(1, s2.executed());
        assertEquals(1, s2.passed(), "通过率 1/1，探针 FAIL 不进 failed");
        assertEquals(0, s2.failed());
        assertEquals(1, s2.probeExecuted(), "探针 FAIL 只进 probeExecuted");
        assertEquals(0, s2.probePassed());
    }

    // ===== context_json 校验（坏格式 fail-loud 拒存） =====

    @Test
    void contextJsonValidation() {
        assertEquals("", EvalLoopService.validateContextJson("   "));
        assertEquals("{\"a\":\"b\"}", EvalLoopService.validateContextJson("{\"a\":\"b\"}"));
        assertEquals("{\"n\":1}", EvalLoopService.validateContextJson("{\"n\":1}"), "数字值也收（存归一后的 JSON）");
        assertThrows(IllegalArgumentException.class, () -> EvalLoopService.validateContextJson("not json"));
        assertThrows(IllegalArgumentException.class, () -> EvalLoopService.validateContextJson("[1,2]"));
        assertThrows(IllegalArgumentException.class,
                () -> EvalLoopService.validateContextJson("{\"a\":{\"b\":1}}"), "嵌套对象拒收");

        // updateTask patch 语义：null 保持原值，非 null 覆盖并归一
        EvalTask t = task("{\"old\":\"1\"}");
        service.updateTask(1L, null, null, false, null, null, null, null);
        assertEquals("{\"old\":\"1\"}", t.getContextJson(), "null = 保持原值");
        service.updateTask(1L, null, null, false, null, null, null, "{\"configKey\":\"SS.X.Config\"}");
        assertEquals("{\"configKey\":\"SS.X.Config\"}", t.getContextJson());
        verify(taskRepo, times(2)).save(t);
    }

    // ===== taskJson 透出 contextJson（前端设置页回显） =====

    @Test
    void detailExposesContextJson() {
        task("{\"baseUrl\":\"https://gw\"}");
        when(caseRepo.findByTaskIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());
        JsonObject d = service.detail(1L);
        assertEquals("{\"baseUrl\":\"https://gw\"}",
                JsonParser.parseString(d.get("contextJson").getAsString()).toString());
    }
}
