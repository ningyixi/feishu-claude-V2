package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.model.entity.SupplierKbEntry;
import com.lark.oapi.sample.ws.model.entity.SupplierKbPage;
import com.lark.oapi.sample.ws.repository.SupplierKbPageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 站点条目 → Wiki 编译输入的取材验证（11-24-adhoc-site-crawl AC5/SC4）。
 * <p>
 * {@code SupplierWikiService.build} 的取材面是 {@link SupplierWikiSources#snapshot}（build 同步调用，
 * 抛异常即编译不启动）。本测试用<b>真实产物文件</b>（@TempDir，格式与 crawlNow 落盘的
 * site-&lt;id&gt;/index.md + pN.md 一致）+ mock 仓储钉住两条硬语义：
 * <ol>
 *   <li><b>深层页面正文真的进编译输入</b>：站点条目 DONE 后，snapshot 把每个 loaded 子页
 *       作为独立来源（e&lt;条目id&gt;-p&lt;页id&gt;）读入，第 3 层深层页的固定关键词
 *       {@code DEEP-PAGE-KEYWORD-42}（与 {@link SiteCrawlHttpFixtureTest} 夹具站同一锚点）
 *       必须逐字出现在来源 content 里——不是只吃首屏/索引，不靠人眼看；</li>
 *   <li><b>站点条目未完成绝不取材</b>：条目非 DONE（抓取中/取消/超限失败）→ snapshot 抛
 *       "Wiki 来源未就绪"；条目 DONE 但没有任何 loaded 页 → 抛"没有已载入的正文页"。
 *       这是 build 侧的兜底闸（AdHocService INGEST 段"全部 DONE 才进 WIKI"是第一道闸，
 *       见 {@link AdHocSiteCrawlTest}）。</li>
 * </ol>
 * 失败页照实进 warnings（缺口不静默）。无网络、无 Spring、无 LLM。
 */
class SiteCrawlWikiInputTest {

    private static final String DEEP_KEYWORD = "DEEP-PAGE-KEYWORD-42";

    private SupplierKbEntry siteEntry(Path index) {
        SupplierKbEntry e = new SupplierKbEntry();
        e.setId(10L);
        e.setKbCode("adhoc-9");
        e.setType("url");
        e.setTitle("供应商文档站");
        e.setSource("http://127.0.0.1:9/docs/start");
        e.setStatus("DONE");
        e.setMdPath(index.toString());
        e.setMetaJson("{\"site\":true,\"found\":3,\"loaded\":2,\"failed\":1,\"pending\":0,\"partial\":true}");
        return e;
    }

    private static SupplierKbPage page(long id, long entryId, String url, String status, String error, Path md) {
        SupplierKbPage p = new SupplierKbPage();
        p.setId(id);
        p.setEntryId(entryId);
        p.setUrl(url);
        p.setUrlKey(SiteCrawler.logicKey(url));
        p.setTitle("页-" + id);
        p.setStatus(status);
        p.setError(error);
        p.setMdPath(md == null ? "" : md.toString());
        return p;
    }

    @Test
    void deepPageContentEntersWikiCompileInputVerbatim(@TempDir Path dir) throws Exception {
        // 与 crawlNow 落盘格式一致的产物：index.md（条目主产物）+ p1/p2（loaded 子页）
        Path index = dir.resolve("index.md");
        Files.writeString(index, "> 来源入口: http://127.0.0.1:9/docs/start（同站点抓取：载入 2 页，失败 1 页，属**部分导入**——缺口详见页面清单）\n\n"
                + "## 1. 入口\n> 来源: http://127.0.0.1:9/docs/start ｜ 正文文件: " + dir.resolve("p1.md") + "\n\n"
                + "入口页正文：接口总览与快速开始。\n", StandardCharsets.UTF_8);
        Path p1 = dir.resolve("p1.md");
        Files.writeString(p1, "> 来源URL: http://127.0.0.1:9/docs/start\n\n# 入口\n\n接口总览与快速开始。\n", StandardCharsets.UTF_8);
        Path p2 = dir.resolve("p2.md");
        Files.writeString(p2, "> 来源URL: http://127.0.0.1:9/docs/deep/policy\n\n# 取消政策\n\n"
                + "取消政策深层条款 " + DEEP_KEYWORD + "：取消必须携带供应商订单号。\n", StandardCharsets.UTF_8);

        SupplierKbEntry entry = siteEntry(index);
        List<SupplierKbPage> rows = List.of(
                page(101, 10L, "http://127.0.0.1:9/docs/start", "loaded", "", p1),
                page(102, 10L, "http://127.0.0.1:9/docs/deep/policy", "loaded", "", p2),
                page(103, 10L, "http://127.0.0.1:9/docs/broken", "failed", "HTTP 404: http://127.0.0.1:9/docs/broken", null));

        SupplierKbService kb = mock(SupplierKbService.class);
        SupplierKbPageRepository pageRepo = mock(SupplierKbPageRepository.class);
        when(kb.kbCode(any(SupplierJob.class))).thenReturn("adhoc-9");
        when(kb.list("adhoc-9")).thenReturn(List.of(entry));
        when(pageRepo.findByEntryIdOrderByIdAsc(10L)).thenReturn(rows);

        SupplierJob job = new SupplierJob();   // transient 适配器语义（AdHocService.wikiJob 同款），从不落库
        var snapshot = new SupplierWikiSources(kb, pageRepo).snapshot(job);

        // 索引 + 每个 loaded 子页都是独立编译来源
        List<String> ids = snapshot.sources().stream().map(SupplierWikiSources.Source::id).toList();
        assertEquals(List.of("e10", "e10-p101", "e10-p102"), ids, "站点条目索引 + 全部 loaded 子页都进来源清单: " + ids);
        // AC5 核心断言：第 3 层深层页正文逐字进入编译输入（固定关键词，不靠人眼）
        String deep = snapshot.sources().stream().filter(s -> s.id().equals("e10-p102")).findFirst().orElseThrow().content();
        assertTrue(deep.contains(DEEP_KEYWORD),
                "深层页面正文必须逐字进 Wiki 编译输入，实际 content=" + deep);
        assertTrue(deep.contains("取消必须携带供应商订单号"), deep);
        assertFalse(snapshot.sources().stream().filter(s -> s.id().equals("e10")).findFirst().orElseThrow().content().contains(DEEP_KEYWORD),
                "关键词只在深层页产物里——证明编译输入不是只吃首屏/索引");
        // 失败页如实进 warnings（缺口不静默）
        assertTrue(snapshot.warnings().stream().anyMatch(w -> w.contains("/docs/broken") && w.contains("failed")),
                "失败页必须进缺口清单: " + snapshot.warnings());
        System.out.println("[AC5] 编译输入来源=" + ids + ", warnings=" + snapshot.warnings());
        System.out.println("[AC5] 深层页来源 content=" + deep.replace("\n", "\\n"));
    }

    @Test
    void snapshotRefusesWhenSiteEntryNotDone(@TempDir Path dir) throws Exception {
        Path index = dir.resolve("index.md");
        Files.writeString(index, "索引\n", StandardCharsets.UTF_8);
        SupplierKbEntry entry = siteEntry(index);

        SupplierKbService kb = mock(SupplierKbService.class);
        SupplierKbPageRepository pageRepo = mock(SupplierKbPageRepository.class);
        when(kb.kbCode(any(SupplierJob.class))).thenReturn("adhoc-9");
        when(kb.list("adhoc-9")).thenReturn(List.of(entry));
        when(pageRepo.findByEntryIdOrderByIdAsc(10L)).thenReturn(List.of());
        var sources = new SupplierWikiSources(kb, pageRepo);
        SupplierJob job = new SupplierJob();

        // 抓取中（PARSING）/ 取消或超限后的 FAILED：build 取材面直接拒绝——Wiki 不可能吃到半成品站点
        for (String status : List.of("PARSING", "FAILED", "RAW", "PENDING")) {
            entry.setStatus(status);
            var ex = assertThrows(IllegalArgumentException.class, () -> sources.snapshot(job));
            assertTrue(ex.getMessage().contains("Wiki 来源未就绪"), status + " → " + ex.getMessage());
            System.out.println("[SC4-取材闸] 条目状态=" + status + " → " + ex.getMessage());
        }

        // DONE 但没有任何 loaded 页（例如取消后人工把状态改回来）：同样拒绝，不冒充有内容
        entry.setStatus("DONE");
        var ex = assertThrows(IllegalArgumentException.class, () -> sources.snapshot(job));
        assertTrue(ex.getMessage().contains("没有已载入的正文页"), ex.getMessage());
        System.out.println("[SC4-取材闸] DONE 但无 loaded 页 → " + ex.getMessage());
    }
}
