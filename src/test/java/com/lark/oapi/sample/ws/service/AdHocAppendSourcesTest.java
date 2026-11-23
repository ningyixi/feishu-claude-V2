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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * 临时需求·追加材料（11-23-adhoc-append-sources S1/S3/S4）mockito 纯单测：
 * AC1 追加 URL → 新条目 → 解析 DONE 后强制重编译 Wiki → 任务回到 READY（顺序断言）/
 * AC3 笔记双框都空 → 可读错误 / AC5 重编译期间对话照常 + 注入头带"新增材料尚未进入当前 Wiki 版本" /
 * AC6 连点三次不产生重复条目、不排队第二条重编译；推进中追加被串行闸拒绝 /
 * 追加来源并入任务行 docSources（重试本段幂等留痕）。
 * 脚手架与 {@link AdHocServiceTest} 同款：同步 starter + 快进 sleeper，无 Spring、无网络、无真实睡眠。
 */
class AdHocAppendSourcesTest {

    // ===== 测试脚手架 =====

    /** SupplierWikiRevision 的 id/createdAt 无公共 setter——反射注入（语义同 AdHocServiceTest.revision） */
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
        String reparseResult = "DONE";
        SupplierWikiRevision wikiRow;
        String buildResult = "READY";
        long nextRevisionId = 78L;
        final AtomicInteger builds = new AtomicInteger();
        /** build 调用瞬间的 KB 条目状态快照（断言"全部 DONE 之后才编译"） */
        final List<String> statusAtBuild = new ArrayList<>();
        final List<AdHocChat> chats = new ArrayList<>();
        String chatReply = "好的，已处理。";

        AdHocTask task;

        Harness() {
            service = new AdHocService(taskRepo, chatRepo, instanceRepo, kb, wiki, wikiRevisions, claude, feishu);
            service.pollIntervalMs = 2;
            service.stepTimeoutMs = 10_000;
            service.sleeper = ms -> { };
            service.starter = (name, r) -> r.run();   // 同步执行流水线/对话轮

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
            when(kb.add(anyString(), anyString(), anyString(), nullable(String.class))).thenAnswer(inv -> {
                SupplierKbEntry e = new SupplierKbEntry();
                e.setId((long) (kbEntries.size() + 1));
                e.setKbCode(inv.getArgument(0));
                e.setType(inv.getArgument(1));
                e.setSource(inv.getArgument(2));
                e.setTitle("条目-" + inv.getArgument(2));
                e.setStatus("md".equals(e.getType()) ? "DONE" : "RAW");
                e.setCreatedAt(LocalDateTime.now());
                kbEntries.add(e);
                return e;
            });
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
                    if ("FAILED".equals(reparseResult)) e.setError("抓取超时");
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
                // createdAt 明确晚于所有已入库条目：模拟"build 取快照在追加之后"，避免同刻时钟抖动
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
                    .thenAnswer(inv -> chatReply);
            when(feishu.notifyUserChecked(nullable(Long.class), nullable(String.class), nullable(String.class)))
                    .thenAnswer(inv -> FeishuBotService.NotifyResult.OK);
        }

        /** 预置 READY 任务 + 一条已 DONE 的旧材料 + 一版旧 READY Wiki（追加场景的标准起点） */
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
            kbEntries.add(old);

            wikiRow = revision(77L, "READY", LocalDateTime.now().minusHours(2));
            return task;
        }
    }

    // ===== AC1：追加 URL → 新条目 → 解析 DONE → 强制重编译 Wiki → 回到 READY =====

    @Test
    void appendUrlToReadyTaskTriggersParseThenForcedWikiRebuild() {
        var h = new Harness();
        h.givenReadyTaskWithWiki();

        JsonObject out = h.service.appendSources(1L, "https://docs.acme.com/api-v2", null, null, null, null, null);

        assertEquals(1, out.getAsJsonArray("added").size(), out.toString());
        assertTrue(out.get("wikiRebuildQueued").getAsBoolean(), "追加新条目必须排队重编译");
        // 顺序：新条目入库 → 触发解析 → （全部 DONE 后）才编译 Wiki —— Wiki 输入不吃半成品
        InOrder order = inOrder(h.kb, h.wiki);
        order.verify(h.kb).add("adhoc-1", "url", "https://docs.acme.com/api-v2", null);
        order.verify(h.kb).reparse(2L);
        order.verify(h.wiki).build(any(SupplierJob.class));
        assertEquals(List.of("DONE", "DONE"), h.statusAtBuild, "build 时全部材料必须已 DONE（新来源真的进了编译输入）");
        assertEquals(1, h.builds.get(), "READY 旧版本之上强制重建了一版");
        assertEquals(78L, h.wikiRow.getId(), "重编译产生新版本行");
        assertEquals("READY", h.task.getStatus(), "重编译完成后回到对话待命");
        assertEquals("READY", h.task.getWikiStatus());
        assertEquals("", h.task.getError());
        // 追加来源并入任务行（重试本段的幂等留痕）
        assertEquals("https://docs.acme.com/api\nhttps://docs.acme.com/api-v2", h.task.getDocSources());
    }

    /** 重编译由既有编排串行闸护住：与 create/retry 同一套 orchestrating，不新造并发机制 */
    @Test
    void appendUsesExistingSerialGateAndRejectsWhilePipelineRunning() {
        var h = new Harness();
        h.givenReadyTaskWithWiki();
        // 真实的"推进中"场景：任务停在 INGEST 失败 → retryStage 占住 orchestrating（starter 占位不执行）
        h.task.setStatus("INGEST");
        h.task.setError("文档解析失败：x");
        h.service.starter = (name, r) -> { };
        h.service.retryStage(1L);
        assertTrue(h.service.isRunning(1L), "串行闸已被占用");

        var e = assertThrows(IllegalArgumentException.class,
                () -> h.service.appendSources(1L, "https://docs.acme.com/api-v3", null, null, null, null, null));
        assertTrue(e.getMessage().contains("正在推进中"), e.getMessage());
        assertEquals(0, h.builds.get(), "被拒的追加不得触发任何编译");
        int entriesBefore = h.kbEntries.size();
        assertThrows(IllegalArgumentException.class,
                () -> h.service.appendSources(1L, null, null, null, null, "笔记主要内容", null));
        assertEquals(entriesBefore, h.kbEntries.size(), "推进中一律不入库（先拒绝，不留半截）");
    }

    // ===== AC6：连点三次 → 一条新条目、一次重编译 =====

    @Test
    void tripleClickAppendCreatesSingleEntryAndSingleRebuild() {
        var h = new Harness();
        h.givenReadyTaskWithWiki();

        var first = h.service.appendSources(1L, "https://docs.acme.com/api-v2", null, null, null, null, null);
        assertTrue(first.get("wikiRebuildQueued").getAsBoolean());
        assertEquals(1, h.builds.get());
        assertEquals("READY", h.task.getStatus(), "同步 starter 下首轮流水线已跑完");

        // 第二、三次连点：exists 命中（同 type+source 已在库）→ 不入库、不再排队重编译
        var second = h.service.appendSources(1L, "https://docs.acme.com/api-v2", null, null, null, null, null);
        var third = h.service.appendSources(1L, "https://docs.acme.com/api-v2", null, null, null, null, null);
        assertEquals(0, second.getAsJsonArray("added").size(), second.toString());
        assertEquals(1, second.getAsJsonArray("duplicated").size(), second.toString());
        assertFalse(second.get("wikiRebuildQueued").getAsBoolean(), "全部去重命中不得再排队重编译");
        assertFalse(third.get("wikiRebuildQueued").getAsBoolean());
        assertEquals(1, h.builds.get(), "三次连点只允许一次重编译");
        assertEquals(2, h.kbEntries.size(), "只有一条新条目（旧 1 + 新 1）");
        verify(h.kb, times(1)).add(anyString(), anyString(), anyString(), nullable(String.class));
        // 任务行 docSources 不因连点重复累积
        assertEquals("https://docs.acme.com/api\nhttps://docs.acme.com/api-v2", h.task.getDocSources());
    }

    // ===== S2/S1：笔记双框与聊天记录经 appendText 落库；AC3 都空 → 可读错误 =====

    @Test
    void appendNoteGoesThroughDualBoxChannel() {
        var h = new Harness();
        h.givenReadyTaskWithWiki();

        JsonObject out = h.service.appendSources(1L, null, null, null, "对接备忘", "押金按房费三倍", "口头约定原文……");

        verify(h.kb).appendText("adhoc-1", "note", "对接备忘", null, "押金按房费三倍", "口头约定原文……");
        assertEquals(1, out.getAsJsonArray("added").size(), out.toString());
        assertTrue(out.get("wikiRebuildQueued").getAsBoolean(), "追加笔记同样触发重编译（用户加了材料就要进 Wiki）");
        assertEquals(1, h.builds.get());
        assertEquals("READY", h.task.getStatus());
    }

    @Test
    void appendChatGoesThroughSameChannelWithDedupAnchor() {
        var h = new Harness();
        h.givenReadyTaskWithWiki();

        h.service.appendSources(1L, null, "对接群确认：字段改用 v2", "群聊摘要", null, null, null);

        verify(h.kb).appendText("adhoc-1", "chat", "群聊摘要", "对接群确认：字段改用 v2", null, null);
        assertEquals(1, h.builds.get());
    }

    @Test
    void appendRejectsBlankInputWithReadableMessages() {
        var h = new Harness();
        h.givenReadyTaskWithWiki();

        // 什么都没填
        var none = assertThrows(IllegalArgumentException.class,
                () -> h.service.appendSources(1L, "  ", " ", null, null, null, null));
        assertTrue(none.getMessage().contains("没有可追加的内容"), none.getMessage());
        // 笔记两框都空（AC3 的可读错误在追加口径下的表现）
        var note = assertThrows(IllegalArgumentException.class,
                () -> h.service.appendSources(1L, null, null, null, "标题", "   ", "\n "));
        assertTrue(note.getMessage().contains("主要内容"), note.getMessage());
        assertTrue(note.getMessage().contains("至少填写一项"), note.getMessage());

        assertEquals(0, h.builds.get(), "校验失败不得触发编译");
        assertEquals(1, h.kbEntries.size(), "校验失败不得入库");
        verify(h.kb, never()).add(anyString(), anyString(), anyString(), nullable(String.class));
        verify(h.kb, never()).appendText(anyString(), anyString(), nullable(String.class), nullable(String.class),
                nullable(String.class), nullable(String.class));
    }

    @Test
    void appendPrechecksUnknownTypesAndMissingFiles() {
        var h = new Harness();
        h.givenReadyTaskWithWiki();
        var unknown = assertThrows(IllegalArgumentException.class,
                () -> h.service.appendSources(1L, "D:\\docs\\a.csv", null, null, null, null, null));
        assertTrue(unknown.getMessage().contains("无法识别"), unknown.getMessage());
        var missing = assertThrows(IllegalArgumentException.class,
                () -> h.service.appendSources(1L, "D:\\docs\\不存在.pdf", null, null, null, null, null));
        assertTrue(missing.getMessage().contains("文件不存在"), missing.getMessage());
        assertEquals(0, h.builds.get());
    }

    // ===== AC5：重编译期间对话照常可用 + 注入头带诚实提示 =====

    @Test
    void chatDuringRebuildStaysAvailableAndInjectsPendingMaterialHint() {
        var h = new Harness();
        h.givenReadyTaskWithWiki();

        // 追加一条笔记但不跑流水线（starter 占位）→ 任务停在"重编译推进中"，新版本尚未产出
        h.service.starter = (name, r) -> { };
        JsonObject out = h.service.appendSources(1L, null, null, null, null, "押金按房费三倍收取", "口头约定原文");
        assertTrue(out.get("wikiRebuildQueued").getAsBoolean());
        assertTrue(h.service.isRunning(1L), "重编译已排队（占位 starter 未执行，串行闸保持占用）");
        h.service.starter = (name, r) -> r.run();

        // 对话照常可用：不锁对话框、不 steer、不打断——同一实例主会话排队语义
        h.service.ask(1L, "刚贴的口头约定里押金怎么收？");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(h.claude).chat(eq(5L), eq("ad-hoc-task-1"), prompt.capture(),
                eq("/work"), eq("sonnet"), any(TurnListener.class));
        String p = prompt.getValue();
        assertTrue(p.contains("1 条新增材料尚未进入当前 Wiki 版本"), "AC5 诚实提示: " + p.substring(0, Math.min(300, p.length())));
        assertTrue(p.contains("正在/待重编译"), p.substring(0, Math.min(300, p.length())));
        assertTrue(p.contains("押金按房费三倍收取") || p.contains("笔记-"), "提示列出涉及的材料: " + p.substring(0, Math.min(400, p.length())));
        assertTrue(p.contains("刚贴的口头约定里押金怎么收？"), "用户原文照发");
        assertEquals("DONE", h.chats.get(1).getStatus(), "对话轮正常收尾（重编译不阻塞对话）");
        verify(h.claude, never()).steer(anyLong(), anyString());
        verify(h.claude, never()).chatInLane(anyString(), anyLong(), anyString(), anyString(), nullable(String.class), any(TurnListener.class));
        verify(h.claude, never()).destroySession(anyLong());
    }

    @Test
    void pendingHintDisappearsAfterRebuildCompletes() {
        var h = new Harness();
        h.givenReadyTaskWithWiki();
        h.service.appendSources(1L, null, null, null, null, "押金按房费三倍收取", null);   // 同步跑完整条重编译

        // 新版本 READY（createdAt 晚于新条目）→ 不再有"尚未进入 Wiki"的材料
        var pending = h.service.pendingMaterials("adhoc-1");
        assertEquals(0, pending.count(), "重编译完成后提示必须消失: " + pending);

        h.service.ask(1L, "押金怎么收？");
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(h.claude).chat(anyLong(), anyString(), prompt.capture(), anyString(), nullable(String.class), any(TurnListener.class));
        assertFalse(prompt.getValue().contains("尚未进入当前 Wiki 版本"), "完成后注入头不再带提示");
        assertTrue(prompt.getValue().contains("【任务 LLM Wiki】版本 #78"), "新版 Wiki 入口正常注入");
    }

    // ===== S3 纯函数面：pendingMaterials / pendingHint =====

    @Test
    void pendingMaterialsCountsEntriesNewerThanLatestReadyRevision() {
        var h = new Harness();
        h.givenReadyTaskWithWiki();   // READY 版本行 createdAt = 2 小时前；旧条目 1 天前

        SupplierKbEntry fresh = new SupplierKbEntry();
        fresh.setId(2L); fresh.setKbCode("adhoc-1"); fresh.setType("note"); fresh.setSource("note-fp:x");
        fresh.setStatus("DONE"); fresh.setTitle("新贴的合同条款");
        fresh.setCreatedAt(LocalDateTime.now());
        h.kbEntries.add(fresh);

        var pending = h.service.pendingMaterials("adhoc-1");
        assertEquals(1, pending.count());
        assertEquals(List.of("新贴的合同条款"), pending.titles());

        // 没有 READY 版本（首编中）→ NONE（"Wiki 尚未就绪"话术已覆盖）
        h.wikiRow = revision(80L, "BUILDING", LocalDateTime.now());
        assertEquals(0, h.service.pendingMaterials("adhoc-1").count());
    }

    @Test
    void pendingHintPureFunctionFormatsAndTruncates() {
        assertEquals("", AdHocService.pendingHint(null));
        assertEquals("", AdHocService.pendingHint(AdHocService.PendingMaterials.NONE));

        String one = AdHocService.pendingHint(new AdHocService.PendingMaterials(1, List.of("新贴的合同条款")));
        assertTrue(one.contains("本任务有 1 条新增材料尚未进入当前 Wiki 版本（正在/待重编译），涉及：新贴的合同条款"), one);
        assertTrue(one.contains("不得声称当前 Wiki 已覆盖"), one);

        List<String> many = new ArrayList<>();
        for (int i = 0; i < 9; i++) many.add("标题".repeat(30) + i);   // 每个 61 字符
        String capped = AdHocService.pendingHint(new AdHocService.PendingMaterials(9, many));
        assertTrue(capped.contains("本任务有 9 条"), capped);
        assertTrue(capped.contains("……等"), "最多列 5 个标题");
        assertFalse(capped.contains("标题".repeat(30) + "5"), "第 6 个起不列出");
        assertTrue(capped.length() < 400, "提示必须轻量: " + capped.length());
    }

    @Test
    void wikiHeaderBlockCarriesHintBeforeTruncation() {
        // 提示紧跟任务名行（最前面）——即使页面清单撑爆截断上限，提示也切不掉
        List<AdHocService.PageBrief> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) many.add(new AdHocService.PageBrief("p" + i, "页" + i, "很长的摘要".repeat(30)));
        var wiki = new AdHocService.WikiSnapshot("READY", 77L, 3, "D:/kb/.wiki/xyz/revisions/77", "", "", many);
        String header = AdHocService.wikiHeaderBlock("t", "READY", wiki,
                new AdHocService.PendingMaterials(2, List.of("材料A", "材料B")));
        assertTrue(header.contains("2 条新增材料尚未进入当前 Wiki 版本"), header.substring(0, 200));
        assertTrue(header.indexOf("2 条新增材料") < 200, "提示必须在头部最前面（截断切不掉）");
        // 3 参老签名行为不变（既有断言零改动通过的兼容性证据）
        assertFalse(AdHocService.wikiHeaderBlock("t", "READY", wiki).contains("尚未进入当前 Wiki 版本"));
    }

    // ===== detail() 暴露给页面的重编译提示数据源 =====

    @Test
    void detailCarriesPendingMaterialCountForPageBanner() {
        var h = new Harness();
        h.givenReadyTaskWithWiki();
        h.service.starter = (name, r) -> { };   // 重编译排队中（新版本未产出）
        h.service.appendSources(1L, null, null, null, null, "新笔记内容", null);

        JsonObject detail = h.service.detail(1L);
        assertEquals(1, detail.get("pendingMaterialCount").getAsInt(), detail.toString());
        assertTrue(detail.getAsJsonArray("pendingMaterialTitles").get(0).getAsString().contains("笔记"),
                detail.toString());
        assertTrue(detail.get("running").getAsBoolean(), "页面据此显示流水线推进中");
    }
}
