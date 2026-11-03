package com.lark.oapi.sample.ws.service;
import com.lark.oapi.sample.ws.model.entity.*;
import com.lark.oapi.sample.ws.repository.*;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SupplierKnowledgeModeTest {
    @Test void selectedModeIsPersistedAndUnknownModesAreRejected() {
        var jobs=mock(SupplierJobRepository.class);var nodes=mock(SupplierJobNodeRepository.class);var templates=mock(WorkflowTemplateRepository.class);var kb=mock(SupplierKbService.class);
        var template=new WorkflowTemplate();template.setMode("API");template.setNodesJson("[]");when(templates.findById(1L)).thenReturn(Optional.of(template));
        when(jobs.save(any())).thenAnswer(call->{var job=call.getArgument(0,SupplierJob.class);job.setId(11L);return job;});
        var service=new SupplierJobService(jobs,nodes,templates,null,null,kb,null,null);
        var draft=new SupplierJob();draft.setTemplateId(1L);draft.setKnowledgeMode("WIKI");assertEquals("WIKI",service.createJob(draft).getKnowledgeMode());
        draft.setKnowledgeMode("RAG");clearInvocations(jobs);assertThrows(IllegalArgumentException.class,()->service.createJob(draft));verify(jobs,never()).save(any());
        org.springframework.test.util.ReflectionTestUtils.setField(draft,"knowledgeMode",null);assertEquals("TEXT",draft.getKnowledgeMode());
    }
    @Test void executionLanesSeparateJobsModesAndInstances() {
        var a=new SupplierJob();a.setId(1L);a.setInstanceId(7L);var b=new SupplierJob();b.setId(2L);b.setInstanceId(7L);
        assertNotEquals(SupplierJobService.supplierLane(a),SupplierJobService.supplierLane(b));
        String text=SupplierJobService.supplierLane(a);a.setKnowledgeMode("WIKI");assertNotEquals(text,SupplierJobService.supplierLane(a));
        String firstInstance=SupplierJobService.supplierLane(a);a.setInstanceId(8L);assertNotEquals(firstInstance,SupplierJobService.supplierLane(a));
    }
}
