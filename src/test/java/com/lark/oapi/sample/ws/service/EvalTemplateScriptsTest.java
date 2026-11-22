package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 通用模板脚本真实执行测试（11-21-supplier-eval-loop MVP-1.5，AC13′ 核心）：
 * 每个脚本都必须在本机 node 上真跑出 PASS，且<b>故意做坏的反例必须真 FAIL</b>（非 0 退出码 + 可定位输出）——
 * 证明模板不是恒过的安慰剂。样例配置/代码全部生成在 JUnit 临时目录，不写任何真实仓库。
 * 最后一条用例走完整判定链：模板文件 → {{var}} 展开 → EvalRunner.buildCommand/execute/judge，
 * 证明模板的 expect 串与脚本真实输出对得上。
 */
class EvalTemplateScriptsTest {

    static Path scripts;

    @TempDir
    Path tmp;

    /** 结构依据 11-18-adhoc-supplier-config PRD 样例（顶层 4 键、Urls 七键、AccountDic 双账号） */
    static final String GOOD_CONFIG = """
            {
              "SupplierID": 2226,
              "IsEnableAntErrType": true,
              "Urls": {
                "hotelcodesUrl":  "https://gw.example.com/static/hotelcodes",
                "hotelinfosUrl":  "https://gw.example.com/static/hotelinfos",
                "hotelsearchUrl": "https://gw.example.com/open/avail/hotelsearch",
                "precheckUrl":    "https://gw.example.com/open/avail/precheck",
                "createUrl":      "https://gw.example.com/open/booking/create",
                "searchUrl":      "https://gw.example.com/open/booking/search",
                "cancelUrl":      "https://gw.example.com/open/booking/cancel"
              },
              "AccountDic": {
                "2996": { "SupplierAccountID": 2996, "AccountName": "instantConfirm",
                          "appid": "<APPID>", "secret": "<REDACTED>", "customerCode": "<CC>",
                          "paymentType": 1, "ConfirmMode": "INSTANT" },
                "3004": { "SupplierAccountID": 3004, "AccountName": "onRequest",
                          "appid": "<APPID>", "secret": "<REDACTED>", "customerCode": "<CC>",
                          "paymentType": 1, "ConfirmMode": "ONREQUEST" }
              }
            }
            """;

    @BeforeAll
    static void locate() {
        scripts = Path.of("scripts/eval").toAbsolutePath().normalize();
        Assumptions.assumeTrue(Files.isDirectory(scripts), "scripts/eval 不存在（测试须在仓库根运行）: " + scripts);
        Assumptions.assumeTrue(nodeAvailable(), "本机没有 node，跳过脚本真实执行测试");
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

    // ===== 执行辅助 =====

    record ProcResult(int exit, String out) {}

    static ProcResult run(File workDir, Map<String, String> env, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("node");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd);
        if (workDir != null) pb.directory(workDir);
        if (env != null) pb.environment().putAll(env);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        // node 管道输出一律 UTF-8（与控制台代码页无关）
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS), "脚本 60s 内必须结束");
        return new ProcResult(p.exitValue(), out);
    }

    ProcResult runScript(String... args) throws Exception {
        return run(tmp.toFile(), null, args);
    }

    String script(String name) {
        return scripts.resolve(name).toString();
    }

    Path writeFixture(String fileName, String content) throws Exception {
        Path f = tmp.resolve(fileName);
        Files.writeString(f, content, StandardCharsets.UTF_8);
        return f;
    }

    /** 在好配置基础上做一处故意破坏 */
    Path brokenConfig(String fileName, java.util.function.Consumer<JsonObject> mutate) throws Exception {
        JsonObject cfg = JsonParser.parseString(GOOD_CONFIG).getAsJsonObject();
        mutate.accept(cfg);
        return writeFixture(fileName, cfg.toString());
    }

    // ===== check-config-shape.js =====

    @Test
    void configShapePassesOnValidConfig() throws Exception {
        Path good = writeFixture("good.json", GOOD_CONFIG);
        ProcResult r = runScript(script("check-config-shape.js"), good.toString());
        assertEquals(0, r.exit(), "合法配置必须 PASS: " + r.out());
        assertTrue(r.out().contains("OK"), r.out());
    }

    @Test
    void configShapeFailsWhenUrlsKeyRemoved() throws Exception {
        // AC13′ 点名反例：故意把 Urls 少给一键 → 必须 FAIL 且指出缺哪键（证明不是恒过）
        Path bad = brokenConfig("missing-key.json",
                cfg -> cfg.getAsJsonObject("Urls").remove("cancelUrl"));
        ProcResult r = runScript(script("check-config-shape.js"), bad.toString());
        assertEquals(1, r.exit(), "缺 cancelUrl 必须 FAIL，退出码非 0");
        assertTrue(r.out().contains("cancelUrl"), "输出必须指出缺失项: " + r.out());
        assertTrue(r.out().startsWith("FAIL"), r.out());
    }

    @Test
    void configShapeFailsOnEmptyFileBadJsonBadAccount() throws Exception {
        ProcResult empty = runScript(script("check-config-shape.js"),
                writeFixture("empty.json", "").toString());
        assertEquals(1, empty.exit(), "空文件必须 FAIL");

        ProcResult badJson = runScript(script("check-config-shape.js"),
                writeFixture("bad.json", "{ broken").toString());
        assertEquals(1, badJson.exit(), "非法 JSON 必须 FAIL");

        ProcResult badAcct = runScript(script("check-config-shape.js"),
                brokenConfig("bad-acct.json", cfg -> {
                    var dic = cfg.getAsJsonObject("AccountDic");
                    dic.getAsJsonObject("2996").addProperty("ConfirmMode", "MAYBE");
                    dic.getAsJsonObject("3004").addProperty("paymentType", "1");   // 字符串 ≠ 数字
                }).toString());
        assertEquals(1, badAcct.exit());
        assertTrue(badAcct.out().contains("ConfirmMode"), badAcct.out());
        assertTrue(badAcct.out().contains("paymentType"), badAcct.out());

        ProcResult noArg = runScript(script("check-config-shape.js"));
        assertEquals(1, noArg.exit(), "缺参数也要 FAIL 而不是静默通过");
    }

    // ===== check-urls-https.js =====

    @Test
    void urlsHttpsPassesAndFails() throws Exception {
        Path good = writeFixture("good.json", GOOD_CONFIG);
        ProcResult ok = runScript(script("check-urls-https.js"), good.toString());
        assertEquals(0, ok.exit(), ok.out());
        assertTrue(ok.out().contains("OK"), ok.out());

        // 反例 1：一条改成 http
        Path http = brokenConfig("http.json", cfg -> cfg.getAsJsonObject("Urls")
                .addProperty("searchUrl", "http://gw.example.com/open/booking/search"));
        ProcResult r1 = runScript(script("check-urls-https.js"), http.toString());
        assertEquals(1, r1.exit(), "http 地址必须 FAIL");
        assertTrue(r1.out().contains("searchUrl") && r1.out().contains("https"), r1.out());

        // 反例 2：前缀不一致（另一台主机）
        Path mixed = brokenConfig("mixed.json", cfg -> cfg.getAsJsonObject("Urls")
                .addProperty("cancelUrl", "https://other.example.com/x"));
        ProcResult r2 = runScript(script("check-urls-https.js"), mixed.toString());
        assertEquals(1, r2.exit(), "前缀不一致必须 FAIL");
        assertTrue(r2.out().contains("cancelUrl"), r2.out());
    }

    // ===== check-class-registered.js（MVP-1.6 双根：定义在 supplierDir、引用在 repoPath 全仓） =====

    @Test
    void classRegisteredDualRootsPassesAndFails() throws Exception {
        // fixture 复刻现网 id=2 case41 假红场景：定义在供应商目录，注册引用只在仓库根（供应商目录之外）
        Path repo = tmp.resolve("repo");
        Path sup = repo.resolve("src/Business/Supplier/Dorak");
        Files.createDirectories(sup);
        Files.createDirectories(repo.resolve("factory"));
        Files.writeString(sup.resolve("DorakSupplierService.cs"),
                "namespace Supplier.Dorak { public class DorakSupplierService : ISupplierService { } }");
        Files.writeString(repo.resolve("factory/SupplierFactory.cs"),
                "public static class SupplierFactory { static object Get(int id) => new Dorak.DorakSupplierService(); }");
        Files.writeString(sup.resolve("LonelyService.cs"),
                "namespace X { public class LonelyService { } }");

        // 双根（修复后口径）：定义根=supplierDir、引用根=repoPath → 必须 PASS
        ProcResult ok = runScript(script("check-class-registered.js"),
                "DorakSupplierService", sup.toString(), repo.toString());
        assertEquals(0, ok.exit(), "注册点在供应商目录外也必须 PASS（双根修复）: " + ok.out());
        assertTrue(ok.out().contains("OK"), ok.out());
        assertTrue(ok.out().contains("代理判据"), "输出必须写明代理判据性质: " + ok.out());

        // 差异证明（旧单根口径的真实行为）：第三参缺省 = 引用根退化成定义根，
        // 同一 fixture 必假红「只有定义」——这正是修复前现网 id=2 case41 的 FAIL 形态
        ProcResult oldBehavior = runScript(script("check-class-registered.js"),
                "DorakSupplierService", sup.toString());
        assertEquals(1, oldBehavior.exit(), "单根扫描对注册点在目录外的供应商必假红（修复前/后差异证据）");
        assertTrue(oldBehavior.out().contains("只有定义"), oldBehavior.out());

        // LonelyService：全仓（repoPath）也没有任何引用 → 双根仍必须 FAIL
        ProcResult lonely = runScript(script("check-class-registered.js"),
                "LonelyService", sup.toString(), repo.toString());
        assertEquals(1, lonely.exit(), "全仓都无引用 → 必须 FAIL");
        assertTrue(lonely.out().contains("LonelyService"), lonely.out());

        ProcResult absent = runScript(script("check-class-registered.js"),
                "NoSuchClass", sup.toString(), repo.toString());
        assertEquals(1, absent.exit(), "类不存在 → 必须 FAIL");
        assertTrue(absent.out().contains("NoSuchClass"), absent.out());

        Path empty = tmp.resolve("nocs");
        Files.createDirectories(empty);
        ProcResult noCs = runScript(script("check-class-registered.js"), "X", empty.toString(), repo.toString());
        assertEquals(1, noCs.exit(), "供应商目录零 .cs → FAIL（防止错误目录下假绿）");

        ProcResult badRef = runScript(script("check-class-registered.js"),
                "DorakSupplierService", sup.toString(), tmp.resolve("no-such-dir").toString());
        assertEquals(1, badRef.exit(), "引用根不存在 → FAIL 而不是静默通过");
    }

    // ===== check-no-empty-catch.js =====

    @Test
    void noEmptyCatchPassesAndFails() throws Exception {
        Path clean = tmp.resolve("clean");
        Files.createDirectories(clean);
        Files.writeString(clean.resolve("Clean.cs"), """
                using System;
                public class Clean {
                    private readonly string _secret;
                    public Clean(IConfiguration cfg) { _secret = cfg["Supplier:Secret"]; }
                    public void Run() {
                        try { DoWork(); }
                        catch (Exception ex) { Log.Error(ex); throw; }
                    }
                    void DoWork() {}
                }
                """);
        ProcResult ok = runScript(script("check-no-empty-catch.js"), clean.toString());
        assertEquals(0, ok.exit(), "干净代码必须 PASS: " + ok.out());

        Path swallow = tmp.resolve("swallow");
        Files.createDirectories(swallow);
        Files.writeString(swallow.resolve("Swallow.cs"), """
                using System;
                public class Swallow {
                    public void Run() {
                        try { DoWork(); }
                        catch (Exception) { }
                    }
                    void DoWork() {}
                }
                """);
        ProcResult r1 = runScript(script("check-no-empty-catch.js"), swallow.toString());
        assertEquals(1, r1.exit(), "空 catch 吞异常 → 必须 FAIL");
        assertTrue(r1.out().contains("Swallow.cs"), "输出要能定位到文件: " + r1.out());

        Path leak = tmp.resolve("leak");
        Files.createDirectories(leak);
        Files.writeString(leak.resolve("Leak.cs"),
                "public class Leak { private string secret = \"hunter22abc\"; }");
        ProcResult r2 = runScript(script("check-no-empty-catch.js"), leak.toString());
        assertEquals(1, r2.exit(), "硬编码密钥明文 → 必须 FAIL");
        assertTrue(r2.out().contains("Leak.cs"), r2.out());

        Path empty = tmp.resolve("nocs2");
        Files.createDirectories(empty);
        ProcResult r3 = runScript(script("check-no-empty-catch.js"), empty.toString());
        assertEquals(1, r3.exit(), "零 .cs 文件 → FAIL（防假绿）");
    }

    // ===== check-has-unit-test.js（MVP-1.7：「本次交付有没有配套测试」= 文件+符号存在性，零模型） =====

    @Test
    void hasUnitTestPassesAndFailsDeterministically() throws Exception {
        Path sup = tmp.resolve("sup");
        Files.createDirectories(sup);
        Files.writeString(sup.resolve("DCYiLvBusiness.cs"),
                "namespace Supplier.DCYiLv { public class DCYiLvBusiness : ISupplierService { } }");
        Files.writeString(sup.resolve("DCYiLvBusinessTest.cs"), """
                using Xunit;
                public class DCYiLvBusinessTest {
                    [Fact]
                    public void SearchReturnsRates() {
                        var b = new DCYiLvBusiness();
                        Assert.NotNull(b);
                    }
                }
                """);

        ProcResult ok = runScript(script("check-has-unit-test.js"), "DCYiLvBusiness", sup.toString());
        assertEquals(0, ok.exit(), "[Fact] 与类名同现的测试文件存在 → PASS: " + ok.out());
        assertTrue(ok.out().startsWith("OK"), ok.out());
        assertTrue(ok.out().contains("DCYiLvBusinessTest.cs"), "输出必须定位到测试文件: " + ok.out());

        // 反例 1：有测试文件但测的不是供应商类 → FAIL（不是恒过）
        Path sup2 = tmp.resolve("sup2");
        Files.createDirectories(sup2);
        Files.writeString(sup2.resolve("OtherTest.cs"),
                "using Xunit;\npublic class OtherTest { [Fact] public void X() { Assert.True(true); } }");
        ProcResult noRef = runScript(script("check-has-unit-test.js"), "DCYiLvBusiness", sup2.toString());
        assertEquals(1, noRef.exit(), "没有测试文件引用供应商类 → FAIL");
        assertTrue(noRef.out().contains("DCYiLvBusiness"), noRef.out());

        // 反例 2：引用了类但没有 [Fact]/[Test] 特性 → 不算测试文件 → FAIL
        Path sup3 = tmp.resolve("sup3");
        Files.createDirectories(sup3);
        Files.writeString(sup3.resolve("Helper.cs"),
                "public class Helper { void Use() { var b = new DCYiLvBusiness(); } }");
        ProcResult noAttr = runScript(script("check-has-unit-test.js"), "DCYiLvBusiness", sup3.toString());
        assertEquals(1, noAttr.exit(), "引用类但没有测试特性 → 没有配套测试 → FAIL");
        assertTrue(noAttr.out().contains("[Fact]"), noAttr.out());

        // 防假绿：根不存在 / 零 .cs / 参数未展开 → 一律 FAIL
        assertEquals(1, runScript(script("check-has-unit-test.js"), "X",
                tmp.resolve("nope").toString()).exit(), "根不存在 → FAIL");
        Path empty = tmp.resolve("no-cs");
        Files.createDirectories(empty);
        assertEquals(1, runScript(script("check-has-unit-test.js"), "X", empty.toString()).exit(),
                "零 .cs → FAIL（防止错误目录下假绿）");
        assertEquals(1, runScript(script("check-has-unit-test.js"), "X", "{{supplierDir}}").exit(),
                "未展开占位符参数 → fail-loud，不当成合法根");

        // 多根：配套测试在独立测试工程目录（第三参）→ 也算命中
        Path testProj = tmp.resolve("testproj");
        Files.createDirectories(testProj);
        Path sup4 = tmp.resolve("sup4");
        Files.createDirectories(sup4);
        Files.writeString(sup4.resolve("Impl.cs"), "public class DBusiness { }");
        Files.writeString(testProj.resolve("DBusinessTests.cs"),
                "using NUnit.Framework;\npublic class DBusinessTests { [Test] public void T() { new DBusiness(); } }");
        ProcResult multi = runScript(script("check-has-unit-test.js"), "DBusiness",
                sup4.toString(), testProj.toString());
        assertEquals(0, multi.exit(), "测试工程目录可作第二扫描根: " + multi.out());

        // bin/obj 里的生成文件不算配套测试
        Path sup5 = tmp.resolve("sup5");
        Files.createDirectories(sup5.resolve("obj"));
        Files.writeString(sup5.resolve("obj/Generated.cs"),
                "using Xunit;\npublic class G { [Fact] public void T() { new Target(); } }");
        Files.writeString(sup5.resolve("Target.cs"), "public class Target { }");
        assertEquals(1, runScript(script("check-has-unit-test.js"), "Target", sup5.toString()).exit(),
                "obj/ 下的测试文件不算（跳过 bin/obj）");
    }

    // ===== MVP-1.7 规则 5：eval 禁止写死时间——请求体日期字段运行时动态生成（今天+7/今天+8） =====

    @Test
    void httpJsonRewritesBodyDatesAtRuntimeNeverHardcoded() throws Exception {
        java.util.concurrent.atomic.AtomicReference<String> received =
                new java.util.concurrent.atomic.AtomicReference<>("");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", ex -> {
            received.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(ex, 200, "application/json", "{\"data\":[{\"rate\":1}]}");
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            java.time.LocalDate in = java.time.LocalDate.now().plusDays(7);
            java.time.LocalDate out = java.time.LocalDate.now().plusDays(8);

            // 写死的腐烂日期（DCYiLv 集合 11/20-21 同款坑）→ 发送前必须被重写为今天+7/今天+8
            Path body = writeFixture("hardcoded.json",
                    "{\"checkIn\":\"2025-11-20\",\"checkOut\":\"2025-11-21\",\"rooms\":1}");
            ProcResult r = runScript(script("check-http-json.js"), "POST", base + "/echo", body.toString());
            assertEquals(0, r.exit(), r.out());
            assertTrue(received.get().contains("\"checkIn\":\"" + in + "\""),
                    "实际发送的请求体必须是动态生成的今天+7: " + received.get());
            assertTrue(received.get().contains("\"checkOut\":\"" + out + "\""), received.get());
            assertFalse(received.get().contains("2025-11-20"), "写死日期绝不允许原样发出");
            assertTrue(r.out().contains("实际使用的日期") && r.out().contains(in.toString())
                            && r.out().contains(out.toString()),
                    "输出必须打印实际使用的日期取值: " + r.out());

            // 模板自带的 search-minbody.json（<RUNTIME_TODAY+7> 标记）→ 同样被重写成真实日期
            ProcResult r2 = runScript(script("check-http-json.js"), "POST", base + "/echo",
                    scripts.resolve("search-minbody.json").toString());
            assertEquals(0, r2.exit(), r2.out());
            assertTrue(received.get().contains("\"checkIn\":\"" + in + "\""), received.get());
            assertFalse(received.get().contains("RUNTIME"), "标记必须被重写掉: " + received.get());
        } finally {
            server.stop(0);
        }
    }

    // ===== check-http-json.js / fetch-config-center.js（对本地测试服务真实收发） =====

    @Test
    void httpJsonAndConfigCenterAgainstLocalServer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", ex -> respond(ex, 200, "application/json", "{\"hotels\":[{\"code\":\"A1\"}]}"));
        server.createContext("/bad", ex -> respond(ex, 200, "text/plain", "not json at all"));
        server.createContext("/five", ex -> respond(ex, 503, "application/json", "{\"error\":\"down\"}"));
        server.createContext("/echo", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            respond(ex, 200, "application/json", "{\"received\":" + body + "}");
        });
        server.createContext("/config", ex -> respond(ex, 200, "application/json",
                "{\"value\":" + com.google.gson.JsonParser.parseString(GOOD_CONFIG) + "}"));
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();

            ProcResult ok = runScript(script("check-http-json.js"), "GET", base + "/ok");
            assertEquals(0, ok.exit(), ok.out());
            assertTrue(ok.out().contains("OK status=200"), ok.out());

            ProcResult bad = runScript(script("check-http-json.js"), "GET", base + "/bad");
            assertEquals(1, bad.exit(), "响应不是 JSON → FAIL");

            ProcResult five = runScript(script("check-http-json.js"), "GET", base + "/five");
            assertEquals(1, five.exit(), "5xx → FAIL");

            // POST + 真实请求体文件（模板 5 用的就是仓库里这个 search-minbody.json）
            ProcResult post = runScript(script("check-http-json.js"), "POST", base + "/echo",
                    scripts.resolve("search-minbody.json").toString());
            assertEquals(0, post.exit(), post.out());
            assertTrue(post.out().contains("OK status=200"), post.out());

            ProcResult refused = runScript(script("check-http-json.js"), "GET", base.replace(String.valueOf(server.getAddress().getPort()), "1") + "/ok");
            assertEquals(1, refused.exit(), "连不上 → FAIL（不是超时挂死）");

            // 配置中心：无环境变量 → FAIL 并写清缺什么
            ProcResult noEnv = run(tmp.toFile(), Map.of("CONFIG_CENTER_URL", ""),
                    script("fetch-config-center.js"), "SS.Test.Config");
            assertEquals(1, noEnv.exit(), "没配 CONFIG_CENTER_URL → FAIL");
            assertTrue(noEnv.out().contains("CONFIG_CENTER_URL"), noEnv.out());

            // 有端点（本地测试服务模拟配置中心，value 包一层 JSON 字符串）→ PASS
            ProcResult cc = run(tmp.toFile(), Map.of("CONFIG_CENTER_URL", base + "/config?key={key}"),
                    script("fetch-config-center.js"), "SS.Test.Config");
            assertEquals(0, cc.exit(), cc.out());
            assertTrue(cc.out().contains("OK"), cc.out());
        } finally {
            server.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange ex, int status, String type, String body)
            throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(status, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    // ===== 完整判定链：模板文件 → 展开 → EvalRunner 执行 + 裁决（AC13′ 全链路） =====

    @Test
    void templateChainEndToEndThroughEvalRunnerJudge() throws Exception {
        EvalTemplates.Library lib = EvalTemplates.load(Path.of(EvalTemplates.DEV_FILE).toString());
        EvalTemplates.Template tpl = lib.templates().stream()
                .filter(t -> t.key().equals("config-shape")).findFirst().orElseThrow();

        Path good = writeFixture("chain-good.json", GOOD_CONFIG);
        Path bad = brokenConfig("chain-missing.json",
                cfg -> cfg.getAsJsonObject("Urls").remove("hotelcodesUrl"));

        // 变量填齐 → 展开 → 真实执行 → judge 判 PASS
        var pass = runChain(tpl, good);
        assertEquals(EvalRunner.PASS, pass.verdict(), "合法配置全链路必须 PASS: " + pass.reason());
        // 故意少一键 → 同一 expect 全链路判 FAIL（不是恒过）
        var fail = runChain(tpl, bad);
        assertEquals(EvalRunner.FAIL, fail.verdict(), "缺 hotelcodesUrl 全链路必须 FAIL");
        assertTrue(fail.output().contains("hotelcodesUrl"), "失败输出可定位: " + fail.output());
    }

    record Chain(String verdict, String reason, String output) {}

    private Chain runChain(EvalTemplates.Template tpl, Path configFixture) throws Exception {
        Map<String, String> vars = Map.of(
                "evalScriptDir", scripts.toString().replace('\\', '/'),
                "configJsonPath", configFixture.toString());
        String target = EvalTemplates.expand(tpl.targetTemplate(), vars).text();
        String expect = EvalTemplates.expand(tpl.expectTemplate(), vars).text();
        assertTrue(EvalRunner.isLanded(target));
        List<String> cmd = EvalRunner.buildCommand(tpl.kind(), target, EvalRunner.isWindows());
        EvalRunner.Execution exec = EvalRunner.execute(cmd, tmp.toFile(), 60);
        assertTrue(exec.finished(), "脚本必须在超时前结束");
        EvalRunner.Judgment j = EvalRunner.judge(exec.exitCode(), exec.output(), expect);
        return new Chain(j.verdict(), j.reason(), exec.output());
    }
}
