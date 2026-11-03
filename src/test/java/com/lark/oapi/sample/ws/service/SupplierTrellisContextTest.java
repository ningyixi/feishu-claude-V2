package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class SupplierTrellisContextTest {
    @TempDir Path root;
    @Test void createsTaskSnapshotsAndKeepsHumanEdits() throws Exception {
        var job = new SupplierJob();
        job.setId(42L);
        job.setSupplierName("Example");
        Path library = Files.createDirectories(root.resolve("src/supplier"));
        job.setLibraryPath(library.toString());
        assertThrows(IllegalArgumentException.class, () -> SupplierTrellisContext.prepare(job, "first"));
        Files.createDirectories(root.resolve(".trellis"));
        Files.writeString(root.resolve(".trellis/workflow.md"), "workflow");
        String result = SupplierTrellisContext.prepare(job, "first");
        assertTrue(result.contains("supplier-42"));
        Path task = root.resolve(".trellis/tasks/supplier-42");
        assertTrue(Files.exists(task.resolve("task.json")));
        Path first = task.resolve("context-" + SupplierReferenceContext.digest("first") + ".md");
        Files.writeString(task.resolve("prd.md"), "human edited requirements");
        SupplierTrellisContext.prepare(job, "second");
        assertEquals("first", Files.readString(first));
        assertEquals("human edited requirements", Files.readString(task.resolve("prd.md")));
        String jsonl = Files.readString(task.resolve("implement.jsonl"));
        assertTrue(jsonl.contains(SupplierReferenceContext.digest("second")));
        assertFalse(jsonl.contains(SupplierReferenceContext.digest("first")));
    }
}
