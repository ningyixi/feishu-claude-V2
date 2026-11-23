package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.model.entity.SupplierKbEntry;
import com.lark.oapi.sample.ws.repository.SupplierKbEntryRepository;
import com.lark.oapi.sample.ws.repository.SupplierKbRemovedRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * AC5：三个入口（临时需求 AdHocService.detectType / 接口建档 QuickProbeService.detectType /
 * 工作台补充材料 SupplierKbService.syncFromJob→add）对同一批扩展名给出一致的类型判定——
 * 同一参数表跑三遍。11-13 加 .html 时 syncFromJob 曾误归 pdf 专门返工，本轮判定收口到
 * {@link DocParser#detectSourceType}，本测试钉死三入口不许再走散。
 * expectedEntryType=null 表示该来源不允许产生 KB 条目（O2b）或应被拒绝。
 */
class KbOfficeTypeConsistencyTest {

    @TempDir
    static Path dir;

    static Path docx, pptx, xlsx, renamedDoc, encryptedDoc, pdf, html, md, txt, csv;

    @BeforeAll
    static void fixtures() throws Exception {
        docx = OfficeFixtures.richDocx(dir, "api.docx");
        pptx = OfficeFixtures.pptx(dir, "kick off.pptx", Map.of(1, new String[]{"要点".repeat(120)}));
        xlsx = OfficeFixtures.xlsx(dir, "hotels.xlsx", new String[]{"S"},
                List.of(List.of(new String[]{"h1", "h2"}, new String[]{"v1", "v2"})), List.of());
        renamedDoc = OfficeFixtures.richDocx(dir, "legacy-renamed.doc");     // docx 内容改名 .doc
        encryptedDoc = OfficeFixtures.encryptedOle2(dir, "encrypted.doc");   // O2b：不得入库
        pdf = dir.resolve("manual.pdf");
        Files.writeString(pdf, "%PDF-1.4 fake", StandardCharsets.UTF_8);
        html = dir.resolve("page.html");
        Files.writeString(html, "<html><body><h1>t</h1></body></html>", StandardCharsets.UTF_8);
        md = dir.resolve("notes.md");
        Files.writeString(md, "# 笔记", StandardCharsets.UTF_8);
        txt = dir.resolve("plain.txt");
        Files.writeString(txt, "纯文本", StandardCharsets.UTF_8);
        csv = dir.resolve("data.csv");
        Files.writeString(csv, "a,b", StandardCharsets.UTF_8);
    }

    static Stream<Arguments> cases() {
        return Stream.of(
                // 文件名, detectType 期望（null=三入口一致拒绝）, syncFromJob 入库期望类型（null=不得产生条目）
                Arguments.of("api.docx", "docx", "docx"),
                Arguments.of("kick off.pptx", "pptx", "pptx"),
                Arguments.of("hotels.xlsx", "xlsx", "xlsx"),
                Arguments.of("legacy-renamed.doc", "doc", "doc"),     // 真实字节=docx，type 保留声明值，meta 标 mislabeled
                Arguments.of("encrypted.doc", "doc", null),           // O2b：加密 → 引导错误且不入库
                Arguments.of("manual.pdf", "pdf", "pdf"),
                Arguments.of("page.html", "html", "html"),
                Arguments.of("notes.md", "md", "md"),
                Arguments.of("plain.txt", "md", "md"),
                Arguments.of("data.csv", null, null)                  // 未支持扩展名：三入口一致拒绝
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void threeEntrypointsAgree(String fileName, String expectedType, String expectedEntryType) {
        String value = dir.resolve(fileName).toString();

        // 入口1：临时需求；入口2：接口建档；权威：DocParser.detectSourceType
        if (expectedType == null) {
            assertThrows(IllegalArgumentException.class, () -> AdHocService.detectType(value), fileName);
            assertThrows(IllegalArgumentException.class, () -> QuickProbeService.detectType(value), fileName);
            assertThrows(IllegalArgumentException.class, () -> DocParser.detectSourceType(value), fileName);
        } else {
            assertEquals(expectedType, AdHocService.detectType(value), "临时需求入口: " + fileName);
            assertEquals(expectedType, QuickProbeService.detectType(value), "接口建档入口: " + fileName);
            assertEquals(expectedType, DocParser.detectSourceType(value), "权威判定: " + fileName);
        }

        // 入口3：工作台补充材料（存量 docs_json 同步 → add 落库类型）
        SupplierKbEntryRepository repo = mock(SupplierKbEntryRepository.class);
        AtomicLong ids = new AtomicLong();
        when(repo.save(any(SupplierKbEntry.class))).thenAnswer(inv -> {
            SupplierKbEntry e = inv.getArgument(0);
            if (e.getId() == null) e.setId(ids.incrementAndGet());
            return e;
        });
        SupplierKbService service = new SupplierKbService(repo, mock(SupplierKbRemovedRepository.class), null,
                dir.resolve("kb-out").toString());
        SupplierJob job = new SupplierJob();
        job.setId(1L);
        job.setSupplierCode("acme");
        JsonArray arr = new JsonArray();
        JsonObject d = new JsonObject();
        d.addProperty("type", "path");
        d.addProperty("value", value);
        arr.add(d);
        job.setDocsJson(arr.toString());

        service.syncFromJob(job);

        ArgumentCaptor<SupplierKbEntry> cap = ArgumentCaptor.forClass(SupplierKbEntry.class);
        if (expectedEntryType == null) {
            verify(repo, never()).save(any(SupplierKbEntry.class));
        } else {
            verify(repo, atLeastOnce()).save(cap.capture());
            assertEquals(expectedEntryType, cap.getAllValues().get(0).getType(),
                    "工作台补充材料入口的入库类型必须与前两个入口一致: " + fileName);
        }
    }

    @ParameterizedTest(name = "URL 一致性: {0}")
    @MethodSource("urlCases")
    void urlDetectConsistentAcrossEntrypoints(String url) {
        assertEquals("url", AdHocService.detectType(url));
        assertEquals("url", QuickProbeService.detectType(url));
        assertEquals("url", DocParser.detectSourceType(url));
    }

    static Stream<Arguments> urlCases() {
        return Stream.of(Arguments.of("https://docs.acme.com/api"), Arguments.of("http://a.cn/x.PDF"));
    }
}
