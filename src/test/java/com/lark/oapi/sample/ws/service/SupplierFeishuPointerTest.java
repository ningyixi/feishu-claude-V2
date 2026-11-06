package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.model.entity.SupplierKbEntry;
import com.lark.oapi.sample.ws.model.entity.SupplierWikiRevision;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.SupplierKbEntryRepository;
import com.lark.oapi.sample.ws.repository.SupplierKbPageRepository;
import com.lark.oapi.sample.ws.repository.SupplierKbRemovedRepository;
import com.lark.oapi.sample.ws.repository.SupplierWikiRevisionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * ②a 飞书 CHAT 模式"供应商知识底座指针"注入 — mockito 纯单测，无 Spring 上下文、无网络、不起进程。
 * 覆盖三层：
 * <ul>
 *   <li>{@link SupplierWikiService#feishuPointer}：WIKI 模式各编译状态的话术（READY/BUILDING/FAILED/无行），
 *       且只读 DB 行、绝不做 sources.snapshot 指纹重算（性能敏感路径）</li>
 *   <li>{@link SupplierKbService#feishuKbPointer}：TEXT 模式索引指针 + 2000 字符截断</li>
 *   <li>{@link FeishuBotService#composePointers}：纯组装函数——0 块原文不变、块序保持、≤3 块、
 *       2400 预算截尾块、「（系统注入，非用户消息）」标记</li>
 * </ul>
 * FeishuBotService 全量构造需要飞书 WS/卡片依赖，不便 mock——按方案取舍：组装逻辑已抽成
 * 静态纯函数在此直测；取数层 buildSupplierPointers 为私有薄封装（查库→按模式分派→异常吞掉），走查覆盖。
 */
class SupplierFeishuPointerTest {

    private static final String KB_CODE = "ACME";
    private static final String WIKI_DIR = "D:/feishu-claude-data/supplier-kb/.wiki/acme/revisions/7";

    // ===== SupplierWikiService.feishuPointer =====

    private final SupplierWikiSources sources = mock(SupplierWikiSources.class);
    private final SupplierWikiRevisionRepository revisions = mock(SupplierWikiRevisionRepository.class);
    private final BotInstanceRepository instances = mock(BotInstanceRepository.class);
    private final ClaudeCodeService claude = mock(ClaudeCodeService.class);
    private final SupplierKbService kb = mock(SupplierKbService.class);

    private SupplierWikiService wiki() {
        return new SupplierWikiService(sources, revisions, instances, claude, kb,
                System.getProperty("java.io.tmpdir"));
    }

    private SupplierJob wikiJob() {
        SupplierJob job = new SupplierJob();
        job.setId(1L);
        job.setKnowledgeMode("WIKI");
        job.setSupplierName("Acme");
        job.setSupplierCode(KB_CODE);
        when(kb.kbCode(job)).thenReturn(KB_CODE);
        return job;
    }

    private SupplierWikiRevision revision(String status) {
        SupplierWikiRevision r = new SupplierWikiRevision();
        ReflectionTestUtils.setField(r, "id", 7L);   // 实体无 setId（IDENTITY 生成），测试用反射种入
        r.setKbCode(KB_CODE);
        r.setStatus(status);
        r.setDirectory(WIKI_DIR);
        r.setSourceCount(12);
        return r;
    }

    private void stubRevision(SupplierJob job, SupplierWikiRevision r) {
        when(revisions.findFirstByKbCodeOrderByIdDesc(KB_CODE)).thenReturn(Optional.ofNullable(r));
    }

    @Test
    void readyPointerCarriesVersionSourceCountIndexPathAndCitationRules() {
        SupplierJob job = wikiJob();
        stubRevision(job, revision("READY"));

        String p = wiki().feishuPointer(job);

        assertTrue(p.startsWith("【供应商 LLM Wiki】Acme（ACME）"), p);
        assertTrue(p.contains("版本 7"), p);
        assertTrue(p.contains("12 个来源"), p);
        assertTrue(p.contains(WIKI_DIR + "/wiki/index.md"), p);
        assertTrue(p.contains("wiki/<页面ID>.md"), p);
        assertTrue(p.contains("raw/<来源ID>.md"), p);
        assertTrue(p.contains("引用页面 ID"), p);
        assertTrue(p.contains("Wiki 未覆盖明说，不得猜测"), p);
        assertTrue(p.length() <= 400, "READY 指针块应 ≤400 字符，实际 " + p.length());
        // 性能敏感路径：只读 DB 行，绝不做来源指纹重算
        verifyNoInteractions(sources);
    }

    @Test
    void buildingPointerSaysCompilingAndAllowsAnsweringFromKnownInfo() {
        SupplierJob job = wikiJob();
        stubRevision(job, revision("BUILDING"));

        String p = wiki().feishuPointer(job);

        assertTrue(p.contains("正在编译中"), p);
        assertTrue(p.contains("完成后会通知"), p);
        assertTrue(p.contains("本轮可先按已知信息回答"), p);
        verifyNoInteractions(sources);
    }

    @Test
    void failedPointerMentionsDirectoryRebuildAndForbidsCitation() {
        SupplierJob job = wikiJob();
        stubRevision(job, revision("FAILED"));

        String p = wiki().feishuPointer(job);

        assertTrue(p.contains("编译失败"), p);
        assertTrue(p.contains(WIKI_DIR), p);
        assertTrue(p.contains("可用工作台重建"), p);
        assertTrue(p.contains("本轮暂勿引用"), p);
        verifyNoInteractions(sources);
    }

    @Test
    void noRevisionRowYieldsEmptyPointer() {
        SupplierJob job = wikiJob();
        stubRevision(job, null);

        assertEquals("", wiki().feishuPointer(job));
    }

    @Test
    void textModeJobNeverGetsWikiPointer() {
        SupplierJob job = wikiJob();
        job.setKnowledgeMode("TEXT");

        assertEquals("", wiki().feishuPointer(job));
        verifyNoInteractions(revisions, sources);   // 模式守卫在查库之前
    }

    // ===== SupplierKbService.feishuKbPointer（TEXT 模式） =====

    private SupplierKbService kbService(SupplierKbEntryRepository repo) {
        return new SupplierKbService(repo, mock(SupplierKbRemovedRepository.class),
                mock(SupplierKbPageRepository.class), System.getProperty("java.io.tmpdir"));
    }

    private SupplierJob textJob() {
        SupplierJob job = new SupplierJob();
        job.setId(1L);
        job.setKnowledgeMode("TEXT");
        job.setSupplierName("Acme");
        job.setSupplierCode(KB_CODE);
        return job;
    }

    private SupplierKbEntry entry(long id, String title, String status) {
        SupplierKbEntry e = new SupplierKbEntry();
        ReflectionTestUtils.setField(e, "id", id);
        e.setKbCode(KB_CODE);
        e.setType("url");
        e.setTitle(title);
        e.setStatus(status);
        e.setMdPath("D:/kb/ACME/" + id + ".md");
        e.setSource("https://acme.dev/docs/" + id);
        return e;
    }

    @Test
    void textPointerCarriesPrefixAndIndexVerbatim() {
        SupplierKbEntryRepository repo = mock(SupplierKbEntryRepository.class);
        when(repo.findByKbCodeOrderByCreatedAtAsc(KB_CODE))
                .thenReturn(List.of(entry(3, "API 文档", "DONE"), entry(4, "商务合同", "RAW")));

        String p = kbService(repo).feishuKbPointer(textJob());

        assertTrue(p.startsWith("【供应商知识库】Acme：材料索引如下，需要细节按路径 Read。"), p);
        assertTrue(p.contains("API 文档"), p);
        assertTrue(p.contains("已解析，文件: D:/kb/ACME/3.md"), p);
        assertTrue(p.contains("商务合同"), p);
        assertTrue(p.contains("未解析"), p);
        assertTrue(p.length() <= 2000 + 20, p.length() + "");
    }

    @Test
    void textPointerTruncatesTo2000WithWorkbenchHint() {
        SupplierKbEntryRepository repo = mock(SupplierKbEntryRepository.class);
        List<SupplierKbEntry> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(entry(100 + i, "超长标题条目".repeat(12) + i, "DONE"));
        }
        when(repo.findByKbCodeOrderByCreatedAtAsc(KB_CODE)).thenReturn(many);

        String p = kbService(repo).feishuKbPointer(textJob());

        assertTrue(p.endsWith("…（详见工作台知识库）"), p.substring(Math.max(0, p.length() - 40)));
        assertEquals(2000 + "…（详见工作台知识库）".length(), p.length());
    }

    @Test
    void emptyKbStillYieldsPlaceholderPointerWithoutCrash() {
        SupplierKbEntryRepository repo = mock(SupplierKbEntryRepository.class);
        when(repo.findByKbCodeOrderByCreatedAtAsc(KB_CODE)).thenReturn(List.of());

        String p = kbService(repo).feishuKbPointer(textJob());

        assertTrue(p.startsWith("【供应商知识库】Acme："), p);
        assertTrue(p.contains("知识库为空"), p);   // indexText 原样占位说明——agent 知道无材料可读
    }

    // ===== FeishuBotService.composePointers（纯组装函数） =====

    @Test
    void zeroPointersLeaveUserTextUntouched() {
        assertEquals("hello", FeishuBotService.composePointers(List.of(), "hello"));
        assertEquals("hello", FeishuBotService.composePointers(null, "hello"));
        assertEquals("hello", FeishuBotService.composePointers(java.util.Arrays.asList("", "  ", null), "hello"));
    }

    @Test
    void pointersKeepOrderAndCarryInjectionMarker() {
        String out = FeishuBotService.composePointers(List.of("指针一", "指针二"), "用户问题");

        assertTrue(out.startsWith("用户问题"), out);
        assertTrue(out.contains("（系统注入，非用户消息）"), out);
        assertTrue(out.indexOf("（系统注入，非用户消息）") < out.indexOf("指针一"), out);
        assertTrue(out.indexOf("指针一") < out.indexOf("指针二"), out);
    }

    @Test
    void atMostThreeBlocksInjected() {
        String out = FeishuBotService.composePointers(List.of("P1", "P2", "P3", "P4", "P5"), "q");

        assertTrue(out.contains("P1") && out.contains("P2") && out.contains("P3"), out);
        assertFalse(out.contains("P4"), out);
        assertFalse(out.contains("P5"), out);
    }

    @Test
    void budgetDropsTailBlocksWhole() {
        String b1 = "1" + "x".repeat(999);   // 1000 字符
        String b2 = "2" + "y".repeat(999);
        String b3 = "3" + "z".repeat(999);   // 1000+2+1000+2+1000=3004 > 2400 → 第 3 块整块丢弃

        String out = FeishuBotService.composePointers(List.of(b1, b2, b3), "q");

        assertTrue(out.contains(b1), "第 1 块应完整注入");
        assertTrue(out.contains(b2), "第 2 块应完整注入");
        assertFalse(out.contains("3zzz"), "超预算的尾部块应整块丢弃");
        // 注入段（不含用户文本与标记行）不超过预算
        assertTrue(out.length() <= "q".length() + 40 + FeishuBotService.POINTER_BUDGET_CHARS);
    }

    @Test
    void firstBlockLargerThanBudgetYieldsOriginalText() {
        String huge = "x".repeat(FeishuBotService.POINTER_BUDGET_CHARS + 1);

        assertEquals("q", FeishuBotService.composePointers(List.of(huge), "q"));
    }
}
