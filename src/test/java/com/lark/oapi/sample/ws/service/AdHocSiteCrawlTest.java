package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonObject;
import com.lark.oapi.sample.ws.model.entity.AdHocChat;
import com.lark.oapi.sample.ws.model.entity.AdHocTask;
import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.model.entity.SupplierKbEntry;
import com.lark.oapi.sample.ws.model.entity.SupplierWikiRevision;
import com.lark.oapi.sample.ws.repository.AdHocChatRepository;
import com.lark.oapi.sample.ws.repository.AdHocTaskRepository;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.SupplierWikiRevisionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * 临时需求·暴露同站点 BFS 递归抓取（11-24-adhoc-site-crawl SC1–SC5）mockito 纯单测：
 * AC1 勾选 → URL 行按 site=true 入库（本地文件行不受影响）/ AC2 不勾选走 4 参老调用（单页语义一字不变）/
 * AC3-AC5 站点条目未完成（PARSING/超时/超限 FAILED）时绝不触发 Wiki 编译 /
 * AC4 200 页超限的 SiteCrawler 原始错误逐字透传到任务段失败原因 /
 * AC6 同一 URL 重复追加命中 type+source 去重 / SC5 一条站点来源按一条计待处理材料 /
 * SC2 detail() 把 meta 的 found/loaded/failed/pending/partial 透传给页面。
 * <p>
 * 真实 HTTP 层的 BFS 行为（多层页/md+html 混合链接/201 页超限站/慢页失败页/取消）见
 * {@link SiteCrawlHttpFixtureTest}（本地 HttpServer 夹具，不访问外网）；深层页面正文进编译输入见
 * {@link SiteCrawlWikiInputTest}。脚手架与 {@link AdHocAppendSourcesTest} 同款：同步 starter + 快进 sleeper。
 */
class AdHocSiteCrawlTest {

    // ===== 测试脚手架 =====

    private static SupplierWikiRevision revision(Long id, String status, LocalDateTime createdAt) {
        SupplierWikiRevision row = new SupplierWikiRevision();
        inject(row, "id", id);
        inject(row, "createdAt", createdAt);
        row.setKbCode("adhoc-1");
        row.setStatus(status);
        row.setSourceCount(1);
        row.setDirectory("D:/kb/.wiki/xyz/revisions/" + id);
        row.setManifestJson("{\"pages\":[{\"id\":\"api-overview\",\"title\":\"接口总览\",\"summary\":\"历史接口清单\"}]}");
        return row;
    }

    private static void inject(Object target, String field, Object value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法注入 " + field + ": " + e, e);
        }
    }

    private static class Harness {
        AdHocTaskRepository taskRepo = mock(AdHocTaskRepository.class);
        AdHocChatRepository chatRepo = mock(AdHocChatRepository.class);
        BotInstanceRepository instanceRepo = mock(BotInstanceRepository.class);
        SupplierKbService kb = mock(SupplierKbService.class);
        SupplierWikiService wiki = mock(SupplierWikiService.class);
        SupplierWikiRevisionRepository wikiRevisions = mock(SupplierWikiRevisionRepository.class);
        ClaudeCodeService claude = mock(ClaudeCodeService.class);
        FeishuBotService feishu = mock(FeishuBotService.class);
        AdHocService service;

        final List<SupplierKbEntry> kbEntries = new ArrayList<>();
        /** reparse 后条目转到的状态；"SITE_PARSING" = 站点抓取进行中（模拟 crawlNow 置 PARSING） */
        String reparseResult = "DONE";
        /** reparse 置 SITE_PARSING 时写进条目的进度 meta（页面「已入 N 页 / 共发现 M 页」数据源） */
        String siteProgressMeta = "{\"site\":true,\"found\":5,\"loaded\":2,\"failed\":0,\"pending\":3}";
        /** FAILED 时的错误文本（超限场景 = SiteCrawler 原始容量错误，逐字） */
        String reparseError = "抓取超时";
        SupplierWikiRevision wikiRow;
        String buildResult = "READY";
        long nextRevisionId = 78L;
        final AtomicInteger builds = new AtomicInteger();
        final AtomicInteger sleeps = new AtomicInteger();
        /** build 调用瞬间的 KB 条目状态快照（断言"全部 DONE 之后才编译"，同 AdHocAppendSourcesTest） */
        final List<String> statusAtBuild = new ArrayList<>();
        /** 站点条目从 PARSING 翻到 DONE 那一刻的 build 计数（必须为 0 = 抓取期间没编译过） */
        final AtomicInteger buildsWhenCrawlFinished = new AtomicInteger(-1);
        final List<AdHocChat> chats = new ArrayList<>();

        AdHocTask task;
        /** 第 N 次 sleep 时把 PARSING 的站点条目翻成 DONE（模拟抓取完成；-1=不翻，永远抓不完） */
        int finishCrawlAtSleep = -1;
        String doneMeta = "{\"site\":true,\"found\":5,\"loaded\":5,\"failed\":0,\"pending\":0}";

        Harness() {
            service = new AdHocService(taskRepo, chatRepo, instanceRepo, kb, wiki, wikiRevisions, claude, feishu);
            service.pollIntervalMs = 2;
            service.stepTimeoutMs = 10_000;
            service.sleeper = ms -> {
                int n = sleeps.incrementAndGet();
                if (n == finishCrawlAtSleep) {
                    kbEntries.stream().filter(e -> "PARSING".equals(e.getStatus())).forEach(e -> {
                        e.setStatus("DONE");
                        e.setMetaJson(doneMeta);
                    });
                    buildsWhenCrawlFinished.set(builds.get());
                }
            };
            service.starter = (name, r) -> r.run();   // 同步执行流水线

            when(taskRepo.save(any(AdHocTask.class))).thenAnswer(inv -> {
                AdHocTask t = inv.getArgument(0);
                if (t.getId() == null) t.setId(1L);
                task = t;
                return t;
            });
            when(taskRepo.findById(1L)).thenAnswer(inv -> Optional.ofNullable(task));
            when(taskRepo.findAll()).thenAnswer(inv -> task == null ? List.of() : List.of(task));
            when(taskRepo.findAllByOrderByIdDesc()).thenAnswer(inv -> task == null ? List.of() : List.of(task));

            BotInstance inst = new BotInstance();
            inst.setId(5L);
            inst.setEnabled(true);
            inst.setName("测试实例");
            inst.setClaudeWorkingDir("/work");
            inst.setClaudeModel("sonnet");
            when(instanceRepo.findById(5L)).thenReturn(Optional.of(inst));

            when(kb.list(anyString())).thenAnswer(inv -> List.copyOf(kbEntries));
            when(kb.exists(anyString(), anyString(), anyString())).thenAnswer(inv -> kbEntries.stream()
                    .anyMatch(e -> e.getType().equals(inv.getArgument(1, String.class))
                            && e.getSource().equals(inv.getArgument(2, String.class))));
            // 4 参老调用（单页语义）与 5 参站点调用分别建条目——mock 上两个重载互不委托，验证口径干净
            when(kb.add(anyString(), anyString(), anyString(), nullable(String.class))).thenAnswer(inv ->
                    newEntry(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), false));
            when(kb.add(anyString(), anyString(), anyString(), nullable(String.class), anyBoolean())).thenAnswer(inv ->
                    newEntry(inv.getArgument(0), inv.getArgument(1), inv.getArgument(2), inv.getArgument(4, Boolean.class)));
            when(kb.appendText(anyString(), anyString(), nullable(String.class), nullable(String.class),
                    nullable(String.class), nullable(String.class))).thenAnswer(inv -> {
                String type = inv.getArgument(1);
                SupplierKbEntry e = new SupplierKbEntry();
                e.setId((long) (kbEntries.size() + 1));
                e.setKbCode(inv.getArgument(0));
                e.setType(type);
                e.setSource(type + "-fp:mockmockmockmock");
                e.setTitle(("note".equals(type) ? "笔记-" : "聊天-") + e.getId());
                e.setStatus("DONE");
                e.setCreatedAt(LocalDateTime.now());
                kbEntries.add(e);
                return new SupplierKbService.Appended(e, true);
            });
            doAnswer(inv -> {
                SupplierKbEntry e = kbEntries.stream()
                        .filter(x -> x.getId().equals(inv.getArgument(0, Long.class))).findFirst().orElse(null);
                if (e != null) {
                    e.setStatus(reparseResult);
                    if ("SITE_PARSING".equals(reparseResult)) {
                        e.setStatus("PARSING");
                        e.setMetaJson(siteProgressMeta);
                    } else if ("FAILED".equals(reparseResult)) {
                        e.setError(reparseError);
                    }
                }
                return null;
            }).when(kb).reparse(anyLong());

            when(wikiRevisions.findFirstByKbCodeOrderByIdDesc(anyString()))
                    .thenAnswer(inv -> Optional.ofNullable(wikiRow));
            when(wikiRevisions.findFirstByKbCodeAndStatusOrderByIdDesc(anyString(), eq("READY")))
                    .thenAnswer(inv -> "READY".equals(wikiRow == null ? null : wikiRow.getStatus())
                            ? Optional.of(wikiRow) : Optional.empty());
            when(wiki.build(any(SupplierJob.class))).thenAnswer(inv -> {
                builds.incrementAndGet();
                kbEntries.forEach(e -> statusAtBuild.add(e.getStatus()));
                wikiRow = revision(nextRevisionId++, buildResult, LocalDateTime.now().plusSeconds(1));
                return wikiRow;
            });

            when(chatRepo.save(any(AdHocChat.class))).thenAnswer(inv -> {
                AdHocChat c = inv.getArgument(0);
                if (c.getId() == null) {
                    c.setId((long) (chats.size() + 1));
                    chats.add(c);
                }
                return c;
            });
            when(chatRepo.findByTaskIdOrderByIdAsc(1L)).thenAnswer(inv -> List.copyOf(chats));
            when(claude.chat(anyLong(), anyString(), anyString(), anyString(), nullable(String.class), any(TurnListener.class)))
                    .thenAnswer(inv -> "好的，已处理。");
            when(feishu.notifyUserChecked(nullable(Long.class), nullable(String.class), nullable(String.class)))
                    .thenAnswer(inv -> FeishuBotService.NotifyResult.OK);
        }

        private SupplierKbEntry newEntry(String kbCode, String type, String source, boolean site) {
            SupplierKbEntry e = new SupplierKbEntry();
            e.setId((long) (kbEntries.size() + 1));
            e.setKbCode(kbCode);
            e.setType(type);
            e.setSource(source);
            e.setTitle("条目-" + source);
            e.setStatus("md".equals(type) ? "DONE" : "RAW");
            if (site) e.setMetaJson("{\"site\":true}");
            e.setCreatedAt(LocalDateTime.now());
            kbEntries.add(e);
            return e;
        }

        /** 预置 READY 任务 + 一条已 DONE 的旧材料 + 一版旧 READY Wiki（追加场景标准起点） */
        AdHocTask givenReadyTaskWithWiki() {
            task = new AdHocTask();
            task.setId(1L);
            task.setName("改历史接口字段");
            task.setDocSources("https://docs.acme.com/api");
            task.setKbCode("adhoc-1");
            task.setInstanceId(5L);
            task.setStatus("READY");
            task.setWikiStatus("READY");
            task.setError("");

            SupplierKbEntry old = new SupplierKbEntry();
            old.setId(1L); old.setKbCode("adhoc-1"); old.setType("url");
            old.setSource("https://docs.acme.com/api"); old.setStatus("DONE"); old.setTitle("旧接口文档");
            old.setCreatedAt(LocalDateTime.now().minusDays(1));
            old.setMetaJson("{}");
            kbEntries.add(old);

            wikiRow = revision(77L, "READY", LocalDateTime.now().minusHours(2));
            return task;
        }
    }

    private static final String CAPACITY_ERROR =
            "发现第 201 个页面，超出同站点 200 页上限——本次导入不完整，请缩小范围或分批导入";

    // ===== AC1 / SC1：创建勾选 → URL 行按 site=true 入库，本地文件行不受影响 =====

    @Test
    void createWithSiteCrawlAddsUrlLinesAsSiteEntriesOnly(@TempDir java.nio.file.Path dir) throws Exception {
        var h = new Harness();
        java.nio.file.Path pdf = dir.resolve("supplier-api.pdf");
        java.nio.file.Files.writeString(pdf, "dummy");   // create 预检只要求文件真实存在（pdf 内容校验在入库管线）

        var t = h.service.create("整站抓取任务", 5L,
                "https://docs.acme.com/api\n" + pdf, true);

        assertTrue(t.isSiteCrawl(), "勾选必须持久化到任务行（重试本段/重启续跑不静默降级成单页）");
        // URL 行走 5 参 site=true；本地 pdf 行仍走 4 参老调用（开关只作用于 URL 行）
        verify(h.kb).add("adhoc-1", "url", "https://docs.acme.com/api", null, true);
        verify(h.kb).add("adhoc-1", "pdf", pdf.toString(), null);
        verify(h.kb, never()).add(anyString(), eq("pdf"), anyString(), nullable(String.class), anyBoolean());
        // 站点条目 meta.site=true（newEntry 按 site 参数落 meta，模拟 SupplierKbService.add 的真实行为）
        SupplierKbEntry siteEntry = h.kbEntries.stream().filter(e -> "url".equals(e.getType())).findFirst().orElseThrow();
        assertEquals("{\"site\":true}", siteEntry.getMetaJson());
        assertEquals("READY", h.task.getStatus(), "mock 解析全 DONE → 流水线照常推进到对话待命");
        assertEquals(1, h.builds.get());
        System.out.println("[AC1] site条目 meta=" + siteEntry.getMetaJson() + ", 任务行 siteCrawl=" + t.isSiteCrawl()
                + ", builds=" + h.builds.get() + ", 终态=" + h.task.getStatus());
    }

    // ===== AC2：不勾选 → 与今天完全一致（4 参老调用，5 参一次都不出现） =====

    @Test
    void createWithoutSiteCrawlKeepsLegacySinglePageSemantics() {
        var h = new Harness();

        var t = h.service.create("单页任务", 5L, "https://docs.acme.com/api");

        assertFalse(t.isSiteCrawl());
        verify(h.kb).add("adhoc-1", "url", "https://docs.acme.com/api", null);
        verify(h.kb, never()).add(anyString(), anyString(), anyString(), nullable(String.class), anyBoolean());
        SupplierKbEntry e = h.kbEntries.get(0);
        assertFalse(SupplierKbService.isSiteCrawl(e), "不勾选不得写 meta.site（isSiteCrawl=解析分派爬虫的唯一判据）");
        assertEquals("READY", h.task.getStatus());
        System.out.println("[AC2] 不勾选：4参add调用=1, 5参add调用=0, metaJson=" + e.getMetaJson()
                + ", 终态=" + h.task.getStatus());
    }

    /** 追加面板同一开关：URL 行 site=true，聊天记录/笔记走 appendText 不受影响 */
    @Test
    void appendSourcesWithSiteCrawlUsesSiteAddForUrlLinesOnly() {
        var h = new Harness();
        h.givenReadyTaskWithWiki();

        JsonObject out = h.service.appendSources(1L, "https://docs.acme.com/api-v2", "群里确认字段改 v2", null,
                null, null, null, true);

        verify(h.kb).add("adhoc-1", "url", "https://docs.acme.com/api-v2", null, true);
        verify(h.kb, never()).add(anyString(), anyString(), anyString(), nullable(String.class));
        verify(h.kb).appendText("adhoc-1", "chat", null, "群里确认字段改 v2", null, null);
        assertEquals(2, out.getAsJsonArray("added").size(), out.toString());
        assertTrue(out.get("wikiRebuildQueued").getAsBoolean());
        assertEquals("READY", h.task.getStatus(), "同步 starter 下重编译跑完回到待命");
        System.out.println("[SC1-追加] added=" + out.getAsJsonArray("added") + ", rebuild=" + out.get("wikiRebuildQueued"));
    }

    /** 追加面板不勾选：沿用 7 参老签名 → 4 参 add，既有调用方一字不改（AC2 的追加口径） */
    @Test
    void appendSourcesWithoutSiteCrawlKeepsLegacyCalls() {
        var h = new Harness();
        h.givenReadyTaskWithWiki();

        h.service.appendSources(1L, "https://docs.acme.com/api-v2", null, null, null, null, null);

        verify(h.kb).add("adhoc-1", "url", "https://docs.acme.com/api-v2", null);
        verify(h.kb, never()).add(anyString(), anyString(), anyString(), nullable(String.class), anyBoolean());
    }

    /** 任务行标记是重试/重启续跑的锚点：INGEST 重跑时未入库的 URL 行仍按站点条目建，不静默降级 */
    @Test
    void retryStageAfterRestartKeepsSiteFlagFromTaskRow() {
        var h = new Harness();
        h.task = new AdHocTask();
        h.task.setId(1L);
        h.task.setName("重启中断的整站任务");
        h.task.setDocSources("https://docs.acme.com/api");
        h.task.setKbCode("adhoc-1");
        h.task.setInstanceId(5L);
        h.task.setStatus("INGEST");
        h.task.setWikiStatus("EMPTY");
        h.task.setError("服务重启中断了流水线，请重试本段");
        h.task.setSiteCrawl(true);
        // kbEntries 为空 = 上个进程在入库前就被杀

        h.service.retryStage(1L);

        verify(h.kb).add("adhoc-1", "url", "https://docs.acme.com/api", null, true);
        verify(h.kb, never()).add(anyString(), anyString(), anyString(), nullable(String.class));
        assertEquals("READY", h.task.getStatus());
        System.out.println("[SC1-重试] 重启后续跑仍按 site=true 入库, 终态=" + h.task.getStatus());
    }

    // ===== AC5 / SC4：站点抓取未完成（PARSING）→ 绝不触发 Wiki 编译；全 DONE 后才 build =====

    @Test
    void siteEntryParsingBlocksWikiBuildUntilAllPagesDone() {
        var h = new Harness();
        h.reparseResult = "SITE_PARSING";   // 站点条目进入抓取中（PARSING + 进度 meta）
        h.finishCrawlAtSleep = 3;           // 第 3 次轮询睡眠时抓取完成 → DONE

        h.service.create("整站抓取任务", 5L, "https://docs.acme.com/api", true);

        assertEquals(0, h.buildsWhenCrawlFinished.get(),
                "抓取完成那一刻 build 计数必须还是 0（INGEST 段在等站点条目，Wiki 没被提前触发）");
        assertEquals(1, h.builds.get(), "全部 DONE 之后才编译了一版");
        assertEquals(List.of("DONE"), h.statusAtBuild, "build 取材瞬间站点条目必须已 DONE");
        assertEquals("READY", h.task.getStatus());
        assertEquals("READY", h.task.getWikiStatus());
        System.out.println("[SC4] 抓取完成时builds=" + h.buildsWhenCrawlFinished.get()
                + ", 最终builds=" + h.builds.get() + ", build时条目状态=" + h.statusAtBuild);
    }

    /** 站点条目永远停在 PARSING（慢站/卡住）→ INGEST 段超时失败，Wiki 一次都没编译 */
    @Test
    void siteEntryNeverFinishingTimesOutWithoutAnyWikiBuild() {
        var h = new Harness();
        h.reparseResult = "SITE_PARSING";
        h.finishCrawlAtSleep = -1;          // 永不完成
        h.service.stepTimeoutMs = 20;       // 快进超时（2ms 轮询 × 10 次）

        h.service.create("卡住的整站任务", 5L, "https://docs.acme.com/api", true);

        assertEquals("INGEST", h.task.getStatus(), "停在 INGEST 段失败");
        assertTrue(h.task.getError().contains("文档解析等待超时"), h.task.getError());
        assertEquals(0, h.builds.get(), "站点条目未完成 → Wiki 编译一次都不得触发");
        verify(h.wiki, never()).build(any(SupplierJob.class));
        System.out.println("[SC4-超时] task.error=" + h.task.getError() + ", builds=" + h.builds.get());
    }

    // ===== AC4 / SC3：200 页超限 → SiteCrawler 原始错误逐字透传，FAILED，不编译、不算就绪 =====

    @Test
    void capacityOverLimitPropagatesOriginalCrawlerErrorAndSkipsWiki() {
        var h = new Harness();
        h.reparseResult = "FAILED";
        h.reparseError = CAPACITY_ERROR;   // crawlNow 落进条目 error 的原文（truncErr 400 内不截断）

        h.service.create("超限整站任务", 5L, "https://docs.acme.com/api", true);

        assertEquals("INGEST", h.task.getStatus(), "段失败停在 INGEST，不得进 WIKI");
        assertTrue(h.task.getError().contains(CAPACITY_ERROR),
                "任务段失败原因必须逐字含 SiteCrawler 原始容量错误，实际=" + h.task.getError());
        assertFalse(h.task.getError().contains("部分成功"), "禁止截成部分成功");
        assertEquals(0, h.builds.get(), "超限失败不得触发 Wiki 编译");
        verify(h.wiki, never()).build(any(SupplierJob.class));
        SupplierKbEntry e = h.kbEntries.get(0);
        assertEquals("FAILED", e.getStatus(), "条目如实 FAILED，不计入已就绪材料");
        System.out.println("[AC4] 条目error原文=" + e.getError());
        System.out.println("[AC4] 任务段失败原文=" + h.task.getError());
    }

    // ===== AC6：同一 URL 行重复追加命中 type+source 去重，不因站点目录重复入库 =====

    @Test
    void duplicateSiteUrlAppendDedupsByTypeAndSource() {
        var h = new Harness();
        h.givenReadyTaskWithWiki();

        var first = h.service.appendSources(1L, "https://docs.acme.com/api-v2", null, null,
                null, null, null, true);
        assertTrue(first.get("wikiRebuildQueued").getAsBoolean());
        assertEquals(1, h.builds.get());

        var second = h.service.appendSources(1L, "https://docs.acme.com/api-v2", null, null,
                null, null, null, true);
        assertEquals(0, second.getAsJsonArray("added").size(), second.toString());
        assertEquals(1, second.getAsJsonArray("duplicated").size(), second.toString());
        assertFalse(second.get("wikiRebuildQueued").getAsBoolean(), "全部去重命中不得再排队重编译");
        assertEquals(1, h.builds.get(), "两次追加只允许一次重编译");
        assertEquals(2, h.kbEntries.size(), "站点条目只有一条（旧 1 + 新 1），不因站点目录重复入库");
        verify(h.kb, times(1)).add(anyString(), anyString(), anyString(), nullable(String.class), anyBoolean());
        System.out.println("[AC6] second=" + second);
    }

    // ===== SC5：一条站点来源按一条计待处理材料（200 页不算 200 条） =====

    @Test
    void siteEntryCountsAsOnePendingMaterialNotPerPage() {
        var h = new Harness();
        h.givenReadyTaskWithWiki();
        h.service.starter = (name, r) -> { };   // 重编译排队中，新版本未产出

        h.service.appendSources(1L, "https://docs.acme.com/api-v2", null, null, null, null, null, true);
        // 模拟 crawlNow 完成后的 meta：200 页全部载入
        SupplierKbEntry siteEntry = h.kbEntries.get(1);
        siteEntry.setMetaJson("{\"site\":true,\"found\":200,\"loaded\":200,\"failed\":0,\"pending\":0}");

        var pending = h.service.pendingMaterials("adhoc-1");
        assertEquals(1, pending.count(), "站点来源按整条计 1，不得把 200 页算成 200 条待处理材料");
        assertEquals(List.of(siteEntry.getTitle()), pending.titles());

        JsonObject detail = h.service.detail(1L);
        assertEquals(1, detail.get("pendingMaterialCount").getAsInt(), detail.toString());
        System.out.println("[SC5] pendingMaterialCount=" + detail.get("pendingMaterialCount")
                + ", titles=" + detail.getAsJsonArray("pendingMaterialTitles"));
    }

    // ===== SC2：detail() 把站点进度透传给页面（已入 N 页 / 共发现 M 页 + 产物目录） =====

    @Test
    void detailCarriesSiteProgressFieldsForEntryRows() {
        var h = new Harness();
        h.givenReadyTaskWithWiki();
        h.service.starter = (name, r) -> { };
        h.service.appendSources(1L, "https://docs.acme.com/api-v2", null, null, null, null, null, true);
        SupplierKbEntry siteEntry = h.kbEntries.get(1);
        siteEntry.setStatus("DONE");
        siteEntry.setMetaJson("{\"site\":true,\"found\":42,\"loaded\":40,\"failed\":2,\"pending\":0,\"partial\":true}");
        siteEntry.setMdPath("D:\\kb\\adhoc-1\\site-2\\index.md");

        JsonObject detail = h.service.detail(1L);
        JsonObject eo = detail.getAsJsonArray("kbEntries").get(1).getAsJsonObject();
        assertTrue(eo.get("site").getAsBoolean());
        assertEquals(42, eo.get("found").getAsInt());
        assertEquals(40, eo.get("loaded").getAsInt());
        assertEquals(2, eo.get("failed").getAsInt());
        assertEquals(0, eo.get("pending").getAsInt());
        assertTrue(eo.get("partial").getAsBoolean());
        assertEquals("D:\\kb\\adhoc-1\\site-2\\index.md", eo.get("mdPath").getAsString(),
                "产物路径透传（页面据此显示产物目录）");
        System.out.println("[SC2] 站点条目 detail JSON=" + eo);
    }
}
