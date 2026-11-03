package com.lark.oapi.sample.ws.service;
import com.lark.oapi.sample.ws.model.entity.*;
import com.lark.oapi.sample.ws.repository.SupplierKbPageRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class SupplierWikiSourcesTest {
    @TempDir Path dir;
    @Test void sitePagesAreReadInFullNotJustTheIndex() throws Exception {
        var kb=mock(SupplierKbService.class);var pages=mock(SupplierKbPageRepository.class);var job=new SupplierJob();job.setSupplierCode("A");when(kb.kbCode(job)).thenReturn("A");
        Path index=dir.resolve("index.md"),body=dir.resolve("p1.md");Files.writeString(index,"站点目录");Files.writeString(body,"真正的接口字段在页面正文里，不能只编译目录。");
        var entry=new SupplierKbEntry();entry.setId(1L);entry.setStatus("DONE");entry.setMdPath(index.toString());entry.setMetaJson("{\"site\":true}");
        var page=new SupplierKbPage();page.setStatus("loaded");page.setMdPath(body.toString());
        org.springframework.test.util.ReflectionTestUtils.setField(page,"id",2L);
        when(kb.list("A")).thenReturn(List.of(entry));when(pages.findByEntryIdOrderByIdAsc(1L)).thenReturn(List.of(page));
        var service=new SupplierWikiSources(kb,pages);var first=service.snapshot(job);
        assertEquals(2,first.sources().size());assertTrue(first.sources().stream().anyMatch(s->s.content().contains("真正的接口字段")));
        Files.writeString(body,"新的接口字段与旧版不同。");assertNotEquals(first.digest(),service.snapshot(job).digest());
        page.setStatus("failed");assertThrows(IllegalArgumentException.class,()->service.snapshot(job));
    }
}
