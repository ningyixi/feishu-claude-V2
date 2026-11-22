package com.lark.oapi.sample.ws.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * eval 判定内核单测（11-21-supplier-eval-loop MVP-1）。
 * <p>
 * 覆盖 PRD 硬纪律："verdict 只来自执行结果（退出码/输出匹配/断言比较）"——
 * 含两类反例：退出码 0 但断言不匹配 = FAIL；退出码非 0 但输出匹配 = FAIL。
 * 以及 kind 分派与命令构造、expect 语法 fail-loud、超时强杀、输出截断、未落地/跳过语义。
 */
class EvalRunnerTest {

    // ===== kind 分派与命令构造 =====

    @Test
    void buildCommandDispatchesPerOsAndRejectsBadInput() {
        for (String kind : List.of("build", "test", "script", "http", "static")) {
            assertEquals(List.of("cmd.exe", "/c", "dotnet build sln"),
                    EvalRunner.buildCommand(kind, "dotnet build sln", true), kind + " Windows 走 cmd /c");
            assertEquals(List.of("bash", "-lc", "make check"),
                    EvalRunner.buildCommand(kind, "make check", false), kind + " Unix 走 bash -lc");
        }
        var e1 = assertThrows(IllegalArgumentException.class,
                () -> EvalRunner.buildCommand("llm-review", "whatever", true));
        assertTrue(e1.getMessage().contains("未知 eval kind"), "未知 kind 拒绝执行（fail-loud，不降级）");
        var e2 = assertThrows(IllegalArgumentException.class,
                () -> EvalRunner.buildCommand("script", "  ", true));
        assertTrue(e2.getMessage().contains("未落地"), "target 空 = 未落地，不可执行");
    }

    @Test
    void defaultTimeoutPerKind() {
        assertEquals(600, EvalRunner.defaultTimeoutSec("build"));
        assertEquals(600, EvalRunner.defaultTimeoutSec("test"));
        assertEquals(120, EvalRunner.defaultTimeoutSec("script"));
        assertEquals(120, EvalRunner.defaultTimeoutSec("http"));
        assertEquals(60, EvalRunner.defaultTimeoutSec("static"));
    }

    // ===== expect 解析 =====

    @Test
    void parseExpectGrammar() {
        var spec = EvalRunner.parseExpect("""
                # 注释行忽略
                exit=0
                stdout~BUILD\\s+SUCCEEDED
                stdout=Done!
                json:data.count=3
                """);
        assertTrue(spec.errors().isEmpty(), "合法语法零错误: " + spec.errors());
        assertTrue(spec.hasExitCondition());
        assertEquals(4, spec.conditions().size());

        var bad = EvalRunner.parseExpect("exit=abc\nfoo~bar\nstdout~[unclosed\njson:novalue\n");
        assertEquals(4, bad.errors().size(), "每行语法错误都要报出来（fail-loud）");
    }

    // ===== 机器判定：退出码 + 断言 =====

    @Test
    void passRequiresExitZeroAndAllAssertions() {
        assertEquals(EvalRunner.PASS, EvalRunner.judge(0, "BUILD SUCCEEDED\nDone!", "").verdict(),
                "空 expect：只看退出码 0");
        var j = EvalRunner.judge(0, "log...\nBUILD SUCCEEDED", "stdout~BUILD\\s+SUCCEEDED");
        assertEquals(EvalRunner.PASS, j.verdict());
        assertEquals("", j.reason(), "PASS 无失败原因");
    }

    @Test
    void counterExampleExitZeroButAssertionMismatchIsFail() {
        // 反例一：退出码 0 但断言不匹配 = FAIL（命令成功不等于结果正确）
        var j = EvalRunner.judge(0, "BUILD FAILED with weird exit", "stdout~BUILD\\s+SUCCEEDED");
        assertEquals(EvalRunner.FAIL, j.verdict());
        assertTrue(j.reason().contains("未匹配正则"), "失败原因可定位");
    }

    @Test
    void counterExampleNonZeroExitButOutputMatchesIsFail() {
        // 反例二：退出码非 0 但输出匹配 = FAIL（输出好看不能掩盖退出码）
        var j = EvalRunner.judge(3, "BUILD SUCCEEDED", "stdout~BUILD\\s+SUCCEEDED");
        assertEquals(EvalRunner.FAIL, j.verdict());
        assertTrue(j.reason().contains("退出码 3"), "失败原因包含退出码");
    }

    @Test
    void explicitExitConditionAllowsNonZeroExpectation() {
        assertEquals(EvalRunner.PASS, EvalRunner.judge(2, "grep found nothing", "exit=2").verdict(),
                "显式 exit=2：退出码 2 判 PASS（grep 无匹配语义）");
        assertEquals(EvalRunner.FAIL, EvalRunner.judge(0, "", "exit=2").verdict(),
                "显式 exit=2：退出码 0 反而 FAIL");
        // 退出码条件成立但输出断言不成立，仍然 FAIL（AND 语义）
        assertEquals(EvalRunner.FAIL, EvalRunner.judge(2, "abc", "exit=2\nstdout~xyz").verdict());
    }

    @Test
    void brokenExpectConfigFailsLoudNeverPasses() {
        var j = EvalRunner.judge(0, "anything", "not-a-condition");
        assertEquals(EvalRunner.FAIL, j.verdict(), "expect 配置坏了 = FAIL，绝不静默判过");
        assertTrue(j.reason().contains("无法解析"));
    }

    @Test
    void jsonEqualsCondition() {
        String json = "{\"data\":{\"count\":3,\"name\":\"Dorak\"},\"ok\":true}";
        assertNull(EvalRunner.checkJsonEquals(json, "data.count", "3"));
        assertNull(EvalRunner.checkJsonEquals(json, "data.name", "Dorak"));
        assertNull(EvalRunner.checkJsonEquals(json, "ok", "true"));
        assertNotNull(EvalRunner.checkJsonEquals(json, "data.count", "4"), "值不等 = 不成立");
        assertNotNull(EvalRunner.checkJsonEquals(json, "data.missing", "x"), "路径不存在 = 不成立");
        assertNotNull(EvalRunner.checkJsonEquals("not json at all", "a", "1"), "非 JSON = 不成立（fail-loud）");
        // 输出裹着日志行时宽松一次：截取首个 { 到最后一个 }
        assertNull(EvalRunner.checkJsonEquals("POSTMAN REQUEST\n" + json + "\nDONE", "data.count", "3"));
        assertEquals(EvalRunner.PASS, EvalRunner.judge(0, json, "json:data.count=3\njson:ok=true").verdict());
    }

    @Test
    void multipleConditionsAreAnded() {
        String out = "Tests run: 12, Failed: 0\nDone!";
        assertEquals(EvalRunner.PASS,
                EvalRunner.judge(0, out, "stdout~Tests run: \\d+\nstdout=Done!").verdict());
        assertEquals(EvalRunner.FAIL,
                EvalRunner.judge(0, out, "stdout~Tests run: \\d+\nstdout=SKIPPED").verdict());
    }

    // ===== 未落地 / 跳过（分母诚实的判定基础） =====

    @Test
    void landedAndSkipSemantics() {
        assertFalse(EvalRunner.isLanded(null));
        assertFalse(EvalRunner.isLanded("  "), "空白 target = 未落地");
        assertTrue(EvalRunner.isLanded("dotnet build"));

        assertTrue(EvalRunner.shouldSkip(true, false), "requiresSandbox 且无凭据 → 跳过");
        assertFalse(EvalRunner.shouldSkip(true, true), "有凭据不跳过");
        assertFalse(EvalRunner.shouldSkip(false, false), "不需要沙箱永不跳过");
    }

    // ===== 输出截断（防无限吞内存/撑爆库） =====

    @Test
    void truncateKeepsHeadAndTail() {
        String text = "H".repeat(3000) + "M".repeat(9000) + "T".repeat(3000);
        String cut = EvalRunner.truncateHeadTail(text, 1000, 1000);
        assertTrue(cut.startsWith("H".repeat(1000)));
        assertTrue(cut.endsWith("T".repeat(1000)));
        assertTrue(cut.contains("中间省略 13000 字符"), "省略标记给出被截数量");
        assertTrue(cut.length() < text.length());
        assertEquals("short", EvalRunner.truncateHeadTail("short", 1000, 1000), "未超限原样返回");
        assertEquals("", EvalRunner.truncateHeadTail(null, 10, 10));
    }

    // ===== 超时强杀（真实进程，秒级） =====

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void timeoutForceKillsOnWindows() throws Exception {
        // ping -n 6 ≈ 5 秒；1 秒超时必须强杀并如实报告"未完成"
        long t0 = System.currentTimeMillis();
        var exec = EvalRunner.execute(List.of("cmd.exe", "/c", "ping -n 6 127.0.0.1 > nul"), null, 1);
        long elapsed = System.currentTimeMillis() - t0;
        assertFalse(exec.finished(), "超时后 finished=false");
        assertNull(exec.exitCode(), "强杀无退出码");
        assertTrue(elapsed < 4500, "超时即杀不傻等（实际耗时 " + elapsed + "ms）");
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void timeoutForceKillsOnUnix() throws Exception {
        var exec = EvalRunner.execute(List.of("bash", "-lc", "sleep 5"), null, 1);
        assertFalse(exec.finished());
        assertNull(exec.exitCode());
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void executeCapturesOutputAndExitCode() throws Exception {
        var ok = EvalRunner.execute(List.of("cmd.exe", "/c", "echo EVAL_MARK_OK"), null, 30);
        assertTrue(ok.finished());
        assertEquals(0, ok.exitCode());
        assertTrue(ok.output().contains("EVAL_MARK_OK"), "stdout 被捕获: " + ok.output());

        var bad = EvalRunner.execute(List.of("cmd.exe", "/c", "echo oops >&2 & exit /b 7"), null, 30);
        assertTrue(bad.finished());
        assertEquals(7, bad.exitCode(), "非零退出码如实上报");
        assertTrue(bad.output().contains("oops"), "stderr 合并进输出（redirectErrorStream）");
        assertEquals(EvalRunner.FAIL, EvalRunner.judge(bad.exitCode(), bad.output(), "stdout~oops").verdict(),
                "端到端：输出匹配但退出码 7 → FAIL（判定只认执行结果）");
    }
}
