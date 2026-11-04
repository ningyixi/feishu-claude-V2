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
        // 新契约：只给“位置 + SHA-256 + 让 agent 自己 Read”，绝不内联正文（否则大 skill/spec 撑爆提示词）
        assertTrue(context.contains(spec.getFileName().toString()) || context.contains(spec.toString()), "应含 spec 路径");
        assertTrue(context.contains(skill.resolve("SKILL.md").toString()), "应含 Skill 路径");
        assertTrue(context.contains(prompt.getFileName().toString()) || context.contains(prompt.toString()), "应含提示词 Skill 路径");
        assertTrue(context.contains("Read"), "应指示 agent 用 Read 读取");
        assertTrue(context.contains("Trellis"));
        assertTrue(context.contains(SupplierReferenceContext.digest("all cancellation requests need booking ID")), "应含 spec 完整性 SHA");
        assertFalse(context.contains("booking ID"), "不应把 spec 正文内联进提示词");
        Files.delete(spec);
        assertThrows(IllegalArgumentException.class, () -> service.load(job, true));
    }
}
