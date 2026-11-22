package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * eval 执行与机器判定内核（11-21-supplier-eval-loop MVP-1）。
 * <p>
 * <b>硬纪律（PRD 用户原话："eval 是要能执行获得结果的，不能是 agent 主观判断的"）</b>：
 * 本类是 PASS/FAIL 的唯一裁决点——只认进程退出码 + 输出断言（正则/子串/JSON 等值），
 * 全路径无任何模型调用。LLM 只允许出现在"自然语言 → 可执行检查"的草稿翻译环节
 * （{@link EvalDrafts}，且本轮为确定性解析），翻译产物必须经用户确认入库并真跑出结果才算落地。
 * <p>
 * 纯函数（{@link #buildCommand} / {@link #parseExpect} / {@link #judge} / {@link #summarize}）与
 * 进程执行（{@link #execute}）分离，前者可脱进程单测。
 */
public final class EvalRunner {

    private EvalRunner() {}

    /** 合法 kind 集合（PRD 数据模型：全部客观可执行） */
    public static final Set<String> KINDS = Set.of("build", "test", "script", "http", "static");

    /** verdict 词表 */
    public static final String PASS = "PASS";
    public static final String FAIL = "FAIL";
    public static final String TIMEOUT = "TIMEOUT";
    public static final String SKIPPED = "SKIPPED";
    public static final String ERROR = "ERROR";
    public static final String RUNNING = "RUNNING";

    /** 内存/落盘输出截断上限（字符）：头 140k + 尾 60k，防无限吞内存（对齐 EmployeeService 有界读取思路） */
    static final int OUTPUT_HEAD_CAP = 140_000;
    static final int OUTPUT_TAIL_CAP = 60_000;
    /** 入库摘要截断：头 1500 + 尾 2500（结论通常在尾部） */
    static final int SUMMARY_HEAD = 1_500;
    static final int SUMMARY_TAIL = 2_500;

    /** 各 kind 的默认超时秒数（字段可配，这里只是新建/草稿预填建议） */
    public static int defaultTimeoutSec(String kind) {
        return switch (kind == null ? "" : kind) {
            case "build", "test" -> 600;   // 编译/测试天然慢
            case "http" -> 120;
            case "static" -> 60;           // grep/文件存在性类应当秒级
            default -> 120;                // script 及未知（未知 kind 在 buildCommand 会被拒）
        };
    }

    // ===== 命令构造（kind 分派） =====

    /**
     * kind 分派 + 命令构造：target 一律是完整命令行（文档化约定），
     * Windows 走 {@code cmd.exe /c}、Unix 走 {@code bash -lc}（对齐 EmployeeService.execShell 的跨平台写法）。
     * kind 的职责：合法性闸门（未知 kind 拒绝执行，fail-loud）+ 默认超时（{@link #defaultTimeoutSec}）。
     *
     * @throws IllegalArgumentException kind 非法或 target 为空——绝不静默降级成"直接执行裸串"
     */
    public static List<String> buildCommand(String kind, String target, boolean windows) {
        if (kind == null || !KINDS.contains(kind)) {
            throw new IllegalArgumentException("未知 eval kind: " + kind + "（合法值 " + KINDS + "）");
        }
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("eval target 为空（未落地），不可执行");
        }
        return windows
                ? List.of("cmd.exe", "/c", target)
                : List.of("bash", "-lc", target);
    }

    public static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    // ===== expect 断言解析 =====

    /**
     * expect 条件（每行一个，AND 语义）：
     * <ul>
     *   <li>{@code exit=<n>} — 退出码等值；expect 里没有任何 exit 条件时默认要求退出码 0</li>
     *   <li>{@code stdout~<正则>} — 输出正则查找（find，非全匹配）</li>
     *   <li>{@code stdout=<子串>} — 输出包含字面子串</li>
     *   <li>{@code json:<点路径>=<期望值>} — 输出为 JSON 且该路径值等值（字符串按字面、数字/布尔按 JSON 原语比较）</li>
     * </ul>
     */
    public record Condition(String type, String arg, String value) {}

    /** 解析结果：conditions + 是否显式声明了退出码；语法错误进 errors（判定时 fail-loud，绝不静默通过） */
    public record ExpectSpec(List<Condition> conditions, boolean hasExitCondition, List<String> errors) {}

    public static ExpectSpec parseExpect(String expect) {
        List<Condition> conditions = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        boolean hasExit = false;
        if (expect != null) {
            for (String rawLine : expect.split("\r?\n")) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;   // 空行/注释行忽略
                if (line.startsWith("exit=")) {
                    String v = line.substring("exit=".length()).trim();
                    try {
                        conditions.add(new Condition("exit", "", String.valueOf(Integer.parseInt(v))));
                        hasExit = true;
                    } catch (NumberFormatException e) {
                        errors.add("exit 条件不是整数: " + line);
                    }
                } else if (line.startsWith("stdout~")) {
                    String regex = line.substring("stdout~".length());
                    if (regex.isEmpty()) {
                        errors.add("stdout~ 后缺正则: " + line);
                    } else {
                        try {
                            Pattern.compile(regex);
                            conditions.add(new Condition("regex", regex, ""));
                        } catch (PatternSyntaxException e) {
                            errors.add("stdout~ 正则非法: " + e.getMessage());
                        }
                    }
                } else if (line.startsWith("stdout=")) {
                    String lit = line.substring("stdout=".length());
                    if (lit.isEmpty()) {
                        errors.add("stdout= 后缺子串: " + line);
                    } else {
                        conditions.add(new Condition("contains", lit, ""));
                    }
                } else if (line.startsWith("json:")) {
                    String body = line.substring("json:".length());
                    int eq = body.indexOf('=');
                    if (eq <= 0 || eq == body.length() - 1) {
                        errors.add("json 条件须为 json:<点路径>=<期望值>: " + line);
                    } else {
                        conditions.add(new Condition("json", body.substring(0, eq).trim(), body.substring(eq + 1)));
                    }
                } else {
                    errors.add("无法识别的 expect 条件: " + line
                            + "（支持 exit=<n> / stdout~<正则> / stdout=<子串> / json:<点路径>=<值>）");
                }
            }
        }
        return new ExpectSpec(conditions, hasExit, errors);
    }

    // ===== 机器判定（纯函数） =====

    /** 判定结果：verdict + 人类可读依据（失败逐条列出不满足的断言，PASS 时 reason 空） */
    public record Judgment(String verdict, String reason) {}

    /**
     * 裁决：PASS 当且仅当 ① 退出码符合条件（expect 无 exit= 时默认要求 0）且 ② 全部输出断言成立。
     * 两类反例（单测覆盖）：退出码 0 但断言不匹配 = FAIL；退出码非 0 但输出匹配 = FAIL。
     * expect 语法错误 = FAIL（fail-loud，配置坏了绝不判过）。
     */
    public static Judgment judge(int exitCode, String output, String expect) {
        ExpectSpec spec = parseExpect(expect);
        List<String> failed = new ArrayList<>();
        if (!spec.errors().isEmpty()) {
            return new Judgment(FAIL, "expect 配置无法解析（判 FAIL，不静默通过）: " + String.join("; ", spec.errors()));
        }
        String out = output == null ? "" : output;

        // ① 退出码：expect 里有 exit= 条件则逐条比对，没有则默认要求 0
        boolean exitChecked = false;
        for (Condition c : spec.conditions()) {
            if (c.type().equals("exit")) {
                exitChecked = true;
                if (exitCode != Integer.parseInt(c.value())) {
                    failed.add("退出码 " + exitCode + " ≠ 期望 " + c.value());
                }
            }
        }
        if (!exitChecked && exitCode != 0) {
            failed.add("退出码 " + exitCode + " ≠ 0（expect 未声明 exit=，默认要求 0）");
        }

        // ② 输出断言
        for (Condition c : spec.conditions()) {
            switch (c.type()) {
                case "regex" -> {
                    if (!Pattern.compile(c.arg(), Pattern.DOTALL).matcher(out).find()) {
                        failed.add("输出未匹配正则 /" + c.arg() + "/");
                    }
                }
                case "contains" -> {
                    if (!out.contains(c.arg())) {
                        failed.add("输出不包含子串 \"" + c.arg() + "\"");
                    }
                }
                case "json" -> {
                    String err = checkJsonEquals(out, c.arg(), c.value());
                    if (err != null) failed.add(err);
                }
                default -> { /* exit 已处理 */ }
            }
        }
        return failed.isEmpty()
                ? new Judgment(PASS, "")
                : new Judgment(FAIL, String.join("; ", failed));
    }

    /** JSON 点路径等值：返回 null = 成立，否则返回失败原因。输出不是合法 JSON 也算失败（fail-loud） */
    static String checkJsonEquals(String output, String path, String expected) {
        JsonElement root;
        try {
            root = JsonParser.parseString(output.trim());
        } catch (Exception whole) {
            // 宽松一次：命令输出可能裹着日志行，取第一个 { 或 [ 到最后一个 } 或 ] 再试
            int s1 = output.indexOf('{'), s2 = output.indexOf('[');
            int start = s1 >= 0 && (s2 < 0 || s1 < s2) ? s1 : s2;
            int e1 = output.lastIndexOf('}'), e2 = output.lastIndexOf(']');
            int end = Math.max(e1, e2);
            if (start < 0 || end <= start) return "输出不是合法 JSON，无法取 json:" + path;
            try {
                root = JsonParser.parseString(output.substring(start, end + 1));
            } catch (Exception part) {
                return "输出不是合法 JSON，无法取 json:" + path;
            }
        }
        JsonElement node = root;
        for (String seg : path.split("\\.")) {
            if (node == null || node.isJsonNull()) return "json:" + path + " 路径中断（值不存在）";
            if (node.isJsonObject()) {
                node = node.getAsJsonObject().get(seg);
            } else if (node.isJsonArray()) {
                try {
                    node = node.getAsJsonArray().get(Integer.parseInt(seg));
                } catch (Exception e) {
                    return "json:" + path + " 数组下标非法（" + seg + "）";
                }
            } else {
                return "json:" + path + " 路径中断（" + seg + " 不是对象/数组）";
            }
        }
        if (node == null || node.isJsonNull()) return "json:" + path + " 值不存在（期望 " + expected + "）";
        String actual = node.isJsonPrimitive() ? node.getAsString() : node.toString();
        return actual.equals(expected)
                ? null
                : "json:" + path + " = " + actual + " ≠ 期望 " + expected;
    }

    // ===== 未落地 / 跳过 语义（分母诚实） =====

    /** 已落地 = 有可执行 target。未落地的 eval 不可执行、不进通过率分母 */
    public static boolean isLanded(String target) {
        return target != null && !target.isBlank();
    }

    /**
     * requiresSandbox 跳过语义：需要沙箱凭据且当前无凭据 → SKIPPED（单列展示，不进分母）。
     * 凭据探测 = 环境变量 {@code EVAL_SANDBOX_CREDENTIALS} 非空（服务层注入 supplier，便于单测）。
     */
    public static boolean shouldSkip(boolean requiresSandbox, boolean sandboxCredsAvailable) {
        return requiresSandbox && !sandboxCredsAvailable;
    }

    // ===== 输出截断 =====

    /** 头尾保留式截断：中间以省略标记衔接（保留可定位信息，防无限吞内存/撑爆库） */
    public static String truncateHeadTail(String text, int headCap, int tailCap) {
        if (text == null) return "";
        if (text.length() <= headCap + tailCap) return text;
        return text.substring(0, headCap)
                + "\n…（中间省略 " + (text.length() - headCap - tailCap) + " 字符）…\n"
                + text.substring(text.length() - tailCap);
    }

    /** 入库/页面摘要（比落盘更短） */
    public static String summarize(String output) {
        return truncateHeadTail(output, SUMMARY_HEAD, SUMMARY_TAIL);
    }

    // ===== 进程执行（超时强杀 + 有界输出） =====

    /** 执行结果（原始素材，verdict 由 {@link #judge} 另行裁决——执行与判定分离，两者都必须客观） */
    public record Execution(boolean finished, Integer exitCode, long elapsedMs, String output) {}

    /**
     * 起进程执行：合并 stderr、独立虚拟线程有界读输出（头/尾双 cap）、waitFor 超时后
     * {@code killDescendants + destroyForcibly} 强杀（Windows 下 cmd /c 会留子进程树，只杀父进程会漏）。
     * 进程起不来（命令不存在等 IOException）→ finished=true + exitCode=null，由调用方判 ERROR。
     */
    public static Execution execute(List<String> command, File workDir, int timeoutSec)
            throws java.io.IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        if (workDir != null) pb.directory(workDir);
        pb.redirectErrorStream(true);
        long t0 = System.currentTimeMillis();
        Process p = pb.start();

        // 中文 Windows 的 cmd.exe 控制台输出是 ANSI/OEM 编码（native.encoding），不是 UTF-8；
        // 断言通常写 ASCII 正则不受影响，中文输出按本机编码解码尽量可读。
        Charset cs = isWindows()
                ? Charset.forName(System.getProperty("native.encoding", "GBK"))
                : StandardCharsets.UTF_8;
        StringBuilder head = new StringBuilder();
        StringBuilder tail = new StringBuilder();
        final long[] totalChars = {0};
        Thread reader = Thread.ofVirtual().name("eval-run-read").start(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), cs))) {
                String line;
                while ((line = r.readLine()) != null) {
                    totalChars[0] += line.length() + 1;
                    if (head.length() < OUTPUT_HEAD_CAP) {
                        head.append(line).append('\n');
                    } else {
                        tail.append(line).append('\n');
                        // 尾部滚动窗口：只留最后 OUTPUT_TAIL_CAP 字符
                        if (tail.length() > OUTPUT_TAIL_CAP * 2L) {
                            tail.delete(0, tail.length() - OUTPUT_TAIL_CAP);
                        }
                    }
                }
            } catch (Exception ignored) {}
        });

        boolean finished = p.waitFor(Math.max(1, timeoutSec), TimeUnit.SECONDS);
        if (!finished) {
            TerminalSession.killDescendants(p);   // 包内可见；先杀进程树再杀本体
            p.destroyForcibly();
        }
        reader.join(5_000);
        long elapsed = System.currentTimeMillis() - t0;
        // 有界拼装：超过 cap 的部分（含 tail 滚动窗口丢弃的中段）用省略标记显式衔接，绝不静默丢
        boolean dropped = totalChars[0] > head.length() + tail.length();
        String output = dropped
                ? head + "\n…（中间省略 " + (totalChars[0] - head.length() - tail.length()) + " 字符）…\n" + tail
                : head + tail.toString();
        return new Execution(finished, finished ? p.exitValue() : null, elapsed, output);
    }
}
