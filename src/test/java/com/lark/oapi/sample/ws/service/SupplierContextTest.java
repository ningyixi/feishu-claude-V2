package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.SupplierKbEntry;
import com.lark.oapi.sample.ws.repository.SupplierKbEntryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupplierContextTest {
    @TempDir Path dir;

    @Test void deliversTextAndRejectsUnresolvedMissingAndOversizedMaterial() throws Exception {
        var repo = mock(SupplierKbEntryRepository.class);
        var service = new SupplierKbService(repo, null, null, dir.toString());
        var entry = new SupplierKbEntry();
        entry.setId(1L);
        entry.setTitle("商务约定");
        entry.setType("chat");
        entry.setStatus("DONE");
        Path file = dir.resolve("context.md");
        Files.writeString(file, "取消订单必须使用供应商订单号");
        entry.setMdPath(file.toString());
        when(repo.findByKbCodeOrderByCreatedAtAsc("A")).thenReturn(List.of(entry));
        when(repo.findByKbCodeOrderByCreatedAtAsc("B")).thenReturn(List.of());
        String content = service.contextText("A");
        assertTrue(content.contains("取消订单必须使用供应商订单号"));
        String receipt = service.snapshotDelivery("A", content);
        assertTrue(receipt.contains(SupplierReferenceContext.digest(content)));
        assertTrue(Files.walk(dir).anyMatch(p -> p.getFileName().toString().equals(SupplierReferenceContext.digest(content) + ".md")));
        assertFalse(service.contextText("B").contains("取消订单必须使用供应商订单号"));
        entry.setContextStage("probe");
        entry.setStatus("RAW");
        assertFalse(service.contextText("A", "codegen").contains("取消订单必须使用供应商订单号"));
        entry.setContextStage("all");
        assertThrows(IllegalArgumentException.class, () -> service.contextText("A"));
        entry.setStatus("DONE");
        Files.writeString(file, "a".repeat(120001));
        assertThrows(IllegalArgumentException.class, () -> service.contextText("A"));
        Files.delete(file);
        assertThrows(IllegalArgumentException.class, () -> service.contextText("A"));
    }
}
