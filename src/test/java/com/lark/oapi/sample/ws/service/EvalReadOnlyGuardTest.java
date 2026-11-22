package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonObject;
import com.lark.oapi.sample.ws.model.entity.EvalCase;
import com.lark.oapi.sample.ws.model.entity.EvalCaseResult;
import com.lark.oapi.sample.ws.model.entity.EvalTask;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.EvalCaseRepository;
import com.lark.oapi.sample.ws.repository.EvalCaseResultRepository;
import com.lark.oapi.sample.ws.repository.EvalTaskRepository;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * MVP-1.6 追加两项的测试：
 * <p>
 * 追加 1「eval 强制只读」安全闸（用户原话：「外网请求的，先做查询类的，不要做真实下单的」）：
 * {@link EvalReadOnlyGuard} 纯函数判定 + 导入闸 + 试跑闸。命中写操作端点 → 拒绝执行、verdict 记
 * FAIL（不是 SKIPPED——静默跳过的安全闸等于没有闸）、<b>进程根本不起</b>（canary 文件证明 +
 * exitCode null + 耗时 0 + 无输出落盘）。只读白名单端点（static/*、avail/hotelsearch、
 * avail/precheck、booking/search）照常放行并真实执行。
 * <p>
 * 追加 2「每条 eval 都要有可见的执行记录」：{@link EvalLoopService#resultOutput} 按库记录的
 * outputFile 分页读取输出全文（绝不接受调用方传路径——防任意文件读取），无落盘文件时如实说明。
 */
class EvalReadOnlyGuardTest {

    @TempDir
    Path tmp;

    private EvalTaskRepository taskRepo;
    private EvalCaseRepository caseRepo;
    private EvalCaseResultRepository resultRepo;
    private EvalLoopService service;
    private String scriptDir;

    @BeforeAll
    static void requireNode() {
        Assumptions.assumeTrue(nodeAvailable(), "本机没有 node，跳过安全闸真实执行测试");
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
        BotInstanceRepository instanceRepo = mock(BotInstanceRepository.class);
        when(resultRepo.save(any())).thenAnswer(inv -> {
            EvalCaseResult r = inv.getArgument(0);
            if (r.getId() == null) r.setId(resultIds.incrementAndGet());
            return r;
        });
        Path f = Path.of(EvalTemplates.DEV_FILE).toAbsolutePath().normalize();
        scriptDir = EvalTemplates.resolveScriptDir("");
        service = new EvalLoopService(taskRepo, caseRepo, resultRepo, instanceRepo,
                tmp.resolve("eval-logs").toString(), f.toString(), scriptDir);
        EvalTask t = new EvalTask();
        t.setId(1L);
        t.setName("guard-test");
        t.setContextJson("");
        when(taskRepo.findById(1L)).thenReturn(Optional.of(t));
        when(caseRepo.findByTaskIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());
    }

    /** 结果行 id 发号器（真实环境由 JPA 生成；mock 下 save 时补 id） */
    private final java.util.concurrent.atomic.AtomicLong resultIds = new java.util.concurrent.atomic.AtomicLong(100);

    // ===== ① 纯函数闸：提交语义端点全拦，只读白名单全放 =====

    @Test
    void guardBlocksSubmitSemanticsAndAllowsReadOnlyWhitelist() {
        // 用户点名的反例：booking/create、booking/cancel
        for (String url : List.of(
                "https://gw.example.com/open/booking/create",
                "https://gw.example.com/open/booking/cancel",
                "https://gw.example.com/open/pay/confirm",
                "https://gw.example.com/open/refund/apply",
                "https://gw.example.com/order/submit/123",
                "https://gw.example.com/open/avail/PRECHECKconfirm")) {
            var r = EvalReadOnlyGuard.check("http", "node x.js GET \"" + url + "\"");
            assertTrue(r.blocked(), "必须拦截写操作端点: " + url);
            assertTrue(r.matched().contains(url), "拦截原因必须写清命中片段: " + r.matched());
        }
        // 只读白名单：全部放行
        for (String url : List.of(
                "https://gw.example.com/static/hotelcodes",
                "https://gw.example.com/static/hotelinfos",
                "https://gw.example.com/open/avail/hotelsearch",
                "https://gw.example.com/open/avail/precheck",
                "https://gw.example.com/open/booking/search")) {
            assertFalse(EvalReadOnlyGuard.check("http", "node x.js GET \"" + url + "\"").blocked(),
                    "只读白名单必须放行: " + url);
        }
        // 查询串里的敏感词不算（只看路径分段）；无 URL 的 target 不算；非 http/script kind 不算
        assertFalse(EvalReadOnlyGuard.check("http", "node x.js GET \"https://gw/open/booking/search?sort=order\"")
                .blocked(), "query 参数不参与判定（路径段是 search）");
        assertFalse(EvalReadOnlyGuard.check("http", "postman collection run \"D:/x/collection.json\"").blocked());
        assertFalse(EvalReadOnlyGuard.check("build", "dotnet build \"D:/create-order/X.sln\"").blocked(),
                "build 类是本仓库内命令，不含对外网的写语义");
        assertFalse(EvalReadOnlyGuard.check("http", "").blocked());
    }

    // ===== ①b 判定对象是"会不会产生写副作用"，不是"是不是供应商域名"：内网应用入口一等放行，禁止表优先级最高 =====

    @Test
    void guardAllowsIntranetAppEntriesAndDenyTableBeatsAllowRules() {
        // 回环/内网自家应用入口（下一棒 http 模板的目标类别）：必须放行，白名单不按供应商路径片段枚举
        for (String url : List.of(
                "http://localhost:8080/api/supplier/2166/hotels",
                "http://127.0.0.1:8080/api/hotel/static-list",
                "http://[::1]:5000/api/avail/rates",
                "http://10.12.3.4:9090/api/dcylv/hotelcodes",
                "http://192.168.1.10:5000/api/search",
                "http://172.16.8.9/api/x",
                "http://172.31.0.1:8080/api/y")) {
            assertFalse(EvalReadOnlyGuard.check("http", "node x.js GET \"" + url + "\"").blocked(),
                    "内网/回环应用入口必须放行: " + url);
        }
        // 禁止表优先级高于任何允许规则：内网目标带写语义照样拦（测自家服务也不许真实下单）
        for (String url : List.of(
                "http://127.0.0.1:8080/api/booking/create",
                "http://localhost:8080/api/order/pay",
                "http://192.168.1.10:5000/api/booking/cancel")) {
            var r = EvalReadOnlyGuard.check("http", "node x.js POST \"" + url + "\"");
            assertTrue(r.blocked(), "禁止表优先于允许规则，内网写端点也必须拦: " + url);
            assertTrue(r.matched().contains("禁止表优先"), r.matched());
        }
        // 允许表归类（数据驱动，加条目不改判定结构）
        assertEquals("回环/内网自家应用入口",
                EvalReadOnlyGuard.matchAllow("127.0.0.1:8080", "/api/x").name());
        assertEquals("供应商静态字典只读 static/*",
                EvalReadOnlyGuard.matchAllow("gw.example.com", "/static/hotelcodes").name());
        assertEquals("供应商查价/验价只读 avail/*",
                EvalReadOnlyGuard.matchAllow("gw.example.com", "/open/avail/precheck").name());
        assertEquals("供应商订单状态查询只读 booking/search",
                EvalReadOnlyGuard.matchAllow("gw.example.com", "/open/booking/search").name());
        assertNull(EvalReadOnlyGuard.matchAllow("gw.example.com", "/unknown/read"),
                "未知外网路径不匹配任何允许条目（现口径按 DEFAULT_ALLOW_UNKNOWN_EXTERNAL 放行）");
        assertTrue(EvalReadOnlyGuard.DEFAULT_ALLOW_UNKNOWN_EXTERNAL,
                "现口径：未命中两表的目标放行（收紧时翻转该常量，判定结构不变）");
    }

    // ===== ② 试跑闸：命中写端点 → 同步 FAIL、进程根本不起（canary 证明） =====

    @Test
    void trialOnWriteEndpointFailsSynchronouslyAndNeverStartsProcess() throws Exception {
        // canary：如果闸失效、进程被启动，node 会写出这个文件
        Path canary = tmp.resolve("canary.txt");
        Path canaryJs = tmp.resolve("canary.js");
        Files.writeString(canaryJs, "require('fs').writeFileSync(process.argv[2], 'pwned');\n",
                StandardCharsets.UTF_8);

        EvalCase c = new EvalCase();
        c.setId(50L);
        c.setTaskId(1L);
        c.setTitle("下单端点（必须被闸拦下）");
        c.setKind("http");
        c.setTarget("node \"" + fwd(canaryJs) + "\" \"https://gw.example.com/open/booking/create\" \""
                + fwd(canary) + "\"");
        c.setExpect("exit=0");
        c.setTimeoutSec(60);
        c.setEnabled(true);
        when(caseRepo.findById(50L)).thenReturn(Optional.of(c));

        service.trial(1L, 50L);   // 闸在起进程之前同步记 FAIL，不抛异常、不静默跳过

        ArgumentCaptor<EvalCaseResult> saved = ArgumentCaptor.forClass(EvalCaseResult.class);
        verify(resultRepo).save(saved.capture());   // 恰好一次 save = 同步终态，没有任何异步执行
        EvalCaseResult r = saved.getValue();
        assertEquals(EvalRunner.FAIL, r.getVerdict(), "必须记 FAIL——不是 SKIPPED，静默跳过的安全闸等于没有闸");
        assertNull(r.getExitCode(), "没有退出码 = 进程根本没起");
        assertEquals(0L, r.getElapsedMs(), "耗时 0 = 进程根本没起");
        assertEquals("", r.getOutputSummary(), "无输出 = 进程根本没起");
        assertEquals("", r.getOutputFile(), "无落盘 = 进程根本没起");
        assertTrue(r.getReason().contains("禁止写操作端点"), r.getReason());
        assertTrue(r.getReason().contains("booking/create"), "reason 写清命中片段: " + r.getReason());
        assertFalse(Files.exists(canary), "canary 文件不得被写出——铁证：进程从未执行");
        assertEquals(EvalRunner.FAIL, c.getLastVerdict());

        // FAIL 进分母（诚实面对被拦截的写端点，不伪装成"跳过"缩小分母）
        var s = EvalLoopService.computeStats(List.of(c));
        assertEquals(1, s.executed());
        assertEquals(1, s.failed());
        assertEquals(0, s.skipped());
    }

    // ===== ③ 只读白名单端点照常放行：真实起进程、真实拿 PASS（闸不误伤查询类） =====

    @Test
    void readOnlyEndpointStillReallyExecutesAndPasses() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/static/hotelcodes", ex -> {
            byte[] b = "{\"codes\":[\"A1\"]}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            EvalCase c = new EvalCase();
            c.setId(51L);
            c.setTaskId(1L);
            c.setTitle("字典端点连通（白名单放行）");
            c.setKind("http");
            c.setTarget("node \"" + scriptDir + "/check-http-json.js\" GET \"" + base + "/static/hotelcodes\"");
            c.setExpect("exit=0\nstdout~OK status=");
            c.setTimeoutSec(60);
            c.setEnabled(true);
            when(caseRepo.findById(51L)).thenReturn(Optional.of(c));

            JsonObject resp = service.trial(1L, 51L);
            assertEquals(EvalRunner.RUNNING, resp.get("verdict").getAsString(), "白名单端点放行 → 真实异步执行");

            EvalCaseResult r = awaitResult(resp.get("resultId").getAsLong());
            assertEquals(EvalRunner.PASS, r.getVerdict(),
                    "白名单只读端点必须照常执行并 PASS: reason=" + r.getReason() + " out=" + r.getOutputSummary());
            assertEquals(0, r.getExitCode(), "进程真实起了（有退出码）");
        } finally {
            server.stop(0);
        }
    }

    /** 按 resultId 精确等待本次执行终态（同一行对象被线程原地更新：RUNNING → 终态） */
    private EvalCaseResult awaitResult(long resultId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            ArgumentCaptor<EvalCaseResult> captor = ArgumentCaptor.forClass(EvalCaseResult.class);
            try {
                verify(resultRepo, atLeastOnce()).save(captor.capture());
                for (EvalCaseResult r : captor.getAllValues()) {
                    if (r.getId() != null && r.getId() == resultId && !EvalRunner.RUNNING.equals(r.getVerdict())) {
                        return r;
                    }
                }
            } catch (AssertionError ignore) {
                Thread.sleep(100);
            }
        }
        return fail("等待 resultId=" + resultId + " 的试跑结果超时（30s）");
    }

    // ===== ④ 导入闸：模板展开后指向写端点 → 拒绝落地（target 空、不进分母、notes 写清） =====

    @Test
    void importRefusesTemplateExpandedToWriteEndpoint() throws Exception {
        EvalTask t = taskRepo.findById(1L).orElseThrow();
        t.setContextJson("{\"baseUrl\":\"https://gw.example.com/open/booking/create\"}");

        JsonObject r = service.importTemplates(1L, List.of("http-basic-connectivity"));
        assertEquals(0, r.get("landed").getAsInt(), "指向 booking/create 的 http 模板拒绝落地");
        assertEquals(1, r.get("notLanded").getAsInt());
        ArgumentCaptor<EvalCase> captor = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseRepo).save(captor.capture());
        EvalCase c = captor.getValue();
        assertEquals("", c.getTarget(), "被闸拦下的模板不可执行");
        assertTrue(c.getNotes().contains("只读安全闸"), c.getNotes());
        assertTrue(c.getNotes().contains("booking/create"), c.getNotes());
        var s = EvalLoopService.computeStats(List.of(c));
        assertEquals(0, s.executed(), "被拦模板绝不进分母");

        // 白名单 baseUrl → 照常落地
        reset(caseRepo);
        when(caseRepo.findByTaskIdOrderBySortOrderAscIdAsc(1L)).thenReturn(List.of());
        t.setContextJson("{\"baseUrl\":\"https://gw.example.com/static/hotelcodes\"}");
        JsonObject r2 = service.importTemplates(1L, List.of("http-basic-connectivity"));
        assertEquals(1, r2.get("landed").getAsInt(), "只读白名单端点照常落地");
        ArgumentCaptor<EvalCase> cap2 = ArgumentCaptor.forClass(EvalCase.class);
        verify(caseRepo).save(cap2.capture());
        assertTrue(cap2.getValue().getTarget().contains("https://gw.example.com/static/hotelcodes"),
                cap2.getValue().getTarget());
    }

    // ===== ⑤ 追加 2：resultOutput 分页读落盘输出（路径只取自库记录，绝不接受调用方传路径） =====

    @Test
    void resultOutputPagesThroughRecordedFileOnly() throws Exception {
        EvalCase c = new EvalCase();
        c.setId(5L);
        c.setTaskId(1L);
        c.setTitle("有输出的 case");
        c.setKind("script");
        c.setTarget("node x.js");
        c.setEnabled(true);
        when(caseRepo.findById(5L)).thenReturn(Optional.of(c));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) sb.append("L").append(i).append(" 违规行内容\n");
        Path out = tmp.resolve("case-output.log");
        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);

        EvalCaseResult rec = new EvalCaseResult();
        rec.setId(77L);
        rec.setTaskId(1L);
        rec.setCaseId(5L);
        rec.setVerdict(EvalRunner.FAIL);
        rec.setExitCode(1);
        rec.setElapsedMs(1234L);
        rec.setReason("发现 10 处违规");
        rec.setOutputSummary("L0 …（截断）");
        rec.setOutputFile(out.toString());
        when(resultRepo.findById(77L)).thenReturn(Optional.of(rec));

        // 第一页：0..3 行
        JsonObject p1 = service.resultOutput(1L, 5L, 77L, 0, 4);
        assertEquals("L0 违规行内容\nL1 违规行内容\nL2 违规行内容\nL3 违规行内容\n", p1.get("content").getAsString());
        assertEquals(10, p1.get("totalLines").getAsInt());
        assertTrue(p1.get("truncated").getAsBoolean(), "还有后续页");
        assertEquals(out.toString(), p1.get("outputFile").getAsString(), "必须给出完整输出文件路径");
        assertEquals("发现 10 处违规", p1.get("reason").getAsString());
        assertTrue(p1.get("note").getAsString().contains("完整输出文件"), p1.get("note").getAsString());

        // 末页：8..9 行，truncated=false
        JsonObject p3 = service.resultOutput(1L, 5L, 77L, 8, 4);
        assertEquals("L8 违规行内容\nL9 违规行内容\n", p3.get("content").getAsString());
        assertFalse(p3.get("truncated").getAsBoolean());

        // maxLines 钳制上限 2000（一次不吐几 MB）
        JsonObject clamped = service.resultOutput(1L, 5L, 77L, 0, 999_999);
        assertEquals(2000, clamped.get("maxLines").getAsInt());

        // 记录不属于该 case/task → 拒绝（防串号读取）
        assertThrows(IllegalArgumentException.class, () -> service.resultOutput(1L, 6L, 77L, 0, 200));
        when(resultRepo.findById(78L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> service.resultOutput(1L, 5L, 78L, 0, 200));

        // 无落盘文件（SKIPPED/被闸拦截，没起进程）→ 如实说明，不留空白
        EvalCaseResult skipped = new EvalCaseResult();
        skipped.setId(79L);
        skipped.setTaskId(1L);
        skipped.setCaseId(5L);
        skipped.setVerdict(EvalRunner.SKIPPED);
        skipped.setReason("需要沙箱凭据，本次跳过");
        skipped.setOutputFile("");
        when(resultRepo.findById(79L)).thenReturn(Optional.of(skipped));
        JsonObject o = service.resultOutput(1L, 5L, 79L, 0, 200);
        assertEquals("", o.get("content").getAsString());
        assertTrue(o.get("note").getAsString().contains("没有落盘输出文件"), o.get("note").getAsString());
    }

    private static String fwd(Path p) {
        return p.toString().replace('\\', '/');
    }
}
