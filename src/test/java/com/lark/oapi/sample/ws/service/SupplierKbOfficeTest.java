package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.model.entity.SupplierKbEntry;
import com.lark.oapi.sample.ws.repository.SupplierKbEntryRepository;
import com.lark.oapi.sample.ws.repository.SupplierKbRemovedRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

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
import static org.mockito.Mockito.*;

/**
 * Office 类型（docx/pptx/xlsx/doc）入库全链路单测（11-23-kb-office-documents）：
 * add 分派（OOXML=RAW 异步 / .doc=HWPF 同步即 DONE）、magic bytes 改名件按真实字节解析（AC2b）、
 * O2b 失败分类不产生条目（加密/损坏/空正文）、reparse 白名单（AC6）、大 xlsx 截断 meta（AC3/O4）、
 * 图片型 pptx 诚实失败（AC7/O5）、syncFromJob 类型归属（O3）。
 */
class SupplierKbOfficeTest {

    @TempDir
    Path dir;

    private SupplierKbEntryRepository repo;
    private SupplierKbService service;

    private SupplierKbService newService() {
        repo = mock(SupplierKbEntryRepository.class);
        AtomicLong ids = new AtomicLong(100);
        when(repo.save(any(SupplierKbEntry.class))).thenAnswer(inv -> {
            SupplierKbEntry e = inv.getArgument(0);
            if (e.getId() == null) e.setId(ids.incrementAndGet());
            return e;
        });
        return new SupplierKbService(repo, mock(SupplierKbRemovedRepository.class), null, dir.toString());
    }

    // ===== OOXML：RAW → reparse → DONE =====

    @Test void addDocxRawThenReparseProducesMarkdownArtifact() throws Exception {
        service = newService();
        Path docx = OfficeFixtures.richDocx(dir, "Integration Doc (final).docx");
        SupplierKbEntry e = service.add("acme", "docx", docx.toString(), null);
        assertEquals("docx", e.getType());
        assertEquals("RAW", e.getStatus(), "OOXML 同 pdf/html：入库 RAW，点解析后提取");
        assertEquals("Integration Doc (final)", e.getTitle(), "标题=去扩展名文件名（空格/括号保留）");
        when(repo.findById(e.getId())).thenReturn(Optional.of(e));

        service.reparse(e.getId());
        awaitTerminal(e);

        assertEquals("DONE", e.getStatus(), () -> "解析应成功: " + e.getError());
        String md = Files.readString(Paths.get(e.getMdPath()), StandardCharsets.UTF_8);
        assertTrue(md.contains("> 来源Word(docx)文件: "), md.substring(0, Math.min(120, md.length())));
        assertTrue(md.contains("# 第一章 接口概述"), md);
        assertFalse(md.contains("oldField"), "修订删除内容不得进产物");
        assertTrue(e.getMetaJson().contains("\"localOffice\":true"), e.getMetaJson());
        assertTrue(e.getMetaJson().contains("\"chars\""), e.getMetaJson());
    }

    @Test void addXlsxReparseProducesTablesAndSheetMeta() throws Exception {
        service = newService();
        List<String[]> rows = List.of(new String[]{"hotelId", "name"}, new String[]{"n:1", "北京饭店"});
        Path xlsx = OfficeFixtures.xlsx(dir, "hotels.xlsx", new String[]{"酒店"}, List.of(rows), List.of());
        SupplierKbEntry e = service.add("acme", "xlsx", xlsx.toString(), null);
        assertEquals("xlsx", e.getType());
        when(repo.findById(e.getId())).thenReturn(Optional.of(e));

        service.reparse(e.getId());
        awaitTerminal(e);

        assertEquals("DONE", e.getStatus(), () -> "解析应成功: " + e.getError());
        String md = Files.readString(Paths.get(e.getMdPath()), StandardCharsets.UTF_8);
        assertTrue(md.contains("| hotelId | name |"), md);
        assertTrue(md.contains("北京饭店"), md);
        assertTrue(e.getMetaJson().contains("\"rows\":2"), e.getMetaJson());
        assertTrue(e.getMetaJson().contains("\"cols\":2"), e.getMetaJson());
    }

    // ===== .doc：magic bytes / O2b 分类 / AC2b =====

    @Test void docRenamedFromDocxParsedByRealBytesWithMislabeledMeta() throws Exception {
        service = newService();
        Path renamed = OfficeFixtures.richDocx(dir, "供应商文档.doc");   // docx 内容顶着 .doc 扩展名
        SupplierKbEntry e = service.add("acme", "doc", renamed.toString(), null);
        assertEquals("doc", e.getType(), "条目 type 保留声明类型（流水线幂等匹配依赖它）");
        assertEquals("RAW", e.getStatus(), "真实字节是 OOXML → 走 docx 异步解析路径");
        assertTrue(e.getMetaJson().contains("\"mislabeledExtension\":true"), "AC2b 改名件要标记: " + e.getMetaJson());
        when(repo.findById(e.getId())).thenReturn(Optional.of(e));

        service.reparse(e.getId());
        awaitTerminal(e);

        assertEquals("DONE", e.getStatus(), () -> "按真实字节(docx)解析应成功: " + e.getError());
        String md = Files.readString(Paths.get(e.getMdPath()), StandardCharsets.UTF_8);
        assertTrue(md.contains("# 第一章 接口概述"), md);
        assertTrue(e.getMetaJson().contains("\"mislabeledExtension\":true"), "重解析后标记仍须保留: " + e.getMetaJson());
    }

    @Test void encryptedDocRejectedWithGuidanceAndNoEntry() throws Exception {
        service = newService();
        Path enc = OfficeFixtures.encryptedOle2(dir, "protected.doc");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.add("acme", "doc", enc.toString(), null));
        assertTrue(ex.getMessage().contains("密码"), ex.getMessage());
        assertTrue(ex.getMessage().contains("另存为"), "要给可执行下一步: " + ex.getMessage());
        verify(repo, never()).save(any(SupplierKbEntry.class));   // O2b：不产生任何 KB 条目
    }

    @Test void corruptOle2NamedDocxRejectedWithGuidanceAndNoEntry() throws Exception {
        service = newService();
        Path fake = OfficeFixtures.fakeWordOle2(dir, "legacy.docx");   // OLE2 内容顶着 .docx 扩展名
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.add("acme", "docx", fake.toString(), null));
        assertTrue(ex.getMessage().contains("损坏") || ex.getMessage().contains("另存为"), ex.getMessage());
        verify(repo, never()).save(any(SupplierKbEntry.class));
    }

    @Test void legacyPptRejectedWithSaveAsPptxGuidance() throws Exception {
        service = newService();
        Path ppt = OfficeFixtures.legacyPptOle2(dir, "deck.pptx");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.add("acme", "pptx", ppt.toString(), null));
        assertTrue(ex.getMessage().contains("另存为 .pptx"), ex.getMessage());
        verify(repo, never()).save(any(SupplierKbEntry.class));
    }

    @Test void nonOfficeFileRejectedReadable() throws Exception {
        service = newService();
        Path junk = dir.resolve("junk.docx");
        Files.write(junk, "just text".getBytes(StandardCharsets.UTF_8));
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.add("acme", "docx", junk.toString(), null));
        assertTrue(ex.getMessage().contains("不是有效的 Office 文件"), ex.getMessage());
        // 扩展名不符也拦
        Path pdf = dir.resolve("a.pdf");
        Files.write(pdf, "%PDF-1.4".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> service.add("acme", "docx", pdf.toString(), null));
        verify(repo, never()).save(any(SupplierKbEntry.class));
    }

    // ===== O4/O5 =====

    @Test void bigXlsxTruncatedWithMetaAndHonestSummary() throws Exception {
        service = newService();
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"hotelId", "name", "city"});
        for (int i = 0; i < 3000; i++) rows.add(new String[]{"n:" + i, "hotel-name-" + i, "city-value-" + i});
        Path xlsx = OfficeFixtures.xlsx(dir, "hot-selling.xlsx", new String[]{"动销酒店"}, List.of(rows), List.of());
        SupplierKbEntry e = service.add("acme", "xlsx", xlsx.toString(), null);
        when(repo.findById(e.getId())).thenReturn(Optional.of(e));

        service.reparse(e.getId());
        awaitTerminal(e);

        assertEquals("DONE", e.getStatus(), () -> "解析应成功: " + e.getError());
        assertTrue(e.getMetaJson().contains("\"truncated\":true"), "AC3: meta 必须标记截断: " + e.getMetaJson());
        assertTrue(e.getMetaJson().contains("\"rows\":3001"), "真实行数: " + e.getMetaJson());
        String md = Files.readString(Paths.get(e.getMdPath()), StandardCharsets.UTF_8);
        assertTrue(md.contains("共 3001 行 3 列"), "摘要必须含真实行列数");
        assertTrue(md.contains("已截断") && md.contains("完整数据见本地文件"), md.substring(Math.max(0, md.length() - 400)));
        assertTrue(md.length() < 120_000, "单条产物不得吃光 contextText 120k 共享预算: " + md.length());
    }

    @Test void sparsePptxReparseFailsWithHonestGuidance() throws Exception {
        service = newService();
        Path pptx = OfficeFixtures.pptx(dir, "Kick-Off (1).pptx",
                java.util.Map.of(1, new String[]{"Technical Kick-Off"}, 2, new String[]{"截图"}));
        SupplierKbEntry e = service.add("acme", "pptx", pptx.toString(), null);
        when(repo.findById(e.getId())).thenReturn(Optional.of(e));

        service.reparse(e.getId());
        awaitTerminal(e);

        assertEquals("FAILED", e.getStatus(), "AC7: 图片型 pptx 不许静默 DONE");
        assertTrue(e.getError().contains("图片/截图为主"), e.getError());
        assertTrue(e.getError().contains("URL 或 PDF"), e.getError());
    }

    // ===== AC6 reparse 白名单 =====

    @Test void reparseWhitelistCoversOfficeTypes() throws Exception {
        service = newService();
        Path docx = OfficeFixtures.richDocx(dir, "w.docx");
        for (String type : List.of("docx", "pptx", "xlsx", "doc")) {
            SupplierKbEntry e = givenEntry(50L + type.hashCode(), type, docx.toString(), "t-" + type);
            assertDoesNotThrow(() -> service.reparse(e.getId()), type + " 应在 reparse 白名单内（AC6）");
            awaitTerminal(e);   // 收敛异步线程，避免串台
        }
    }

    // ===== O3 syncFromJob =====

    @Test void syncFromJobMapsOfficeTypesAndSkipsUnparsableDoc() throws Exception {
        service = newService();
        Path docx = OfficeFixtures.richDocx(dir, "a.docx");
        Path pptx = OfficeFixtures.pptx(dir, "b.pptx", java.util.Map.of(1, new String[]{"内容".repeat(80)}));
        Path xlsx = OfficeFixtures.xlsx(dir, "c.xlsx", new String[]{"S"},
                List.of(List.of(new String[]{"h"}, new String[]{"v"})), List.of());
        Path encDoc = OfficeFixtures.encryptedOle2(dir, "secret.doc");

        SupplierJob job = new SupplierJob();
        job.setId(9L);
        job.setSupplierCode("acme");
        job.setDocsJson(docsArrayOf(docx.toString(), pptx.toString(), xlsx.toString(), encDoc.toString()));

        service.syncFromJob(job);

        ArgumentCaptor<SupplierKbEntry> cap = ArgumentCaptor.forClass(SupplierKbEntry.class);
        verify(repo, times(3)).save(cap.capture());   // 加密 .doc 被跳过：不产生条目
        List<SupplierKbEntry> saved = cap.getAllValues();
        assertEquals("docx", saved.get(0).getType(), ".docx 不得再被误归 pdf");
        assertEquals("pptx", saved.get(1).getType());
        assertEquals("xlsx", saved.get(2).getType());
        assertEquals(docx.toString(), saved.get(0).getSource());
    }

    // ===== 工具 =====

    private static String docsArrayOf(String... paths) {
        JsonArray arr = new JsonArray();
        for (String p : paths) {
            JsonObject d = new JsonObject();
            d.addProperty("type", "path");
            d.addProperty("value", p);
            arr.add(d);
        }
        return arr.toString();
    }

    private SupplierKbEntry givenEntry(Long id, String type, String source, String title) {
        SupplierKbEntry e = new SupplierKbEntry();
        e.setId(id);
        e.setKbCode("acme");
        e.setType(type);
        e.setStatus("RAW");
        e.setSource(source);
        e.setTitle(title);
        e.setError("");
        when(repo.findById(id)).thenReturn(Optional.of(e));
        return e;
    }

    /** reparse 走 afterCommit → 虚拟线程异步 parseNow：轮询等待条目离开过渡态（≤15s） */
    private static void awaitTerminal(SupplierKbEntry e) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (List.of("PENDING", "PARSING").contains(e.getStatus()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }
}
