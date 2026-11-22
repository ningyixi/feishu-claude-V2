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

        // 9 条：配置 2 + 配置中心 1 + HTTP 3 + 静态 2 + 构建 1
        List<String> keys = lib.templates().stream().map(EvalTemplates.Template::key).toList();
        assertEquals(List.of("config-shape", "config-urls-https", "config-center-fetch",
                "http-basic-connectivity", "http-search-minbody", "http-postman-collection",
                "static-class-registered", "static-no-empty-catch", "build-dotnet"), keys);
        assertTrue(keys.stream().allMatch(k -> k.startsWith("config-") || k.startsWith("http-")
                || k.startsWith("static-") || k.startsWith("build-")));

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
        for (String k : List.of("http-basic-connectivity", "http-search-minbody", "http-postman-collection")) {
            assertTrue(byKey(lib, k).requiresSandbox(), k + " 要打供应商网关 → 必须沙箱");
        }
        for (String k : List.of("config-shape", "config-urls-https", "static-class-registered",
                "static-no-empty-catch", "build-dotnet")) {
            assertFalse(byKey(lib, k).requiresSandbox(), k + " 应为本地可跑（或本地可判）");
        }

        // 未真实验证的必须标 verified=false（页面据此标「未真实验证」）
        assertFalse(byKey(lib, "build-dotnet").verified(), "dotnet build 基线未验证过 → 必须 verified=false");
        assertTrue(byKey(lib, "build-dotnet").description().contains("未真实验证"));
        assertFalse(byKey(lib, "config-center-fetch").verified(), "配置中心协议未验证 → verified=false");
        assertFalse(byKey(lib, "http-postman-collection").verified());

        // HTTP 模板与全局警示：curl 类连通检查 ≠ 工作台实测门禁认可的 Postman 证据
        assertTrue(lib.httpEvidenceWarning().contains("Postman") && lib.httpEvidenceWarning().contains("不等价"),
                "全局警示必须写明不等价于 Postman 实测证据: " + lib.httpEvidenceWarning());
        for (String k : List.of("http-basic-connectivity", "http-search-minbody")) {
            String d = byKey(lib, k).description();
            assertTrue(d.contains("Postman") && (d.contains("不等价") || d.contains("不能免除实测")),
                    k + " 描述必须自带 Postman 证据警示");
        }
        assertTrue(byKey(lib, "http-postman-collection").description().contains("SupplierProbeEvidence"),
                "Postman 模板应指向实测门禁同源判据");

        // 措辞纪律：模板库全文不得出现禁词
        assertFalse(raw.contains("玩家"), "模板库文案不得出现禁词\"玩家\"");
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
