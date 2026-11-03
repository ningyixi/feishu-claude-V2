package com.lark.oapi.sample.ws.service;

import com.google.gson.*;
import com.lark.oapi.sample.ws.model.entity.*;
import com.lark.oapi.sample.ws.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SupplierWikiTest {
    @TempDir Path root;
    static final String QUOTE="取消订单必须传供应商订单号，不能传内部流水号。";
    final SupplierKbService kb=mock(SupplierKbService.class);
    final SupplierKbPageRepository pages=mock(SupplierKbPageRepository.class);
    final SupplierWikiRevisionRepository revisions=mock(SupplierWikiRevisionRepository.class);
    final BotInstanceRepository instances=mock(BotInstanceRepository.class);
    final ClaudeCodeService model=mock(ClaudeCodeService.class);
    final Map<Long,SupplierWikiRevision> saved=new ConcurrentHashMap<>();
    final AtomicLong ids=new AtomicLong();

    SupplierJob job(){var job=new SupplierJob();job.setId(11L);job.setSupplierCode("supplier-A");job.setKnowledgeMode("WIKI");job.setInstanceId(7L);return job;}
    SupplierWikiService service(String content) throws Exception {
        Path file=root.resolve("original.md");Files.writeString(file,content);
        var entry=new SupplierKbEntry();entry.setId(1L);entry.setTitle("商务取消约定");entry.setType("chat");entry.setStatus("DONE");entry.setMdPath(file.toString());
        when(kb.kbCode(any())).thenAnswer(call->call.getArgument(0,SupplierJob.class).getSupplierCode());
        when(kb.list("supplier-A")).thenReturn(List.of(entry));
        var source=new SupplierWikiSources(kb,pages);
        var instance=new BotInstance();instance.setId(7L);when(instances.findById(7L)).thenReturn(Optional.of(instance));
        when(revisions.save(any())).thenAnswer(call->{var row=call.getArgument(0,SupplierWikiRevision.class);if(row.getId()==null)ReflectionTestUtils.setField(row,"id",ids.incrementAndGet());saved.put(row.getId(),row);return row;});
        when(revisions.findFirstByKbCodeOrderByIdDesc(anyString())).thenAnswer(call->saved.values().stream().filter(r->r.getKbCode().equals(call.getArgument(0))).max(Comparator.comparing(SupplierWikiRevision::getId)));
        when(revisions.findTop20ByKbCodeOrderByIdDesc(anyString())).thenAnswer(call->saved.values().stream().sorted(Comparator.comparing(SupplierWikiRevision::getId).reversed()).toList());
        when(revisions.findById(anyLong())).thenAnswer(call->Optional.ofNullable(saved.get(call.getArgument(0))));
        when(model.chatInLane(anyString(),eq(7L),anyString(),anyString(),nullable(String.class),any(TurnListener.class))).thenAnswer(call->{
            String request=call.getArgument(2);
            if(call.getArgument(0,String.class).startsWith("wiki-source")) {
                assertTrue(request.contains(content),"Extraction must receive the whole source, including its end");
                return new Gson().toJson(Map.of("summary","取消业务限制","facts",List.of(Map.of("statement",QUOTE,"quote",QUOTE))));
            }
            return publication().toString();
        });
        return new SupplierWikiService(source,revisions,instances,model,kb,root.resolve("kb").toString());
    }
    static JsonObject publication(){return JsonParser.parseString("""
        {"pages":[
          {"id":"constraints","title":"关键业务约束","kind":"constraints","summary":"所有阶段必读的商务约定","stages":["all"],"content":"取消订单必须传供应商订单号，不能传内部流水号。详情见 [取消接口](cancel-api.md)。","evidence":[{"sourceId":"e1","quote":"取消订单必须传供应商订单号，不能传内部流水号。"}]},
          {"id":"cancel-api","title":"取消接口","kind":"api","summary":"实现或验证取消操作时阅读","stages":["probe","codegen"],"content":"执行取消前核对 [业务约束](constraints.md)，完整参数读取原文。","evidence":[{"sourceId":"e1","quote":"取消订单必须传供应商订单号，不能传内部流水号。"}]}
        ],"critical":["constraints"],"entrypoints":{"all":["constraints"],"analysis":["constraints"],"probe":["cancel-api"],"codegen":["cancel-api"]}}
        """).getAsJsonObject();}
    static void done(SupplierWikiRevision revision) throws Exception {long deadline=System.nanoTime()+10_000_000_000L;while("BUILDING".equals(revision.getStatus()) && System.nanoTime()<deadline)Thread.sleep(10);assertNotEquals("BUILDING",revision.getStatus());}

    @Test void buildsCompactLinkedKnowledgePreservesRawAndCachesExtraction() throws Exception {
        String original="原始材料上下文。".repeat(6000)+QUOTE;var wiki=service(original);var job=job();
        var first=wiki.build(job);done(first);assertEquals("READY",first.getStatus(),first.getError());
        var pack=wiki.context(job,"probe");assertTrue(pack.body().contains(QUOTE));assertTrue(pack.body().length()<original.length()/5);
        assertTrue(pack.pages().containsAll(List.of("constraints","cancel-api")));
        assertEquals(original,wiki.read(job,first.getId(),"e1",true));assertEquals(original,Files.readString(root.resolve("original.md")));
        assertTrue(wiki.read(job,first.getId(),"constraints",false).contains("../raw/e1.md"));
        assertTrue(wiki.read(job,first.getId(),"index",false).contains("index-api.md"));
        var second=wiki.build(job);done(second);assertEquals("READY",second.getStatus(),second.getError());
        verify(model,times(3)).chatInLane(anyString(),anyLong(),anyString(),anyString(),nullable(String.class),any(TurnListener.class));
        verify(model,times(3)).destroyLane(anyString());
    }
    @Test void sourceChangesBlockExecutionAndFixedRevisionCannotLeakToAnotherSupplier() throws Exception {
        var wiki=service(QUOTE);var job=job();var row=wiki.build(job);done(row);assertEquals("READY",row.getStatus(),row.getError());
        Files.writeString(root.resolve("original.md"),QUOTE+"新决策：取消失败要查单。");
        assertThrows(IllegalArgumentException.class,()->wiki.context(job,"codegen"));assertEquals("STALE",wiki.status(job).get("status"));
        assertEquals(QUOTE,wiki.read(job,row.getId(),"e1",true),"Published raw version must remain immutable");
        var other=job();other.setSupplierCode("supplier-B");assertThrows(IllegalArgumentException.class,()->wiki.read(other,row.getId(),"e1",true));
        assertThrows(IllegalArgumentException.class,()->wiki.read(job,row.getId(),"../original",false));
    }
    @Test void schemaChangesInvalidateWikiWithoutChangingTheRawCorpus() throws Exception {
        var wiki=service(QUOTE);var job=job();var first=wiki.build(job);done(first);assertEquals("READY",first.getStatus(),first.getError());
        String corpus=first.getSourceDigest();wiki.saveInstructions(job,"取消与退款分别建页，保留商务限制。");
        assertEquals("STALE",wiki.status(job).get("status"));assertThrows(IllegalArgumentException.class,()->wiki.context(job,"probe"));
        var second=wiki.build(job);done(second);assertEquals("READY",second.getStatus(),second.getError());assertEquals(corpus,second.getSourceDigest());
        assertEquals(QUOTE,Files.readString(root.resolve("original.md")));
        verify(model,times(4)).chatInLane(anyString(),anyLong(),anyString(),anyString(),nullable(String.class),any(TurnListener.class));
    }
    @Test void modifiedPublishedFilesFailIntegrityChecks() throws Exception {
        var wiki=service(QUOTE);var job=job();var row=wiki.build(job);done(row);
        Files.writeString(Path.of(row.getDirectory()).resolve("wiki/constraints.md"),"tampered content");
        assertThrows(IllegalArgumentException.class,()->wiki.context(job,"probe"));
        assertThrows(IllegalArgumentException.class,()->wiki.read(job,row.getId(),"constraints",false));
    }
    @Test void validatorRejectsInventedEvidenceBrokenLinksMissingSourcesAndOverBudgetEntrypoints() {
        var source=new SupplierWikiSources.Source("e1","title","chat","all","",SupplierReferenceContext.digest(QUOTE),QUOTE);
        var snapshot=new SupplierWikiSources.Snapshot("supplier-A","digest",List.of(source),List.of(),QUOTE.length());
        assertDoesNotThrow(()->SupplierWikiContract.publication(publication(),snapshot));
        var invented=publication();invented.getAsJsonArray("pages").get(0).getAsJsonObject().getAsJsonArray("evidence").get(0).getAsJsonObject().addProperty("quote","这是原文不存在的业务结论");
        assertThrows(IllegalArgumentException.class,()->SupplierWikiContract.publication(invented,snapshot));
        var broken=publication();broken.getAsJsonArray("pages").get(0).getAsJsonObject().addProperty("content","详情见 [不存在](missing.md)");
        assertThrows(IllegalArgumentException.class,()->SupplierWikiContract.publication(broken,snapshot));
        var escaped=publication();escaped.getAsJsonArray("pages").get(0).getAsJsonObject().addProperty("id","../escape");
        assertThrows(IllegalArgumentException.class,()->SupplierWikiContract.publication(escaped,snapshot));
        var large=publication();
        for(int i=0;i<3;i++){var page=publication().getAsJsonArray("pages").get(0).getAsJsonObject().deepCopy();page.addProperty("id","large-"+i);page.addProperty("content","x".repeat(12000));large.getAsJsonArray("pages").add(page);large.getAsJsonArray("critical").add("large-"+i);}
        assertThrows(IllegalArgumentException.class,()->SupplierWikiContract.publication(large,snapshot));
        var extra=new SupplierWikiSources.Source("e2","other","note","all","","hash","第二份不能遗漏的来源");
        assertThrows(IllegalArgumentException.class,()->SupplierWikiContract.publication(publication(),new SupplierWikiSources.Snapshot("A","x",List.of(source,extra),List.of(),30)));
    }
    @Test void changedSourcesDuringCompilationCannotPublishAnObsoleteRevision() throws Exception {
        var wiki=service(QUOTE);var job=job();var entered=new java.util.concurrent.CountDownLatch(1);var release=new java.util.concurrent.CountDownLatch(1);
        when(model.chatInLane(anyString(),anyLong(),anyString(),anyString(),nullable(String.class),any(TurnListener.class))).thenAnswer(call->{
            if(call.getArgument(0,String.class).startsWith("wiki-source")){entered.countDown();release.await(5,java.util.concurrent.TimeUnit.SECONDS);return new Gson().toJson(Map.of("summary","summary","facts",List.of(Map.of("statement",QUOTE,"quote",QUOTE))));}
            return publication().toString();
        });
        var row=wiki.build(job);assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS));Files.writeString(root.resolve("original.md"),QUOTE+"新增政策");release.countDown();done(row);
        assertEquals("FAILED",row.getStatus());assertTrue(row.getError().contains("来源发生变化"));assertThrows(IllegalArgumentException.class,()->wiki.read(job,row.getId(),"constraints",false));
    }
    @Test void compilationFailureDoesNotPublishOrFallBackToText() throws Exception {
        var wiki=service(QUOTE);var job=job();when(model.chatInLane(anyString(),anyLong(),anyString(),anyString(),nullable(String.class),any(TurnListener.class))).thenReturn("❌ provider unavailable");
        var row=wiki.build(job);done(row);assertEquals("FAILED",row.getStatus());assertThrows(IllegalArgumentException.class,()->wiki.context(job,"analysis"));
    }
}
