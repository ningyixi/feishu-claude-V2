package com.lark.oapi.sample.ws.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 内置通用模板库单测（11-21-supplier-eval-loop MVP-1.5）。
 * <p>
 * AC16′：模板清单只来自文件——解析器读临时目录文件证明"改文件即生效、新增模板不改 Java"；
 * 默认库文件覆盖 PRD 最小集合（配置 2 + 配置中心 1 + HTTP 3 + 静态 2 + 构建 1），
 * kind 全部可执行（非法 kind fail-loud，不存在模型判分通道），target 是脚本调用不是内联 JS 大字符串，
 * HTTP 模板必须带"curl 类连通检查 ≠ Postman 实测门禁证据"警示，构建模板标"未真实验证"。
 */
class EvalTemplatesTest {

    @TempDir
    Path tmp;

    private static String libJson(String... templateBlocks) {
        return "{\"version\":1,\"httpEvidenceWarning\":\"警示文案\",\"variables\":[],"
                + "\"templates\":[" + String.join(",", templateBlocks) + "]}";
    }

    private static String tpl(String key, String kind, String target) {
        return "{\"key\":\"" + key + "\",\"group\":\"g\",\"title\":\"t-" + key + "\",\"kind\":\"" + kind
                + "\",\"targetTemplate\":\"" + target + "\",\"expectTemplate\":\"exit=0\",\"requiresSandbox\":false,"
                + "\"needsVariables\":[\"v1\"],\"description\":\"d\",\"verified\":true}";
    }

    // ===== AC16′：解析器读的是文件——改文件即生效，不需要改 Java 代码 =====

    @Test
    void parserReadsFileAndPicksUpEditsWithoutCodeChange() throws Exception {
        Path f = tmp.resolve("eval-templates.json");
        Files.writeString(f, libJson(tpl("a", "script", "node a.js {{v1}}")), StandardCharsets.UTF_8);

        EvalTemplates.Library lib1 = EvalTemplates.load(f.toString());
        assertEquals(1, lib1.templates().size());
        assertEquals("a", lib1.templates().get(0).key());
        assertEquals("警示文案", lib1.httpEvidenceWarning());

        // 同一个路径，直接改文件加一条模板 → 再加载立刻生效（无缓存、无硬编码清单）
        Files.writeString(f, libJson(tpl("a", "script", "node a.js"), tpl("b", "static", "node b.js")),
                StandardCharsets.UTF_8);
        EvalTemplates.Library lib2 = EvalTemplates.load(f.toString());
        assertEquals(2, lib2.templates().size(), "改文件即生效：新增模板不需要改 Java 代码");
        assertEquals(List.of("a", "b"), lib2.templates().stream().map(EvalTemplates.Template::key).toList());
    }

    @Test
    void loadFailsLoudWhenConfiguredFileMissing() {
        var e = assertThrows(IllegalStateException.class,
                () -> EvalTemplates.load(tmp.resolve("nope.json").toString()));
        assertTrue(e.getMessage().contains("不存在"), e.getMessage());
    }

    @Test
    void parserRejectsIllegalKindAndBrokenExpectFailLoud() {
        // kind 非法（比如幻想中的"模型判分"通道）→ 加载即炸，绝不静默入库
        var e1 = assertThrows(IllegalStateException.class,
                () -> EvalTemplates.parse(libJson(tpl("x", "llm-score", "ask the model")), "test"));
        assertTrue(e1.getMessage().contains("kind 非法"), e1.getMessage());
        // expectTemplate 语法坏 → 加载即炸
        var e2 = assertThrows(IllegalStateException.class,
                () -> EvalTemplates.parse("{\"templates\":[{\"key\":\"y\",\"title\":\"t\",\"kind\":\"script\","
                        + "\"targetTemplate\":\"node y.js\",\"expectTemplate\":\"exit=abc\"}]}", "test"));
        assertTrue(e2.getMessage().contains("expectTemplate"), e2.getMessage());
        // key 重复 → 炸
        var e3 = assertThrows(IllegalStateException.class,
                () -> EvalTemplates.parse(libJson(tpl("dup", "script", "a"), tpl("dup", "script", "b")), "test"));
        assertTrue(e3.getMessage().contains("重复"), e3.getMessage());
    }

    // ===== 默认模板库文件：覆盖 PRD 最小集合 + 纪律断言 =====

    @Test
    void defaultLibraryFileCoversPrdMinimalSet() throws Exception {
        Path dev = Path.of(EvalTemplates.DEV_FILE);
        assertTrue(Files.isRegularFile(dev), "仓库里必须有模板库文件（测试在仓库根运行）: " + dev.toAbsolutePath());
        String raw = Files.readString(dev, StandardCharsets.UTF_8);
        EvalTemplates.Library lib = EvalTemplates.load(dev.toString());

        // 10 条：配置 2 + 配置中心 1 + 端到端 1 + HTTP 探针 2 + 静态 3 + 构建 1
        //（MVP-1.7：http-postman-collection 已移除——用户不要 Postman 形态的 eval，移除原因记录在文件里）
        List<String> keys = lib.templates().stream().map(EvalTemplates.Template::key).toList();
        assertEquals(List.of("config-shape", "config-urls-https", "config-center-fetch",
                "e2e-supplier-unit-test",
                "http-basic-connectivity", "http-search-minbody",
                "has-unit-test", "static-class-registered", "static-no-empty-catch", "build-dotnet"), keys);
        assertTrue(keys.stream().allMatch(k -> k.startsWith("config-") || k.startsWith("e2e-")
                || k.startsWith("http-") || k.equals("has-unit-test")
                || k.startsWith("static-") || k.startsWith("build-")));
        assertFalse(keys.contains("http-postman-collection"), "Postman 形态模板必须已移除（用户明确不要）");
        assertTrue(raw.contains("\"removedTemplates\"") && raw.contains("http-postman-collection"),
                "移除必须留下原因注释（removedTemplates），不许静默消失");

        // kind 全部是客观可执行五种之一（parse 已校验，这里再显式断言一遍）
        for (EvalTemplates.Template t : lib.templates()) {
            assertTrue(EvalRunner.KINDS.contains(t.kind()), t.key() + " kind=" + t.kind());
            assertEquals("", String.join("", EvalRunner.parseExpect(t.expectTemplate()).errors()),
                    t.key() + " expectTemplate 语法必须干净");
            // needsVariables 必须是变量清单里声明过的
            for (String v : t.needsVariables()) {
                assertTrue(lib.variables().stream().anyMatch(x -> x.name().equals(v)),
                        t.key() + " 引用了未声明变量 " + v);
            }
        }

        // 脚本类 target 一律"node <脚本文件>"形态，绝不把几十行 JS 内联进 target
        for (EvalTemplates.Template t : lib.templates()) {
            if (t.kind().equals("script") || t.kind().equals("static")) {
                if (t.targetTemplate().startsWith("node ")) {
                    assertTrue(t.targetTemplate().length() < 300,
                            t.key() + " target 过长，疑似内联了脚本正文: " + t.targetTemplate().length());
                    assertTrue(t.targetTemplate().contains("{{evalScriptDir}}/"),
                            t.key() + " 必须引用 scripts/eval 下的脚本文件");
                }
            }
        }

        // requiresSandbox 纪律：要凭据/内网的一律标沙箱，不伪装成本地可跑
        assertTrue(byKey(lib, "config-center-fetch").requiresSandbox(), "配置中心要凭据 → 必须沙箱");
        for (String k : List.of("http-basic-connectivity", "http-search-minbody")) {
            assertTrue(byKey(lib, k).requiresSandbox(), k + " 要打供应商网关 → 必须沙箱");
        }
        for (String k : List.of("config-shape", "config-urls-https", "e2e-supplier-unit-test",
                "has-unit-test", "static-class-registered", "static-no-empty-catch", "build-dotnet")) {
            assertFalse(byKey(lib, k).requiresSandbox(), k + " 应为本地可跑（或本地可判）");
        }

        // 未真实验证的必须标 verified=false（页面据此标「未真实验证」）
        assertFalse(byKey(lib, "build-dotnet").verified(), "dotnet build 基线未验证过 → 必须 verified=false");
        assertTrue(byKey(lib, "build-dotnet").description().contains("未真实验证"));
        assertFalse(byKey(lib, "config-center-fetch").verified(), "配置中心协议未验证 → verified=false");
        assertFalse(byKey(lib, "e2e-supplier-unit-test").verified(),
                "dotnet test 基线未真实验证（本机红线：不真跑 dotnet）→ 必须 verified=false 橙标");
        assertTrue(byKey(lib, "e2e-supplier-unit-test").description().contains("未真实验证"));

        // HTTP 模板与全局警示：curl 类连通检查 ≠ 工作台实测门禁认可的 Postman 证据
        assertTrue(lib.httpEvidenceWarning().contains("Postman") && lib.httpEvidenceWarning().contains("不等价"),
                "全局警示必须写明不等价于 Postman 实测证据: " + lib.httpEvidenceWarning());
        for (String k : List.of("http-basic-connectivity", "http-search-minbody")) {
            String d = byKey(lib, k).description();
            assertTrue(d.contains("Postman") && (d.contains("不等价") || d.contains("不能免除实测")),
                    k + " 描述必须自带 Postman 证据警示");
        }

        // 措辞纪律：模板库全文不得出现禁词
        assertFalse(raw.contains("玩家"), "模板库文案不得出现禁词\"玩家\"");
    }

    // ===== MVP-1.7：直连供应商的 HTTP 模板降级为探针（不计入通过率），端到端模板计分 =====

    @Test
    void directSupplierHttpTemplatesAreNonScoringProbes() throws Exception {
        EvalTemplates.Library lib = EvalTemplates.load(Path.of(EvalTemplates.DEV_FILE).toString());
        for (String k : List.of("http-basic-connectivity", "http-search-minbody")) {
            EvalTemplates.Template t = byKey(lib, k);
            assertFalse(t.countsTowardScore(), k + " 直连供应商网关绕过被测代码 → 必须降级为探针不计分");
            assertTrue(t.title().startsWith("探针·"), k + " title 必须加「探针·」前缀: " + t.title());
            assertTrue(t.description().contains("不计入通过率"), k + " description 必须写明不计入通过率");
            assertTrue(t.description().contains("不证明本次交付的代码正确"),
                    k + " description 必须写明它不证明交付代码正确: " + t.description());
        }
        // 其余模板（含端到端与 has-unit-test）缺省计分——countsTowardScore 缺省 true
        for (EvalTemplates.Template t : lib.templates()) {
            if (!t.key().startsWith("http-")) {
                assertTrue(t.countsTowardScore(), t.key() + " 应为计分项（缺省 true）");
            }
        }
    }

    @Test
    void parseDefaultsCountsTowardScoreToTrue() {
        // 模板文件里没写 countsTowardScore → 缺省 true（计分项）；显式 false → 探针
        EvalTemplates.Library lib = EvalTemplates.parse(libJson(tpl("a", "script", "node a.js")), "test");
        assertTrue(lib.templates().get(0).countsTowardScore(), "缺省必须 true（存量模板文件不因加字段变探针）");
        EvalTemplates.Library off = EvalTemplates.parse(libJson(
                "{\"key\":\"p\",\"group\":\"g\",\"title\":\"探针·t\",\"kind\":\"http\",\"targetTemplate\":\"curl x\","
                        + "\"expectTemplate\":\"exit=0\",\"countsTowardScore\":false}"), "test");
        assertFalse(off.templates().get(0).countsTowardScore());
    }

    // ===== MVP-1.7 追加①：端到端 = dotnet test + accountId 必填 + EVAL_ACCOUNT_ID 契约 =====

    @Test
    void e2eTemplateIsDotnetTestWithAccountIdEnvContract() throws Exception {
        EvalTemplates.Library lib = EvalTemplates.load(Path.of(EvalTemplates.DEV_FILE).toString());
        EvalTemplates.Template t = byKey(lib, "e2e-supplier-unit-test");
        assertEquals("test", t.kind(), "端到端判定 = dotnet test 退出码（客观可执行）");
        assertEquals("set EVAL_ACCOUNT_ID={{accountId}}&& dotnet test \"{{testProjectPath}}\" "
                + "--filter \"FullyQualifiedName~{{supplierClassHint}}\" --nologo", t.targetTemplate(),
                "target 必须以环境变量传账号（cmd 形态，&& 前不留空格防值带尾空格）+ dotnet test 测试工程 + 供应商类名 filter");
        assertTrue(t.needsVariables().containsAll(List.of("accountId", "testProjectPath", "supplierClassHint")));
        // 契约必须写进 description（写测试的人和跑 eval 的人不许各猜一套）
        assertTrue(t.description().contains("EVAL_ACCOUNT_ID") && t.description().contains("必须从 EVAL_ACCOUNT_ID 读取账号")
                        && t.description().contains("缺省时才回落默认账号"),
                "description 必须写明账号契约: " + t.description());
        // accountId 变量本身：必填语义 + 人话 label + 绝不回显凭据的承诺
        var acc = lib.variables().stream().filter(v -> v.name().equals("accountId")).findFirst()
                .orElseThrow(() -> new AssertionError("变量清单必须声明 accountId"));
        assertTrue(acc.label().contains("必填") && acc.label().contains("哪个账号"),
                "label 必须是人话，让用户一眼知道填什么: " + acc.label());
        assertTrue(acc.placeholder().contains("2996"), "placeholder 给真实形态示例: " + acc.placeholder());
        assertTrue(lib.variables().stream().anyMatch(v -> v.name().equals("testProjectPath")),
                "testProjectPath 必须在变量清单里声明（探测规则见 EvalLoopService.resolveTestProjectPath）");
    }

    @Test
    void hasUnitTestTemplateIsDeterministicStaticCheck() throws Exception {
        EvalTemplates.Library lib = EvalTemplates.load(Path.of(EvalTemplates.DEV_FILE).toString());
        EvalTemplates.Template t = byKey(lib, "has-unit-test");
        assertEquals("static", t.kind());
        assertTrue(t.countsTowardScore(), "「有没有配套测试」是 review 检查表条目 → 必须计分");
        assertEquals("node \"{{evalScriptDir}}/check-has-unit-test.js\" \"{{supplierClassHint}}\" \"{{supplierDir}}\"",
                t.targetTemplate());
        assertTrue(t.description().contains("[Fact]") && t.description().contains("文件与符号存在性"),
                "判据必须是文件+符号存在性，不是模型: " + t.description());
    }

    // ===== MVP-1.7 规则 5：eval 禁止写死时间——落成代码约束（本测试就是那条单测） =====

    @Test
    void noTemplateHardcodesDatesAndPolicyIsPublished() throws Exception {
        Path dev = Path.of(EvalTemplates.DEV_FILE);
        EvalTemplates.Library lib = EvalTemplates.load(dev.toString());
        // 规则：模板与 target 里不许出现任何写死日期（防重蹈 DCYiLv 集合里 11/20-21 随日历腐烂的坑）
        java.util.regex.Pattern iso = java.util.regex.Pattern.compile("\\d{4}-\\d{1,2}-\\d{1,2}");
        java.util.regex.Pattern slash = java.util.regex.Pattern.compile("\\d{1,2}/\\d{1,2}/\\d{2,4}");
        for (EvalTemplates.Template t : lib.templates()) {
            for (String field : List.of(t.title(), t.targetTemplate(), t.expectTemplate())) {
                assertFalse(iso.matcher(field).find(), t.key() + " 里出现写死日期（ISO）: " + field);
                assertFalse(slash.matcher(field).find(), t.key() + " 里出现写死日期（斜杠）: " + field);
            }
        }
        // 日期只允许运行时动态生成——探针请求体夹具里也不许有真实日期字面量，只能是 RUNTIME 标记
        Path body = Path.of("scripts/eval/search-minbody.json");
        if (Files.isRegularFile(body)) {
            String rawBody = Files.readString(body, StandardCharsets.UTF_8);
            assertFalse(iso.matcher(rawBody).find(), "search-minbody.json 不许写死日期: " + rawBody);
            assertTrue(rawBody.contains("<RUNTIME_TODAY+7>") && rawBody.contains("<RUNTIME_TODAY+8>"),
                    "住期只能是运行时重写标记（check-http-json.js 发送前重写为今天+7/今天+8）: " + rawBody);
        }
        // 规则本身作为固定警示文案发布给页面（datePolicy），不是只藏在代码里
        assertTrue(lib.datePolicy().contains("禁止写死") && lib.datePolicy().contains("运行时动态生成"),
                "datePolicy 必须写明禁止写死时间: " + lib.datePolicy());
    }

    private static EvalTemplates.Template byKey(EvalTemplates.Library lib, String key) {
        return lib.templates().stream().filter(t -> t.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("模板不存在: " + key));
    }

    // ===== {{var}} 展开 =====

    @Test
    void expandReplacesVarsAndCollectsMissing() {
        var ok = EvalTemplates.expand("node \"{{evalScriptDir}}/a.js\" \"{{configJsonPath}}\"",
                Map.of("evalScriptDir", "Z:/repo/scripts/eval", "configJsonPath", "D:/cfg.json"));
        assertEquals("node \"Z:/repo/scripts/eval/a.js\" \"D:/cfg.json\"", ok.text());
        assertTrue(ok.missingVariables().isEmpty());

        var miss = EvalTemplates.expand("node {{evalScriptDir}}/a.js {{configJsonPath}} {{baseUrl}}",
                Map.of("evalScriptDir", "S", "configJsonPath", "  "));   // 空白值 = 没填
        assertEquals(List.of("configJsonPath", "baseUrl"), miss.missingVariables());
        assertTrue(miss.text().contains("{{configJsonPath}}"), "缺失占位符原样保留，便于页面展示");
        assertTrue(miss.text().contains("S/a.js"), "已填的正常展开");

        assertEquals("", EvalTemplates.expand(null, Map.of()).text());
    }

    // ===== 脚本目录解析 =====

    @Test
    void resolveScriptDirPrefersConfigThenExistingDir() {
        assertEquals("D:/custom/dir", EvalTemplates.resolveScriptDir("D:\\custom\\dir"), "配置优先，反斜杠归一");
        String auto = EvalTemplates.resolveScriptDir("");
        assertTrue(auto.endsWith("scripts/eval"), "默认解析到进程目录 scripts/eval: " + auto);
        assertTrue(Files.isDirectory(Path.of(auto)), "测试在仓库根运行，解析出的目录应真实存在");
        assertFalse(auto.contains("\\"), "统一正斜杠（node 在 Windows 也认，且 JSON 展示干净）");
    }

    // ===== context_json → 变量表 =====

    @Test
    void contextVarsParsesFlatObjectAndToleratesGarbage() {
        assertEquals(Map.of("baseUrl", "https://x", "n", "1"),
                EvalTemplates.contextVars("{\"baseUrl\":\"https://x\",\"n\":1}"));
        assertTrue(EvalTemplates.contextVars("").isEmpty());
        assertTrue(EvalTemplates.contextVars(null).isEmpty());
        assertTrue(EvalTemplates.contextVars("not json{{").isEmpty(), "脏数据 → 空表（模板全部落未落地，诚实）");
    }
}
