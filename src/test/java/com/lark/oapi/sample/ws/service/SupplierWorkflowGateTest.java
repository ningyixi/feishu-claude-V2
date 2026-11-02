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
        var service = spy(new SupplierJobService(jobs, nodes, null, null, null, kb, null));
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
        var service = new SupplierJobService(jobs, nodes, null, null, null, kb, null);
        assertThrows(IllegalArgumentException.class, () -> service.completeNode(1L, "n2"));
        verifyNoInteractions(kb);
        node.setToolEvidence("observed request and HTTP response");
        service.completeNode(1L, "n2");
        assertEquals("DONE", node.getStatus());
        verify(kb).archiveArtifact(eq(job), eq("n2"), eq("接口实测"), contains("observed request and HTTP response"));
    }
}
