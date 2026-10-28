package com.lark.oapi.sample.ws.service;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 供应商知识库解析管线单测（R2）：分块语义 / HTML→MD 清洗 / SSRF 与入参校验 / PDF 按页提取。
 * 全部离线确定性，不发真实网络请求。
 */
class DocParserTest {

    // ===== 分块（约 6000 字符，保留行号定位） =====

    @Test
    void chunkSplitsLongDocWithLineMarkers() {
        StringBuilder md = new StringBuilder("# 大文档\n\n");
        for (int i = 0; i < 400; i++) {
            md.append("这是第").append(i).append("行内容，用于填充长度大约六十个字符aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa。\n");
            if (i % 10 == 0) md.append('\n');
        }
        String out = DocParser.chunk(md.toString());
        assertTrue(out.contains("## ▍分块 1（原文第 1 行起）"), "长文档应给第 1 块也标定位头");
        assertTrue(out.contains("## ▍分块 2"), "超 6000 字符应产生第 2 块");
        for (String seg : out.split("## ▍分块 \\d+（原文第 \\d+ 行起）")) {
            assertTrue(seg.length() <= DocParser.CHUNK_CHARS * 1.35 + 200,
                    "单块不应显著超上限，实际=" + seg.length());
        }
    }

    @Test
    void chunkNoOpOnShortDoc() {
        String shortDoc = "# 小文档\n\n就几行。\n";
        String out = DocParser.chunk(shortDoc);
        assertEquals(shortDoc, out, "短文档应逐字节不变（含尾部换行归一）");
        assertFalse(out.contains("分块"));
    }

    // ===== HTML → Markdown 清洗 =====

    @Test
    void cleanHtmlToMarkdownStripsNoiseAndAbsolutizesLinks() {
        String html = """
                <html><head><title>API 文档</title><style>body{color:red}</style></head>
                <body>
                <script>var track="GARBAGE_SCRIPT";</script>
                <nav class="menu">GARBAGE_NAV</nav>
                <h1>查价接口</h1>
                <p>调用 <a href="/api/rate">RatePlan</a> 获取价格。<span style="display:none">GARBAGE_HIDDEN</span></p>
                <table><tr><th>字段</th><th>类型</th></tr><tr><td>rateId</td><td>string</td></tr></table>
                </body></html>""";
        String md = DocParser.cleanHtmlToMarkdown(html, "https://docs.example.com/booking");
        assertTrue(md.contains("# 查价接口"), "标题应转成 markdown 标题: " + md);
        assertTrue(md.contains("https://docs.example.com/api/rate"), "链接应绝对化");
        assertFalse(md.contains("GARBAGE_SCRIPT"), "script 内容必须被清洗");
        assertFalse(md.contains("GARBAGE_HIDDEN"), "display:none 内容必须被清洗");
        assertTrue(md.toLowerCase().contains("rateid"), "表格内容应保留");
    }

    @Test
    void setextUnderlinesBecomeAtx() {
        assertEquals("# Title\n\nBody\n", DocParser.setextToAtx("Title\n=====\n\nBody\n"));
        assertEquals("## Sub\n", DocParser.setextToAtx("Sub\n-----\n"));
        // 代码围栏内的假 setext 不动
        String fenced = "```\nfoo\n---\nbar\n```\n";
        assertEquals(fenced, DocParser.setextToAtx(fenced));
        // 独立分割线（前有空行）保持原样
        assertEquals("Text\n\n---\n\nMore\n", DocParser.setextToAtx("Text\n\n---\n\nMore\n"));
    }

    @Test
    void mdProbeUrlOnlyAppendsOnRealPath() {
        assertNull(DocParser.mdProbeUrl("https://example.com"), "根路径不得拼出 example.com.md 假域名");
        assertNull(DocParser.mdProbeUrl("https://example.com/"));
        assertEquals("https://docs.x.com/api.md?id=1", DocParser.mdProbeUrl("https://docs.x.com/api?id=1"));
    }

    // ===== SSRF 与入参校验 =====

    @Test
    void assertSafeUrlRejectsInternalAndBadSchemes() {
        for (String bad : new String[]{
                "http://127.0.0.1/api", "http://localhost:8080/", "http://10.0.0.1/",
                "http://192.168.1.1/doc.pdf", "http://169.254.169.254/latest/meta-data/",
                "http://[::1]/", "file:///C:/Windows/win.ini", "http://0.0.0.0/"}) {
            assertThrows(IllegalArgumentException.class, () -> DocParser.assertSafeUrl(bad), "应拒绝: " + bad);
        }
        assertDoesNotThrow(() -> DocParser.assertSafeUrl("http://8.8.8.8/docs"));   // 公网字面 IP 放行（无需 DNS）
    }

    // ===== PDF 按页提取 =====

    @Test
    void pdfBytesToMarkdownKeepsPerPageMarkers() throws IOException {
        byte[] pdf = buildTwoPagePdf("RatePlan Query 12345", "");
        String md = DocParser.pdfBytesToMarkdown(pdf, "supplier-api.pdf");
        assertTrue(md.contains("[PDF 第 1 页]"), "应有第 1 页定位: " + md);
        assertTrue(md.contains("RatePlan Query 12345"));
        assertTrue(md.contains("[PDF 第 2 页]"));
        assertTrue(md.contains("无可提取文字"), "空页应标注疑似扫描件");
    }

    @Test
    void pdfRejectsNonPdfHeader() throws IOException {
        byte[] fake = "<html>not a pdf</html>".getBytes();
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("fake", ".pdf");
        try {
            java.nio.file.Files.write(tmp, fake);
            IOException ex = assertThrows(IOException.class, () -> DocParser.pdfToMarkdown(tmp));
            assertTrue(ex.getMessage().contains("文件头"), "应报文件头校验失败: " + ex.getMessage());
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }

    @Test
    void fullyScannedPdfFailsWithOcrHint() throws IOException {
        byte[] allEmpty = buildTwoPagePdf("", "");
        IOException ex = assertThrows(IOException.class, () -> DocParser.pdfBytesToMarkdown(allEmpty, "scan.pdf"));
        assertTrue(ex.getMessage().contains("OCR"), "整本无文字应给出明确扫描件的失败原因: " + ex.getMessage());
    }

    /** 用 PDFBox 造一个两页 PDF（第二页空白，模拟扫描件） */
    private static byte[] buildTwoPagePdf(String page1Text, String page2Text) throws IOException {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            for (String text : new String[]{page1Text, page2Text}) {
                PDPage page = new PDPage();
                doc.addPage(page);
                if (!text.isEmpty()) {
                    try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                        cs.beginText();
                        cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                        cs.newLineAtOffset(72, 700);
                        cs.showText(text);
                        cs.endText();
                    }
                }
            }
            doc.save(bos);
            return bos.toByteArray();
        }
    }
}
