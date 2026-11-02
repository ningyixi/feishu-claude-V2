package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupplierReferenceContextTest {
    @TempDir Path dir;
    @Test void loadsCompanySourcesAndBlocksMissingMaterials() throws Exception {
        var service = new SupplierReferenceContext(mock(SupplierKbService.class));
        var job = new SupplierJob();
        assertThrows(IllegalArgumentException.class, () -> service.load(job, true));
        Path skill = Files.createDirectories(dir.resolve("skill"));
        Files.writeString(skill.resolve("SKILL.md"), "use company supplier conventions");
        job.setSkillsJson(new com.google.gson.Gson().toJson(new String[]{skill.toString()}));
        Path spec = dir.resolve("spec.md");
        Files.writeString(spec, "all cancellation requests need booking ID");
        job.setSpecDocPath(spec.toString());
        assertThrows(IllegalArgumentException.class, () -> service.load(job, true));
        Path prompt = dir.resolve("prompt.md");
        Files.writeString(prompt, "produce a prompt with validation criteria");
        job.setPromptSkillPath(prompt.toString());
        String context = service.load(job, true);
        assertTrue(context.contains("booking ID"));
        assertTrue(context.contains("validation criteria"));
        assertTrue(context.contains("supplier conventions"));
        assertTrue(context.contains("Trellis"));
        assertTrue(context.contains(SupplierReferenceContext.digest("all cancellation requests need booking ID")));
        Files.delete(spec);
        assertThrows(IllegalArgumentException.class, () -> service.load(job, true));
    }
}
