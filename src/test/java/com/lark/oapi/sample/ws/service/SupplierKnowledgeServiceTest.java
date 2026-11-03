package com.lark.oapi.sample.ws.service;
import com.lark.oapi.sample.ws.model.entity.*;
import com.lark.oapi.sample.ws.repository.SupplierKnowledgeRunRepository;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SupplierKnowledgeServiceTest {
    @Test void wikiFailureNeverCallsTheTextFallback() {
        var kb=mock(SupplierKbService.class);var wiki=mock(SupplierWikiService.class);var source=mock(SupplierWikiSources.class);var runs=mock(SupplierKnowledgeRunRepository.class);
        var job=new SupplierJob();job.setKnowledgeMode("WIKI");when(wiki.context(job,"probe")).thenThrow(new IllegalArgumentException("stale"));
        var service=new SupplierKnowledgeService(kb,wiki,source,runs);
        assertThrows(IllegalArgumentException.class,()->service.context(job,"probe"));verifyNoInteractions(kb);
    }
    @Test void textControlDoesNotAcquireWikiReadinessRequirements() {
        var kb=mock(SupplierKbService.class);var wiki=mock(SupplierWikiService.class);var source=mock(SupplierWikiSources.class);var runs=mock(SupplierKnowledgeRunRepository.class);
        var job=new SupplierJob();when(kb.kbCode(job)).thenReturn("A");when(kb.contextText("A","probe")).thenReturn("existing text context");
        when(source.snapshot(job)).thenThrow(new IllegalArgumentException("unrelated unparsed source"));
        var pack=new SupplierKnowledgeService(kb,wiki,source,runs).context(job,"probe");
        assertEquals("TEXT",pack.mode());assertEquals("existing text context",pack.body());verifyNoInteractions(wiki);
    }
    @Test void actualUsageIsRecordedSeparatelyFromCharacterCounts() {
        var repo=mock(SupplierKnowledgeRunRepository.class);var service=new SupplierKnowledgeService(null,null,null,repo);var job=new SupplierJob();job.setId(2L);
        when(repo.save(any())).thenAnswer(call->{var row=call.getArgument(0,SupplierKnowledgeRun.class);org.springframework.test.util.ReflectionTestUtils.setField(row,"id",1L);return row;});
        var pack=new SupplierKnowledgeService.Pack("WIKI","small context","corpus",4L,90000,List.of("constraints"));
        Long id=service.record(job,"n2","probe",pack,"full request");var capture=org.mockito.ArgumentCaptor.forClass(SupplierKnowledgeRun.class);verify(repo).save(capture.capture());var row=capture.getValue();
        when(repo.findById(id)).thenReturn(Optional.of(row));var result=new TurnResult("answer",false);result.inputTokens=31;result.outputTokens=9;result.cacheReadTokens=400;result.cacheWriteTokens=20;
        service.finish(id,result,"answer");assertEquals(13,row.getKnowledgeChars());assertEquals(31,row.getInputTokens());assertEquals(400,row.getCacheReadTokens());assertEquals("COMPLETED",row.getStatus());
    }
}
