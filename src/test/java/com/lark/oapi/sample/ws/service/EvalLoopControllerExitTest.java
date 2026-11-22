package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.config.RequestIdentity;
import com.lark.oapi.sample.ws.controller.EvalLoopController;
import com.lark.oapi.sample.ws.model.entity.EvalCase;
import com.lark.oapi.sample.ws.model.entity.EvalTask;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.EvalCaseRepository;
import com.lark.oapi.sample.ws.repository.EvalCaseResultRepository;
import com.lark.oapi.sample.ws.repository.EvalTaskRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 控制器出口回归测试（11-21-supplier-eval-loop MVP-1.8 缺陷A/缺陷B）。
 * <p>
 * 上一棒的教训：accountOptions 的单测直接测 service、绕过了 HTTP 出口，真环境照样翻车。
 * 本测试走 MockMvc → {@link EvalLoopController} 真实方法 → 真实请求体绑定 → 真实响应字符串，
 * 完整复刻用户 2026-11-22 的复现步骤：
 * <pre>
 * POST /api/eval-tasks {"name":…,"supplierCode":"2226"}
 * PUT  /api/eval-tasks/{id}  contextJson = {"configJsonPath":…,"accountId":"2996","repoPath":…}
 * GET  /api/eval-tasks/{id}/templates          → accountOptions 必须枚举出 2996/3004
 * POST /api/eval-tasks/{id}/templates/import   → supplierDir 必须解析到 …/Supplier/DCYiLv
 * </pre>
 * 缺陷A根因（实测钉死）：PUT 的 contextJson 传 JSON <b>对象</b>形态（curl 手写体的自然形态）时，
 * 旧代码 opt() 的 getAsString() 抛 UnsupportedOperationException → 裸 500 → 上下文静默没存上 →
 * GET templates 的 accountOptions 因「未填 configJsonPath」枚举不出账号（下拉不出来）。
 * 修复后：对象形态照常收（序列化成字符串走同一套 validateContextJson），且 update() 兜住一切
 * 未预期异常回可读错误——不再有任何静默 500。
 * <p>
 * 判定纪律不变：本测试不涉及任何 verdict——导入/枚举都不是判分。
 */
class EvalLoopControllerExitTest {

    @TempDir
    Path tmp;

    private final Map<Long, EvalTask> taskDb = new HashMap<>();
    private final AtomicLong taskIds = new AtomicLong(0);
    private final List<EvalCase> caseDb = new ArrayList<>();
    private final AtomicLong caseIds = new AtomicLong(0);

    private MockMvc mvc;
    private String scriptDir;

    @BeforeEach
    void setUp() {
        EvalTaskRepository taskRepo = mock(EvalTaskRepository.class);
        EvalCaseRepository caseRepo = mock(EvalCaseRepository.class);
        EvalCaseResultRepository resultRepo = mock(EvalCaseResultRepository.class);
        BotInstanceRepository instanceRepo = mock(BotInstanceRepository.class);
        EmployeeService employees = mock(EmployeeService.class);

        when(taskRepo.save(any(EvalTask.class))).thenAnswer(inv -> {
            EvalTask t = inv.getArgument(0);
            if (t.getId() == null) t.setId(taskIds.incrementAndGet());
            taskDb.put(t.getId(), t);
            return t;
        });
        when(taskRepo.findById(anyLong())).thenAnswer(inv -> Optional.ofNullable(taskDb.get(inv.getArgument(0, Long.class))));
        when(caseRepo.findByTaskIdOrderBySortOrderAscIdAsc(anyLong())).thenAnswer(inv -> {
            Long id = inv.getArgument(0);
            return caseDb.stream().filter(c -> c.getTaskId().equals(id)).toList();
        });
        when(caseRepo.save(any(EvalCase.class))).thenAnswer(inv -> {
            EvalCase c = inv.getArgument(0);
            if (c.getId() == null) c.setId(caseIds.incrementAndGet());
            caseDb.add(c);
            return c;
        });

        Path f = Path.of(EvalTemplates.DEV_FILE).toAbsolutePath().normalize();
        assertTrue(Files.isRegularFile(f), "模板库文件必须存在（测试在仓库根运行）: " + f);
        scriptDir = EvalTemplates.resolveScriptDir("");
        EvalLoopService service = new EvalLoopService(taskRepo, caseRepo, resultRepo, instanceRepo,
                tmp.resolve("eval-logs").toString(), f.toString(), scriptDir);
        mvc = MockMvcBuilders.standaloneSetup(new EvalLoopController(service, employees)).build();
    }

    private static String fwd(Path p) {
        return p.toString().replace('\\', '/');
    }

    /** 复刻真实配置副本（与 SS.DCYiLv.Config.eval.json 同构）：双账号 + 凭据字段一律金丝雀占位 */
    private Path configFixture() throws Exception {
        Path cfg = tmp.resolve("SS.DCYiLv.Config.eval.json");
        Files.writeString(cfg, """
                { "SupplierID": 2226,
                  "AccountDic": {
                    "2996": { "SupplierAccountID": 2996, "AccountName": "instantConfirm",
                              "appid": "APPID-LEAK-CANARY", "secret": "SECRET-LEAK-CANARY",
                              "customerCode": "CC-LEAK-CANARY", "paymentType": 1, "ConfirmMode": "INSTANT" },
                    "3004": { "SupplierAccountID": 3004, "AccountName": "onRequest",
                              "appid": "APPID-LEAK-CANARY", "secret": "SECRET-LEAK-CANARY",
                              "customerCode": "CC-LEAK-CANARY", "paymentType": 2, "ConfirmMode": "ONREQUEST" }
                  } }
                """, StandardCharsets.UTF_8);
        return cfg;
    }

    /** 复刻真仓目录形态：Supplier/ 下 DCYiLv + 一串其它供应商目录（数字 code 2226 谁都匹配不上） */
    private Path repoFixture() throws Exception {
        Path repo = tmp.resolve("SupplierV2-fixture");
        for (String n : List.of("DCYiLv", "Yeste", "YiXiangJia", "Yoyo", "YoyoOversea", "Zaitu", "ZyxTravel")) {
            Files.createDirectories(repo.resolve("src/Business/Supplier/" + n));
        }
        return repo;
    }

    /** PanelAuthFilter 对本机/面板请求写入 ADMIN 身份（RequestIdentity 靠 request attribute 识别） */
    private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder b) {
        return b.requestAttr(RequestIdentity.ATTR_KIND, RequestIdentity.Kind.ADMIN);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder b) {
        return b.contentType("application/json;charset=UTF-8");
    }

    private String body(ResultActions ra) throws Exception {
        return ra.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    // ===== 缺陷A：用户复现步骤原样重放——PUT(对象形态) → GET templates → accountOptions 必须枚举出账号 =====

    @Test
    void userReproFlowAtControllerExitAccountOptionsEnumerateAndNoCredentialLeaks() throws Exception {
        Path cfg = configFixture();
        Path repo = repoFixture();

        // 1. 建任务（现网真任务同款：supplierCode 是数字 2226）
        String created = body(mvc.perform(asAdmin(json(post("/api/eval-tasks"))
                .content("{\"name\":\"DCYiLv 复现\",\"supplierCode\":\"2226\"}")))
                .andExpect(status().isOk()));
        long id = JsonParser.parseString(created).getAsJsonObject().get("id").getAsLong();

        // 2. PUT contextJson——JSON 对象形态（用户复现时的写法；旧代码在这里裸 500、上下文静默丢失）
        String putBody = "{\"contextJson\":{\"configJsonPath\":\"" + fwd(cfg)
                + "\",\"accountId\":\"2996\",\"repoPath\":\"" + fwd(repo)
                + "\",\"supplierClassHint\":\"DCYiLvBusiness\"}}";
        String updated = body(mvc.perform(asAdmin(json(put("/api/eval-tasks/" + id))
                .content(putBody))).andExpect(status().isOk()));
        assertTrue(updated.contains("configJsonPath"),
                "对象形态 contextJson 必须存上（缺陷A回归锁：旧代码 getAsString() 抛异常 → 500 → 静默没存）: " + updated);

        // 3. GET templates——控制器出口断言 accountOptions（不是 service 层自证）
        String tpl = body(mvc.perform(get("/api/eval-tasks/" + id + "/templates")).andExpect(status().isOk()));
        JsonObject d = JsonParser.parseString(tpl).getAsJsonObject();
        assertTrue(d.has("accountOptions") && d.get("accountOptions").isJsonObject(),
                "响应顶层必须带 accountOptions 对象: " + tpl.substring(0, Math.min(300, tpl.length())));
        JsonArray accounts = d.getAsJsonObject("accountOptions").getAsJsonArray("accounts");
        assertEquals(2, accounts.size(), "AccountDic 双账号都要枚举出来: " + accounts);
        JsonObject a0 = accounts.get(0).getAsJsonObject();
        assertEquals("2996", a0.get("id").getAsString());
        assertEquals("instantConfirm", a0.get("accountName").getAsString());
        assertEquals("INSTANT", a0.get("confirmMode").getAsString());
        JsonObject a1 = accounts.get(1).getAsJsonObject();
        assertEquals("3004", a1.get("id").getAsString());
        assertEquals("onRequest", a1.get("accountName").getAsString());
        assertEquals("ONREQUEST", a1.get("confirmMode").getAsString());
        assertFalse(d.getAsJsonObject("accountOptions").has("problem"), "配置读得到就不该有 problem: " + d.get("accountOptions"));

        // 4. 凭据纪律（控制器出口全响应）：凭据值金丝雀与凭据字段名一个都不许出现
        String lower = tpl.toLowerCase();
        for (String canary : List.of("secret-leak-canary", "appid-leak-canary", "cc-leak-canary")) {
            assertFalse(lower.contains(canary), "凭据值绝不出现在响应里: " + canary);
        }
        for (String word : List.of("secret", "appid", "customercode")) {
            assertFalse(lower.contains(word), "响应里不出现凭据字段名「" + word + "」任何字（含模板文案）");
        }

        // 5. 缺陷B联动：导入 e2e + has-unit-test——supplierDir 必须靠 supplierClassHint 解析到 DCYiLv
        String imp = body(mvc.perform(asAdmin(json(post("/api/eval-tasks/" + id + "/templates/import"))
                .content("{\"keys\":[\"e2e-supplier-unit-test\",\"has-unit-test\"]}"))).andExpect(status().isOk()));
        JsonObject ir = JsonParser.parseString(imp).getAsJsonObject();
        assertEquals(1, ir.get("landed").getAsInt(), "has-unit-test 变量齐（supplierDir 靠 hint 解析）必须落地: " + imp);
        assertEquals(1, ir.get("notLanded").getAsInt(), "e2e 缺 testProjectPath（fixture 仓库没有测试工程）诚实落未落地: " + imp);

        String detail = body(mvc.perform(get("/api/eval-tasks/" + id)).andExpect(status().isOk()));
        JsonObject dd = JsonParser.parseString(detail).getAsJsonObject();
        JsonObject hasTest = null;
        JsonObject e2e = null;
        for (var el : dd.getAsJsonArray("cases")) {
            JsonObject c = el.getAsJsonObject();
            if (c.get("title").getAsString().startsWith("本次交付有配套测试")) hasTest = c;
            if (c.get("title").getAsString().startsWith("端到端")) e2e = c;
        }
        assertNotNull(hasTest);
        assertEquals("node \"" + scriptDir + "/check-has-unit-test.js\" \"DCYiLvBusiness\" \""
                        + fwd(repo.resolve("src/Business/Supplier/DCYiLv")) + "\"",
                hasTest.get("target").getAsString(),
                "supplierCode=2226 匹配不上目录名 → 用 supplierClassHint=DCYiLvBusiness 去后缀(DCYiLv)命中（缺陷B回归锁）");
        assertTrue(hasTest.get("landed").getAsBoolean());
        assertNotNull(e2e);
        assertEquals("", e2e.get("target").getAsString(), "e2e 未落地（缺 testProjectPath）——诚实，不混进分母");
        assertFalse(e2e.get("landed").getAsBoolean());
        assertTrue(e2e.get("notes").getAsString().contains("testProjectPath"), e2e.get("notes").getAsString());
        assertTrue(e2e.get("notes").getAsString().contains("EVAL_ACCOUNT_ID"),
                "账号契约写进 notes: " + e2e.get("notes").getAsString());
    }

    // ===== 缺陷A补充：字符串形态（前端一直这么传）不回归；坏形态必须回可读 400，绝不裸 500 =====

    @Test
    void stringFormStillWorksAndBadFormsReturnReadableErrors() throws Exception {
        Path cfg = configFixture();
        String created = body(mvc.perform(asAdmin(json(post("/api/eval-tasks"))
                .content("{\"name\":\"string-form\",\"supplierCode\":\"2226\"}")))
                .andExpect(status().isOk()));
        long id = JsonParser.parseString(created).getAsJsonObject().get("id").getAsLong();

        // 字符串形态（前端 JSON.stringify(ctxClean) 的既有路径）照常收
        String putBody = "{\"contextJson\":\"{\\\"configJsonPath\\\":\\\"" + fwd(cfg) + "\\\"}\"}";
        String updated = body(mvc.perform(asAdmin(json(put("/api/eval-tasks/" + id))
                .content(putBody))).andExpect(status().isOk()));
        assertTrue(updated.contains("configJsonPath"), updated);
        String tpl = body(mvc.perform(get("/api/eval-tasks/" + id + "/templates")).andExpect(status().isOk()));
        assertEquals(2, JsonParser.parseString(tpl).getAsJsonObject()
                .getAsJsonObject("accountOptions").getAsJsonArray("accounts").size());

        // 数组形态 → validateContextJson fail-loud：可读 400（旧代码这类未预期异常会裸 500 且无日志）
        String bad = body(mvc.perform(asAdmin(json(put("/api/eval-tasks/" + id))
                .content("{\"contextJson\":[1,2]}"))).andExpect(status().isBadRequest()));
        assertTrue(bad.contains("上下文变量必须是 JSON 对象"), "坏形态必须给人话错误: " + bad);

        // 嵌套对象值 → 同样 fail-loud 400
        String nested = body(mvc.perform(asAdmin(json(put("/api/eval-tasks/" + id))
                .content("{\"contextJson\":{\"a\":{\"b\":1}}}"))).andExpect(status().isBadRequest()));
        assertTrue(nested.contains("不能嵌套对象或数组"), nested);
    }
}
