package com.lark.oapi.sample.ws.service;

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
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.*;

/**
 * 临时需求编排（11-17-adhoc-requirement-module）mockito 纯单测（AC6）：
 * 两段顺序推进 / 失败停在当段 / 重试幂等 / 超时置败 / 删除只清本模块记录 / 重启恢复标记 /
 * A3 注入纯函数（Wiki 入口头部 + 对话 prompt + 措辞纪律）/ AC4 对话走实例主会话、永不使用 lane。
 * 等待循环经注入的 sleeper+pollInterval/stepTimeout 快进，无真实睡眠、无 Spring 上下文、无网络。
 */
class AdHocServiceTest {

    // ===== 测试脚手架 =====

    /**
     * SupplierWikiRevision 只暴露 getId()（公共实体不为测试加 setter）——反射注入主键构造版本行。
     * 断言语义不变：AdHoc 侧读的正是这一行的 status/phase/error/directory/manifestJson/sourceCount。
     */
    private static SupplierWikiRevision revision(Long id) {
        SupplierWikiRevision row = new SupplierWikiRevision();
        try {
            java.lang.reflect.Field f = SupplierWikiRevision.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(row, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("无法为 SupplierWikiRevision 注入 id: " + e, e);
        }
        return row;
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

        /** 知识库条目真值表（kb.list 的返回源，测试直接改状态模拟解析推进） */
        final List<SupplierKbEntry> kbEntries = new ArrayList<>();
        /** reparse 后条目转到的状态（模拟解析成功/失败/进行中） */
        String reparseResult = "DONE";
        /** 当前最新 Wiki 版本行（null = EMPTY）——build 的产物真值 */
        SupplierWikiRevision wikiRow;
        /** build 产出的新行状态（"READY"/"FAILED"） */
        String buildResult = "READY";
        /** build 产出的 manifest（默认一页干净内容；卡点场景往里塞带【卡点】的页面正文） */
        String buildManifest = "{\"pages\":[{\"id\":\"api-overview\",\"title\":\"接口总览\",\"summary\":\"历史接口清单与字段\",\"content\":\"取消接口必须携带供应商订单号。\"}]}";
        /** build 产出的版本行 id（每次 build 递增，模拟"重编总是产生新版本行"） */
        long nextRevisionId = 77L;
        final AtomicInteger builds = new AtomicInteger();
        final AtomicInteger sleeps = new AtomicInteger();
        /** 对话流水真值表 */
        final List<AdHocChat> chats = new ArrayList<>();
        String chatReply = "好的，已按 Wiki 页面 api-overview 处理。";
        /** 卡点飞书推送结果（默认成功）；失败场景改成 new NotifyResult(false, "<原因>") */
        FeishuBotService.NotifyResult notifyResult = FeishuBotService.NotifyResult.OK;
        /** 实际推送出去的飞书文案（断言通知内容/次数） */
        final List<String> notices = new ArrayList<>();

        AdHocTask task;

        Harness() {
            service = new AdHocService(taskRepo, chatRepo, instanceRepo, kb, wiki, wikiRevisions, claude, feishu);
            service.pollIntervalMs = 2;
            service.stepTimeoutMs = 10_000;
            service.sleeper = ms -> sleeps.incrementAndGet();
            service.starter = (name, r) -> r.run();   // 同步执行流水线/对话轮（create/retry/ask 内联跑完）

            // 任务仓储：save 补 id 并持有行；findById 按 id 返回
            when(taskRepo.save(any(AdHocTask.class))).thenAnswer(inv -> {
                AdHocTask t = inv.getArgument(0);
                if (t.getId() == null) t.setId(1L);
                task = t;
                return t;
            });
            when(taskRepo.findById(1L)).thenAnswer(inv -> Optional.ofNullable(task));
            when(taskRepo.findAll()).thenAnswer(inv -> task == null ? List.of() : List.of(task));
            when(taskRepo.findAllByOrderByIdDesc()).thenAnswer(inv -> task == null ? List.of() : List.of(task));

            // 实例（工作目录/模型走与工作台同源读法；authorized_open_id = 卡点通知收件人）
            BotInstance inst = new BotInstance();
            inst.setId(5L);
            inst.setEnabled(true);
            inst.setName("测试实例");
            inst.setClaudeWorkingDir("/work");
            inst.setShellWorkingDir("/shell");
            inst.setClaudeModel("sonnet");
            inst.setAuthorizedOpenId("ou_testuser0001");
            when(instanceRepo.findById(5L)).thenReturn(Optional.of(inst));

            // 知识库：add 建条目（url/pdf/html=RAW 待解析，md 入库即 DONE）；reparse 按 reparseResult 转态
            when(kb.list(anyString())).thenAnswer(inv -> List.copyOf(kbEntries));
            when(kb.add(anyString(), anyString(), anyString(), nullable(String.class))).thenAnswer(inv -> {
                SupplierKbEntry e = new SupplierKbEntry();
                e.setId((long) (kbEntries.size() + 1));
                e.setKbCode(inv.getArgument(0));
                e.setType(inv.getArgument(1));
                e.setSource(inv.getArgument(2));
                e.setTitle("条目-" + inv.getArgument(2));
                e.setStatus("md".equals(e.getType()) ? "DONE" : "RAW");
                kbEntries.add(e);
                return e;
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

            // Wiki：轻量单行读 + build 产出新行（模拟异步编译在下次轮询前完成）
            when(wikiRevisions.findFirstByKbCodeOrderByIdDesc(anyString()))
                    .thenAnswer(inv -> Optional.ofNullable(wikiRow));
            when(wiki.build(any(SupplierJob.class))).thenAnswer(inv -> {
                builds.incrementAndGet();
                wikiRow = revision(nextRevisionId++);
                wikiRow.setKbCode("adhoc-1");
                wikiRow.setStatus(buildResult);
                wikiRow.setSourceCount(1);
                wikiRow.setDirectory("D:/kb/.wiki/xyz/revisions/77");
                wikiRow.setManifestJson(buildManifest);
                if ("FAILED".equals(buildResult)) wikiRow.setError("模型输出不是 JSON");
                return wikiRow;
            });

            // 对话流水
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

            // 卡点飞书推送：记下文案，按 notifyResult 返回成功/失败
            when(feishu.notifyUserChecked(nullable(Long.class), nullable(String.class), nullable(String.class)))
                    .thenAnswer(inv -> {
                        notices.add(inv.getArgument(2));
                        return notifyResult;
                    });
        }

        /** 预置一个已存在的任务（不触发流水线） */
        AdHocTask givenTask(String status, String error) {
            task = new AdHocTask();
            task.setId(1L);
            task.setName("改历史接口字段");
            task.setDocSources("https://docs.acme.com/api");
            task.setKbCode("adhoc-1");
            task.setInstanceId(5L);
            task.setStatus(status);
            task.setWikiStatus("READY".equals(status) ? "READY" : "EMPTY");
            task.setError(error == null ? "" : error);
            return task;
        }
    }

    // ===== a) 创建 + 两段顺序推进（AC2：全自动到 READY，无第三段） =====

    @Test
    void createNormalizesSourcesAndAssignsExclusiveKbCode(@TempDir java.nio.file.Path dir) throws Exception {
        var h = new Harness();
        h.service.starter = (name, r) -> { };   // 本测只看创建产物，不跑流水线
        java.nio.file.Path pdf = dir.resolve("a.pdf");
        java.nio.file.Files.writeString(pdf, "x");   // 本地路径预检要求文件真实存在

        var t = h.service.create("改字段", 5L, "  https://docs.acme.com/api  \n\n" + pdf + "\nhttps://docs.acme.com/api\n");

        assertEquals("改字段", t.getName());
        assertEquals("adhoc-1", t.getKbCode(), "kbCode = 专属命名空间 adhoc-<id>");
        assertEquals("https://docs.acme.com/api\n" + pdf, t.getDocSources(), "去空行/去首尾空白/去重，保持顺序");
        assertEquals("INGEST", t.getStatus());
        assertEquals("EMPTY", t.getWikiStatus());
        assertEquals("", t.getError());
    }

    @Test
    void createRejectsMissingFilesAndUnknownSourceTypes(@TempDir java.nio.file.Path dir) {
        var h = new Harness();
        h.service.starter = (name, r) -> { };
        var missing = assertThrows(IllegalArgumentException.class,
                () -> h.service.create("t", 5L, dir.resolve("不存在.pdf").toString()));
        assertTrue(missing.getMessage().contains("文件不存在"), missing.getMessage());
        var unknown = assertThrows(IllegalArgumentException.class,
                () -> h.service.create("t", 5L, "D:\\docs\\a.csv"));
        assertTrue(unknown.getMessage().contains("无法识别"), unknown.getMessage());
        // 11-23 起 .docx 是受支持类型：不存在的 docx 走"文件不存在"分支而非"无法识别"
        var missingOffice = assertThrows(IllegalArgumentException.class,
                () -> h.service.create("t", 5L, "D:\\docs\\a.docx"));
        assertTrue(missingOffice.getMessage().contains("文件不存在"), missingOffice.getMessage());
        assertThrows(IllegalArgumentException.class, () -> h.service.create("t", 5L, "   \n "));
    }

    @Test
    void blankNameFallsBackToTimestampLabel() {
        var h = new Harness();
        h.service.starter = (name, r) -> { };
        var t = h.service.create("  ", 5L, "https://docs.acme.com/api");
        assertTrue(t.getName().startsWith("临时需求-"), t.getName());
    }

    @Test
    void createRunsIngestThenWikiToReadyWithoutManualSteps() {
        var h = new Harness();

        // create 内联跑完整条流水线（AC2：建一个含单条 URL 来源的任务 → 自动推进 入库→Wiki READY）
        h.service.create("改字段", 5L, "https://docs.acme.com/api");

        InOrder order = inOrder(h.kb, h.wiki);
        order.verify(h.kb).add("adhoc-1", "url", "https://docs.acme.com/api", null);
        order.verify(h.kb).reparse(1L);                        // url 入库为 RAW → 触发解析
        order.verify(h.wiki).build(any(SupplierJob.class));    // 材料就绪后编译 Wiki
        assertTrue(h.sleeps.get() > 0, "等待循环应经过轮询睡眠");

        assertEquals("READY", h.task.getStatus());
        assertEquals("READY", h.task.getWikiStatus());
        assertEquals("", h.task.getError());
        assertEquals(1, h.builds.get());
    }

    @Test
    void wikiBuildReceivesTransientJobAdapterNeverPersisted() {
        var h = new Harness();
        h.givenTask("INGEST", "");

        h.service.runPipeline(1L, "WIKI");

        // 复用 SupplierWikiService.build 的唯一途径是 transient 适配器：五个字段齐、id=null（从不落库，
        // 不往 supplier_job 表塞行；本服务构造器也没有任何 SupplierJob 仓储依赖）
        ArgumentCaptor<SupplierJob> captor = ArgumentCaptor.forClass(SupplierJob.class);
        verify(h.wiki).build(captor.capture());
        SupplierJob adapter = captor.getValue();
        assertNull(adapter.getId(), "适配器必须 transient（无 id，永不落库）");
        assertEquals("adhoc-1", adapter.getSupplierCode(), "kbCode 经 supplierCode 传入（Wiki 按它归属版本行/目录）");
        assertEquals("改历史接口字段", adapter.getSupplierName());
        assertEquals("WIKI", adapter.getKnowledgeMode());
        assertEquals(5L, adapter.getInstanceId());
        assertEquals("[]", adapter.getDocsJson(), "来源已由 INGEST 段入库，不再经 docsJson 同步");
        assertEquals("READY", h.task.getStatus());

        // 红线坐实（结构性）：本服务不持有任何 supplier_job*/QuickProbe 组件 —— 没有任何路径能写
        // supplier_job、supplier_job_node、supplier_job_chat 行；适配器也只被 SupplierWikiService.build
        // 读 5 个字段（build 无 @Transactional、内部只 revisions.save(row)，从不 save(job)）
        for (Class<?> dep : AdHocService.class.getDeclaredConstructors()[0].getParameterTypes()) {
            assertFalse(dep.getSimpleName().startsWith("SupplierJob"),
                    "AdHocService 不得依赖 supplier_job* 组件（解耦红线）: " + dep.getSimpleName());
            assertFalse(dep.getSimpleName().startsWith("QuickProbe"),
                    "AdHocService 不得依赖 QuickProbe（解耦红线）: " + dep.getSimpleName());
        }
        assertFalse(QuickProbeService.class.isAssignableFrom(AdHocService.class), "不得继承 QuickProbeService");
        assertFalse(SupplierJobService.class.isAssignableFrom(AdHocService.class), "不得继承 SupplierJobService");
    }

    // ===== b) 失败停在当段 =====

    @Test
    void ingestFailureStopsAtIngestStage() {
        var h = new Harness();
        h.givenTask("INGEST", "");
        h.reparseResult = "FAILED";

        h.service.runPipeline(1L, "INGEST");

        assertEquals("INGEST", h.task.getStatus(), "停在当段");
        assertTrue(h.task.getError().contains("文档解析失败"), h.task.getError());
        assertTrue(h.task.getError().contains("抓取超时"), h.task.getError());
        verify(h.wiki, never()).build(any());   // 不再推进下一段
    }

    @Test
    void wikiFailureRebuildsOnceThenStopsAtWikiStage() {
        var h = new Harness();
        h.givenTask("WIKI", "");
        h.buildResult = "FAILED";

        h.service.runPipeline(1L, "WIKI");

        assertEquals("WIKI", h.task.getStatus());
        assertTrue(h.task.getError().contains("Wiki 编译失败"), h.task.getError());
        assertTrue(h.task.getError().contains("模型输出不是 JSON"), h.task.getError());
        assertEquals(2, h.builds.get(), "FAILED 应自动重建一次后再判失败: builds=" + h.builds.get());
    }

    @Test
    void wikiFailureAutoRebuildSucceedsAndPipelineCompletes() {
        var h = new Harness();
        h.givenTask("INGEST", "");
        when(h.wiki.build(any(SupplierJob.class))).thenAnswer(inv -> {
            h.builds.incrementAndGet();
            h.wikiRow = revision(77L);
            h.wikiRow.setStatus(h.builds.get() == 1 ? "FAILED" : "READY");   // 首次失败，重建成功
            h.wikiRow.setDirectory("D:/kb/.wiki/xyz/revisions/77");
            return h.wikiRow;
        });
        // 入库段先跑通
        SupplierKbEntry e = new SupplierKbEntry();
        e.setId(1L); e.setKbCode("adhoc-1"); e.setType("md"); e.setSource("D:\\docs\\a.md"); e.setStatus("DONE"); e.setTitle("a");
        h.kbEntries.add(e);

        h.service.runPipeline(1L, "INGEST");

        assertEquals("READY", h.task.getStatus(), h.task.getError());
        assertTrue(h.builds.get() >= 2, "FAILED 后应自动重建: builds=" + h.builds.get());
    }

    @Test
    void waitTimeoutMarksStageFailed() {
        var h = new Harness();
        h.givenTask("INGEST", "");
        h.reparseResult = "PARSING";   // 解析永不完成（超时路径）
        h.service.stepTimeoutMs = 5;   // 快进：2ms 轮询 × 数次即超时

        h.service.runPipeline(1L, "INGEST");

        assertEquals("INGEST", h.task.getStatus());
        assertTrue(h.task.getError().contains("超时"), h.task.getError());
        assertTrue(h.sleeps.get() >= 3, "超时前应至少轮询数次: " + h.sleeps.get());
        verify(h.wiki, never()).build(any());
    }

    // ===== c) 重试幂等 =====

    @Test
    void retryFromIngestStageIsIdempotent() {
        var h = new Harness();
        h.givenTask("INGEST", "");
        h.reparseResult = "FAILED";
        h.service.runPipeline(1L, "INGEST");
        assertEquals("INGEST", h.task.getStatus());

        // 修复外部条件后重试本段：条目已存在 → 不再 add；解析成功 → 一路到 READY
        h.reparseResult = "DONE";
        h.service.retryStage(1L);

        verify(h.kb, times(1)).add(anyString(), anyString(), anyString(), nullable(String.class));   // 无重复条目
        assertEquals("READY", h.task.getStatus());
        assertEquals("", h.task.getError());
    }

    @Test
    void retryFromWikiStageDoesNotRerunIngest() {
        var h = new Harness();
        h.givenTask("WIKI", "");
        h.buildResult = "FAILED";
        h.service.runPipeline(1L, "WIKI");
        assertEquals("WIKI", h.task.getStatus());

        h.buildResult = "READY";
        h.service.retryStage(1L);

        verify(h.kb, never()).add(anyString(), anyString(), anyString(), nullable(String.class));   // 入库段未重跑
        assertEquals("READY", h.task.getStatus());
    }

    @Test
    void retryGuardsRejectInvalidStates() {
        var h = new Harness();
        h.givenTask("READY", "");
        assertThrows(IllegalArgumentException.class, () -> h.service.retryStage(1L));    // 未失败不重试
        assertThrows(IllegalArgumentException.class, () -> h.service.retryStage(999L));  // 任务不存在

        // 已有流水线在跑 → 拒绝并发重试（starter 占位不执行 → orchestrating 保持占用）
        h.givenTask("INGEST", "boom");
        h.service.starter = (name, r) -> { };
        h.service.retryStage(1L);
        var e = assertThrows(IllegalArgumentException.class, () -> h.service.retryStage(1L));
        assertTrue(e.getMessage().contains("正在推进中"), e.getMessage());
    }

    @Test
    void pipelineStartFailureMarksStageFailedAndReleasesGuard() {
        var h = new Harness();
        h.givenTask("INGEST", "文档解析失败：x");
        h.service.starter = (name, r) -> { throw new IllegalStateException("线程资源耗尽"); };

        assertThrows(IllegalStateException.class, () -> h.service.retryStage(1L));

        assertTrue(h.task.getError().contains("流水线启动失败"), h.task.getError());
        assertFalse(h.service.isRunning(1L), "占位必须释放，否则后续重试永远被拒");
    }

    // ===== d) 删除只清本模块记录（AC5） =====

    @Test
    void deleteRemovesModuleRecordsAndOwnKbNamespaceOnly() {
        var h = new Harness();
        h.givenTask("READY", "");
        SupplierKbEntry e1 = new SupplierKbEntry(); e1.setId(11L); e1.setKbCode("adhoc-1"); e1.setType("url"); e1.setSource("u"); e1.setStatus("DONE"); e1.setTitle("u");
        SupplierKbEntry e2 = new SupplierKbEntry(); e2.setId(12L); e2.setKbCode("adhoc-1"); e2.setType("md"); e2.setSource("m"); e2.setStatus("DONE"); e2.setTitle("m");
        h.kbEntries.add(e1); h.kbEntries.add(e2);

        h.service.delete(1L);

        verify(h.chatRepo).deleteByTaskId(1L);          // 对话流水清掉
        verify(h.kb).delete(11L);                        // 专属 kbCode 的条目逐个经 KB 管线删除（含产物文件/墓碑）
        verify(h.kb).delete(12L);
        verify(h.taskRepo).delete(h.task);               // 任务行清掉
        verify(h.wikiRevisions, never()).delete(any());  // Wiki 版本行是不可变历史，沿用现有语义保留
        verify(h.wikiRevisions, never()).deleteAll();
        verify(h.wiki, never()).build(any());
    }

    // ===== e) 重启恢复 =====

    @Test
    void recoverInterruptedMarksOnlyUnfinishedCleanTasks() {
        var h = new Harness();
        AdHocTask running = new AdHocTask(); running.setId(1L); running.setStatus("WIKI"); running.setError("");
        AdHocTask ready = new AdHocTask(); ready.setId(2L); ready.setStatus("READY"); ready.setError("");
        AdHocTask failed = new AdHocTask(); failed.setId(3L); failed.setStatus("INGEST"); failed.setError("文档解析失败：x");
        when(h.taskRepo.findAll()).thenReturn(List.of(running, ready, failed));

        h.service.recoverInterrupted();

        assertTrue(running.getError().contains("服务重启中断"), running.getError());
        assertEquals("", ready.getError(), "READY 待命任务不动");
        assertEquals("文档解析失败：x", failed.getError(), "已有失败原因保留原文");
    }

    @Test
    void recoverInterruptedFailsOrphanedPendingChatRows() {
        var h = new Harness();
        AdHocChat orphan = new AdHocChat();
        orphan.setId(9L); orphan.setTaskId(1L); orphan.setRole("agent");
        orphan.setStatus("PENDING"); orphan.setText("⏳ 思考中（已 3 秒）");
        when(h.chatRepo.findByStatus("PENDING")).thenReturn(List.of(orphan));

        h.service.recoverInterrupted();

        assertEquals("FAILED", orphan.getStatus(), "重启后 PENDING 轮次再也不会被回写，必须落终态");
        assertTrue(orphan.getText().contains("服务重启中断了本轮对话"), orphan.getText());
        verify(h.chatRepo, times(1)).save(any(AdHocChat.class));
    }

    // ===== f) A3 注入纯函数（AC3 + 措辞纪律注入面） =====

    @Test
    void detectTypeClassifiesSupportedSourcesAndRejectsOthers() {
        assertEquals("url", AdHocService.detectType(" https://docs.acme.com/api "));
        assertEquals("url", AdHocService.detectType("http://a.cn/x"));
        assertEquals("pdf", AdHocService.detectType("D:\\docs\\a.PDF"));
        assertEquals("html", AdHocService.detectType("D:\\docs\\a.htm"));
        assertEquals("html", AdHocService.detectType("D:\\docs\\a.html"));
        assertEquals("md", AdHocService.detectType("D:\\docs\\a.markdown"));
        assertEquals("md", AdHocService.detectType("D:\\docs\\a.txt"));
        // 11-23-kb-office-documents：Office 类型三入口一致（判定收口到 DocParser.detectSourceType）
        assertEquals("docx", AdHocService.detectType("D:\\docs\\a.DOCX"));
        assertEquals("pptx", AdHocService.detectType("D:\\docs\\a.pptx"));
        assertEquals("xlsx", AdHocService.detectType("D:\\docs\\a.xlsx"));
        assertEquals("doc", AdHocService.detectType("D:\\docs\\a.doc"), ".doc 是标记类型：入库时按 magic bytes 定真实格式");
        assertThrows(IllegalArgumentException.class, () -> AdHocService.detectType("D:\\docs\\a.ppt"));
        assertThrows(IllegalArgumentException.class, () -> AdHocService.detectType("ftp://a.cn"));
    }

    /** 与 QuickProbeService.detectType 逐项对齐：文件名含空格/括号是正常路径字符，整串直取不切分（11-13 钉死） */
    @Test
    void detectTypeKeepsSpacesAndParenthesesInLocalPaths() {
        assertEquals("html", AdHocService.detectType("D:\\供应商文档\\接口说明 (最终版) 2026-11.html"));
        assertEquals("html", AdHocService.detectType("D:\\docs\\Supplier API (v2) - Copy.htm"));
        assertEquals("pdf", AdHocService.detectType("D:\\docs\\接口 手册 (1).PDF"));
        assertEquals("md", AdHocService.detectType("D:\\docs\\my notes (final).md"));
        // 多行清单同样按整行处理：一行一个含空格括号的路径，不被拆成多条来源
        assertEquals(List.of("D:\\docs\\Supplier API (v2).html"),
                AdHocService.sourceLines("  D:\\docs\\Supplier API (v2).html  \n"));
        assertEquals("html", AdHocService.detectType(
                AdHocService.sourceLines("D:\\docs\\Supplier API (v2).html\nhttps://a.cn/x").get(0)));
    }

    @Test
    void stepStatusDerivesStageStates() {
        assertEquals("DONE", AdHocService.stepStatus("READY", "", "INGEST"));
        assertEquals("DONE", AdHocService.stepStatus("READY", "", "WIKI"));
        assertEquals("RUNNING", AdHocService.stepStatus("INGEST", "", "INGEST"));
        assertEquals("PENDING", AdHocService.stepStatus("INGEST", "", "WIKI"));
        assertEquals("FAILED", AdHocService.stepStatus("INGEST", "boom", "INGEST"));
        assertEquals("DONE", AdHocService.stepStatus("WIKI", "boom", "INGEST"));
        assertEquals("FAILED", AdHocService.stepStatus("WIKI", "boom", "WIKI"));
        assertEquals("PENDING", AdHocService.stepStatus("WEIRD", "", "INGEST"));
    }

    @Test
    void parsePageBriefsReadsManifestAndDegradesGracefully() {
        var briefs = AdHocService.parsePageBriefs(
                "{\"pages\":[{\"id\":\"a\",\"title\":\"总览\",\"summary\":\"s1\"},{\"id\":\"b\",\"title\":\"约束\",\"summary\":\"s2\"}]}");
        assertEquals(2, briefs.size());
        assertEquals("a", briefs.get(0).id());
        assertEquals("总览", briefs.get(0).title());
        assertEquals("s2", briefs.get(1).summary());

        assertTrue(AdHocService.parsePageBriefs("不是 JSON").isEmpty(), "manifest 异常降级为空清单，不阻断对话");
        assertTrue(AdHocService.parsePageBriefs("{}").isEmpty());
        assertTrue(AdHocService.parsePageBriefs(null).isEmpty());
    }

    private static AdHocService.WikiSnapshot readyWiki(List<AdHocService.PageBrief> pages) {
        return new AdHocService.WikiSnapshot("READY", 77L, 3, "D:/kb/.wiki/xyz/revisions/77", "", "", pages);
    }

    @Test
    void wikiHeaderBlockInjectsEntryPointsNotFullText() {
        String header = AdHocService.wikiHeaderBlock("改历史接口字段", "READY", readyWiki(List.of(
                new AdHocService.PageBrief("api-overview", "接口总览", "历史接口清单与字段"),
                new AdHocService.PageBrief("order-flow", "下单流程", "下单/取消的状态机"))));

        assertTrue(header.contains("【临时需求任务】改历史接口字段"), "任务名头部");
        assertTrue(header.contains("版本 #77") && header.contains("3 个来源"), "Wiki 版本+来源数");
        assertTrue(header.contains("D:/kb/.wiki/xyz/revisions/77/wiki/index.md"), "索引入口路径");
        assertTrue(header.contains("/wiki/<页面ID>.md") && header.contains("/raw/<来源ID>.md"), "页面/原文路径模式");
        assertTrue(header.contains("- api-overview｜接口总览：历史接口清单与字段"), "页面清单行（id/标题/摘要）");
        assertTrue(header.contains("- order-flow｜下单流程"), "页面清单行 2");
        assertTrue(header.contains("引用页面 ID") && header.contains("不得猜测"), "读页规则");
        assertFalse(header.contains("玩家"), "注入面自身无禁词");
        // 只注入入口不注入全文：清单行只有摘要，不存在正文载体
        assertTrue(header.length() < 2000, "头部必须轻量: " + header.length());
    }

    @Test
    void wikiHeaderBlockDegradesToOneLineWhenWikiNotReady() {
        // 设计变更（2026-11-17）：Wiki 未就绪不注入入口段，只给一行"尚未就绪（当前阶段 X）+ 直接给路径"
        String ingest = AdHocService.wikiHeaderBlock("t", "INGEST", AdHocService.WikiSnapshot.EMPTY);
        assertTrue(ingest.contains("该任务 Wiki 尚未就绪"), ingest);
        assertTrue(ingest.contains("当前阶段：入库解析中"), ingest);
        assertTrue(ingest.contains("如需引用文档请直接给路径"), ingest);
        assertFalse(ingest.contains("index.md"), "未就绪不得注入 Wiki 入口路径");
        assertFalse(ingest.contains("【任务 LLM Wiki】"), "未就绪不注入入口段");
        assertTrue(ingest.contains("【临时需求任务】t"), "任务名头部仍然保留");

        String building = AdHocService.wikiHeaderBlock("t", "WIKI",
                new AdHocService.WikiSnapshot("BUILDING", null, 0, "", "抽取中 1/3", "", List.of()));
        assertTrue(building.contains("编译 Wiki 中 · Wiki 正在编译（抽取中 1/3）"), building);

        String failed = AdHocService.wikiHeaderBlock("t", "WIKI",
                new AdHocService.WikiSnapshot("FAILED", 76L, 1, "", "", "模型输出不是 JSON", List.of()));
        assertTrue(failed.contains("Wiki 上次编译失败：模型输出不是 JSON"), failed);

        // 任务已 READY 但版本行不是 READY（如版本行被清）：仍按未就绪降级，不塞入口段
        assertTrue(AdHocService.wikiHeaderBlock("t", "READY", AdHocService.WikiSnapshot.EMPTY)
                .contains("对话待命 · Wiki 尚未编译"));
        assertTrue(AdHocService.wikiHeaderBlock("t", null, AdHocService.WikiSnapshot.EMPTY)
                .contains("当前阶段：未知"));
    }

    @Test
    void stageHintNamesTaskStageAndWikiState() {
        assertEquals("入库解析中 · Wiki 尚未编译",
                AdHocService.stageHint("INGEST", AdHocService.WikiSnapshot.EMPTY));
        assertEquals("编译 Wiki 中 · Wiki 正在编译（抽取中 2/5）", AdHocService.stageHint("WIKI",
                new AdHocService.WikiSnapshot("BUILDING", null, 0, "", "抽取中 2/5", "", List.of())));
        assertEquals("对话待命 · Wiki 上次编译失败：未知原因", AdHocService.stageHint("READY",
                new AdHocService.WikiSnapshot("FAILED", 1L, 0, "", "", "", List.of())));
        assertEquals("WEIRD · Wiki 尚未编译", AdHocService.stageHint("WEIRD", null));
    }

    @Test
    void wikiHeaderBlockTruncatesHugePageLists() {
        List<AdHocService.PageBrief> many = new ArrayList<>();
        for (int i = 0; i < 100; i++) many.add(new AdHocService.PageBrief("p" + i, "页" + i, "摘要" + i));

        String header = AdHocService.wikiHeaderBlock("t", "READY", readyWiki(many));

        assertTrue(header.contains("其余 40 页见 index.md"), "超出清单上限指向索引: 尾部=" + header.substring(Math.max(0, header.length() - 200)));
        assertFalse(header.contains("- p60｜"), "第 61 页起不再逐行列出");
        assertTrue(header.contains("- p59｜"), "上限内页面保留");
    }

    @Test
    void wikiHeaderBlockHardCapsTotalLength() {
        List<AdHocService.PageBrief> fat = new ArrayList<>();
        for (int i = 0; i < 100; i++) fat.add(new AdHocService.PageBrief("p" + i, "页" + i, "很长的摘要".repeat(30)));

        String header = AdHocService.wikiHeaderBlock("t", "READY", readyWiki(fat));

        assertTrue(header.length() <= AdHocService.MAX_HEADER_CHARS + 60, "总长受硬上限约束: " + header.length());
        assertTrue(header.contains("清单超长已截断"), "截断时给出指向索引的说明");
    }

    @Test
    void buildChatPromptCarriesWikiEntryUserTextAndWordingRule() {
        String header = AdHocService.wikiHeaderBlock("改历史接口字段", "READY", readyWiki(List.of(
                new AdHocService.PageBrief("api-overview", "接口总览", "历史接口清单"))));

        String prompt = AdHocService.buildChatPrompt(header, "  某供应商把历史接口字段改了，帮我改一下  ");

        assertTrue(prompt.startsWith(header), "Wiki 入口段注入消息头部（AC3）");
        assertTrue(prompt.contains("【用户吩咐】"), "用户原文段标识");
        assertTrue(prompt.contains("某供应商把历史接口字段改了，帮我改一下"), "用户原文逐字进 prompt（AC3）");
        assertTrue(prompt.contains("措辞纪律") && prompt.contains("一律用\"用户\""),
                "对话轮不走 ACTION_SPEC，必须自带措辞纪律（对齐 buildAskMessage 注入面，11-10 清单不得遗漏）");
        assertTrue(prompt.endsWith(SupplierJobService.WORDING_RULE), "纪律句尾部拼接（与既有注入面同款）");
        assertFalse(SupplierJobService.stripWordingRule(prompt).contains("玩家"),
                "剥离纪律句后无裸用禁词");
    }

    // ===== g) 对话待命：主会话 + 注入 + 门禁（AC3/AC4） =====

    @Test
    void askSendsWikiHeaderPlusUserTextThroughInstanceMainSession() {
        var h = new Harness();
        h.givenTask("READY", "");
        h.builds.set(0);
        h.wikiRow = revision(77L);
        h.wikiRow.setStatus("READY");
        h.wikiRow.setSourceCount(1);
        h.wikiRow.setDirectory("D:/kb/.wiki/xyz/revisions/77");
        h.wikiRow.setManifestJson("{\"pages\":[{\"id\":\"api-overview\",\"title\":\"接口总览\",\"summary\":\"历史接口清单\"}]}");

        h.service.ask(1L, "某供应商把历史接口字段改了，帮我改一下");

        // 主会话 chat()：实例/工作目录/模型与工作台同源读法；openId 带 ad-hoc-task- 前缀
        ArgumentCaptor<String> promptCaptor = ArgumentCaptor.forClass(String.class);
        verify(h.claude).chat(eq(5L), eq("ad-hoc-task-1"), promptCaptor.capture(),
                eq("/work"), eq("sonnet"), any(TurnListener.class));
        String prompt = promptCaptor.getValue();
        assertTrue(prompt.contains("【任务 LLM Wiki】版本 #77"), "Wiki 入口段（AC3）");
        assertTrue(prompt.contains("- api-overview｜接口总览"), "页面清单注入");
        assertTrue(prompt.contains("某供应商把历史接口字段改了，帮我改一下"), "用户原文（AC3）");
        assertTrue(prompt.contains("措辞纪律"), "措辞纪律注入面");

        // 排队语义不动：不 steer、不打断、不开 lane（AC4/红线）
        verify(h.claude, never()).steer(anyLong(), anyString());
        verify(h.claude, never()).chatInLane(anyString(), anyLong(), anyString(), anyString(), nullable(String.class), any(TurnListener.class));
        verify(h.claude, never()).destroySession(anyLong());

        // 对话留档：user 行原文 + agent 行终态回写
        assertEquals(2, h.chats.size());
        assertEquals("user", h.chats.get(0).getRole());
        assertEquals("某供应商把历史接口字段改了，帮我改一下", h.chats.get(0).getText());
        assertEquals("agent", h.chats.get(1).getRole());
        assertEquals("DONE", h.chats.get(1).getStatus());
        assertEquals(h.chatReply, h.chats.get(1).getText());
    }

    @Test
    void consecutiveAsksStayOnSameInstanceMainSession() {
        var h = new Harness();
        h.givenTask("READY", "");

        h.service.ask(1L, "第一条");
        h.service.ask(1L, "第二条");

        // 两轮都打到同一 instanceId 的主会话入口 chat()——ClaudeCodeService 对同实例复用同一
        // TerminalSession（同 sessionID/同队列，架构既成事实，SupplierSharedSessionTest 已断言），
        // 飞书私聊同实例也走这里 → 浏览器与飞书天然同 session（AC4）
        ArgumentCaptor<Long> instances = ArgumentCaptor.forClass(Long.class);
        verify(h.claude, times(2)).chat(instances.capture(), eq("ad-hoc-task-1"), anyString(),
                anyString(), nullable(String.class), any(TurnListener.class));
        assertEquals(List.of(5L, 5L), instances.getAllValues(), "两轮同实例 → 同一主会话");
        verify(h.claude, never()).chatInLane(anyString(), anyLong(), anyString(), anyString(), nullable(String.class), any(TurnListener.class));
        verify(h.claude, never()).newSession(anyLong(), anyString(), nullable(String.class));
    }

    @Test
    void askAllowedInEveryStageButRejectsBlankQuestion() {
        var h = new Harness();
        // 设计变更（2026-11-17）：Wiki 编译期也能说话——未就绪只降级注入一行，不再 400 挡回
        h.givenTask("WIKI", "");
        h.service.ask(1L, "先问一句：这个供应商的历史接口在哪个文档里？");

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(h.claude).chat(eq(5L), eq("ad-hoc-task-1"), prompt.capture(),
                eq("/work"), eq("sonnet"), any(TurnListener.class));
        assertTrue(prompt.getValue().contains("该任务 Wiki 尚未就绪（当前阶段：编译 Wiki 中"), prompt.getValue());
        assertTrue(prompt.getValue().contains("先问一句：这个供应商的历史接口在哪个文档里？"), "用户原文照发");
        assertFalse(prompt.getValue().contains("【任务 LLM Wiki】版本"), "未就绪不注入入口段");
        assertTrue(prompt.getValue().endsWith(SupplierJobService.WORDING_RULE), "纪律句照旧尾部拼接");
        assertEquals("DONE", h.chats.get(1).getStatus());

        // 入库段同理可对话
        clearInvocations(h.claude);
        h.givenTask("INGEST", "");
        h.service.ask(1L, "在吗");
        verify(h.claude).chat(eq(5L), anyString(), contains("入库解析中"),
                anyString(), nullable(String.class), any(TurnListener.class));

        // 空内容仍然拒绝：不落对话行、不发消息
        clearInvocations(h.claude);
        h.givenTask("READY", "");
        int before = h.chats.size();
        assertThrows(IllegalArgumentException.class, () -> h.service.ask(1L, "  "));
        verify(h.claude, never()).chat(anyLong(), anyString(), anyString(), anyString(),
                nullable(String.class), any(TurnListener.class));
        assertEquals(before, h.chats.size(), "空问题不落库");
    }

    @Test
    void askMarksAgentRowFailedWhenTurnCannotBeDispatched() {
        var h = new Harness();
        h.givenTask("READY", "");
        h.service.starter = (name, r) -> { throw new IllegalStateException("无法创建对话线程"); };

        assertThrows(IllegalStateException.class, () -> h.service.ask(1L, "在吗"));

        assertEquals("FAILED", h.chats.get(1).getStatus(), "投递失败也要落终态，否则前端永远转圈");
        assertTrue(h.chats.get(1).getText().contains("对话投递失败"), h.chats.get(1).getText());
        verify(h.claude, never()).chat(anyLong(), anyString(), anyString(), anyString(),
                nullable(String.class), any(TurnListener.class));
    }

    @Test
    void askMarksAgentRowFailedOnErrorResponse() {
        var h = new Harness();
        h.givenTask("READY", "");
        h.chatReply = "❌ Claude 对话失败: 进程崩溃";

        h.service.ask(1L, "在吗");

        assertEquals("FAILED", h.chats.get(1).getStatus());
        assertTrue(h.chats.get(1).getText().startsWith("❌"));
    }

    // ===== h) 查询 JSON =====

    @Test
    void detailCarriesStepsWikiCardAndKbEntries() {
        var h = new Harness();
        h.givenTask("WIKI", "编译出错");
        SupplierKbEntry e = new SupplierKbEntry();
        e.setId(11L); e.setKbCode("adhoc-1"); e.setType("url"); e.setSource("u"); e.setStatus("DONE"); e.setTitle("接口文档"); e.setError("");
        h.kbEntries.add(e);
        h.wikiRow = revision(77L); h.wikiRow.setStatus("FAILED"); h.wikiRow.setError("模型输出不是 JSON"); h.wikiRow.setSourceCount(1);

        var o = h.service.detail(1L);

        assertEquals("adhoc-1", o.get("kbCode").getAsString());
        assertEquals("编译出错", o.get("error").getAsString());
        var steps = o.getAsJsonArray("steps");
        assertEquals("DONE", steps.get(0).getAsJsonObject().get("status").getAsString());
        assertEquals("FAILED", steps.get(1).getAsJsonObject().get("status").getAsString());
        assertEquals("FAILED", o.getAsJsonObject("wiki").get("status").getAsString());
        assertEquals("接口文档", o.getAsJsonArray("kbEntries").get(0).getAsJsonObject().get("title").getAsString());
    }

    // ===== i) 卡点待确认（11-17-adhoc-blocker-notify）：判定 / 状态迁移 / 通知落库 / 纪律文案 =====

    /** 决策 1：复用全项目唯一卡点协议 SupplierJobService.STANDBLOCK，本类不得另拷一份正则 */
    @Test
    void standblockDetectionReusesProjectWideProtocol() {
        assertTrue(AdHocService.isStandblock("【卡点】多酒店查价上限是多少？"), "行首标记命中");
        assertTrue(AdHocService.isStandblock("先说结论。\n  【卡点】需要用户给上限\n后续说明"), "行首允许空白");
        assertFalse(AdHocService.isStandblock("转述纪律：以【卡点】开头写清问题"), "行中提及不算命中");
        assertFalse(AdHocService.isStandblock("已完成，无需确认"), "普通回复不误报");
        assertFalse(AdHocService.isStandblock(null), "null 安全");

        assertEquals("【卡点】多酒店上限是多少？\nA. 10\nB. 20",
                AdHocService.extractStandblockQuestion("分析如下\n【卡点】多酒店上限是多少？\nA. 10\nB. 20"),
                "问题原文 = 标记行起到文本末尾（保留原文）");
        assertNull(AdHocService.extractStandblockQuestion("没有卡点"), "未命中返回 null");

        String cut = AdHocService.extractStandblockQuestion("【卡点】" + "问".repeat(AdHocService.MAX_QUESTION_CHARS + 200));
        assertTrue(cut.length() <= AdHocService.MAX_QUESTION_CHARS + 1, "超长截断到上限: " + cut.length());
        assertTrue(cut.endsWith("…"), "截断有省略号");

        // 语义与 STANDBLOCK 完全一致（同一协议），且本类没有自带 Pattern 字段
        for (String s : List.of("【卡点】a", "x\n【卡点】b", " 【卡点】c", "无标记", "中间【卡点】不算")) {
            assertEquals(SupplierJobService.STANDBLOCK.matcher(s).find(), AdHocService.isStandblock(s), "判定与协议正则一致: " + s);
        }
        assertTrue(java.util.Arrays.stream(AdHocService.class.getDeclaredFields())
                        .noneMatch(f -> java.util.regex.Pattern.class.isAssignableFrom(f.getType())),
                "卡点正则必须引用 SupplierJobService.STANDBLOCK，不得在 AdHocService 里另拷一份");
    }

    /** 决策 2 + AC6：提问纪律文案（先问再改的清单与顺序）+ 剥离式双向禁词断言 */
    @Test
    void askDisciplinePinsAskBeforeEditAndCarriesNoBannedWording() {
        String rule = AdHocService.ASK_DISCIPLINE;
        assertTrue(rule.contains("【卡点】"), "复用全项目卡点标记，不发明新词");
        assertTrue(rule.contains("配置文件") && rule.contains("配置项"), "清单①改配置文件/新增配置项");
        assertTrue(rule.contains("多酒店查价的酒店数量上限"), "清单②业务参数取值无法确定（PRD 指定的例子）");
        assertTrue(rule.contains("线上行为") && rule.contains("线上数据"), "清单③影响线上行为或数据");
        assertTrue(rule.contains("两种以上合理解释"), "清单④同一需求多种合理解释");
        assertTrue(rule.contains("Wiki 覆盖范围"), "清单⑤要动的文件不在 Wiki 覆盖范围");
        assertTrue(rule.contains("输出【卡点】之前不得调用任何写文件"), "顺序写死：先问再改");
        assertTrue(rule.contains("不改任何代码、不写任何文件"), "命中即本轮停手");
        assertTrue(rule.contains("不必问的：纯实现细节"), "纯实现细节不问");
        assertTrue(rule.contains("用户") && rule.contains("不要另起会话"), "答复走同一会话续跑");

        // 剥离式双向断言：纪律文案自身不含措辞纪律句（剥离后等于自身），且不得引入任何被禁说辞
        assertEquals(rule, SupplierJobService.stripWordingRule(rule), "提问纪律独立于 WORDING_RULE");
        for (String banned : List.of("玩家", "开一局", "通关", "存档", "🎮")) {
            assertFalse(rule.contains(banned), "提问纪律不得引入被禁说辞: " + banned);
        }

        String prompt = AdHocService.buildChatPrompt("头部", "给多酒店查价加上数量上限");
        assertTrue(prompt.contains(rule), "纪律注入每一轮对话");
        assertTrue(prompt.endsWith(SupplierJobService.WORDING_RULE), "措辞纪律仍收尾（既有注入面契约不破）");
        assertTrue(prompt.indexOf(rule) < prompt.indexOf("措辞纪律"), "提问纪律排在措辞纪律之前");
        assertFalse(SupplierJobService.stripWordingRule(prompt).contains("玩家"), "剥离纪律句后无裸用禁词");
    }

    /** AC1 + AC2 + 决策 3/4：命中卡点 → 对话行 BLOCKED、任务行记问题原文、飞书推送并记录结果 */
    @Test
    void chatTurnHittingStandblockBlocksRowRecordsQuestionAndPushesFeishu() {
        var h = new Harness();
        h.givenTask("READY", "");
        h.chatReply = "我先看了下代码。\n【卡点】多酒店查价的数量上限定多少？\nA. 10 家\nB. 20 家";

        h.service.ask(1L, "给多酒店查价加上数量上限");

        AdHocChat agent = h.chats.get(1);
        assertEquals(AdHocService.CHAT_BLOCKED, agent.getStatus(), "命中卡点 → agent 行 BLOCKED（不是 DONE）");
        assertTrue(agent.getText().contains("【卡点】多酒店查价的数量上限定多少？"), "问题原文保留在对话行");

        String expectedQuestion = "【卡点】多酒店查价的数量上限定多少？\nA. 10 家\nB. 20 家";
        assertEquals(expectedQuestion, h.task.getPendingQuestion(), "任务行记问题原文（待确认卡数据源）");
        assertNotNull(h.task.getNotifiedAt(), "推送成功记时间");
        assertEquals("", h.task.getNotifyError(), "成功时无失败原因");

        assertEquals(1, h.notices.size(), "命中即推一条飞书私聊");
        assertEquals("【临时需求·待确认】改历史接口字段\n" + expectedQuestion + "\n\n直接在本对话回复即可，我会继续。",
                h.notices.get(0), "通知文案逐字对齐 PRD 决策 4");
        verify(h.feishu).notifyUserChecked(eq(5L), eq("ou_testuser0001"), anyString());

        // 前端数据源：详情卡 + 列表标记
        var detail = h.service.detail(1L);
        assertEquals(expectedQuestion, detail.get("pendingQuestion").getAsString());
        assertEquals("", detail.get("notifyError").getAsString());
        assertTrue(detail.has("notifiedAt"), "详情带推送时间");
        assertTrue(h.service.listJson().get(0).getAsJsonObject().get("blocked").getAsBoolean(), "列表行标 blocked");
    }

    /** 普通回复不误报：不置 BLOCKED、不记待确认、不推飞书 */
    @Test
    void ordinaryReplyDoesNotTriggerStandblockPath() {
        var h = new Harness();
        h.givenTask("READY", "");

        h.service.ask(1L, "在吗");

        assertEquals("DONE", h.chats.get(1).getStatus());
        assertEquals("", h.task.getPendingQuestion());
        assertNull(h.task.getNotifiedAt());
        assertEquals(List.of(), h.notices, "没卡点就不推送");
        assertFalse(h.service.listJson().get(0).getAsJsonObject().get("blocked").getAsBoolean());
    }

    /** AC2：推送失败必须可见 —— 原因落库并进详情 JSON，且待确认卡（权威事实源）照常显示 */
    @Test
    void standblockNotifyFailureIsPersistedNotSwallowed() {
        var h = new Harness();
        h.givenTask("READY", "");
        h.chatReply = "【卡点】历史接口的兼容期定多久？";
        h.notifyResult = new FeishuBotService.NotifyResult(false, "飞书实例未连接（长连接未建立或已断开）");

        h.service.ask(1L, "把兼容期加上");

        assertEquals(AdHocService.CHAT_BLOCKED, h.chats.get(1).getStatus());
        assertEquals("【卡点】历史接口的兼容期定多久？", h.task.getPendingQuestion(), "推送失败也要留下待确认问题");
        assertNull(h.task.getNotifiedAt(), "未成功推送不记时间");
        assertEquals("飞书实例未连接（长连接未建立或已断开）", h.task.getNotifyError(), "失败原因落库");
        assertEquals("飞书实例未连接（长连接未建立或已断开）",
                h.service.detail(1L).get("notifyError").getAsString(), "详情 JSON 暴露原因，前端明示不静默");
    }

    /** AC3：用户回一句即视为答复 → 清空待确认，仍在同一实例主会话续跑（不新建 lane / 不 steer / 不打断） */
    @Test
    void answeringClearsPendingQuestionAndContinuesOnSameMainSession() {
        var h = new Harness();
        h.givenTask("READY", "");
        h.task.setPendingQuestion("【卡点】多酒店查价的数量上限定多少？");
        h.task.setNotifyError("飞书实例未连接");
        h.task.setNotifiedAt(java.time.LocalDateTime.now().minusMinutes(5));

        h.service.ask(1L, "上限按 20 家");

        assertEquals("", h.task.getPendingQuestion(), "答复即清空待确认（卡片收起）");
        assertEquals("", h.task.getNotifyError(), "上一轮通知失败原因一并清掉");
        assertEquals(List.of(), h.notices, "答复轮本身不是卡点，不推送");
        assertEquals("DONE", h.chats.get(1).getStatus(), "答复轮正常收尾");

        // 续跑靠"下一句自然进同一主会话"：同实例 + 同 openId（= 同 TerminalSession/同 sessionID，同队列）
        verify(h.claude).chat(eq(5L), eq("ad-hoc-task-1"), anyString(), eq("/work"), eq("sonnet"), any(TurnListener.class));
        verify(h.claude, never()).chatInLane(anyString(), anyLong(), anyString(), anyString(), nullable(String.class), any(TurnListener.class));
        verify(h.claude, never()).steer(anyLong(), anyString());
        verify(h.claude, never()).newSession(anyLong(), anyString(), nullable(String.class));
        verify(h.claude, never()).destroySession(anyLong());
    }

    /** 已知局限的缓解：提示词拦不住"先改再问"，就把本轮写类工具动作明写在回复里 */
    @Test
    void blockedTurnSurfacesWriteToolTraceToUser() {
        assertEquals("", AdHocService.writeActionBanner(List.of("Read", "Grep", "Glob")), "只读工具不告警");
        assertEquals("", AdHocService.writeActionBanner(List.of()), "空清单不告警");
        assertEquals("", AdHocService.writeActionBanner(null), "null 安全");
        String banner = AdHocService.writeActionBanner(List.of("Read", "Edit", "Bash", "Edit"));
        assertTrue(banner.contains("Edit、Bash"), "写类工具按发生顺序去重列出: " + banner);
        assertTrue(banner.contains("提问纪律要求先问再改"), "文案点明纪律");

        var h = new Harness();
        h.givenTask("READY", "");
        h.chatReply = "【卡点】上限按多少？";
        when(h.claude.chat(anyLong(), anyString(), anyString(), anyString(), nullable(String.class), any(TurnListener.class)))
                .thenAnswer(inv -> {
                    TurnListener l = inv.getArgument(5);
                    l.onTurnStart();
                    l.onToolStart(new ToolStep("t1", "Read", "HotelPriceService.java", false));
                    l.onToolStart(new ToolStep("t2", "Edit", "application.yml", false));
                    l.onToolStart(new ToolStep("t3", "Bash", "git diff", false));
                    return h.chatReply;
                });

        h.service.ask(1L, "给多酒店查价加上数量上限");

        assertEquals(AdHocService.CHAT_BLOCKED, h.chats.get(1).getStatus());
        assertTrue(h.chats.get(1).getText().contains("本轮在提问之前已出现可写工具动作：Edit、Bash"),
                "本轮工具动作流水对可见: " + h.chats.get(1).getText());
        assertFalse(h.chats.get(1).getText().contains("Read"), "只读工具不进告警");
    }

    /** 决策 5 的判定面：manifest 必须解码后再扫（转义 \n 让多行正则在原始 JSON 串上永远匹配不到） */
    @Test
    void wikiStandblockQuestionScansDecodedManifestPhaseAndError() {
        SupplierWikiRevision page = revision(9L);
        page.setStatus("READY");
        page.setManifestJson("{\"pages\":[{\"id\":\"p\",\"title\":\"t\",\"summary\":\"s\","
                + "\"content\":\"取消必须带订单号。\\n【卡点】多酒店查价上限未在任何材料中给出，请用户确认。\"}]}");
        assertEquals("【卡点】多酒店查价上限未在任何材料中给出，请用户确认。", AdHocService.wikiStandblockQuestion(page));
        assertFalse(SupplierJobService.STANDBLOCK.matcher(page.getManifestJson()).find(),
                "直接扫原始 JSON 串会漏（\\n 是字面量）——证明必须解码");

        SupplierWikiRevision warn = revision(10L);
        warn.setManifestJson("{\"pages\":[],\"warnings\":[\"【卡点】来源文档打不开，需要用户提供访问方式\"]}");
        assertEquals("【卡点】来源文档打不开，需要用户提供访问方式", AdHocService.wikiStandblockQuestion(warn), "warnings 同样扫");

        SupplierWikiRevision clean = revision(11L);
        clean.setManifestJson("{\"pages\":[{\"id\":\"p\",\"title\":\"t\",\"summary\":\"s\",\"content\":\"干净正文\"}],\"warnings\":[]}");
        assertNull(AdHocService.wikiStandblockQuestion(clean), "干净产物不误报");
        assertNull(AdHocService.wikiStandblockQuestion(null), "null 安全");

        SupplierWikiRevision broken = revision(12L);
        broken.setManifestJson("不是 JSON");
        assertNull(AdHocService.wikiStandblockQuestion(broken), "manifest 异常不抛，按未检出");

        SupplierWikiRevision failedPhase = revision(13L);
        failedPhase.setPhase("【卡点】编译取不到原文");
        assertEquals("【卡点】编译取不到原文", AdHocService.wikiStandblockQuestion(failedPhase), "phase 直扫");
    }

    /** AC4：Wiki 编译产物含【卡点】→ 任务不得变 READY，阶段卡显示问题原文，同样推飞书 */
    @Test
    void wikiRevisionCarryingStandblockIsNotAcceptedAsReady() {
        var h = new Harness();
        h.buildManifest = "{\"pages\":[{\"id\":\"api-overview\",\"title\":\"接口总览\",\"summary\":\"历史接口清单\","
                + "\"content\":\"取消必须带订单号。\\n【卡点】多酒店查价上限未在材料中给出，请用户确认上限值。\"}]}";
        h.givenTask("WIKI", "上次编译中断");

        h.service.retryStage(1L);

        assertNotEquals("READY", h.task.getStatus(), "残缺 Wiki 不得当成功产物");
        assertEquals("WIKI", h.task.getStatus(), "停在 WIKI 段");
        assertEquals("FAILED", AdHocService.stepStatus(h.task.getStatus(), h.task.getError(), "WIKI"), "阶段卡显示失败");
        assertTrue(h.task.getError().contains("【卡点】多酒店查价上限未在材料中给出"), "阶段卡带问题原文: " + h.task.getError());
        assertTrue(h.task.getError().contains("重试本段"), "给出续跑指引");
        assertTrue(h.task.getPendingQuestion().contains("多酒店查价上限"), "待确认卡同样显示");
        assertEquals(77L, h.task.getBlockedRevision(), "记下被拒版本，重试据此重编而不是原地再拒");
        assertEquals(1, h.notices.size(), "Wiki 段卡点同样主动推飞书");
        assertTrue(h.notices.get(0).startsWith("【临时需求·待确认】改历史接口字段"), h.notices.get(0));
    }

    /** AC4 续跑：答复后「重试本段」重新编译（新版本行），干净产物即 READY */
    @Test
    void wikiBlockedRetryRecompilesInsteadOfRejectingSameRevision() {
        var h = new Harness();
        h.buildManifest = "{\"pages\":[{\"id\":\"p\",\"title\":\"t\",\"summary\":\"s\",\"content\":\"正文\\n【卡点】上限未给出，请用户确认。\"}]}";
        h.givenTask("WIKI", "上次编译中断");
        h.service.retryStage(1L);
        assertEquals(77L, h.task.getBlockedRevision());
        int buildsAfterBlock = h.builds.get();

        // 用户在对话框答复（清 pendingQuestion），随后点「重试本段」
        h.chatReply = "收到，上限按 20 家，已写进 Wiki 材料。";
        h.service.ask(1L, "上限按 20 家");
        assertEquals("", h.task.getPendingQuestion());
        h.buildManifest = "{\"pages\":[{\"id\":\"p\",\"title\":\"t\",\"summary\":\"s\",\"content\":\"多酒店查价上限 20 家。\"}]}";

        h.service.retryStage(1L);

        assertEquals("READY", h.task.getStatus(), "重编出干净产物 → 任务 READY 进对话待命");
        assertEquals("READY", h.task.getWikiStatus());
        assertNull(h.task.getBlockedRevision(), "拒绝锚点已消费");
        assertEquals(buildsAfterBlock + 1, h.builds.get(), "重试触发了一次重新编译");
        assertEquals(78L, h.wikiRow.getId(), "编译产生新版本行（不是复用被拒的那版）");
        assertEquals("", h.task.getPendingQuestion());
    }
}
