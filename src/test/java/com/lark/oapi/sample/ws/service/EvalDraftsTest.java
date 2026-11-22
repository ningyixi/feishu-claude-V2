package com.lark.oapi.sample.ws.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「从 review skill 生成初版 eval」草稿器单测（11-21-supplier-eval-loop MVP-1）。
 * <p>
 * 核心纪律断言：草稿生成<b>只做翻译、不做判分</b>——产物 target/expect 必须为空（= 未落地，
 * 不可执行、不进通过率分母），自然语言预期只进 notes/title；Draft 结构里根本不存在 verdict 字段。
 */
class EvalDraftsTest {

    @TempDir
    Path tmp;

    private Path writeSkill(String validationMd, String coreMd, String engineeringMd) throws IOException {
        Path dir = tmp.resolve("review-supplier");
        Path refs = dir.resolve("references");
        Files.createDirectories(refs);
        if (validationMd != null) Files.writeString(refs.resolve("report-and-validation.md"), validationMd, StandardCharsets.UTF_8);
        if (coreMd != null) Files.writeString(refs.resolve("core-review.md"), coreMd, StandardCharsets.UTF_8);
        if (engineeringMd != null) Files.writeString(refs.resolve("engineering-review.md"), engineeringMd, StandardCharsets.UTF_8);
        return dir;
    }

    /** 与真实 report-and-validation.md 同构的最小样本（表头「场景 | 关键预期」） */
    private static final String VALIDATION_MD = """
            # 报告、验证与行为自检

            ## 有针对性的验证

            按受影响逻辑选择验证项，不强制每次全跑：

            | 场景 | 关键预期 |
            |---|---|
            | 总价100 / 3晚、多房 | 逐晚累加精确等于100，房间倍率无重复 |
            | 实时库存0、少于/等于请求间数 | 0/不足拦截，等于可用；旧缓存兼容另测 |
            | 空行下面不是表格 |

            ## 实测证据与接口文档的可复核性

            - [E#1] 这一段不属于「场景 → 关键预期」表，不应进草稿。
            """;

    private static final String CORE_MD = """
            # 核心业务检查表

            ## 1. 标识与缓存链

            - [A#2，B#8] RP ID 拼装/解析集中在具体 Helper，静态和实时一致；供应商 rateId 是否入 ID 按 history 处理。
            - [D#5] 床型等值形态不可预知的文本维度，不在供应商项目内写关键词人工解析表——属自欺；直接调平台统一解析。

            ## 2. 查价与验价

            - [C#24] 实时库存 < 请求间数则查价不返回、验价拒绝；0库存不能绕过 `>0` 条件。
            """;

    private static final String ENGINEERING_MD = """
            # 工程、DAL、配置与静态 Job

            ## 层次与项目规范 [create-supplier]

            - [E#6] 包装对象判空与定性归属唯一落点：具体 BLL 入口；Business override 只做薄透传。
            """;

    @Test
    void validationTableRowsBecomeDrafts() throws IOException {
        List<EvalDrafts.Draft> drafts = EvalDrafts.fromValidationTable(
                writeSkill(VALIDATION_MD, null, null).resolve("references/report-and-validation.md"));

        assertEquals(2, drafts.size(), "只取「场景 | 关键预期」表的数据行（表外 bullet、缺列行不进）");
        EvalDrafts.Draft first = drafts.get(0);
        assertEquals("[场景] 总价100 / 3晚、多房", first.title());
        assertEquals("script", first.kind(), "PRD：script 是自然语言 eval 翻译的主要落点");
        // 只做翻译不做判分：target/expect 必须为空 → 未落地，不可执行、不进分母
        assertEquals("", first.target(), "草稿没有可执行 target（未落地）");
        assertEquals("", first.expect(), "草稿没有断言（未落地）");
        assertFalse(EvalRunner.isLanded(first.target()), "未落地判定成立");
        assertTrue(first.notes().contains("逐晚累加精确等于100"), "自然语言预期完整保留在 notes");
        assertTrue(first.source().contains("report-and-validation.md"), "来源可追溯");
        assertTrue(first.selected(), "表格行默认勾选（用户仍须确认后才入库）");
        assertFalse(first.enabled(), "草稿默认停用，入库由用户确认");
        assertEquals(120, first.timeoutSec());
    }

    @Test
    void checklistBulletsBecomeStaticDrafts() throws IOException {
        Path dir = writeSkill(null, CORE_MD, ENGINEERING_MD);
        List<EvalDrafts.Draft> core = EvalDrafts.fromChecklist(dir.resolve("references/core-review.md"), "static");
        List<EvalDrafts.Draft> eng = EvalDrafts.fromChecklist(dir.resolve("references/engineering-review.md"), "static");

        assertEquals(3, core.size());
        assertEquals(1, eng.size());
        EvalDrafts.Draft b1 = core.get(0);
        assertEquals("static", b1.kind(), "检查表条目建议 static（确定性规则程序，不是 LLM）");
        assertEquals("", b1.target());
        assertEquals("", b1.expect());
        assertTrue(b1.notes().startsWith("[A#2，B#8]"), "notes 保留 bullet 全文（含来源标签）");
        assertTrue(b1.title().contains("core-review § 1. 标识与缓存链"), "标题带文件与小节定位: " + b1.title());
        assertTrue(b1.title().contains("RP ID 拼装/解析集中在具体 Helper"), "标题取第一个分句: " + b1.title());
        assertFalse(b1.selected(), "检查表 bullet 量大，默认不勾选由用户挑选");
        assertEquals(60, b1.timeoutSec(), "static 默认 60s");
    }

    @Test
    void fullGenerationMergesAllThreeFiles() throws IOException {
        Path dir = writeSkill(VALIDATION_MD, CORE_MD, ENGINEERING_MD);
        List<EvalDrafts.Draft> drafts = EvalDrafts.fromReviewSkillDir(dir);
        assertEquals(6, drafts.size(), "2 表行 + 3 core bullet + 1 engineering bullet");
        // 结构上不存在判分：所有草稿一律未落地
        assertTrue(drafts.stream().allMatch(d -> d.target().isBlank() && d.expect().isBlank()),
                "全部草稿未落地——生成环节不产出任何 verdict，判定只归 EvalRunner");
    }

    @Test
    void missingFilesAndDirsFailWithReadableMessage() {
        assertThrows(IllegalArgumentException.class, () -> EvalDrafts.fromReviewSkillDir(null),
                "路径未配置要给可读提示");
        var e = assertThrows(IllegalArgumentException.class,
                () -> EvalDrafts.fromReviewSkillDir(tmp.resolve("not-exist")));
        assertTrue(e.getMessage().contains("references/"), "缺 references/ 目录报得明白: " + e.getMessage());
    }

    @Test
    void emptySkillProducesNothingButError() throws IOException {
        Path dir = writeSkill("# 空文件\n没有表格也没有条目\n", null, null);
        var e = assertThrows(IllegalArgumentException.class, () -> EvalDrafts.fromReviewSkillDir(dir));
        assertTrue(e.getMessage().contains("没有解析出任何"), "解析不出条目要明说，不静默返回空列表");
        // 缺文件不炸：core/engineering 不存在时只出表格行
        Path dir2 = writeSkill(VALIDATION_MD, null, null);
        assertEquals(2, EvalDrafts.fromReviewSkillDir(dir2).size());
    }

    @Test
    void firstClauseStripsTagsAndCuts() {
        assertEquals("RP ID 拼装/解析集中在具体 Helper，静态和实时一致",
                EvalDrafts.firstClause("[A#2，B#8] RP ID 拼装/解析集中在具体 Helper，静态和实时一致；后半句"),
                "剥来源标签、截到第一个分号");
        assertEquals("多层标签也剥掉", EvalDrafts.firstClause("[规范，D#5] [E#1] 多层标签也剥掉。句号截断"));
        String longBullet = "很长的条目内容".repeat(20);
        assertTrue(EvalDrafts.firstClause(longBullet).length() <= 61, "超 60 字符截断加省略号");
        assertTrue(EvalDrafts.firstClause(longBullet).endsWith("…"));
    }

    @Test
    void splitRowHandlesEdgePipes() {
        assertEquals(List.of("a", "b"), EvalDrafts.splitRow("| a | b |"));
        assertEquals(List.of("a", "b"), EvalDrafts.splitRow("a | b"));
        assertEquals(List.of(""), EvalDrafts.splitRow("| |"), "「| |」按 markdown 语义是 1 个空格子");
    }
}
