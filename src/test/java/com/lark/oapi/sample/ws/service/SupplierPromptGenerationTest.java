package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.SupplierJobRepository;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupplierPromptGenerationTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
    @Test void generatedPromptCannotDropOriginalContextAndIsArchived() throws Exception {
        var jobs = mock(SupplierJobRepository.class);
        var instances = mock(BotInstanceRepository.class);
        var agent = mock(ClaudeCodeService.class);
        var kb = mock(SupplierKbService.class);
        var job = new SupplierJob(); job.setId(1L); job.setInstanceId(2L);
        java.nio.file.Files.createDirectories(root.resolve(".trellis"));
        java.nio.file.Files.writeString(root.resolve(".trellis/workflow.md"), "workflow");
        job.setLibraryPath(root.toString());
        var instance = new BotInstance(); instance.setId(2L);
        when(jobs.findById(1L)).thenReturn(Optional.of(job));
        when(instances.findById(2L)).thenReturn(Optional.of(instance));
        var service = spy(new SupplierJobService(jobs, null, null, instances, agent, kb, null));
        doReturn("mandatory original supplier context").when(service).codegenPrompt(1L);
        when(agent.chat(eq(2L), eq("supplier-prompt-1"), contains("mandatory original supplier context"),
                isNull(), anyString(), nullable(String.class), any(TurnListener.class))).thenReturn("generated development instructions");
        String prompt = service.generateCodegenPrompt(1L);
        assertTrue(prompt.contains("generated development instructions"));
        assertTrue(prompt.contains("mandatory original supplier context"));
        assertEquals(SupplierReferenceContext.digest("mandatory original supplier context"), job.getGeneratedPromptDigest());
        verify(kb).archiveArtifact(job, "generated-prompt", "公司 Skill 生成的对接提示词", prompt);
    }
}
