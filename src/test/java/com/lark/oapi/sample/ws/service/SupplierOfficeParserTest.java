package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.service.SupplierOfficeParser.DocParseException;
import com.lark.oapi.sample.ws.service.SupplierOfficeParser.Limits;
import com.lark.oapi.sample.ws.service.SupplierOfficeParser.Magic;
import com.lark.oapi.sample.ws.service.SupplierOfficeParser.Parsed;
import com.lark.oapi.sample.ws.service.SupplierOfficeParser.Resolution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SupplierOfficeParser 单测：程序化最小 OOXML 夹具覆盖
 * docx（标题/tab/域代码/修订删除/表格）、pptx（数值序）、xlsx（表格/截断/5 sheet 上限）、
 * zip 安全（炸弹预检/解压硬上限/加密 entry/结构缺失）、magic bytes 分类与 O2b 失败分类。
 * 真实 Word97 二进制的成功态无法凭空构造，由任务回报中的真实文件验证覆盖。
 */
class SupplierOfficeParserTest {

    @TempDir
    Path dir;

    // ===== docx =====

    @Test void docxKeepsHeadingsTablesAndSkipsDeletedAndFieldCode() throws Exception {
        Path f = OfficeFixtures.richDocx(dir, "rich.docx");
        Parsed p = SupplierOfficeParser.parse(f, "docx");
        String md = p.markdown();
        assertTrue(md.contains("# 第一章 接口概述"), "Heading1 应转一级标题: " + md);
        assertTrue(md.contains("## 1.1 认证方式"), "Heading2 应转二级标题: " + md);
        assertTrue(md.contains("本接口提供酒店静态信息查询。 分页返回。"), "w:tab 应转空格: " + md);
        assertFalse(md.contains("MERGEFORMAT"), "w:instrText 域代码必须跳过: " + md);
        assertFalse(md.contains("oldField"), "w:del 修订删除内容必须跳过（不许当现行事实）: " + md);
        assertTrue(md.contains("newField"), "w:ins 修订插入（现行内容）必须保留: " + md);
        assertTrue(md.contains("| 字段 | 类型 |"), "表格应转 markdown 表: " + md);
        assertTrue(md.contains("| hotelId | string |"), "表格数据行: " + md);
        assertTrue(p.extractedChars() >= SupplierOfficeParser.MIN_TEXT_CHARS, "抽取字数: " + p.extractedChars());
        assertFalse(p.truncated());
    }

    @Test void docxMissingDocumentXmlReadableError() throws Exception {
        Path f = OfficeFixtures.zip(dir, "broken.docx", Map.of("foo/bar.xml", "<x/>"));
        IOException ex = assertThrows(IOException.class, () -> SupplierOfficeParser.parse(f, "docx"));
        assertTrue(ex.getMessage().contains("缺少 word/document.xml"), ex.getMessage());
    }

    @Test void docxNonXmlEntryReportsEncryptionOrCorruption() throws Exception {
        byte[] junk = new byte[64];
        for (int i = 0; i < junk.length; i++) junk[i] = (byte) (0x9C + (i % 50));   // 首字节非 '<'
        Path f = OfficeFixtures.zipBytes(dir, "enc.docx", Map.of("word/document.xml", junk));
        IOException ex = assertThrows(IOException.class, () -> SupplierOfficeParser.parse(f, "docx"));
        assertTrue(ex.getMessage().contains("不是有效 XML"), ex.getMessage());
        assertTrue(ex.getMessage().contains("加密") || ex.getMessage().contains("损坏"), ex.getMessage());
    }

    // ===== pptx =====

    @Test void pptxSlidesInNumericOrderNotLexicographic() throws Exception {
        Map<Integer, String[]> slides = new LinkedHashMap<>();
        slides.put(1, new String[]{"第一页要点：对接总览"});
        slides.put(2, new String[]{"第二页要点：鉴权说明"});
        slides.put(10, new String[]{"第十页要点：价格推送"});
        Path f = OfficeFixtures.pptx(dir, "deck.pptx", slides);   // 夹具故意按 10,2,1 的 zip 顺序写入
        Parsed p = SupplierOfficeParser.parse(f, "pptx");
        String md = p.markdown();
        assertEquals(3, p.slides());
        int i1 = md.indexOf("## Slide 1\n"), i2 = md.indexOf("## Slide 2\n"), i10 = md.indexOf("## Slide 10\n");
        assertTrue(i1 >= 0 && i2 > i1 && i10 > i2, "slide 必须数值有序（slide2 在 slide10 前）: " + md);
        assertTrue(md.indexOf("第一页要点") < md.indexOf("第二页要点"), md);
        assertTrue(md.indexOf("第二页要点") < md.indexOf("第十页要点"), md);
    }

    @Test void pptxSparseTextBelowHonestyThreshold() throws Exception {
        Path f = OfficeFixtures.pptx(dir, "sparse.pptx", Map.of(1, new String[]{"截图为主"}));
        Parsed p = SupplierOfficeParser.parse(f, "pptx");
        assertTrue(p.extractedChars() < SupplierOfficeParser.MIN_TEXT_CHARS,
                "图片型 pptx 抽取字数应低于诚实阈值，实际: " + p.extractedChars());
    }

    @Test void pptxEmptySlideMarkedHonestly() throws Exception {
        Map<Integer, String[]> slides = new LinkedHashMap<>();
        slides.put(1, new String[]{"有内容的页"});
        slides.put(2, new String[]{});
        Path f = OfficeFixtures.pptx(dir, "mixed.pptx", slides);
        String md = SupplierOfficeParser.parse(f, "pptx").markdown();
        assertTrue(md.contains("（本页无可抽取文本）"), "空页要如实标注: " + md);
    }

    @Test void pptxNoSlidesReadableError() throws Exception {
        Path f = OfficeFixtures.zip(dir, "empty.pptx", Map.of("ppt/presentation.xml", OfficeFixtures.PRESENTATION_XML));
        IOException ex = assertThrows(IOException.class, () -> SupplierOfficeParser.parse(f, "pptx"));
        assertTrue(ex.getMessage().contains("缺少 ppt/slides"), ex.getMessage());
    }

    // ===== xlsx =====

    @Test void xlsxRendersSheetsAsMarkdownTables() throws Exception {
        List<String> shared = List.of("酒店ID", "名称", "北京饭店");
        List<String[]> rows1 = List.of(
                new String[]{"s:0", "s:1"},
                new String[]{"n:1001", "s:2"},
                new String[]{"n:1002", "上海宾馆"});
        List<String[]> rows2 = List.of(new String[]{"s:0"}, new String[]{"n:7"});
        Path f = OfficeFixtures.xlsx(dir, "hotels.xlsx", new String[]{"酒店列表", "价格"},
                List.of(rows1, rows2), shared);
        Parsed p = SupplierOfficeParser.parse(f, "xlsx");
        String md = p.markdown();
        assertTrue(md.contains("### Sheet: 酒店列表"), md);
        assertTrue(md.contains("| 酒店ID | 名称 |"), "共享字符串表头: " + md);
        assertTrue(md.contains("北京饭店") && md.contains("1001") && md.contains("上海宾馆"), md);
        assertTrue(md.contains("### Sheet: 价格"), "第二个 sheet 也要转换: " + md);
        assertEquals(5, p.rows(), "真实总行数（sheet1 3 行 + sheet2 2 行）");
        assertEquals(2, p.cols());
        assertEquals(2, p.sheets());
        assertFalse(p.truncated());
    }

    @Test void xlsxTruncatesWithRealRowColCounts() throws Exception {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"hotelId", "name", "city"});
        for (int i = 0; i < 800; i++) rows.add(new String[]{"n:" + i, "hotel-name-" + i, "city-value-" + i});
        Path f = OfficeFixtures.xlsx(dir, "big.xlsx", new String[]{"酒店"}, List.of(rows), List.of());
        Parsed p = SupplierOfficeParser.parse(f, "xlsx", new Limits(256L * 1024 * 1024, 96L * 1024 * 1024, 3000));
        assertTrue(p.truncated(), "超预算必须标记截断");
        assertEquals(801, p.rows(), "截断后行数统计仍须真实");
        assertEquals(3, p.cols());
        String md = p.markdown();
        assertTrue(md.contains("共 801 行 3 列"), "摘要必须含真实行列数");
        assertTrue(md.contains("已截断"), md);
        assertTrue(md.contains("完整数据见本地文件"), md);
        assertTrue(md.length() < 6000, "产物必须受预算约束: " + md.length());
    }

    @Test void xlsxCapsAtFiveSheetsAndNamesExcluded() throws Exception {
        String[] names = {"S1", "S2", "S3", "S4", "S5", "S6", "S7"};
        List<List<String[]>> sheets = new ArrayList<>();
        for (int i = 0; i < 7; i++) sheets.add(List.of(new String[]{"a", "b"}, new String[]{"n:1", "n:2"}));
        Path f = OfficeFixtures.xlsx(dir, "many.xlsx", names, sheets, List.of());
        Parsed p = SupplierOfficeParser.parse(f, "xlsx");
        String md = p.markdown();
        assertEquals(7, p.sheets(), "sheet 总数要如实统计");
        assertTrue(p.truncated());
        assertTrue(md.contains("### Sheet: S5") && !md.contains("### Sheet: S6"), "只转换前 5 个: " + md);
        assertTrue(md.contains("仅转换前 5 个"), md);
        assertTrue(md.contains("S6、S7"), "未纳入的 sheet 要列名: " + md);
    }

    @Test void xlsxNoWorkbookReadableError() throws Exception {
        Path f = OfficeFixtures.zip(dir, "bad.xlsx", Map.of("xl/worksheets/sheet1.xml",
                OfficeFixtures.sheetXml(List.<String[]>of(new String[]{"n:1"}))));
        IOException ex = assertThrows(IOException.class, () -> SupplierOfficeParser.parse(f, "xlsx"));
        assertTrue(ex.getMessage().contains("缺少 xl/workbook.xml"), ex.getMessage());
    }

    // ===== zip 安全 =====

    @Test void zipBombRejectedByDeclaredSizePrecheck() throws Exception {
        Path f = bombDocx("bomb.docx");
        assertTrue(Files.size(f) < 100_000, "高重复内容压缩后应很小（炸弹形态）: " + Files.size(f));
        Limits lim = new Limits(1_000_000, 96L * 1024 * 1024, 90_000);   // 声明解压总量 5MB > 1MB 上限
        long t0 = System.currentTimeMillis();
        IOException ex = assertThrows(IOException.class, () -> SupplierOfficeParser.parse(f, "docx", lim));
        assertTrue(ex.getMessage().contains("zip 炸弹"), ex.getMessage());
        assertTrue(System.currentTimeMillis() - t0 < 10_000, "必须在预检快速拒绝，不许解压到死");
    }

    @Test void entryDecompressionHardCapStopsStream() throws Exception {
        Path f = bombDocx("cap.docx");
        Limits lim = new Limits(256L * 1024 * 1024, 100_000, 90_000);   // 预检放过，解压流硬上限拦截
        IOException ex = assertThrows(IOException.class, () -> SupplierOfficeParser.parse(f, "docx", lim));
        assertTrue(ex.getMessage().contains("上限") && ex.getMessage().contains("zip 炸弹"), ex.getMessage());
    }

    private Path bombDocx(String name) throws IOException {
        String huge = "<?xml version=\"1.0\"?><w:document xmlns:w=\"" + OfficeFixtures.NS_W
                + "\"><w:body><w:p><w:r><w:t>" + "a".repeat(5_000_000) + "</w:t></w:r></w:p></w:body></w:document>";
        return OfficeFixtures.zip(dir, name, Map.of("word/document.xml", huge));
    }

    // ===== magic bytes / OLE2 分类 =====

    @Test void sniffByMagicBytesNotExtension() throws Exception {
        Path docx = OfficeFixtures.richDocx(dir, "real.docx");
        Path enc = OfficeFixtures.encryptedOle2(dir, "enc.doc");
        Path junk = dir.resolve("junk.docx");
        Files.write(junk, "hello world".getBytes(StandardCharsets.UTF_8));
        assertEquals(Magic.ZIP, SupplierOfficeParser.sniff(docx));
        assertEquals(Magic.OLE2, SupplierOfficeParser.sniff(enc));
        assertEquals(Magic.UNKNOWN, SupplierOfficeParser.sniff(junk));
    }

    @Test void resolveOfficeFileDetectsMislabeledExtensions() throws Exception {
        // docx 内容改名 .doc → 按真实字节走 docx，标记 mislabeled（AC2b）
        Path renamed = OfficeFixtures.richDocx(dir, "renamed.doc");
        Resolution r1 = SupplierOfficeParser.resolveOfficeFile(renamed, "doc");
        assertEquals("docx", r1.effectiveType());
        assertTrue(r1.mislabeled());
        // OLE2-Word 内容顶着 .docx 扩展名 → 按真实字节走 doc
        Path ole2NamedDocx = OfficeFixtures.fakeWordOle2(dir, "legacy.docx");
        Resolution r2 = SupplierOfficeParser.resolveOfficeFile(ole2NamedDocx, "docx");
        assertEquals("doc", r2.effectiveType());
        assertTrue(r2.mislabeled());
        // 真 .doc → doc 且非 mislabeled
        Resolution r3 = SupplierOfficeParser.resolveOfficeFile(ole2NamedDocx, "doc");
        assertEquals("doc", r3.effectiveType());
        assertFalse(r3.mislabeled());
    }

    @Test void resolveOfficeFileRejectsUnparsableForms() throws Exception {
        Path enc = OfficeFixtures.encryptedOle2(dir, "secret.doc");
        IllegalArgumentException e1 = assertThrows(IllegalArgumentException.class,
                () -> SupplierOfficeParser.resolveOfficeFile(enc, "doc"));
        assertTrue(e1.getMessage().contains("密码"), e1.getMessage());
        Path ppt = OfficeFixtures.legacyPptOle2(dir, "old.pptx");
        IllegalArgumentException e2 = assertThrows(IllegalArgumentException.class,
                () -> SupplierOfficeParser.resolveOfficeFile(ppt, "pptx"));
        assertTrue(e2.getMessage().contains("另存为 .pptx"), e2.getMessage());
        Path xls = OfficeFixtures.legacyXlsOle2(dir, "old.xlsx");
        IllegalArgumentException e3 = assertThrows(IllegalArgumentException.class,
                () -> SupplierOfficeParser.resolveOfficeFile(xls, "xlsx"));
        assertTrue(e3.getMessage().contains("另存为 .xlsx"), e3.getMessage());
        Path junk = dir.resolve("junk.docx");
        Files.write(junk, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        IllegalArgumentException e4 = assertThrows(IllegalArgumentException.class,
                () -> SupplierOfficeParser.resolveOfficeFile(junk, "docx"));
        assertTrue(e4.getMessage().contains("不是有效的 Office 文件"), e4.getMessage());
        IllegalArgumentException e5 = assertThrows(IllegalArgumentException.class,
                () -> SupplierOfficeParser.resolveOfficeFile(dir.resolve("missing.docx"), "docx"));
        assertTrue(e5.getMessage().contains("不存在"), e5.getMessage());
    }

    @Test void docFailureClassificationEncryptedLegacyCorrupt() throws Exception {
        DocParseException enc = assertThrows(DocParseException.class,
                () -> SupplierOfficeParser.docToMarkdown(OfficeFixtures.encryptedOle2(dir, "a.doc")));
        assertEquals("encrypted", enc.kind);
        assertTrue(enc.getMessage().contains("密码"), enc.getMessage());

        DocParseException ppt = assertThrows(DocParseException.class,
                () -> SupplierOfficeParser.docToMarkdown(OfficeFixtures.legacyPptOle2(dir, "b.doc")));
        assertEquals("legacy-ppt", ppt.kind);
        assertTrue(ppt.getMessage().contains("另存为 .pptx"), ppt.getMessage());

        DocParseException xls = assertThrows(DocParseException.class,
                () -> SupplierOfficeParser.docToMarkdown(OfficeFixtures.legacyXlsOle2(dir, "c.doc")));
        assertEquals("legacy-xls", xls.kind);
        assertTrue(xls.getMessage().contains("另存为 .xlsx"), xls.getMessage());

        DocParseException corrupt = assertThrows(DocParseException.class,
                () -> SupplierOfficeParser.docToMarkdown(OfficeFixtures.fakeWordOle2(dir, "d.doc")));
        assertEquals("corrupt", corrupt.kind, "伪造 WordDocument 流应判结构损坏");
        assertTrue(corrupt.getMessage().contains("另存为 .docx") || corrupt.getMessage().contains("PDF"), corrupt.getMessage());

        // 非 OLE2 冒充 .doc：文件头不可读 → 也是分类失败而非乱码入库
        Path junk = dir.resolve("junk.doc");
        Files.write(junk, "plain text not ole2".getBytes(StandardCharsets.UTF_8));
        assertThrows(DocParseException.class, () -> SupplierOfficeParser.docToMarkdown(junk));
    }

    @Test void colFromRefMapsExcelColumnLetters() {
        assertEquals(0, SupplierOfficeParser.colFromRef("A1"));
        assertEquals(1, SupplierOfficeParser.colFromRef("B12"));
        assertEquals(25, SupplierOfficeParser.colFromRef("Z9"));
        assertEquals(26, SupplierOfficeParser.colFromRef("AA1"));
        assertEquals(54, SupplierOfficeParser.colFromRef("BC12"));
    }

    @Test void cleanDocTextStripsFieldCodesAndCellMarks() {
        // \u0013 HYPERLINK 指令 \u0014 显示文本 \u0015；\u0007 单元格标记
        String raw = "\u0013 HYPERLINK \"http://x\" \u0014显示文本\u0015 尾注\u0007";
        String cleaned = SupplierOfficeParser.cleanDocText(raw);
        assertFalse(cleaned.contains("HYPERLINK"), "域指令必须剥掉: " + cleaned);
        assertTrue(cleaned.contains("显示文本"), "域显示文本必须保留: " + cleaned);
        assertTrue(cleaned.contains("尾注"));
        assertFalse(cleaned.contains("\u0007"));
    }
}
