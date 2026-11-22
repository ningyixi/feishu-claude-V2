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
                case "http-basic-connectivity" -> assertEquals(List.of("baseUrl"), missing);
                case "http-postman-collection" -> assertEquals(List.of("collectionPath", "envPath"), missing);
                case "static-no-empty-catch" -> assertEquals(List.of("supplierDir"), missing,
                        "未绑定实例且探测不出 supplierDir → 缺供应商目录扫描根（绝不退化成后端进程 cwd/仓库根）");
                case "static-class-registered" -> assertEquals(List.of("supplierClassHint", "supplierDir", "repoPath"), missing);
                case "build-dotnet" -> assertEquals(List.of("slnPath"), missing,
                        "无 repoPath → 解析不出唯一 .sln → 缺 slnPath（不跑必失败命令）");
                default -> { }
            }
        }
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
