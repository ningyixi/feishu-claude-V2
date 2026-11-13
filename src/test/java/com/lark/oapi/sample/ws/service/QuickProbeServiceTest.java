package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.model.entity.SupplierJobNode;
import com.lark.oapi.sample.ws.model.entity.SupplierKbEntry;
import com.lark.oapi.sample.ws.model.entity.WorkflowTemplate;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.SupplierJobNodeRepository;
import com.lark.oapi.sample.ws.repository.SupplierJobRepository;
import com.lark.oapi.sample.ws.repository.WorkflowTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * 快速建档编排状态机（11-13-standalone-doc-probe）mockito 纯单测：
 * 顺序推进四段 / 中段失败停在当段 / 重试幂等（不重复入库、归档各一次）/ 超时置 FAILED /
 * listJobs 过滤 quick / 重启恢复标记 / 工作台节点迁移跳过 quick 任务。
 * 等待循环经注入的 sleeper+pollInterval/stepTimeout 快进，无真实睡眠、无 Spring 上下文、无网络。
 */
class QuickProbeServiceTest {

    // ===== 测试脚手架 =====

    private static class Harness {
        SupplierJobRepository jobRepo = mock(SupplierJobRepository.class);
        SupplierJobNodeRepository nodeRepo = mock(SupplierJobNodeRepository.class);
        WorkflowTemplateRepository templateRepo = mock(WorkflowTemplateRepository.class);
        BotInstanceRepository instanceRepo = mock(BotInstanceRepository.class);
        SupplierKbService kb = mock(SupplierKbService.class);
        SupplierJobService jobs = mock(SupplierJobService.class);
        SupplierWikiService wiki = mock(SupplierWikiService.class);
        QuickProbeService service;

        /** 知识库条目真值表（kb.list 的返回源，测试直接改状态模拟解析推进） */
        final List<SupplierKbEntry> kbEntries = new java.util.ArrayList<>();
        /** reparse 后条目转到的状态（模拟解析成功/失败） */
        String reparseResult = "DONE";
        /** Wiki 状态真值（build → READY；archiveArtifact → STALE 由 answer 维护） */
        final AtomicReference<String> wikiStatus = new AtomicReference<>("EMPTY");
        /** 节点真值表 */
        final Map<String, SupplierJobNode> nodes = new HashMap<>();
        /** runNode 后节点还需被轮询几次才"完成"（完成形态由 nodeEnds 决定） */
        final Map<String, Integer> pollsUntilDone = new HashMap<>();
        /** 节点完成形态："ok"=工件 / "note"=失败备注 / "none"=永不完成（超时路径） */
        final Map<String, String> nodeEnds = new HashMap<>();
        final AtomicInteger sleeps = new AtomicInteger();

        SupplierJob job;

        Harness() {
            service = new QuickProbeService(jobRepo, nodeRepo, templateRepo, instanceRepo, kb, jobs, wiki);
            service.pollIntervalMs = 2;
            service.stepTimeoutMs = 10_000;
            service.sleeper = ms -> sleeps.incrementAndGet();
            service.starter = Runnable::run;   // 同步执行流水线（create/retry 内联跑完）

            // job 仓储：save 补 id；findById 按 id 返回
            when(jobRepo.save(any(SupplierJob.class))).thenAnswer(inv -> {
                SupplierJob j = inv.getArgument(0);
                if (j.getId() == null) j.setId(1L);
                return j;
            });
            when(jobRepo.findById(1L)).thenAnswer(inv -> Optional.ofNullable(job));
            when(jobRepo.findAllByQuickTrueOrderByUpdatedAtDesc())
                    .thenAnswer(inv -> job == null ? List.of() : List.of(job));

            // 实例
            BotInstance inst = new BotInstance();
            inst.setId(5L);
            inst.setEnabled(true);
            when(instanceRepo.findById(5L)).thenReturn(Optional.of(inst));

            // 内置 API 模板（含 n1/n2 + 干扰节点 n3）
            WorkflowTemplate tpl = new WorkflowTemplate();
            tpl.setId(9L);
            tpl.setMode("API");
            tpl.setBuiltin(true);
            tpl.setNodesJson("[{\"id\":\"n1\",\"type\":\"agent\",\"title\":\"拉取接口文档 → 接口映射报告\",\"description\":\"分析说明\",\"prompt\":\"分析P {libraryPath}\"},"
                    + "{\"id\":\"n2\",\"type\":\"agent\",\"title\":\"接口实测 → 响应示例归档\",\"description\":\"实测说明\",\"prompt\":\"实测P {artifact:n1}\"},"
                    + "{\"id\":\"n3\",\"type\":\"manual\",\"title\":\"确认基础信息\",\"description\":\"\",\"prompt\":\"\"}]");
            when(templateRepo.findByMode("API")).thenReturn(List.of(tpl));

            // 知识库
            when(kb.kbCode(any(SupplierJob.class))).thenAnswer(inv -> {
                SupplierJob j = inv.getArgument(0);
                return j.getSupplierCode() == null || j.getSupplierCode().isBlank() ? "job-" + j.getId() : j.getSupplierCode();
            });
            when(kb.list(anyString())).thenAnswer(inv -> List.copyOf(kbEntries));
            when(kb.add(anyString(), anyString(), anyString(), any(), anyBoolean())).thenAnswer(inv -> {
                SupplierKbEntry e = new SupplierKbEntry();
                e.setId((long) (kbEntries.size() + 1));
                e.setKbCode(inv.getArgument(0));
                e.setType(inv.getArgument(1));
                e.setSource(inv.getArgument(2));
                e.setTitle("条目-" + inv.getArgument(2));
                e.setStatus("md".equals(e.getType()) ? "DONE" : "RAW");   // md 入库即产物；url/pdf 待解析
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
            doAnswer(inv -> { wikiStatus.set("STALE"); return null; })
                    .when(kb).archiveArtifact(any(SupplierJob.class), anyString(), anyString(), anyString());

            // Wiki：status 读真值；build → READY（模拟异步编译在下次轮询前完成）
            when(wiki.status(any(SupplierJob.class))).thenAnswer(inv -> Map.of(
                    "status", wikiStatus.get(), "error", "FAILED".equals(wikiStatus.get()) ? "编译出错" : ""));
            when(wiki.build(any(SupplierJob.class))).thenAnswer(inv -> { wikiStatus.set("READY"); return null; });

            // 节点仓储：轮询时按 pollsUntilDone/nodeEnds 推进状态（模拟实例轮次异步完成）
            when(nodeRepo.findByJobIdAndNodeId(eq(1L), anyString())).thenAnswer(inv -> {
                String nodeId = inv.getArgument(1);
                SupplierJobNode n = nodes.get(nodeId);
                if (n == null) return Optional.empty();
                Integer left = pollsUntilDone.get(nodeId);
                if (left != null) {
                    if (left <= 0) {
                        pollsUntilDone.remove(nodeId);
                        String end = nodeEnds.getOrDefault(nodeId, "ok");
                        if ("ok".equals(end)) {
                            n.setStatus("PENDING");
                            n.setArtifact("工件-" + nodeId);
                            if ("n2".equals(nodeId)) n.setToolEvidence("## 实测工具事件");
                        } else if ("note".equals(end)) {
                            n.setStatus("PENDING");
                            n.setNote("执行异常: boom");
                        }   // "none"：保持 RUNNING（超时路径）
                    } else {
                        pollsUntilDone.put(nodeId, left - 1);
                    }
                }
                return Optional.of(n);
            });
            when(nodeRepo.save(any(SupplierJobNode.class))).thenAnswer(inv -> inv.getArgument(0));

            // runNode：置 RUNNING（并如真实 runNode 一样清掉上轮 note/工件/证据）+ 登记"再轮询 N 次后完成"
            doAnswer(inv -> {
                String nodeId = inv.getArgument(1);
                SupplierJobNode n = nodes.get(nodeId);
                n.setStatus("RUNNING");
                n.setNote("");
                n.setArtifact("");
                n.setToolEvidence("");
                n.setPendingQuestion("");
                pollsUntilDone.put(nodeId, 2);
                return null;
            }).when(jobs).runNode(eq(1L), anyString(), anyString());
        }

        /** 预置一个已创建的 quick 任务（含 n1/n2 节点与单 URL 来源），不触发流水线 */
        SupplierJob givenQuickJob(String docsJson) {
            job = new SupplierJob();
            job.setId(1L);
            job.setName("acme");
            job.setMode("API");
            job.setKnowledgeMode("WIKI");
            job.setQuick(true);
            job.setSupplierCode("acme");
            job.setSupplierName("acme");
            job.setInstanceId(5L);
            job.setDocsJson(docsJson);
            job.setStatus("ACTIVE");
            job.setQuickPhase("KB");
            for (String id : List.of("n1", "n2")) {
                SupplierJobNode n = new SupplierJobNode();
                n.setJobId(1L);
                n.setNodeId(id);
                n.setSeq("n1".equals(id) ? 0 : 1);
                n.setType("agent");
                n.setTitle("n1".equals(id) ? "拉取接口文档 → 接口映射报告" : "接口实测 → 响应示例归档");
                n.setStatus("PENDING");
                nodes.put(id, n);
            }
            return job;
        }
    }

    private static final String URL_DOCS = "[{\"type\":\"url\",\"value\":\"https://docs.acme.com/api\"}]";

    // ===== a) 创建 + 顺序推进四段 =====

    @Test void createBuildsQuickJobWithTwoAgentNodesOnly() {
        var h = new Harness();
        h.service.starter = r -> { };   // 本测只看创建产物，不跑流水线
        var job = h.service.create("acme", null, 5L, List.of("https://docs.acme.com/api"), true, null, null);

        assertTrue(job.isQuick());
        assertEquals("WIKI", job.getKnowledgeMode());
        assertEquals("acme", job.getName());          // 任务名默认 = 标识
        assertEquals("acme", job.getSupplierCode());
        assertEquals("", job.getLibraryPath());       // 工具定位：不填任何开发向字段
        assertEquals("KB", job.getQuickPhase());
        assertTrue(job.getDocsJson().contains("\"site\":true"), "URL 来源应带站点抓取标记: " + job.getDocsJson());

        ArgumentCaptor<SupplierJobNode> captor = ArgumentCaptor.forClass(SupplierJobNode.class);
        verify(h.nodeRepo, times(2)).save(captor.capture());
        var saved = captor.getAllValues();
        assertEquals("n1", saved.get(0).getNodeId());
        assertEquals("n2", saved.get(1).getNodeId());
        assertEquals("分析P {libraryPath}", saved.get(0).getPrompt());   // 照抄内置模板 prompt（开发占位符原样保留，渲染时为空）
        assertEquals("agent", saved.get(0).getType());
        assertEquals("PENDING", saved.get(1).getStatus());
    }

    @Test void blankIdentifierFallsBackToQuickTimestamp() {
        var h = new Harness();
        h.service.starter = r -> { };
        var job = h.service.create("  ", null, 5L, List.of("https://docs.acme.com/api"), false, null, null);
        assertTrue(job.getSupplierCode().startsWith("quick-"), job.getSupplierCode());
        assertEquals(job.getSupplierCode(), job.getName());
    }

    @Test void pipelineRunsFourStagesInOrderToDone() {
        var h = new Harness();
        h.givenQuickJob(URL_DOCS);

        h.service.runPipeline(1L, "KB");

        // 顺序：入库 → 首编译 → n1 → n2 → 归档 → 终编译（InOrder 校验编排顺序）
        InOrder order = inOrder(h.kb, h.jobs, h.wiki);
        order.verify(h.kb).add("acme", "url", "https://docs.acme.com/api", null, false);
        order.verify(h.kb).reparse(1L);                        // url 入库为 RAW → 触发解析
        order.verify(h.wiki).build(h.job);                     // KB 段尾部首次 Wiki 编译
        order.verify(h.jobs).runNode(1L, "n1", "");
        order.verify(h.jobs).runNode(1L, "n2", "");
        order.verify(h.kb).archiveArtifact(eq(h.job), eq("n1"), anyString(), eq("工件-n1"));
        order.verify(h.kb).archiveArtifact(eq(h.job), eq("n2"), anyString(), contains("实测执行证据"));
        order.verify(h.wiki).build(h.job);                     // 归档弄脏来源 → 终编译
        verify(h.wiki, times(2)).build(h.job);
        assertTrue(h.sleeps.get() > 0, "等待循环应经过轮询睡眠");

        assertEquals("DONE", h.job.getQuickPhase());
        assertEquals("", h.job.getQuickError());
        assertEquals("COMPLETED", h.job.getStatus());
        assertNull(h.job.getCurrentNodeId());
        assertEquals("DONE", h.nodes.get("n1").getStatus());
        assertEquals("DONE", h.nodes.get("n2").getStatus());
        assertEquals("READY", h.wikiStatus.get());
    }

    // ===== b) 中段失败停在当段 =====

    @Test void agentFailureStopsAtThatStage() {
        var h = new Harness();
        h.givenQuickJob(URL_DOCS);
        h.nodeEnds.put("n1", "note");   // n1 轮次以失败备注收场

        h.service.runPipeline(1L, "KB");

        assertEquals("ANALYSIS", h.job.getQuickPhase());
        assertTrue(h.job.getQuickError().contains("boom"), h.job.getQuickError());
        verify(h.jobs, never()).runNode(1L, "n2", "");          // 停在当段，不再推进
        verify(h.kb, never()).archiveArtifact(any(), anyString(), anyString(), anyString());
        verify(h.wiki, times(1)).build(h.job);                  // 只有 KB 段那次编译
        assertEquals("ACTIVE", h.job.getStatus());              // 任务未标完成
    }

    @Test void kbParseFailureStopsAtKbStage() {
        var h = new Harness();
        h.givenQuickJob(URL_DOCS);
        h.reparseResult = "FAILED";

        h.service.runPipeline(1L, "KB");

        assertEquals("KB", h.job.getQuickPhase());
        assertTrue(h.job.getQuickError().contains("文档解析失败"), h.job.getQuickError());
        assertTrue(h.job.getQuickError().contains("抓取超时"), h.job.getQuickError());
        verify(h.jobs, never()).runNode(anyLong(), anyString(), anyString());
        verify(h.wiki, never()).build(any());
    }

    // ===== c) 重试幂等 =====

    @Test void retryFromKbStageIsIdempotent() {
        var h = new Harness();
        h.givenQuickJob(URL_DOCS);
        h.reparseResult = "FAILED";
        h.service.runPipeline(1L, "KB");
        assertEquals("KB", h.job.getQuickPhase());

        // 修复外部条件后从 KB 段重试：条目已存在 → 不再 add；解析成功 → 一路到 DONE
        h.reparseResult = "DONE";
        h.service.retry(1L, "kb");

        verify(h.kb, times(1)).add(anyString(), anyString(), anyString(), any(), anyBoolean());   // 无重复条目
        verify(h.kb, times(1)).archiveArtifact(any(), eq("n1"), anyString(), anyString());        // 归档各一次
        verify(h.kb, times(1)).archiveArtifact(any(), eq("n2"), anyString(), anyString());
        verify(h.jobs, times(1)).runNode(1L, "n1", "");
        verify(h.jobs, times(1)).runNode(1L, "n2", "");
        assertEquals("DONE", h.job.getQuickPhase());
        assertEquals("COMPLETED", h.job.getStatus());
    }

    @Test void retryFromAnalysisStageRerunsOnlyTailStages() {
        var h = new Harness();
        h.givenQuickJob(URL_DOCS);
        h.nodeEnds.put("n1", "note");
        h.service.runPipeline(1L, "KB");
        assertEquals("ANALYSIS", h.job.getQuickPhase());

        h.nodeEnds.put("n1", "ok");
        h.service.retry(1L, "analysis");

        verify(h.kb, times(1)).add(anyString(), anyString(), anyString(), any(), anyBoolean());   // KB 段未重跑
        verify(h.jobs, times(2)).runNode(1L, "n1", "");   // 首次失败 + 重试各一次
        verify(h.jobs, times(1)).runNode(1L, "n2", "");
        assertEquals("DONE", h.job.getQuickPhase());
    }

    @Test void retryGuardsRejectInvalidStates() {
        var h = new Harness();
        h.givenQuickJob(URL_DOCS);
        h.job.setQuickPhase("DONE");
        h.job.setQuickError("");
        assertThrows(IllegalArgumentException.class, () -> h.service.retry(1L, "kb"));    // 未失败不重试
        h.job.setQuickError("x");
        assertThrows(IllegalArgumentException.class, () -> h.service.retry(1L, "code"));  // 未知段
    }

    // ===== e) 超时置 FAILED =====

    @Test void nodeWaitTimeoutMarksStageFailed() {
        var h = new Harness();
        h.givenQuickJob(URL_DOCS);
        h.nodeEnds.put("n1", "none");   // 轮次永不完成（如卡点无人答复）
        h.service.stepTimeoutMs = 5;    // 快进：2ms 轮询 × 数次即超时

        h.service.runPipeline(1L, "KB");

        assertEquals("ANALYSIS", h.job.getQuickPhase());
        assertTrue(h.job.getQuickError().contains("超时"), h.job.getQuickError());
        assertTrue(h.sleeps.get() >= 3, "超前应至少轮询数次: " + h.sleeps.get());
        verify(h.jobs, never()).runNode(1L, "n2", "");
    }

    // ===== Wiki 编译失败：自动重建一次（否则「从本段重试」永远撞旧 FAILED 版本） =====

    @Test void wikiFailureAutoRebuildsOnceAndPipelineCompletes() {
        var h = new Harness();
        h.givenQuickJob(URL_DOCS);
        var builds = new AtomicInteger();
        when(h.wiki.build(any(SupplierJob.class))).thenAnswer(inv -> {
            h.wikiStatus.set(builds.incrementAndGet() == 1 ? "FAILED" : "READY");   // 首次编译失败，重建成功
            return null;
        });

        h.service.runPipeline(1L, "KB");

        assertEquals("DONE", h.job.getQuickPhase(), h.job.getQuickError());
        assertEquals("READY", h.wikiStatus.get());
        assertTrue(builds.get() >= 2, "FAILED 后应自动重建: builds=" + builds.get());
    }

    @Test void wikiPersistentFailureFailsStageWithoutInfiniteRebuild() {
        var h = new Harness();
        h.givenQuickJob(URL_DOCS);
        when(h.wiki.build(any(SupplierJob.class))).thenAnswer(inv -> {
            h.wikiStatus.set("FAILED");   // 每次编译都失败
            return null;
        });

        h.service.runPipeline(1L, "KB");

        assertEquals("KB", h.job.getQuickPhase());
        assertTrue(h.job.getQuickError().contains("Wiki 编译失败"), h.job.getQuickError());
        verify(h.wiki, times(2)).build(h.job);   // 首建 + 自动重建一次为止，有界不空转
        verify(h.jobs, never()).runNode(anyLong(), anyString(), anyString());
    }

    // ===== d) listJobs 过滤 quick =====

    @Test void workbenchListJobsExcludesQuickJobs() {
        var repo = mock(SupplierJobRepository.class);
        var normal = new SupplierJob(); normal.setId(1L); normal.setName("正式工作");
        when(repo.findAllByQuickFalseOrderByUpdatedAtDesc()).thenReturn(List.of(normal));
        var service = new SupplierJobService(repo, null, null, null, null,
                mock(SupplierKbService.class), null, null);

        List<SupplierJob> listed = service.listJobs();

        assertEquals(List.of(normal), listed);
        verify(repo, times(1)).findAllByQuickFalseOrderByUpdatedAtDesc();
        verify(repo, never()).findAllByOrderByUpdatedAtDesc();
    }

    @Test void flowMigrationSkipsQuickJobs() {
        var repo = mock(SupplierJobRepository.class);
        var nodeRepo = mock(SupplierJobNodeRepository.class);
        var templateRepo = mock(WorkflowTemplateRepository.class);
        var quick = new SupplierJob(); quick.setId(1L); quick.setQuick(true); quick.setStatus("ACTIVE");
        when(templateRepo.findAll()).thenReturn(List.of());
        when(repo.findAll()).thenReturn(List.of(quick));
        var n1 = new SupplierJobNode(); n1.setJobId(1L); n1.setNodeId("n1"); n1.setSeq(0);
        var n2 = new SupplierJobNode(); n2.setJobId(1L); n2.setNodeId("n2"); n2.setSeq(1);
        when(nodeRepo.findByJobIdOrderBySeqAsc(1L)).thenReturn(List.of(n1, n2));
        var service = new SupplierJobService(repo, nodeRepo, templateRepo, null, null,
                mock(SupplierKbService.class), null, null);

        service.migrateFlowNodes();

        verify(nodeRepo, never()).save(any());   // quick 任务不被回填 n45/n9 开发节点
    }

    // ===== 重启恢复 =====

    @Test void restartRecoveryMarksInProgressTasksFailed() {
        var h = new Harness();
        h.givenQuickJob(URL_DOCS);
        h.job.setQuickPhase("PROBE");
        h.service.recoverInterrupted();
        assertTrue(h.job.getQuickError().contains("服务重启中断"), h.job.getQuickError());

        h.job.setQuickError("");
        h.job.setQuickPhase("DONE");
        h.service.recoverInterrupted();
        assertEquals("", h.job.getQuickError());   // 已完成任务不动
    }

    // ===== 删除运行中任务：流水线在下一个轮询点自行终止 =====

    @Test void deletedJobStopsPipelineAtNextPoll() {
        var h = new Harness();
        h.givenQuickJob(URL_DOCS);
        h.nodeEnds.put("n1", "none");   // agent 轮次永不完成——让等待循环持续轮询
        h.service.sleeper = ms -> {
            h.sleeps.incrementAndGet();
            if (h.sleeps.get() == 2) h.job = null;   // 模拟用户在等待中删除任务（findById 随即落空）
        };

        assertDoesNotThrow(() -> h.service.runPipeline(1L, "KB"));   // 内部消化"任务已删除"，不外抛

        assertTrue(h.sleeps.get() >= 2, "应已轮询到删除点: " + h.sleeps.get());
        verify(h.jobs, never()).runNode(1L, "n2", "");   // 删除后不再推进后续段/触发实例侧开销
    }

    // ===== 纯函数 =====

    @Test void detectTypeMapsSourceLines() {
        assertEquals("url", QuickProbeService.detectType("https://docs.acme.com/api"));
        assertEquals("url", QuickProbeService.detectType("HTTP://docs.acme.com"));
        assertEquals("pdf", QuickProbeService.detectType("D:\\docs\\api.PDF"));
        assertEquals("md", QuickProbeService.detectType("/tmp/api.md"));
        assertEquals("md", QuickProbeService.detectType("/tmp/api.txt"));
        assertThrows(IllegalArgumentException.class, () -> QuickProbeService.detectType("D:\\docs\\api.docx"));
        assertThrows(IllegalArgumentException.class, () -> QuickProbeService.detectType("ftp://x"));
    }

    @Test void stepStatusDerivesFromPhaseAndError() {
        assertEquals("DONE", QuickProbeService.stepStatus("DONE", "", "KB"));
        assertEquals("DONE", QuickProbeService.stepStatus("WIKI", "", "PROBE"));
        assertEquals("RUNNING", QuickProbeService.stepStatus("PROBE", "", "PROBE"));
        assertEquals("FAILED", QuickProbeService.stepStatus("PROBE", "boom", "PROBE"));
        assertEquals("PENDING", QuickProbeService.stepStatus("ANALYSIS", "", "WIKI"));
        assertEquals("PENDING", QuickProbeService.stepStatus("", "", "KB"));
    }

    @Test void parseDocsReadsTypeAndSiteFlag() {
        var refs = QuickProbeService.parseDocs(
                "[{\"type\":\"url\",\"value\":\"https://a.com\",\"site\":true},{\"type\":\"path\",\"value\":\"D:\\\\x.pdf\"}]");
        assertEquals(2, refs.size());
        assertEquals("url", refs.get(0).type());
        assertTrue(refs.get(0).site());
        assertEquals("pdf", refs.get(1).type());
        assertFalse(refs.get(1).site());
    }

    // ===== 创建入参校验 =====

    @Test void createValidatesInstanceAndSources(@TempDir Path dir) throws Exception {
        var h = new Harness();
        h.service.starter = r -> { };
        assertThrows(IllegalArgumentException.class,
                () -> h.service.create("acme", null, null, List.of("https://a.com"), false, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> h.service.create("acme", null, 404L, List.of("https://a.com"), false, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> h.service.create("acme", null, 5L, List.of(), false, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> h.service.create("acme", null, 5L, List.of("D:\\不存在.docx"), false, null, null));
        // 输入上限：来源行数 ≤50、单行 ≤2000 字符（工具定位防超大清单拖垮串行解析）
        List<String> tooMany = new java.util.ArrayList<>();
        for (int i = 0; i < QuickProbeService.MAX_SOURCE_LINES + 1; i++) tooMany.add("https://a.com/" + i);
        assertThrows(IllegalArgumentException.class,
                () -> h.service.create("acme", null, 5L, tooMany, false, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> h.service.create("acme", null, 5L,
                        List.of("https://a.com/" + "x".repeat(QuickProbeService.MAX_SOURCE_LINE_CHARS)), false, null, null));
        Path missing = dir.resolve("nope.pdf");
        assertThrows(IllegalArgumentException.class,
                () -> h.service.create("acme", null, 5L, List.of(missing.toString()), false, null, null));
        Path md = dir.resolve("api.md");
        Files.writeString(md, "# API", StandardCharsets.UTF_8);
        assertDoesNotThrow(() -> h.service.create("acme", null, 5L, List.of(md.toString()), false, null, null));
    }
}
