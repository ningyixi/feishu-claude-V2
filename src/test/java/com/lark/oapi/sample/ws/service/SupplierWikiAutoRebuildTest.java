package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.model.entity.SupplierJobNode;
import com.lark.oapi.sample.ws.model.entity.SupplierKbEntry;
import com.lark.oapi.sample.ws.model.entity.SupplierWikiRevision;
import com.lark.oapi.sample.ws.model.entity.SupplierWorkflowAttachment;
import com.lark.oapi.sample.ws.model.entity.SupplierWorkflowRun;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.SupplierJobNodeRepository;
import com.lark.oapi.sample.ws.repository.SupplierJobRepository;
import com.lark.oapi.sample.ws.repository.SupplierWikiRevisionRepository;
import com.lark.oapi.sample.ws.repository.SupplierWorkflowAttachmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * WIKI 模式下工件归档弄脏 Wiki 来源后的自动重编译（completeNode/saveArtifact → scheduleWikiRecompile）、
 * 工作流待办上传材料后的自动重编译（SupplierWorkflowFiles.upload → scheduleWikiRecompile）、
 * 待办提交只落工件不入 KB（setArtifactForCompletion，respond 死循环修复），
 * 以及 SupplierWikiService.ready() 的引导话术。mockito 纯单测，无 Spring 上下文、无网络。
 */
class SupplierWikiAutoRebuildTest {

    private record Fixture(SupplierJobService service, SupplierJob job, SupplierJobNode node,
                           SupplierJobNodeRepository nodes, SupplierKbService kb, SupplierWikiService wiki) {}

    /** 普通 manual 节点（非 codegenPrep、非 probe、标题不含"写代码/实测/分析"）避开重门禁 */
    private Fixture fixture(String knowledgeMode, String wikiStatus) {
        var jobs = mock(SupplierJobRepository.class);
        var nodes = mock(SupplierJobNodeRepository.class);
        var kb = mock(SupplierKbService.class);
        var wiki = mock(SupplierWikiService.class);
        var job = new SupplierJob(); job.setId(1L); job.setKnowledgeMode(knowledgeMode);
        var node = new SupplierJobNode(); node.setNodeId("n3"); node.setTitle("确认基础信息与前置申请");
        node.setStatus("PENDING"); node.setArtifact("已核对基础信息");
        when(jobs.findById(1L)).thenReturn(Optional.of(job));
        when(nodes.findByJobIdAndNodeId(1L, "n3")).thenReturn(Optional.of(node));
        if (wikiStatus != null) when(wiki.status(job)).thenReturn(Map.of("status", wikiStatus));
        var service = new SupplierJobService(jobs, nodes, null, null, null, kb, null, null);
        @SuppressWarnings("unchecked")
        ObjectProvider<SupplierWikiService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(wiki);
        ReflectionTestUtils.setField(service, "wikiProvider", provider);
        return new Fixture(service, job, node, nodes, kb, wiki);
    }

    // ===== A. 工件归档后自动重编译 =====

    @Test void staleWikiRebuildsAfterNodeCompletion() {
        var f = fixture("WIKI", "STALE");
        f.service().completeNode(1L, "n3");
        assertEquals("DONE", f.node().getStatus());
        verify(f.kb()).archiveArtifact(eq(f.job()), eq("n3"), eq("确认基础信息与前置申请"), anyString());
        verify(f.wiki(), times(1)).build(f.job());
    }

    @Test void textModeNeverTouchesWiki() {
        var f = fixture("TEXT", null);   // status 都不该被查
        f.service().completeNode(1L, "n3");
        assertEquals("DONE", f.node().getStatus());
        verifyNoInteractions(f.wiki());
    }

    @Test void readyOrBuildingWikiDoesNotRebuild() {
        var ready = fixture("WIKI", "READY");
        ready.service().completeNode(1L, "n3");
        verify(ready.wiki(), never()).build(any());

        var building = fixture("WIKI", "BUILDING");
        building.service().completeNode(1L, "n3");
        verify(building.wiki(), never()).build(any());
    }

    @Test void missingWikiProviderDoesNotBreakCompletion() {
        var jobs = mock(SupplierJobRepository.class);
        var nodes = mock(SupplierJobNodeRepository.class);
        var kb = mock(SupplierKbService.class);
        var job = new SupplierJob(); job.setId(1L); job.setKnowledgeMode("WIKI");
        var node = new SupplierJobNode(); node.setNodeId("n3"); node.setTitle("确认基础信息与前置申请");
        node.setStatus("PENDING"); node.setArtifact("已核对基础信息");
        when(jobs.findById(1L)).thenReturn(Optional.of(job));
        when(nodes.findByJobIdAndNodeId(1L, "n3")).thenReturn(Optional.of(node));
        var service = new SupplierJobService(jobs, nodes, null, null, null, kb, null, null);   // wikiProvider 未注入
        assertDoesNotThrow(() -> service.completeNode(1L, "n3"));
        assertEquals("DONE", node.getStatus());
    }

    @Test void editedArtifactRebuildsStaleWiki() {
        var f = fixture("WIKI", "STALE");
        f.service().saveArtifact(1L, "n3", "玩家修改后的工件");
        assertEquals("玩家修改后的工件", f.node().getArtifact());
        verify(f.kb()).archiveArtifact(eq(f.job()), eq("n3"), eq("确认基础信息与前置申请"), anyString());
        verify(f.wiki(), times(1)).build(f.job());
    }

    // ===== A2. 待办提交只落工件、不入 KB（respond 死循环修复：归档延后到 completeNode 门禁之后） =====

    @Test void completionArtifactWriteSkipsKnowledgeBase() {
        var f = fixture("WIKI", null);   // status 都不该被查
        f.service().setArtifactForCompletion(1L, "n3", "工作流待办答复文本");
        assertEquals("工作流待办答复文本", f.node().getArtifact());   // 工件落实体
        verify(f.nodes()).save(f.node());                            // 节点落库
        // 不写知识库 → 同事务内 Wiki 来源指纹不变，门禁校验不会自脏必败
        verify(f.kb(), never()).archiveArtifact(any(), anyString(), anyString(), anyString());
        verifyNoInteractions(f.wiki());                              // 也不触发重编译（归档统一在 completeNode 尾部）
    }

    @Test void completionArtifactWriteDoesNotScheduleRebuild() {
        var f = fixture("TEXT", null);
        assertDoesNotThrow(() -> f.service().setArtifactForCompletion(1L, "n3", "答复"));
        assertEquals("答复", f.node().getArtifact());
        verifyNoInteractions(f.kb(), f.wiki());
    }

    @Test void rebuildFailureNeverBreaksCompletion() {
        var f = fixture("WIKI", "STALE");
        when(f.wiki().build(f.job())).thenThrow(new IllegalArgumentException("请先绑定用于编译 Wiki 的实例"));
        assertDoesNotThrow(() -> f.service().completeNode(1L, "n3"));
        assertEquals("DONE", f.node().getStatus());   // best-effort：重编译失败只 warn，不影响节点完成
    }

    // ===== A3. 工作流待办「补充文件」上传后自动重编译（SupplierWorkflowFiles.upload） =====

    private record UploadFixture(SupplierWorkflowFiles service, SupplierJob job, SupplierKbService kb, SupplierWikiService wiki) {}

    /** 上传装配：最小 .md 文本材料走通 upload（不碰 PDFBox/真实文件系统外的东西，root 用 @TempDir） */
    private UploadFixture uploadFixture(String knowledgeMode, String wikiStatus, Path root) {
        var workflow = mock(SupplierWorkflowService.class);
        var files = mock(SupplierWorkflowAttachmentRepository.class);
        var jobs = mock(SupplierJobRepository.class);
        var kb = mock(SupplierKbService.class);
        var wiki = mock(SupplierWikiService.class);
        var run = new SupplierWorkflowRun(); run.id = 7L; run.jobId = 1L; run.status = "ACTIVE";
        when(workflow.activeTaskForUpload(1L, "task-1")).thenReturn(run);
        var job = new SupplierJob(); job.setId(1L); job.setKnowledgeMode(knowledgeMode);
        when(jobs.findById(1L)).thenReturn(Optional.of(job));
        when(kb.kbCode(job)).thenReturn("supplier-1");
        var entry = new SupplierKbEntry(); entry.setId(9L); entry.setStatus("DONE");
        when(kb.add(eq("supplier-1"), eq("note"), anyString(), anyString())).thenReturn(entry);
        when(files.findByRunIdAndTaskIdOrderByIdAsc(7L, "task-1")).thenReturn(List.of());
        when(files.save(any(SupplierWorkflowAttachment.class))).thenAnswer(inv -> inv.getArgument(0));
        if (wikiStatus != null) when(wiki.status(job)).thenReturn(Map.of("status", wikiStatus));
        var service = new SupplierWorkflowFiles(workflow, files, jobs, kb, root.toString());
        @SuppressWarnings("unchecked")
        ObjectProvider<SupplierWikiService> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(wiki);
        ReflectionTestUtils.setField(service, "wikiProvider", provider);
        return new UploadFixture(service, job, kb, wiki);
    }

    private static MultipartFile material() {
        return new MockMultipartFile("file", "会议补充.md", "text/markdown",
                "会议补充材料内容".getBytes(StandardCharsets.UTF_8));
    }

    @Test void staleWikiRebuildsAfterMaterialUpload(@TempDir Path root) {
        var f = uploadFixture("WIKI", "STALE", root);
        var saved = f.service().upload(1L, "task-1", material());
        assertEquals("会议补充.md", saved.filename);
        assertEquals(9L, saved.entryId.longValue());   // 附件已挂上 KB 条目
        verify(f.kb()).add(eq("supplier-1"), eq("note"), contains("会议补充材料内容"), eq("会议补充.md"));
        verify(f.wiki(), times(1)).build(f.job());     // 入库弄脏来源 → 触发重编译
    }

    @Test void textModeUploadNeverTouchesWiki(@TempDir Path root) {
        var f = uploadFixture("TEXT", null, root);   // status 都不该被查
        f.service().upload(1L, "task-1", material());
        verifyNoInteractions(f.wiki());
    }

    @Test void buildingWikiDoesNotRebuildOnUpload(@TempDir Path root) {
        var f = uploadFixture("WIKI", "BUILDING", root);
        f.service().upload(1L, "task-1", material());
        verify(f.wiki(), never()).build(any());        // 编译中不重复触发
    }

    @Test void readyWikiDoesNotRebuildOnUpload(@TempDir Path root) {
        var f = uploadFixture("WIKI", "READY", root);
        f.service().upload(1L, "task-1", material());
        verify(f.wiki(), never()).build(any());
    }

    @Test void uploadRebuildFailureNeverBreaksUpload(@TempDir Path root) {
        var f = uploadFixture("WIKI", "STALE", root);
        when(f.wiki().build(f.job())).thenThrow(new IllegalArgumentException("请先绑定用于编译 Wiki 的实例"));
        var saved = assertDoesNotThrow(() -> f.service().upload(1L, "task-1", material()));
        assertEquals(9L, saved.entryId.longValue());   // best-effort：重编译失败只 warn，上传仍成功
    }

    // ===== B. ready() 引导话术（经 wiki.context 触发） =====

    @Test void wikiContextGuidesInsteadOfDeadEnd(@TempDir Path root) {
        var sources = mock(SupplierWikiSources.class);
        var revisions = mock(SupplierWikiRevisionRepository.class);
        var instances = mock(BotInstanceRepository.class);
        var claude = mock(ClaudeCodeService.class);
        var kb = mock(SupplierKbService.class);
        var wiki = new SupplierWikiService(sources, revisions, instances, claude, kb, root.toString());
        var job = new SupplierJob(); job.setId(11L); job.setSupplierCode("supplier-A"); job.setKnowledgeMode("WIKI");
        when(kb.kbCode(job)).thenReturn("supplier-A");

        // BUILDING → 自动重编译进行中，可等待
        var building = new SupplierWikiRevision(); building.setKbCode("supplier-A"); building.setStatus("BUILDING");
        when(revisions.findFirstByKbCodeOrderByIdDesc("supplier-A")).thenReturn(Optional.of(building));
        var e1 = assertThrows(IllegalArgumentException.class, () -> wiki.context(job, "codegen"));
        assertTrue(e1.getMessage().contains("正在自动重新编译"), e1.getMessage());
        assertTrue(e1.getMessage().contains("请稍候重试"), e1.getMessage());

        // READY 但来源指纹变化（STALE）→ 引导去 Wiki 面板
        var stale = new SupplierWikiRevision(); stale.setKbCode("supplier-A"); stale.setStatus("READY"); stale.setSourceDigest("old-digest");
        when(revisions.findFirstByKbCodeOrderByIdDesc("supplier-A")).thenReturn(Optional.of(stale));
        when(sources.snapshot(job)).thenReturn(new SupplierWikiSources.Snapshot("supplier-A", "new-digest", List.of(), List.of(), 0));
        var e2 = assertThrows(IllegalArgumentException.class, () -> wiki.context(job, "codegen"));
        assertTrue(e2.getMessage().contains("来源已更新"), e2.getMessage());
        assertTrue(e2.getMessage().contains("Wiki 面板"), e2.getMessage());

        // FAILED 等其余状态 → 保留原有格式
        var failed = new SupplierWikiRevision(); failed.setKbCode("supplier-A"); failed.setStatus("FAILED"); failed.setError("编译中断");
        when(revisions.findFirstByKbCodeOrderByIdDesc("supplier-A")).thenReturn(Optional.of(failed));
        var e3 = assertThrows(IllegalArgumentException.class, () -> wiki.context(job, "codegen"));
        assertTrue(e3.getMessage().contains("Wiki 尚未就绪：FAILED"), e3.getMessage());
        assertTrue(e3.getMessage().contains("编译中断"), e3.getMessage());
    }
}
