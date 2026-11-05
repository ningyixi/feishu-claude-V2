package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.model.entity.SupplierJobNode;
import com.lark.oapi.sample.ws.repository.SupplierJobRepository;
import com.lark.oapi.sample.ws.repository.SupplierJobNodeRepository;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupplierWorkflowGateTest {
    @Test void changedMaterialsRequireRegenerationBeforeDevelopmentCompletes() {
        var jobs = mock(SupplierJobRepository.class);
        var nodes = mock(SupplierJobNodeRepository.class);
        var kb = mock(SupplierKbService.class);
        var job = new SupplierJob(); job.setId(1L);
        job.setMeetingDocUrl("https://example.feishu.cn/docx/a");
        job.setConfigJson("[{\"key\":\"fixture\",\"value\":\"on\"}]");
        job.setGeneratedPromptDigest(SupplierReferenceContext.digest("old material"));
        var node = new SupplierJobNode(); node.setContextStage("codegen");
        when(jobs.findById(1L)).thenReturn(Optional.of(job));
        when(nodes.findByJobIdAndNodeId(1L, "code")).thenReturn(Optional.of(node));
        var service = spy(new SupplierJobService(jobs, nodes, null, null, null, kb, null, null));
        doReturn("updated material").when(service).codegenPrompt(1L);
        var error = assertThrows(IllegalArgumentException.class, () -> service.completeNode(1L, "code"));
        assertTrue(error.getMessage().contains("重新生成"));
        assertEquals("PENDING", node.getStatus());
        verifyNoInteractions(kb);
    }
    @Test void proseCannotCompleteProbeOrArchiveUnverifiedEvidence() {
        var jobs = mock(SupplierJobRepository.class);
        var nodes = mock(SupplierJobNodeRepository.class);
        var kb = mock(SupplierKbService.class);
        var job = new SupplierJob(); job.setId(1L);
        var node = new SupplierJobNode(); node.setTitle("接口实测"); node.setArtifact("I tested all APIs successfully");
        when(jobs.findById(1L)).thenReturn(Optional.of(job));
        when(nodes.findByJobIdAndNodeId(1L, "n2")).thenReturn(Optional.of(node));
        var service = new SupplierJobService(jobs, nodes, null, null, null, kb, null, null);
        assertThrows(IllegalArgumentException.class, () -> service.completeNode(1L, "n2"));
        verifyNoInteractions(kb);
        node.setToolEvidence("observed request and HTTP response");
        service.completeNode(1L, "n2");
        assertEquals("DONE", node.getStatus());
        verify(kb).archiveArtifact(eq(job), eq("n2"), eq("接口实测"), contains("observed request and HTTP response"));
    }
    @Test void uploadedMeetingMaterialsSatisfyDocGate() throws Exception {
        var jobs = mock(SupplierJobRepository.class);
        var nodes = mock(SupplierJobNodeRepository.class);
        var kb = mock(SupplierKbService.class);
        var job = new SupplierJob(); job.setId(1L);
        job.setConfigJson("[{\"key\":\"fixture\",\"value\":\"on\"}]");   // KV 完整，会议文档链接为空
        var node = new SupplierJobNode(); node.setCodegenPrep(true);    // contextStage 默认 auto → codegen 分支，spy+doReturn 规避
        job.setGeneratedPromptDigest(SupplierReferenceContext.digest("material"));
        when(jobs.findById(1L)).thenReturn(Optional.of(job));
        when(nodes.findByJobIdAndNodeId(1L, "code")).thenReturn(Optional.of(node));
        var entry = new com.lark.oapi.sample.ws.model.entity.SupplierKbEntry();
        entry.setId(9L); entry.setSource("job:1/node:generated-prompt"); entry.setStatus("DONE");
        when(kb.kbCode(job)).thenReturn("supplier-1");
        when(kb.list("supplier-1")).thenReturn(java.util.List.of(entry));
        when(kb.readMd(9L)).thenReturn("# dev prompt");
        var service = spy(new SupplierJobService(jobs, nodes, null, null, null, kb, null, null));
        doReturn("material").when(service).codegenPrompt(1L);
        service.completeNode(1L, "code", true);   // 待办已上传会议材料 → 文档门禁视为已交
        assertEquals("DONE", node.getStatus());
    }
    @Test void prepGateErrorListsMissingItemsSeparately() {
        var jobs = mock(SupplierJobRepository.class);
        var nodes = mock(SupplierJobNodeRepository.class);
        var kb = mock(SupplierKbService.class);
        var job = new SupplierJob(); job.setId(1L);   // doc 空 + KV 空
        var node = new SupplierJobNode(); node.setCodegenPrep(true);
        when(jobs.findById(1L)).thenReturn(Optional.of(job));
        when(nodes.findByJobIdAndNodeId(1L, "code")).thenReturn(Optional.of(node));
        var service = new SupplierJobService(jobs, nodes, null, null, null, kb, null, null);
        var both = assertThrows(IllegalArgumentException.class, () -> service.completeNode(1L, "code", false));
        assertTrue(both.getMessage().contains("未收到三方会议文档") && both.getMessage().contains("配置 key/value"));
        job.setMeetingDocUrl("https://a.feishu.cn/docx/x");   // doc 有 + KV 缺 → 只报配置
        var kvOnly = assertThrows(IllegalArgumentException.class, () -> service.completeNode(1L, "code", false));
        assertTrue(kvOnly.getMessage().contains("配置 key/value"));
        assertFalse(kvOnly.getMessage().contains("未收到"));
        verifyNoInteractions(kb);
    }
}
