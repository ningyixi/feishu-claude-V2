package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.model.entity.SupplierKbEntry;
import com.lark.oapi.sample.ws.repository.SupplierKbEntryRepository;
import com.lark.oapi.sample.ws.repository.SupplierKbPageRepository;
import com.lark.oapi.sample.ws.repository.SupplierKbRemovedRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 笔记双框 + 追加式文本入库（11-23-adhoc-append-sources S2）单测：
 * AC2 原文逐字等值（不是"包含"）/ AC3 三种填法 / AC4 老路径零变化 + 指纹去重 / 超长原文走 chunk /
 * AC1 的正文级证据：追加材料的内容逐字进入 Wiki 编译输入（SupplierWikiSources.snapshot 正文包含断言）。
 * 真文件系统（@TempDir）+ mock 仓储，无 Spring 上下文、无网络（脚手架同 SupplierKbOfficeTest）。
 */
class SupplierKbNoteDualTest {

    @TempDir
    Path dir;

    private SupplierKbEntryRepository repo;
    private SupplierKbService service;
    /** repo.save 落过的条目（findFirstByKbCodeAndTypeAndSource 的去重真值源） */
    private final List<SupplierKbEntry> saved = new ArrayList<>();

    private SupplierKbService newService() {
        repo = mock(SupplierKbEntryRepository.class);
        AtomicLong ids = new AtomicLong(100);
        when(repo.save(any(SupplierKbEntry.class))).thenAnswer(inv -> {
            SupplierKbEntry e = inv.getArgument(0);
            if (e.getId() == null) e.setId(ids.incrementAndGet());
            if (!saved.contains(e)) saved.add(e);
            return e;
        });
        when(repo.findFirstByKbCodeAndTypeAndSource(anyString(), anyString(), anyString())).thenAnswer(inv ->
                saved.stream()
                        .filter(x -> x.getKbCode().equals(inv.getArgument(0, String.class))
                                && x.getType().equals(inv.getArgument(1, String.class))
                                && x.getSource().equals(inv.getArgument(2, String.class)))
                        .findFirst());
        when(repo.findByKbCodeOrderByCreatedAtAsc(anyString())).thenAnswer(inv ->
                saved.stream().filter(x -> x.getKbCode().equals(inv.getArgument(0, String.class))).toList());
        return new SupplierKbService(repo, mock(SupplierKbRemovedRepository.class),
                mock(SupplierKbPageRepository.class), dir.toString());
    }

    private String mdOf(SupplierKbEntry e) throws Exception {
        return Files.readString(Paths.get(e.getMdPath()), StandardCharsets.UTF_8);
    }

    /** AC2 的提取契约：md 里 "## 原文\n" 之后、去掉文件收尾的单个换行 = 原文段内容 */
    private static String rawSection(String md) {
        int i = md.indexOf("## 原文\n");
        assertTrue(i >= 0, "md 必须含 ## 原文 段: " + md);
        String rest = md.substring(i + "## 原文\n".length());
        assertTrue(rest.endsWith("\n"), "文件以换行收尾");
        return rest.substring(0, rest.length() - 1);
    }

    private static String summarySection(String md) {
        int i = md.indexOf("## 主要内容\n");
        assertTrue(i >= 0, "md 必须含 ## 主要内容 段: " + md);
        int j = md.indexOf("\n\n## 原文\n", i);
        assertTrue(j > i, "两段结构固定：主要内容在前、原文在后");
        return md.substring(i + "## 主要内容\n".length(), j);
    }

    // ===== AC2：原文逐字一致（等值断言，含换行/缩进/行内多空格/空行） =====

    @Test
    void noteKeepsRawTextVerbatimWithBothSections() throws Exception {
        service = newService();
        StringBuilder raw = new StringBuilder();
        for (int i = 1; i <= 30; i++) {
            // 故意混入前导空格、行内多空格、空行、全角字符——都必须原样保留
            if (i % 7 == 0) raw.append('\n');
            else if (i % 5 == 0) raw.append("    缩进  行内多空格\t制表符 ").append(i).append('\n');
            else raw.append("第").append(i).append("行 供应商口头约定原文   尾部空格   \n");
        }
        // 30 行整（末尾去掉最后一个换行，验证"不以换行结尾的原文"同样逐字保存）
        String rawText = raw.substring(0, raw.length() - 1);
        String summary = "结论：取消接口必须带订单号；押金口径待确认。";

        var r = service.appendText("adhoc-9", "note", "对接备忘", null, summary, rawText);

        assertTrue(r.created());
        SupplierKbEntry e = r.entry();
        assertEquals("note", e.getType());
        assertEquals("DONE", e.getStatus(), () -> "note 入库即 DONE: " + e.getError());
        assertEquals("对接备忘", e.getTitle());
        String md = mdOf(e);
        assertTrue(md.startsWith("> 笔记（录入于 "), "标题行沿用现网既有格式: " + md.substring(0, 30));
        assertEquals(summary, summarySection(md), "主要内容段 = summary（strip 后）");
        assertEquals(rawText, rawSection(md), "AC2：原文段必须与粘贴内容逐字等值（含换行/缩进/多空格/空行）");
        assertTrue(e.getSource().startsWith("note-fp:"), "内容指纹做去重锚点: " + e.getSource());
    }

    @Test
    void noteWithoutCustomTitleDerivesFromSummaryFirstLine() throws Exception {
        service = newService();
        var r = service.appendText("adhoc-9", "note", null, null, "第一行要点\n第二行", "原文");
        assertEquals("第一行要点", r.entry().getTitle());
        String md = mdOf(r.entry());
        assertEquals("第一行要点\n第二行", summarySection(md));
        assertEquals("原文", rawSection(md));
    }

    // ===== AC3：只填一段仍可入库（另一段留空标记）；两段全空 → 可读错误 =====

    @Test
    void noteWithOnlyRawOrOnlySummarySucceedsWithPlaceholder() throws Exception {
        service = newService();
        var onlyRaw = service.appendText("adhoc-9", "note", null, null, "   ", "只有原文的邮件内容");
        assertTrue(onlyRaw.created());
        String md1 = mdOf(onlyRaw.entry());
        assertEquals("（未填）", summarySection(md1), "只填原文 → 主要内容段留空标记");
        assertEquals("只有原文的邮件内容", rawSection(md1), "原文仍逐字保存");

        var onlySummary = service.appendText("adhoc-9", "note", null, null, "只有结论", null);
        assertTrue(onlySummary.created());
        String md2 = mdOf(onlySummary.entry());
        assertEquals("只有结论", summarySection(md2));
        assertEquals("（未填）", rawSection(md2), "只填主要内容 → 原文段留空标记");

        var bothBlank = assertThrows(IllegalArgumentException.class,
                () -> service.appendText("adhoc-9", "note", null, null, "  ", "\n  "));
        assertTrue(bothBlank.getMessage().contains("主要内容"), bothBlank.getMessage());
        assertTrue(bothBlank.getMessage().contains("原文"), bothBlank.getMessage());
        assertTrue(bothBlank.getMessage().contains("至少填写一项"), bothBlank.getMessage());
        assertEquals(2, saved.size(), "两段全空不得产生条目");
    }

    // ===== AC6：同内容重复追加 → 去重命中，不产生重复条目 =====

    @Test
    void duplicateAppendReturnsExistingEntryWithoutCreatingNew() throws Exception {
        service = newService();
        var first = service.appendText("adhoc-9", "note", null, null, "同一份主要内容", "同一份原文");
        var second = service.appendText("adhoc-9", "note", null, null, "同一份主要内容", "同一份原文");
        var third = service.appendText("adhoc-9", "note", null, null, "同一份主要内容", "同一份原文");
        assertTrue(first.created());
        assertFalse(second.created(), "第二次追加应命中去重");
        assertFalse(third.created(), "第三次追加应命中去重");
        assertEquals(first.entry().getId(), second.entry().getId());
        assertEquals(first.entry().getId(), third.entry().getId());
        assertEquals(1, saved.stream().filter(x -> "note".equals(x.getType())).count(), "只有一条 note 条目");

        // chat 同理
        var c1 = service.appendText("adhoc-9", "chat", null, "同一段聊天记录", null, null);
        var c2 = service.appendText("adhoc-9", "chat", null, "同一段聊天记录", null, null);
        assertTrue(c1.created());
        assertFalse(c2.created());
        assertEquals(c1.entry().getId(), c2.entry().getId());
        // 内容不同 → 是新条目
        assertTrue(service.appendText("adhoc-9", "note", null, null, "另一份", null).created());
    }

    // ===== AC4：老路径零变化（工作台单框 note / chat、archiveArtifact 归档都还在用它） =====

    @Test
    void legacySingleValueNotePathIsUnchanged() throws Exception {
        service = newService();
        SupplierKbEntry e = service.add("acme", "note", "  老形态单框笔记内容  ", null);
        assertEquals("DONE", e.getStatus());
        assertEquals("", e.getSource(), "老路径 source 仍为空串（不被指纹污染）");
        String md = mdOf(e);
        assertTrue(md.startsWith("> 笔记（录入于 "), md);
        assertTrue(md.endsWith("老形态单框笔记内容\n"), "老路径 = 单段正文（strip 后直写），无双段结构: " + md);
        assertFalse(md.contains("## 主要内容"), "老路径不得出现双段标题");
        assertFalse(md.contains("## 原文"), "老路径不得出现双段标题");

        SupplierKbEntry c = service.add("acme", "chat", "老形态聊天记录", null);
        String cmd = mdOf(c);
        assertTrue(cmd.startsWith("> 聊天记录补充（录入于 "), cmd);
        assertTrue(cmd.endsWith("老形态聊天记录\n"), cmd);
        assertEquals("", c.getSource());
    }

    @Test
    void appendedChatUsesSameArtifactShapeAsLegacy() throws Exception {
        service = newService();
        var r = service.appendText("adhoc-9", "chat", "群聊摘要", "  对接群里确认：字段改用 v2  ", null, null);
        assertTrue(r.created());
        String md = mdOf(r.entry());
        assertTrue(md.startsWith("> 聊天记录补充（录入于 "), md);
        assertTrue(md.endsWith("）\n\n对接群里确认：字段改用 v2\n"), "chat 产物与老格式同款（strip 后单段）: " + md);
        assertEquals("群聊摘要", r.entry().getTitle());
        assertTrue(r.entry().getSource().startsWith("chat-fp:"));
    }

    // ===== 超长原文仍走 DocParser.chunk =====

    @Test
    void oversizedRawIsChunked() throws Exception {
        service = newService();
        String bigRaw = ("供应商合同条款原文行，含固定关键词 FORCE_MAJEURE_CLAUSE_7788。\n").repeat(400);   // >6000 字符
        var r = service.appendText("adhoc-9", "note", null, null, "合同要点", bigRaw);
        String md = mdOf(r.entry());
        assertTrue(md.contains("## ▍分块 2"), "超长原文按既有 chunk 语义分块: len=" + md.length());
        assertTrue(md.contains("FORCE_MAJEURE_CLAUSE_7788"), "分块不丢正文");
    }

    // ===== AC1 的正文级证据：追加材料逐字进入 Wiki 编译输入 =====

    /**
     * SupplierWikiSources.snapshot 是 Wiki 编译的唯一取材口（SupplierWikiService.build → snapshot）。
     * 本测用真 KB 服务 + 真取材口断言：追加的 md 文档与笔记原文里的固定关键词出现在编译输入正文里
     * （包含关系断言，不靠人眼）。真实模型编译超出单测边界，由流水线顺序（INGEST 全 DONE → WIKI build）
     * 在 AdHocAppendSourcesTest 钉住。
     */
    @Test
    void appendedMaterialsFlowIntoWikiCompilationInputVerbatim() throws Exception {
        service = newService();
        // ① 追加一个本地 md 文档来源（入库即 DONE，内容来自文件）
        Path mdFile = dir.resolve("supplier-api-notes.md");
        Files.writeString(mdFile, "# 接口变更\n\n取消接口必须携带 CANCEL_NEEDS_ORDER_NO_V2 字段。\n", StandardCharsets.UTF_8);
        service.add("adhoc-9", "md", mdFile.toString(), null);
        // ② 追加一条双框笔记（原文含固定关键词）
        service.appendText("adhoc-9", "note", null, null, "押金口径待确认",
                "口头约定原文：押金按房费三倍收取，关键词 VERBAL_DEPOSIT_TRIPLE。");

        SupplierJob adapter = new SupplierJob();
        adapter.setSupplierCode("adhoc-9");
        adapter.setKnowledgeMode("WIKI");
        var sources = new SupplierWikiSources(service, mock(SupplierKbPageRepository.class));
        var snapshot = sources.snapshot(adapter);

        String all = String.join("\n", snapshot.sources().stream().map(s -> s.content()).toList());
        assertTrue(all.contains("CANCEL_NEEDS_ORDER_NO_V2"), "追加的文档来源关键词必须进入 Wiki 编译输入");
        assertTrue(all.contains("VERBAL_DEPOSIT_TRIPLE"), "笔记原文关键词必须逐字进入 Wiki 编译输入");
        assertTrue(all.contains("押金按房费三倍收取"), "原文正文进入编译输入");
        assertFalse(all.contains("> 笔记（录入于"), "样板标题行被 stripInjected 剥掉，正文只留真实内容");
        assertEquals(2, snapshot.sources().size(), "两条追加材料都进快照");
    }
}
