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

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * 本地 HTML 来源类型（html）全链路单测：
 * add 入参校验（绝对路径/存在/扩展名）→ syncFromJob 扩展名映射（.html/.htm 不再误归 pdf）→
 * reparse/parseNow 解析三态（正常转换 DONE / JS 壳页面 FAILED 指引 / GBK 编码回退）。
 * 文件名含空格与括号（"OTA Default Interface (1).html"）贯穿全链路不被拆坏。
 */
class SupplierKbHtmlTest {

    @TempDir
    Path dir;

    private SupplierKbEntryRepository repo;
    private SupplierKbService service;

    private SupplierKbService newService() {
        repo = mock(SupplierKbEntryRepository.class);
        when(repo.save(any(SupplierKbEntry.class))).thenAnswer(inv -> {
            SupplierKbEntry e = inv.getArgument(0);
            if (e.getId() == null) e.setId(1L);
            return e;
        });
        return new SupplierKbService(repo, mock(SupplierKbRemovedRepository.class), null, dir.toString());
    }

    // ===== add() 入参校验 =====

    @Test void addHtmlRequiresExistingAbsoluteHtmlFile() throws Exception {
        service = newService();
        // 相对路径拒绝（须为绝对路径）
        assertThrows(IllegalArgumentException.class, () -> service.add("acme", "html", "docs/api.html", null));
        // 不存在的文件拒绝
        assertThrows(IllegalArgumentException.class, () -> service.add("acme", "html", dir.resolve("missing.html").toString(), null));
        // 非 .html/.htm 扩展名拒绝（即使文件存在）
        Path txt = dir.resolve("doc.txt");
        Files.writeString(txt, "hello");
        assertThrows(IllegalArgumentException.class, () -> service.add("acme", "html", txt.toString(), null));
        verify(repo, never()).save(any(SupplierKbEntry.class));
    }

    @Test void addHtmlAcceptsPathWithSpacesAndParensAsRaw() throws Exception {
        service = newService();
        Path html = dir.resolve("OTA Default Interface (1).html");
        Files.writeString(html, "<html><body><h1>接口文档</h1><p>正文内容占位，足够长以通过后续解析。</p></body></html>", StandardCharsets.UTF_8);

        SupplierKbEntry e = service.add("acme", "html", html.toString(), null);

        assertEquals("html", e.getType());
        assertEquals("RAW", e.getStatus(), "html 同 pdf：入库为原文引用，点「解析」后提取");
        assertEquals(html.toString(), e.getSource(), "来源必须是原样完整路径（空格/括号不被破坏）");
        assertEquals("OTA Default Interface (1)", e.getTitle(), "标题 = 去扩展名的完整文件名");
    }

    // ===== syncFromJob 扩展名映射 =====

    @Test void syncFromJobMapsHtmlExtensionsToHtmlTypeNotPdf() throws Exception {
        service = newService();
        Path html = dir.resolve("OTA Default Interface (1).html");
        Files.writeString(html, "<html><body><h1>酒店接口</h1></body></html>", StandardCharsets.UTF_8);
        Path htm = dir.resolve("legacy doc.HTM");
        Files.writeString(htm, "<html><body><h1>旧版接口</h1></body></html>", StandardCharsets.UTF_8);
        Path pdf = dir.resolve("real doc.pdf");
        Files.writeString(pdf, "%PDF-1.4 fake");   // add(pdf) 只校验扩展名/大小

        SupplierJob job = new SupplierJob();
        job.setId(7L);
        job.setSupplierCode("acme");
        job.setDocsJson(docsArrayOf(html.toString(), htm.toString(), pdf.toString()));

        service.syncFromJob(job);

        ArgumentCaptor<SupplierKbEntry> cap = ArgumentCaptor.forClass(SupplierKbEntry.class);
        verify(repo, times(3)).save(cap.capture());
        List<SupplierKbEntry> saved = cap.getAllValues();
        assertEquals("html", saved.get(0).getType(), ".html 不再误归 pdf");
        assertEquals("OTA Default Interface (1)", saved.get(0).getTitle());
        assertEquals(html.toString(), saved.get(0).getSource());
        assertEquals("html", saved.get(1).getType(), ".HTM 大小写不敏感 → html");
        assertEquals("legacy doc", saved.get(1).getTitle());
        assertEquals("pdf", saved.get(2).getType(), ".pdf 分流不受影响");
    }

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

    // ===== parseNow（经 reparse 触发，虚拟线程异步 → 轮询等待终态） =====

    @Test void parseNowConvertsHtmlFileToMarkdownArtifact() throws Exception {
        service = newService();
        Path html = dir.resolve("OTA Default Interface (1).html");
        Files.writeString(html, """
                <!DOCTYPE html>
                <html><head><title>OTA Default Interface</title><style>.menu{color:red}</style></head>
                <body>
                <nav>GARBAGE_NAV</nav>
                <script>console.log("GARBAGE_JS")</script>
                <h1>酒店静态信息接口</h1>
                <p>GET /v2/hotels，分页参数 pageSize 与 pageNum，返回酒店基础字段列表。</p>
                <table><tr><th>字段</th><th>类型</th></tr><tr><td>hotelId</td><td>string</td></tr><tr><td>name</td><td>string</td></tr></table>
                </body></html>""", StandardCharsets.UTF_8);
        SupplierKbEntry e = givenEntry(3L, "html", html.toString(), "OTA Default Interface (1)");

        service.reparse(3L);
        awaitTerminal(e);

        assertEquals("DONE", e.getStatus(), () -> "解析应成功: " + e.getError());
        assertEquals("", e.getError());
        assertTrue(e.getMetaJson().contains("\"localHtml\":true"), "meta 应记录 localHtml: " + e.getMetaJson());
        String md = Files.readString(Paths.get(e.getMdPath()), StandardCharsets.UTF_8);
        assertTrue(md.contains("> 来源HTML文件: "), "产物应带来源头: " + md.substring(0, Math.min(120, md.length())));
        assertTrue(md.contains("OTA Default Interface (1).html"), "来源头保留含空格/括号的完整文件名");
        assertTrue(md.contains("# 酒店静态信息接口"), "h1 应转成 ATX 标题");
        assertTrue(md.contains("hotelId"), "表格内容应保留");
        assertFalse(md.contains("GARBAGE_JS"), "script 噪音应被剥离");
        assertFalse(md.contains("GARBAGE_NAV"), "nav 噪音应被剥离");
    }

    @Test void parseNowFailsOnJsShellHtmlWithGuidance() throws Exception {
        service = newService();
        Path html = dir.resolve("spa-shell.html");
        Files.writeString(html, """
                <!DOCTYPE html>
                <html><head><title>OTA Docs</title></head>
                <body><div id="app"></div><script src="/assets/bundle.min.js"></script></body></html>""", StandardCharsets.UTF_8);
        SupplierKbEntry e = givenEntry(4L, "html", html.toString(), "spa-shell");

        service.reparse(4L);
        awaitTerminal(e);

        assertEquals("FAILED", e.getStatus(), "JS 壳页面（正文为空）应判失败");
        assertTrue(e.getError().contains("该 HTML 可能是 JS 动态页（正文为空）"), e.getError());
        assertTrue(e.getError().contains("建议改用在线文档 URL/站点抓取"), e.getError());
    }

    @Test void parseNowFallsBackToGbkWhenUtf8DecodingFails() throws Exception {
        service = newService();
        Path html = dir.resolve("gbk-doc.html");
        String content = "<html><head><meta charset=\"gbk\"><title>接口文档</title></head><body>"
                + "<h1>游由接口签名说明</h1>"
                + "<p>所有请求必须携带 X-Access-Key、X-Timestamp、X-Nonce、X-Signature 四个请求头，"
                + "签名算法为 HMAC-SHA256 后做 Base64 编码，密钥 sk 仅保存在客户端本地，严禁放入请求。</p>"
                + "</body></html>";
        Files.write(html, content.getBytes(Charset.forName("GBK")));
        SupplierKbEntry e = givenEntry(5L, "html", html.toString(), "gbk-doc");

        service.reparse(5L);
        awaitTerminal(e);

        assertEquals("DONE", e.getStatus(), () -> "GBK 编码应回退解码成功: " + e.getError());
        String md = Files.readString(Paths.get(e.getMdPath()), StandardCharsets.UTF_8);
        assertTrue(md.contains("游由接口签名说明"), "GBK 字节应被正确解码，无乱码: " + md);
        assertTrue(md.contains("四个请求头"), "正文中文应完整可读");
    }

    @Test void reparseStillRejectsNonParsableTypes() {
        service = newService();
        SupplierKbEntry chat = givenEntry(9L, "chat", "", "沟通记录");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () -> service.reparse(chat.getId()));
        assertTrue(ex.getMessage().contains("仅 url/pdf/html/md 支持解析"), ex.getMessage());
    }

    private SupplierKbEntry givenEntry(Long id, String type, String source, String title) {
        SupplierKbEntry e = new SupplierKbEntry();
        e.setId(id);
        e.setKbCode("acme");
        e.setType(type);
        e.setStatus("RAW");
        e.setSource(source);
        e.setTitle(title);
        when(repo.findById(id)).thenReturn(Optional.of(e));
        return e;
    }

    /** reparse 走 afterCommit → 虚拟线程异步 parseNow：轮询等待条目离开过渡态（≤10s） */
    private static void awaitTerminal(SupplierKbEntry e) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (List.of("PENDING", "PARSING").contains(e.getStatus()) && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
        }
    }
}
