package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 内置通用 eval 模板库（11-21-supplier-eval-loop MVP-1.5）。
 * <p>
 * 模板清单<b>只存在于可读文件</b>（默认 {@code src/main/resources/eval-templates.json}），
 * Java 里不硬编码任何模板——改文件即生效，新增模板不需要改代码（AC16′）。加载顺序：
 * ① 配置 {@code feishu.eval-templates} 指定的外部文件 → ② 进程目录下
 * {@code src/main/resources/eval-templates.json}（开发态改文件即生效，无需重新打包）→
 * ③ classpath 兜底（打包部署）。每次调用都重新读文件，不做缓存。
 * <p>
 * 判定纪律不变：模板只提供 target/expect 的<b>文本素材</b>，PASS/FAIL 仍只由
 * {@link EvalRunner} 依进程退出码 + 输出断言裁决；本类不含任何判分逻辑，
 * 加载时校验 kind 合法性与 expect 语法（配置坏了 fail-loud，绝不放行）。
 */
public final class EvalTemplates {

    private EvalTemplates() {}

    /** 开发态默认文件位置（相对进程目录） */
    static final String DEV_FILE = "src/main/resources/eval-templates.json";
    /** classpath 兜底资源名 */
    static final String CLASSPATH_RESOURCE = "eval-templates.json";
    /** 通用脚本目录（相对进程目录的默认位置） */
    static final String DEV_SCRIPT_DIR = "scripts/eval";

    /** 模板占位符语法：{{varName}} */
    private static final Pattern VAR = Pattern.compile("\\{\\{\\s*([A-Za-z_][A-Za-z0-9_]*)\\s*}}");

    /** 上下文变量说明（页面按此渲染输入框——变量清单同样来自文件，不硬编码） */
    public record VariableSpec(String name, String label, String placeholder) {}

    /**
     * 一条模板。verified=false 的必须在页面标「未真实验证」（如 dotnet build/test 基线、配置中心协议）。
     * countsTowardScore=false = 可用性探针（MVP-1.7）：可跑、可看输出，但只回答「外部服务活着吗」，
     * 不证明本次交付的代码正确，<b>不进通过率分母</b>；缺省 true（计分项）。
     */
    public record Template(String key, String group, String title, String kind,
                           String targetTemplate, String expectTemplate, int timeoutSec,
                           boolean requiresSandbox, List<String> needsVariables,
                           String description, boolean verified, boolean countsTowardScore) {}

    /**
     * 模板库整体。httpEvidenceWarning：curl 类连通检查 ≠ Postman 实测门禁证据的固定警示文案；
     * datePolicy（MVP-1.7 规则 5）：eval 禁止写死时间的固定警示文案（住期/日期一律运行时动态生成）。
     */
    public record Library(String httpEvidenceWarning, String datePolicy,
                          List<VariableSpec> variables, List<Template> templates) {}

    /** {{var}} 展开结果：missingVariables 非空 = 变量没填齐（该模板须落成「未落地」，不进分母） */
    public record Expansion(String text, List<String> missingVariables) {}

    // ===== 加载（读文件，不硬编码） =====

    /**
     * 加载模板库。
     *
     * @param configuredFile {@code feishu.eval-templates} 配置的外部文件路径；空 = 走默认解析顺序
     * @throws IllegalStateException 文件不存在 / JSON 非法 / 模板字段非法（fail-loud，绝不静默降级成内置清单）
     */
    public static Library load(String configuredFile) throws IOException {
        if (configuredFile != null && !configuredFile.isBlank()) {
            Path p = Path.of(configuredFile.trim());
            if (!Files.isRegularFile(p)) {
                throw new IllegalStateException("feishu.eval-templates 指定的模板文件不存在: " + p.toAbsolutePath());
            }
            return parse(Files.readString(p, StandardCharsets.UTF_8), p.toAbsolutePath().toString());
        }
        Path dev = Path.of(DEV_FILE);
        if (Files.isRegularFile(dev)) {
            return parse(Files.readString(dev, StandardCharsets.UTF_8), dev.toAbsolutePath().toString());
        }
        try (InputStream in = EvalTemplates.class.getClassLoader().getResourceAsStream(CLASSPATH_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("找不到模板库文件（进程目录 " + DEV_FILE + " 与 classpath 均无）");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8), "classpath:" + CLASSPATH_RESOURCE);
        }
    }

    /** 解析 + 校验模板文件内容（kind 必须在 EvalRunner.KINDS 内、key 非空且不重复、expect 语法可解析） */
    static Library parse(String json, String origin) {
        JsonObject root;
        try {
            root = JsonParser.parseString(json).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalStateException("模板库不是合法 JSON（" + origin + "）: " + e.getMessage());
        }
        String warning = str(root, "httpEvidenceWarning", "");
        String datePolicy = str(root, "datePolicy", "");
        List<VariableSpec> variables = new ArrayList<>();
        JsonArray vs = root.has("variables") && root.get("variables").isJsonArray() ? root.getAsJsonArray("variables") : new JsonArray();
        for (JsonElement el : vs) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String name = str(o, "name", "");
            if (name.isBlank()) throw new IllegalStateException("模板库 variables 有缺 name 的条目（" + origin + "）");
            variables.add(new VariableSpec(name, str(o, "label", name), str(o, "placeholder", "")));
        }
        List<Template> templates = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        JsonArray ts = root.has("templates") && root.get("templates").isJsonArray() ? root.getAsJsonArray("templates") : new JsonArray();
        for (JsonElement el : ts) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            String key = str(o, "key", "");
            String title = str(o, "title", "");
            String kind = str(o, "kind", "");
            String target = str(o, "targetTemplate", "");
            if (key.isBlank() || title.isBlank() || target.isBlank()) {
                throw new IllegalStateException("模板缺 key/title/targetTemplate（" + origin + "）: " + key);
            }
            if (!keys.add(key)) {
                throw new IllegalStateException("模板 key 重复（" + origin + "）: " + key);
            }
            if (!EvalRunner.KINDS.contains(kind)) {
                // fail-loud：不许出现"模型判分"之类非法 kind——判定通道只有可执行五种
                throw new IllegalStateException("模板 " + key + " 的 kind 非法: " + kind
                        + "（合法值 " + EvalRunner.KINDS + "，不存在任何模型判分通道）");
            }
            String expect = str(o, "expectTemplate", "");
            List<String> expectErrs = EvalRunner.parseExpect(expect).errors();
            if (!expectErrs.isEmpty()) {
                throw new IllegalStateException("模板 " + key + " 的 expectTemplate 语法有误: " + String.join("; ", expectErrs));
            }
            List<String> needs = new ArrayList<>();
            if (o.has("needsVariables") && o.get("needsVariables").isJsonArray()) {
                for (JsonElement v : o.getAsJsonArray("needsVariables")) {
                    String n = v.isJsonPrimitive() ? v.getAsString().trim() : "";
                    if (!n.isBlank()) needs.add(n);
                }
            }
            templates.add(new Template(key, str(o, "group", "其他"), title, kind, target, expect,
                    o.has("timeoutSec") && o.get("timeoutSec").isJsonPrimitive() ? o.get("timeoutSec").getAsInt() : 0,
                    o.has("requiresSandbox") && o.get("requiresSandbox").isJsonPrimitive() && o.get("requiresSandbox").getAsBoolean(),
                    List.copyOf(needs), str(o, "description", ""),
                    o.has("verified") && o.get("verified").isJsonPrimitive() && o.get("verified").getAsBoolean(),
                    // countsTowardScore 缺省 true（计分项）；显式 false = 探针，不进通过率分母
                    !o.has("countsTowardScore") || !o.get("countsTowardScore").isJsonPrimitive()
                            || o.get("countsTowardScore").getAsBoolean()));
        }
        if (templates.isEmpty()) {
            throw new IllegalStateException("模板库没有任何模板（" + origin + "）");
        }
        return new Library(warning, datePolicy, List.copyOf(variables), List.copyOf(templates));
    }

    // ===== {{var}} 展开 =====

    /**
     * 展开模板文本里的 {{var}}：变量缺失或值为空白 → 保留原占位符并记入 missingVariables
     *（导入时据此把该 eval 落成「未落地」，页面写清缺哪个变量，不计入通过率分母）。
     */
    public static Expansion expand(String template, Map<String, String> vars) {
        if (template == null) return new Expansion("", List.of());
        Matcher m = VAR.matcher(template);
        StringBuilder sb = new StringBuilder();
        List<String> missing = new ArrayList<>();
        while (m.find()) {
            String name = m.group(1);
            String v = vars == null ? null : vars.get(name);
            if (v == null || v.isBlank()) {
                if (!missing.contains(name)) missing.add(name);
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));   // 保留 {{name}} 原样
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(v.trim()));
            }
        }
        m.appendTail(sb);
        return new Expansion(sb.toString(), List.copyOf(missing));
    }

    // ===== 通用脚本目录解析 =====

    /**
     * 解析 scripts/eval 脚本目录为绝对路径（正斜杠，Windows 下 node 也认）：
     * ① 配置 {@code feishu.eval-script-dir} → ② 进程目录下 scripts/eval（存在才用）→ ③ 相对路径兜底
     *（此时 eval 只能在后端进程目录里找到脚本——target 仍可执行，找不到会 FAIL，不会假绿）。
     */
    public static String resolveScriptDir(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim().replace('\\', '/');
        }
        Path p = Path.of(DEV_SCRIPT_DIR).toAbsolutePath().normalize();
        if (Files.isDirectory(p)) return p.toString().replace('\\', '/');
        return DEV_SCRIPT_DIR;
    }

    // ===== 小工具 =====

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && o.get(key).isJsonPrimitive() ? o.get(key).getAsString() : def;
    }

    /**
     * 任务 context_json → 变量表（扁平 JSON 对象；值一律转字符串）。
     * 库里的存量脏数据不让整页崩：解析失败返回空表（导入时按"变量全缺"落未落地，仍是诚实的）。
     */
    public static Map<String, String> contextVars(String contextJson) {
        Map<String, String> vars = new LinkedHashMap<>();
        if (contextJson == null || contextJson.isBlank()) return vars;
        try {
            JsonObject o = JsonParser.parseString(contextJson).getAsJsonObject();
            for (Map.Entry<String, JsonElement> e : o.entrySet()) {
                if (e.getValue() != null && e.getValue().isJsonPrimitive()) {
                    vars.put(e.getKey(), e.getValue().getAsString());
                }
            }
        } catch (Exception ignored) {
            // 空表 = 全部模板落未落地，不会伪装成"变量已填"
        }
        return vars;
    }
}
