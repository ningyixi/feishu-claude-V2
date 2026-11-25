package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.model.entity.SupplierKbEntry;
import com.lark.oapi.sample.ws.model.entity.SupplierKbRemoved;
import com.lark.oapi.sample.ws.repository.SupplierKbEntryRepository;
import com.lark.oapi.sample.ws.repository.SupplierKbPageRepository;
import com.lark.oapi.sample.ws.repository.SupplierKbRemovedRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 知识库删除 + 墓碑语义（11-24-adhoc-kb-delete-entry KD4 / AC3取材层 / AC4）——真实
 * {@link SupplierKbService} + 内存化 mock 仓储 + @TempDir 真实产物文件（不碰
 * D:/feishu-claude-data/supplier-kb，不碰现网数据，无 Spring、无网络、无 LLM）：
 * <ol>
 *   <li><b>AC3 取材层关键词断言</b>：删除某文档后，{@link SupplierWikiSources#snapshot}
 *       （= {@code SupplierWikiService.build} 的同步取材首步，build:55）产出的编译输入
 *       <b>不再包含</b>被删文档的固定关键词，未删文档的关键词原样保留——复用
 *       {@link SiteCrawlWikiInputTest} 的取材层断言风格，不靠人眼；</li>
 *   <li><b>删除的物理语义</b>：产物 md 文件被删、墓碑 (kbCode,type,source) 落库、条目消失；</li>
 *   <li><b>AC4/KD4 墓碑结论 = 允许重新入库 + 清除墓碑 + 可回传「已恢复该来源」</b>：
 *       删过的 url 来源重新 {@code add} 成功且墓碑被清除；删过的笔记同文重贴
 *       {@code appendText} → created=true 且 restored=true（页面据此提示"已恢复该来源"），
 *       绝不静默不入库、不条目凭空消失。</li>
 * </ol>
 */
class SupplierKbDeleteTombstoneTest {

    private static final String KB = "adhoc-77";
    private static final String DELETED_KEYWORD = "FIXED-KEYWORD-DELETE-A";
    private static final String KEPT_KEYWORD = "FIXED-KEYWORD-KEEP-B";
    // 字面公网 IP（非内网/回环）——add("url") 的 assertSafeUrl 对字面 IP 不做 DNS 解析，测试保持离线确定
    private static final String SOURCE_A = "http://93.184.216.34/cancel-policy";
    private static final String SOURCE_B = "http://93.184.216.34/price-api";

    @TempDir
    Path dir;

    // ===== 内存化仓储夹具 =====
    private final List<SupplierKbEntry> rows = new ArrayList<>();
    private final List<SupplierKbRemoved> tombstones = new ArrayList<>();
    private final AtomicLong ids = new AtomicLong(100);
    private SupplierKbEntryRepository repo;
    private SupplierKbRemovedRepository removedRepo;
    private SupplierKbPageRepository pageRepo;
    private SupplierKbService kb;

    @BeforeEach
    void setUp() {
        repo = mock(SupplierKbEntryRepository.class);
        removedRepo = mock(SupplierKbRemovedRepository.class);
        pageRepo = mock(SupplierKbPageRepository.class);

        when(repo.save(any(SupplierKbEntry.class))).thenAnswer(inv -> {
            SupplierKbEntry e = inv.getArgument(0);
            if (e.getId() == null) { e.setId(ids.incrementAndGet()); rows.add(e); }
            return e;
        });
        when(repo.findById(any())).thenAnswer(inv -> rows.stream()
                .filter(e -> e.getId().equals(inv.getArgument(0))).findFirst());
        when(repo.findByKbCodeOrderByCreatedAtAsc(anyString())).thenAnswer(inv -> rows.stream()
                .filter(e -> e.getKbCode().equals(inv.getArgument(0, String.class)))
                .sorted(Comparator.comparing(SupplierKbEntry::getCreatedAt)).toList());
        when(repo.existsByKbCodeAndTypeAndSource(anyString(), anyString(), anyString())).thenAnswer(inv ->
                rows.stream().anyMatch(e -> e.getKbCode().equals(inv.getArgument(0))
                        && e.getType().equals(inv.getArgument(1)) && e.getSource().equals(inv.getArgument(2))));
        when(repo.findFirstByKbCodeAndTypeAndSource(anyString(), anyString(), anyString())).thenAnswer(inv ->
                rows.stream().filter(e -> e.getKbCode().equals(inv.getArgument(0))
                        && e.getType().equals(inv.getArgument(1)) && e.getSource().equals(inv.getArgument(2)))
                        .findFirst());
        org.mockito.Mockito.doAnswer(inv -> {
            SupplierKbEntry e = inv.getArgument(0);
            rows.removeIf(x -> x.getId().equals(e.getId()));
            return null;
        }).when(repo).delete(any(SupplierKbEntry.class));

        when(removedRepo.save(any(SupplierKbRemoved.class))).thenAnswer(inv -> {
            SupplierKbRemoved t = inv.getArgument(0);
            // 模拟 JPA @PrePersist（实体无公共 setter，反射注入——与 AdHocAppendSourcesTest.inject 同款）
            if (t.getCreatedAt() == null) {
                java.lang.reflect.Field f = SupplierKbRemoved.class.getDeclaredField("createdAt");
                f.setAccessible(true);
                f.set(t, LocalDateTime.now());
            }
            tombstones.add(t);
            return t;
        });
        when(removedRepo.existsByKbCodeAndTypeAndSource(anyString(), anyString(), anyString())).thenAnswer(inv ->
                tombstones.stream().anyMatch(t -> t.getKbCode().equals(inv.getArgument(0))
                        && t.getType().equals(inv.getArgument(1)) && t.getSource().equals(inv.getArgument(2))));
        org.mockito.Mockito.doAnswer(inv -> {
            tombstones.removeIf(t -> t.getKbCode().equals(inv.getArgument(0))
                    && t.getType().equals(inv.getArgument(1)) && t.getSource().equals(inv.getArgument(2)));
            return null;
        }).when(removedRepo).deleteByKbCodeAndTypeAndSource(anyString(), anyString(), anyString());
        when(removedRepo.countByKbCodeAndCreatedAtAfter(anyString(), any())).thenAnswer(inv ->
                tombstones.stream().filter(t -> t.getKbCode().equals(inv.getArgument(0))
                        && t.getCreatedAt().isAfter(inv.getArgument(1, LocalDateTime.class))).count());

        when(pageRepo.findByEntryIdOrderByIdAsc(any())).thenReturn(List.of());

        kb = new SupplierKbService(repo, removedRepo, pageRepo, dir.toString());
    }

    /** 直接落一条 DONE 条目 + 真实产物文件（含固定关键词），绕过解析管线 */
    private SupplierKbEntry givenDoneEntry(String source, String title, String keyword) throws Exception {
        SupplierKbEntry e = new SupplierKbEntry();
        e.setKbCode(KB);
        e.setType("url");
        e.setSource(source);
        e.setTitle(title);
        e.setStatus("DONE");
        e.setMetaJson("{}");
        e.setCreatedAt(LocalDateTime.now().minusDays(1));
        repo.save(e);
        Path md = dir.resolve(e.getId() + ".md");
        Files.writeString(md, "> 来源URL: " + source + "\n\n# " + title + "\n\n正文固定关键词 " + keyword + " 所在段落。\n",
                StandardCharsets.UTF_8);
        e.setMdPath(md.toString());
        repo.save(e);
        return e;
    }

    private SupplierJob job() {
        SupplierJob job = new SupplierJob();
        job.setSupplierCode(KB);   // kbCode(job) → supplierCode，与 AdHocService.wikiJob 适配器同语义
        return job;
    }

    // ===== AC3（取材层）：删除后编译输入不再含被删文档关键词 =====

    @Test
    void deletedDocumentKeywordDisappearsFromWikiCompileInputWhileKeptOneSurvives() throws Exception {
        SupplierKbEntry a = givenDoneEntry(SOURCE_A, "取消政策文档", DELETED_KEYWORD);
        givenDoneEntry(SOURCE_B, "查价接口文档", KEPT_KEYWORD);
        var sources = new SupplierWikiSources(kb, pageRepo);

        // 删除前：两个关键词都在编译输入里（取材基线）
        String before = joinContent(sources.snapshot(job()));
        assertTrue(before.contains(DELETED_KEYWORD), "基线：被删文档关键词必须在编译输入里");
        assertTrue(before.contains(KEPT_KEYWORD), "基线：未删文档关键词必须在编译输入里");

        // 删除（真实 SupplierKbService.delete：产物文件 + 墓碑 + 条目行）
        kb.delete(a.getId());
        assertFalse(Files.exists(Path.of(a.getMdPath())), "产物 md 文件必须被物理删除");
        assertEquals(1, tombstones.size(), "删除必须落墓碑");
        assertEquals(SOURCE_A, tombstones.get(0).getSource());
        assertEquals("url", tombstones.get(0).getType());
        assertEquals(KB, tombstones.get(0).getKbCode());
        assertTrue(kb.removedBefore(KB, "url", SOURCE_A), "removedBefore 能查到墓碑");

        // AC3 核心断言：build 的同步取材首步（SupplierWikiService.build:55 → snapshot）在删除后
        // 产出的编译输入不再含被删文档的固定关键词；未删文档原样保留
        var snapshot = sources.snapshot(job());
        String after = joinContent(snapshot);
        assertFalse(after.contains(DELETED_KEYWORD),
                "删除后新 Wiki 的编译输入不得再含被删文档关键词，实际=" + after);
        assertTrue(after.contains(KEPT_KEYWORD), "未删文档必须原样保留在编译输入里");
        assertEquals(List.of("e" + (a.getId() + 1)), snapshot.sources().stream()
                .map(SupplierWikiSources.Source::id).toList(), "来源清单只剩未删条目");
        System.out.println("[AC3-取材层] 删除前编译输入含 " + DELETED_KEYWORD + "=" + before.contains(DELETED_KEYWORD)
                + "；删除后含=" + after.contains(DELETED_KEYWORD) + "（保留 " + KEPT_KEYWORD + "=" + after.contains(KEPT_KEYWORD) + "）");
        System.out.println("[AC3-取材层] 删除后编译输入=" + after.replace("\n", "\\n"));
    }

    private static String joinContent(SupplierWikiSources.Snapshot snapshot) {
        StringBuilder sb = new StringBuilder();
        snapshot.sources().forEach(s -> sb.append(s.content()).append('\n'));
        return sb.toString();
    }

    // ===== AC4/KD4：删除后重新追加同一来源 → 允许重新入库 + 清除墓碑 =====

    @Test
    void reAddingDeletedUrlSourceSucceedsAndClearsTombstone() throws Exception {
        SupplierKbEntry a = givenDoneEntry(SOURCE_A, "取消政策文档", DELETED_KEYWORD);
        kb.delete(a.getId());
        assertTrue(kb.removedBefore(KB, "url", SOURCE_A));

        // 显式重新入库（工作台补充表单 / AdHoc appendSources 走的同一 add 通道）：不被墓碑拒绝
        SupplierKbEntry fresh = kb.add(KB, "url", SOURCE_A, null);
        assertNotNull(fresh.getId());
        assertNotEquals(a.getId(), fresh.getId(), "重新入库产生新条目");
        assertEquals("RAW", fresh.getStatus(), "url 重新入库回到 RAW（点解析再抓，与首次入库语义一致）");
        assertEquals(1, rows.size(), "条目真实回到库里（绝不静默不入库）");

        // 墓碑被清除：自动同步路径（syncFromJob / AdHoc INGEST 重跑）恢复对该来源的正常判定
        assertFalse(kb.removedBefore(KB, "url", SOURCE_A), "重新入库必须清除墓碑");
        assertTrue(tombstones.isEmpty(), "墓碑表里不留残账");
        System.out.println("[AC4] 删除→重新入库成功: 新条目 id=" + fresh.getId() + ", 墓碑已清除=" + tombstones.isEmpty());
    }

    @Test
    void reAppendingDeletedNoteReportsRestoredAndClearsTombstone() throws Exception {
        // 首次贴笔记：created=true, restored=false
        var first = kb.appendText(KB, "note", "对接备忘", null, "押金按房费三倍", "口头约定原文");
        assertTrue(first.created());
        assertFalse(first.restored(), "首次入库不算恢复");
        String fingerprint = first.entry().getSource();
        assertTrue(fingerprint.startsWith("note-fp:"), "笔记按内容指纹去重: " + fingerprint);

        // 删除 → 墓碑按指纹落
        kb.delete(first.entry().getId());
        assertTrue(kb.removedBefore(KB, "note", fingerprint));
        assertFalse(Files.exists(Path.of(first.entry().getMdPath())), "笔记产物文件也被删");

        // 同文重贴（KD4 结论：允许重新入库 + 清除墓碑 + restored=true 供页面提示「已恢复该来源」）
        var second = kb.appendText(KB, "note", "对接备忘", null, "押金按房费三倍", "口头约定原文");
        assertTrue(second.created(), "删了再加回来必须真实入库（严禁静默不入库）");
        assertTrue(second.restored(), "restored=true → 页面提示「已恢复该来源」");
        assertFalse(kb.removedBefore(KB, "note", fingerprint), "墓碑随重新入库清除");
        assertEquals("DONE", second.entry().getStatus());
        assertTrue(Files.exists(Path.of(second.entry().getMdPath())), "新产物文件真实落盘");

        // 既有幂等语义不受影响：第三次同文重贴 → 去重命中，不产生重复条目
        var third = kb.appendText(KB, "note", "对接备忘", null, "押金按房费三倍", "口头约定原文");
        assertFalse(third.created(), "同文再贴走去重（既有 AC6 语义不变）");
        assertFalse(third.restored());
        assertEquals(second.entry().getId(), third.entry().getId());
        System.out.println("[AC4-笔记] 删除后同文重贴: created=" + second.created() + ", restored=" + second.restored()
                + ", 墓碑清除=" + !kb.removedBefore(KB, "note", fingerprint));
    }

    @Test
    void deleteEntryWithoutSourceWritesNoTombstoneAndChatLegacyAddUnaffected() {
        // 老形态 chat（add 单段路径，source=""）：删除不落墓碑（无 source 可记），重贴照常入库
        SupplierKbEntry chat = kb.add(KB, "chat", "群里确认字段用 v2", "群聊摘要");
        assertEquals("", chat.getSource());
        kb.delete(chat.getId());
        assertTrue(tombstones.isEmpty(), "无来源条目不写墓碑（既有语义）");
        SupplierKbEntry again = kb.add(KB, "chat", "群里确认字段用 v2", "群聊摘要");
        assertNotNull(again.getId(), "老 chat 通道删后重加不受影响");
    }

    // ===== removedCountSince：AdHoc「已移除、尚未编译进 Wiki」横幅的数据源 =====

    @Test
    void removedCountSinceCountsOnlyTombstonesNewerThanAnchor() throws Exception {
        SupplierKbEntry a = givenDoneEntry(SOURCE_A, "取消政策文档", DELETED_KEYWORD);
        LocalDateTime anchor = LocalDateTime.now().minusHours(2);   // 模拟 READY 版本行的 createdAt
        assertEquals(0, kb.removedCountSince(KB, anchor), "删除前计数为 0");
        kb.delete(a.getId());
        assertEquals(1, kb.removedCountSince(KB, anchor), "删除后（墓碑晚于锚点）计数为 1");
        assertEquals(0, kb.removedCountSince(KB, LocalDateTime.now().plusHours(1)), "锚点晚于墓碑 → 0（重编译完成后横幅消失的机制）");
        kb.add(KB, "url", SOURCE_A, null);
        assertEquals(0, kb.removedCountSince(KB, anchor), "重新入库清墓碑 → 计数回落（转为新增材料语义）");
    }
}
