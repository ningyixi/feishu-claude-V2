package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonObject;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * 临时需求·知识库删除（11-24-adhoc-kb-delete-entry）AdHocService 层 mockito 纯单测。
 * <p>
 * KD3 修正版（2026-11-24 用户新规则）：删除<b>不自动</b>触发 Wiki 重编译（手动编译按钮归下一棒
 * 11-24-adhoc-manual-stages）——本类钉住删除后的"只提示不动手"语义：
 * <ul>
 *   <li>KD3：删除后 {@code wiki.build} 调用次数 = 0；detail 的 pendingRemovalCount 反映"已移除待重编译"，
 *       且移除绝不被算成"新增材料"（pendingMaterialCount=0）；旧 Wiki 版本原样保留（READY 不变）。</li>
 *   <li>KD4 复活护栏：删除过的来源留墓碑——「重试本段」重跑 INGEST 不把它自动带回（{@code kb.add} 不被调）；
 *       用户在 appendSources 显式重贴 → 真实入库 + 清墓碑 + restored 回传（"已恢复该来源"）。</li>
 *   <li>KD5：删到 0 条来源时旧 Wiki 保留、pendingRemovalCount 计数、不触发编译；空库重跑 INGEST 给可读
 *       失败（"知识库没有可解析的材料"）而非死锁/挂起。</li>
 * </ul>
 * 脚手架与 {@link AdHocAppendSourcesTest} 同款：同步 starter + 快进 sleeper，无 Spring、无网络、无真实睡眠。
 * 删除的物理语义（产物文件删除 / 墓碑落库 / 编译输入不含被删关键词）由真实 SupplierKbService 的
 * {@link SupplierKbDeleteTombstoneTest} 钉住；本类 kb 为 mock，delete 夹具忠实模拟"移除条目 + 落墓碑"。
 */
class AdHocKbDeleteTest {

    private static final String SOURCE_A = "http://93.184.216.34/cancel-policy";
    private static final String SOURCE_B = "http://93.184.216.34/price-api";

    // ===== 脚手架 =====

    private static SupplierWikiRevision revision(Long id, String status, LocalDateTime createdAt) {
        SupplierWikiRevision row = new SupplierWikiRevision();
        inject(row, "id", id);
        inject(row, "createdAt", createdAt);
        row.setKbCode("adhoc-1");
        row.setStatus(status);
        row.setSourceCount(2);
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
        // 墓碑夹具：key = type+source，value = 落墓碑时刻（removedCountSince 按时间锚点过滤）
        final Map<String, LocalDateTime> tombstones = new HashMap<>();
        final AtomicLong nextEntryId = new AtomicLong(10);
        SupplierWikiRevision wikiRow;
        final AtomicInteger builds = new AtomicInteger();
        AdHocTask task;

        Harness() {
            service = new AdHocService(taskRepo, chatRepo, instanceRepo, kb, wiki, wikiRevisions, claude, feishu);
            service.pollIntervalMs = 2;
            service.stepTimeoutMs = 10_000;
            service.sleeper = ms -> { };
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
            when(instanceRepo.findById(5L)).thenReturn(Optional.of(inst));
            // ===== KB mock：条目 + 墓碑 =====
            when(kb.list(anyString())).thenAnswer(inv -> List.copyOf(kbEntries));
            when(kb.exists(anyString(), anyString(), anyString())).thenAnswer(inv -> kbEntries.stream()
                    .anyMatch(e -> e.getType().equals(inv.getArgument(1, String.class))
                            && e.getSource().equals(inv.getArgument(2, String.class))));
            // add：新建条目 + 清墓碑（KD4：显式重新入库 → 允许入库并清除删除墓碑）
            when(kb.add(anyString(), anyString(), anyString(), nullable(String.class))).thenAnswer(inv -> {
                String type = inv.getArgument(1), value = inv.getArgument(2);
                tombstones.remove(type + "|" + value.trim());
                SupplierKbEntry e = new SupplierKbEntry();
                e.setId(nextEntryId.incrementAndGet());
                e.setKbCode(inv.getArgument(0));
                e.setType(type);
                e.setSource(value.trim());
                e.setTitle("条目-" + value);
                e.setStatus("RAW");
                e.setCreatedAt(LocalDateTime.now());
                kbEntries.add(e);
                return e;
            });
            // delete：移除条目 + 落墓碑（忠实模拟真实 SupplierKbService.delete 的两个可观测效果）
            doAnswer(inv -> {
                Long id = inv.getArgument(0);
                SupplierKbEntry e = kbEntries.stream().filter(x -> x.getId().equals(id)).findFirst().orElse(null);
                if (e == null) throw new IllegalArgumentException("知识库条目不存在: " + id);
                kbEntries.remove(e);
                if (e.getSource() != null && !e.getSource().isBlank()) {
                    tombstones.put(e.getType() + "|" + e.getSource(), LocalDateTime.now());
                }
                return null;
            }).when(kb).delete(anyLong());
            when(kb.removedBefore(anyString(), anyString(), anyString())).thenAnswer(inv -> {
                String source = inv.getArgument(2);
                return source != null && !source.isBlank()
                        && tombstones.containsKey(inv.getArgument(1, String.class) + "|" + source);
            });
            when(kb.removedCountSince(anyString(), any())).thenAnswer(inv -> {
                LocalDateTime since = inv.getArgument(1);
                return tombstones.values().stream().filter(ts -> ts.isAfter(since)).count();
            });
            doAnswer(inv -> {
                SupplierKbEntry e = kbEntries.stream()
                        .filter(x -> x.getId().equals(inv.getArgument(0, Long.class))).findFirst().orElse(null);
                if (e != null) e.setStatus("DONE");
                return null;
            }).when(kb).reparse(anyLong());
            when(wikiRevisions.findFirstByKbCodeOrderByIdDesc(anyString()))
                    .thenAnswer(inv -> Optional.ofNullable(wikiRow));
            when(wikiRevisions.findFirstByKbCodeAndStatusOrderByIdDesc(anyString(), eq("READY")))
                    .thenAnswer(inv -> "READY".equals(wikiRow == null ? null : wikiRow.getStatus())
                            ? Optional.of(wikiRow) : Optional.empty());
            when(wiki.build(any(SupplierJob.class))).thenAnswer(inv -> {
                builds.incrementAndGet();
                wikiRow = revision(80L + builds.get(), "READY", LocalDateTime.now().plusSeconds(1));
                return wikiRow;
            });
            when(feishu.notifyUserChecked(nullable(Long.class), nullable(String.class), nullable(String.class)))
                    .thenAnswer(inv -> FeishuBotService.NotifyResult.OK);
        }

        /** 预置 READY 任务 + 两条已 DONE 的 url 材料（A/B）+ 一版旧 READY Wiki（删除场景标准起点） */
        AdHocTask givenReadyTaskWithTwoSources() {
            task = new AdHocTask();
            task.setId(1L);
            task.setName("删材料任务");
            task.setDocSources(SOURCE_A + "\n" + SOURCE_B);
            task.setKbCode("adhoc-1");
            task.setInstanceId(5L);
            task.setStatus("READY");
            task.setWikiStatus("READY");
            task.setError("");
            kbEntries.add(doneEntry(1L, SOURCE_A, "取消政策文档"));
            kbEntries.add(doneEntry(2L, SOURCE_B, "查价接口文档"));
            nextEntryId.set(2L);
            wikiRow = revision(77L, "READY", LocalDateTime.now().minusHours(2));
            return task;
        }

        private SupplierKbEntry doneEntry(Long id, String source, String title) {
            SupplierKbEntry e = new SupplierKbEntry();
            e.setId(id); e.setKbCode("adhoc-1"); e.setType("url");
            e.setSource(source); e.setStatus("DONE"); e.setTitle(title);
            e.setCreatedAt(LocalDateTime.now().minusDays(1));
            return e;
        }
    }
    // ===== KD3（修正版）：删除不自动重编译，只出"已移除待重编译"横幅数据 =====

    @Test
    void deleteDoesNotTriggerWikiBuildAndCountsAsRemovalNotAddition() {
        var h = new Harness();
        h.givenReadyTaskWithTwoSources();

        // 用户在工作台/临时需求页删除条目 A（前端打的是 SupplierKbController DELETE → kbService.delete，
        // 不经过 AdHocService 任何编排入口）——夹具忠实模拟"条目消失 + 墓碑落库"
        h.kbEntries.remove(0);
        h.tombstones.put("url|" + SOURCE_A, LocalDateTime.now());

        JsonObject detail = h.service.detail(1L);

        // 核心断言：删除动作自始至终没有任何编译被触发（手动编译归下一棒的显式按钮）
        verify(h.wiki, never()).build(any(SupplierJob.class));
        assertEquals(0, h.builds.get(), "删除后 wiki.build 调用次数必须为 0（不得自动重编译）");
        // 移除语义与新增分开：删除绝不能被算成"新增材料"，也不能删完毫无提示
        assertEquals(1, detail.get("pendingRemovalCount").getAsInt(), "移除待重编译提示: " + detail);
        assertEquals(0, detail.get("pendingMaterialCount").getAsInt(), "删除不得计入新增材料: " + detail);
        // 旧 Wiki 版本原样保留
        assertEquals("READY", detail.getAsJsonObject("wiki").get("status").getAsString());
        assertEquals(77L, detail.getAsJsonObject("wiki").get("revision").getAsLong(), "保留旧 Wiki 版本行");
        assertEquals(1, detail.getAsJsonArray("kbEntries").size(), "列表实时更新为剩余 1 条");

        // 用户之后显式编译出新版 Wiki（新 READY 版本行晚于墓碑）→ 横幅计数自然归零
        h.wikiRow = revision(78L, "READY", LocalDateTime.now().plusSeconds(1));
        assertEquals(0, h.service.detail(1L).get("pendingRemovalCount").getAsInt(),
                "重编译完成后移除提示必须消失");
        System.out.println("[KD3] 删除后 builds=" + h.builds.get() + ", pendingRemovalCount="
                + detail.get("pendingRemovalCount") + ", pendingMaterialCount=" + detail.get("pendingMaterialCount"));
    }

    // ===== KD4：墓碑复活护栏 + 显式重贴恢复 =====

    @Test
    void retryStageDoesNotResurrectDeletedSource() {
        var h = new Harness();
        h.givenReadyTaskWithTwoSources();
        // 删除 A（墓碑在库），任务随后停在 INGEST 失败态
        h.kbEntries.remove(0);
        h.tombstones.put("url|" + SOURCE_A, LocalDateTime.now());
        h.task.setStatus("INGEST");
        h.task.setError("文档解析失败：模拟");

        h.service.retryStage(1L);

        // 「重试本段」重跑 INGEST：被删来源不得自动带回（复活护栏），B 已在库跳过——kb.add 一次都不发生
        verify(h.kb, never()).add(anyString(), anyString(), anyString(), nullable(String.class));
        assertEquals(1, h.kbEntries.size(), "库里仍然只有 B，删除决定被尊重");
        assertEquals(SOURCE_B, h.kbEntries.get(0).getSource());
        assertEquals("READY", h.task.getStatus(), "其余材料就绪 → 流水线正常走完，不死锁");
        assertTrue(h.tombstones.containsKey("url|" + SOURCE_A), "护栏靠墓碑生效，墓碑不被重试消费掉");
        System.out.println("[KD4-护栏] 重试后条目=" + h.kbEntries.stream().map(SupplierKbEntry::getSource).toList()
                + ", kb.add 调用=0, 墓碑仍在=" + h.tombstones.containsKey("url|" + SOURCE_A));
    }

    @Test
    void reAppendDeletedSourceRestoresEntryAndClearsTombstone() {
        var h = new Harness();
        h.givenReadyTaskWithTwoSources();
        // 用户删掉了 A
        h.kbEntries.remove(0);
        h.tombstones.put("url|" + SOURCE_A, LocalDateTime.now());

        // 用户又想要它回来：在「继续补充材料」里显式重贴同一 URL（KD4 采用：允许重新入库 + 清墓碑 + 提示恢复）
        JsonObject out = h.service.appendSources(1L, SOURCE_A, null, null, null, null, null);

        assertEquals(1, out.getAsJsonArray("added").size(), out.toString());
        assertEquals(SOURCE_A, out.getAsJsonArray("added").get(0).getAsString());
        assertEquals(1, out.getAsJsonArray("restored").size(), "restored 回传供页面提示「已恢复该来源」: " + out);
        assertEquals(SOURCE_A, out.getAsJsonArray("restored").get(0).getAsString());
        assertEquals(0, out.getAsJsonArray("duplicated").size(), out.toString());
        // 真实入库（绝不静默不入库/条目凭空消失）+ 墓碑被清除
        assertTrue(h.kbEntries.stream().anyMatch(e -> SOURCE_A.equals(e.getSource())), "条目必须真实回到库里");
        assertFalse(h.tombstones.containsKey("url|" + SOURCE_A), "重新入库必须清除墓碑（add 夹具同真实服务语义）");
        verify(h.kb).removedBefore("adhoc-1", "url", SOURCE_A);
        System.out.println("[KD4-恢复] added=" + out.getAsJsonArray("added") + ", restored=" + out.getAsJsonArray("restored")
                + ", 墓碑已清=" + !h.tombstones.containsKey("url|" + SOURCE_A));
    }
    // ===== AC5/KD5：删到 0 条来源 → 状态可读、旧 Wiki 保留、不死锁、不编译 =====

    @Test
    void deleteAllSourcesKeepsOldWikiReadableAndCountsAllRemovals() {
        var h = new Harness();
        h.givenReadyTaskWithTwoSources();

        h.kbEntries.remove(1);
        h.tombstones.put("url|" + SOURCE_B, LocalDateTime.now());
        h.kbEntries.remove(0);
        h.tombstones.put("url|" + SOURCE_A, LocalDateTime.now());

        JsonObject detail = h.service.detail(1L);
        assertEquals(0, detail.getAsJsonArray("kbEntries").size(), "列表为空");
        assertEquals(2, detail.get("pendingRemovalCount").getAsInt(), "两条移除都进横幅计数: " + detail);
        assertEquals(0, detail.get("pendingMaterialCount").getAsInt());
        assertEquals("READY", detail.getAsJsonObject("wiki").get("status").getAsString(), "旧 Wiki 版本保留可查");
        assertEquals("READY", detail.get("status").getAsString(), "任务仍是对话待命，不进入无意义状态");
        assertFalse(detail.get("running").getAsBoolean());
        verify(h.wiki, never()).build(any(SupplierJob.class));
        assertEquals(0, h.builds.get(), "删空也不得自动编译（空 Wiki 无意义，等用户补材料后手动编）");

        // 「重试本段」不被禁死：任务停在失败态时照常可点，点了给可读失败而不是挂起/死锁
        h.task.setStatus("INGEST");
        h.task.setError("模拟中断");
        h.service.retryStage(1L);   // 同步 starter：立即返回 = 没有死锁
        assertTrue(h.task.getError().contains("知识库没有可解析的材料"),
                "空库重试必须给可读失败原因，实际=" + h.task.getError());
        verify(h.kb, never()).add(anyString(), anyString(), anyString(), nullable(String.class));
        assertEquals(0, h.builds.get(), "没有材料绝不进编译");
        System.out.println("[KD5] 删空后 wiki=" + detail.getAsJsonObject("wiki").get("status")
                + ", pendingRemovalCount=" + detail.get("pendingRemovalCount")
                + ", 空库重试错误=" + h.task.getError());
    }

    @Test
    void pendingRemovalCountIsZeroWhenWikiNeverReady() {
        var h = new Harness();
        h.givenReadyTaskWithTwoSources();
        h.wikiRow = null;   // Wiki 从未编译（首编前删除材料）
        h.kbEntries.remove(0);
        h.tombstones.put("url|" + SOURCE_A, LocalDateTime.now());

        JsonObject detail = h.service.detail(1L);
        assertEquals(0, detail.get("pendingRemovalCount").getAsInt(),
                "没有 READY 版本就没有'仍含被删内容'的旧 Wiki——横幅不显示（'尚未编译'话术已覆盖）");
        assertEquals("EMPTY", detail.getAsJsonObject("wiki").get("status").getAsString());
    }
}