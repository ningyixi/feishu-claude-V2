package com.lark.oapi.sample.ws.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 「从 review skill 生成初版 eval」草稿器（11-21-supplier-eval-loop MVP-1）。
 * <p>
 * <b>只做翻译、不做判分</b>（PRD 纪律 2）：本类是确定性文本解析——把 review-supplier 的
 * 「场景 → 关键预期」表（references/report-and-validation.md）与核心/工程检查表条目
 * （core-review.md / engineering-review.md）逐条搬成 <b>草稿</b>：title/notes 携带自然语言预期，
 * target/expect 留空（= 未落地，不可执行、不进分母）。草稿必须经用户在页面确认后才入库，
 * 入库后仍需补出可执行 target 并试跑真跑出结果，才算"已落地"。
 * <p>
 * 本轮不调用任何模型（ClaudeCodeService 一律不碰）；后续若引入 LLM 辅助翻译，
 * 也只允许产出这里的草稿形态，判定仍归 {@link EvalRunner}。
 */
public final class EvalDrafts {

    private EvalDrafts() {}

    /** 草稿总条数上限（防解析异常时刷屏） */
    static final int MAX_DRAFTS = 120;

    /**
     * 一条草稿。selected = 前端确认列表的默认勾选态：
     * 「场景 → 关键预期」表条目默认勾选（天然的测试用例素材）；检查表 bullet 默认不勾（量大，用户挑选）。
     */
    public record Draft(String title, String kind, String target, String expect, int timeoutSec,
                        boolean requiresSandbox, boolean enabled, String notes, String source,
                        boolean selected) {}

    /**
     * 读取 review skill 目录生成草稿（只读，绝不写该目录）。
     *
     * @param reviewSkillDir review-supplier skill 根目录（含 references/ 子目录）
     * @throws IllegalArgumentException 路径未配置/不存在/没有任何可解析条目
     */
    public static List<Draft> fromReviewSkillDir(Path reviewSkillDir) throws IOException {
        if (reviewSkillDir == null) {
            throw new IllegalArgumentException("请先在任务设置里填写 review-supplier skill 路径");
        }
        Path refs = reviewSkillDir.resolve("references");
        if (!Files.isDirectory(refs)) {
            throw new IllegalArgumentException("review skill 路径下没有 references/ 目录: " + reviewSkillDir);
        }
        List<Draft> drafts = new ArrayList<>();
        drafts.addAll(fromValidationTable(refs.resolve("report-and-validation.md")));
        drafts.addAll(fromChecklist(refs.resolve("core-review.md"), "static"));
        drafts.addAll(fromChecklist(refs.resolve("engineering-review.md"), "static"));
        if (drafts.isEmpty()) {
            throw new IllegalArgumentException("review skill 里没有解析出任何「场景 → 关键预期」表行或检查表条目: " + refs);
        }
        return drafts.size() > MAX_DRAFTS ? drafts.subList(0, MAX_DRAFTS) : drafts;
    }

    // ===== report-and-validation.md：「场景 | 关键预期」表 =====

    static List<Draft> fromValidationTable(Path md) throws IOException {
        List<Draft> out = new ArrayList<>();
        if (!Files.isRegularFile(md)) return out;
        String section = "";
        boolean inTable = false;
        for (String line : Files.readAllLines(md, StandardCharsets.UTF_8)) {
            String t = line.strip();
            if (t.startsWith("#")) {
                section = t.replaceAll("^#+\\s*", "");
                inTable = false;
                continue;
            }
            if (!t.startsWith("|")) { inTable = false; continue; }
            List<String> cells = splitRow(t);
            if (cells.isEmpty()) continue;
            boolean isHeader = cells.stream().anyMatch(c -> c.contains("场景"))
                    && cells.stream().anyMatch(c -> c.contains("预期"));
            if (isHeader) { inTable = true; continue; }
            boolean isSeparator = cells.stream().allMatch(c -> c.matches(":?-{3,}:?"));
            if (isSeparator || !inTable || cells.size() < 2) continue;
            String scene = cells.get(0);
            String expected = cells.get(1);
            if (scene.isBlank() || expected.isBlank()) continue;
            out.add(new Draft(
                    "[场景] " + scene,
                    "script",                       // PRD：script 是自然语言 eval 翻译的主要落点
                    "", "",                          // target/expect 留空 = 未落地
                    EvalRunner.defaultTimeoutSec("script"),
                    false, false,
                    "关键预期：" + expected,
                    md.getFileName() + " · " + section,
                    true));
            if (out.size() >= MAX_DRAFTS) break;
        }
        return out;
    }

    // ===== core-review.md / engineering-review.md：## 小节 + "- " 检查条目 =====

    static List<Draft> fromChecklist(Path md, String kind) throws IOException {
        List<Draft> out = new ArrayList<>();
        if (!Files.isRegularFile(md)) return out;
        String section = md.getFileName().toString();
        for (String line : Files.readAllLines(md, StandardCharsets.UTF_8)) {
            String t = line.strip();
            if (t.startsWith("##")) {
                section = md.getFileName().toString().replace(".md", "") + " § " + t.replaceAll("^#+\\s*", "");
                continue;
            }
            if (!t.startsWith("- ") || out.size() >= MAX_DRAFTS) continue;
            String bullet = t.substring(2).strip();
            if (bullet.isBlank()) continue;
            out.add(new Draft(
                    "[" + section + "] " + firstClause(bullet),
                    kind,
                    "", "",
                    EvalRunner.defaultTimeoutSec(kind),
                    false, false,
                    bullet,
                    section,
                    false));
        }
        return out;
    }

    /** bullet → 短标题：去掉前置来源标签 [A#2，规范]，截到第一个句读，最长 60 字符 */
    static String firstClause(String bullet) {
        String s = bullet.replaceAll("^(\\[[^\\]]*\\]\\s*)+", "").strip();
        int cut = s.length();
        for (String stop : new String[]{"；", "。", ";", "\n"}) {
            int i = s.indexOf(stop);
            if (i > 0 && i < cut) cut = i;
        }
        s = s.substring(0, cut).strip();
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    /** markdown 表行拆格：去首尾 |，按 | 切（不处理转义 \| —— review skill 表格里没有） */
    static List<String> splitRow(String row) {
        String t = row.strip();
        if (t.startsWith("|")) t = t.substring(1);
        if (t.endsWith("|")) t = t.substring(0, t.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String c : t.split("\\|", -1)) cells.add(c.strip());
        return cells;
    }
}
