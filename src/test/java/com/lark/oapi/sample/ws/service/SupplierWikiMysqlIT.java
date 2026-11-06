package com.lark.oapi.sample.ws.service;

import com.google.gson.*;
import com.lark.oapi.sample.ws.model.entity.*;
import com.lark.oapi.sample.ws.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Explicit integration suite: requires an isolated MySQL schema. The model alone is stubbed. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.profiles.active=mysql","spring.datasource.url=${TEST_MYSQL_URL}","spring.datasource.username=${TEST_MYSQL_USER}",
    "spring.datasource.password=${TEST_MYSQL_PASSWORD}","feishu.kb-dir=${TEST_KB_DIR}"})
@DirtiesContext
class SupplierWikiMysqlIT {
    @Autowired SupplierJobService jobs;
    @Autowired SupplierKbService kb;
    @Autowired SupplierWikiService wiki;
    @Autowired SupplierKnowledgeService knowledge;
    @Autowired WorkflowTemplateRepository templates;
    @Autowired BotInstanceRepository instances;
    @Autowired TestRestTemplate http;
    @MockitoBean ClaudeCodeService model;
    static void await(java.util.function.BooleanSupplier condition) throws Exception {long end=System.nanoTime()+10_000_000_000L;while(!condition.getAsBoolean()&&System.nanoTime()<end)Thread.sleep(20);assertTrue(condition.getAsBoolean());}
    SupplierJob create(String mode,String supplier,Long instance,Long template){var draft=new SupplierJob();draft.setKnowledgeMode(mode);draft.setSupplierName("integration fixture");draft.setSupplierCode(supplier);draft.setInstanceId(instance);draft.setTemplateId(template);draft.setLibraryPath("/workspace");return jobs.createJob(draft);}
    @Test void mysqlBackedCompilationDispatchAndSourceVersioningWorkThroughRealApis() throws Exception {
        var instance=new BotInstance();instance.setName("isolated integration fixture");instances.save(instance);
        Long template=templates.findAll().stream().filter(t->"API".equals(t.getMode())).findFirst().orElseThrow().getId();
        var wikiJob=create("WIKI","it-supplier",instance.getId(),template);var textJob=create("TEXT","it-supplier",instance.getId(),template);
        var entry=kb.add("it-supplier","chat","原始业务上下文。".repeat(5000)+SupplierWikiTest.QUOTE,"商务约束");
        String original=kb.readMd(entry.getId()),sourceId="e"+entry.getId();
        when(model.chatInLane(anyString(),eq(instance.getId()),anyString(),anyString(),nullable(String.class),any(TurnListener.class))).thenAnswer(call->{
            String lane=call.getArgument(0);if(lane.startsWith("wiki-source"))return new Gson().toJson(Map.of("summary","商务规则","facts",List.of(Map.of("statement",SupplierWikiTest.QUOTE,"quote",SupplierWikiTest.QUOTE))));
            if(lane.startsWith("wiki-synthesis"))return SupplierWikiTest.publication().toString().replace("\"sourceId\":\"e1\"","\"sourceId\":\""+sourceId+"\"");
            throw new IllegalStateException("lane 只应承载 Wiki 编译，出现: "+lane);
        });
        // 11-05：runNode 走实例主会话（与飞书对话同 sessionID/队列/上下文），不再走独立 lane
        when(model.chat(eq(instance.getId()),anyString(),anyString(),anyString(),nullable(String.class),any(TurnListener.class))).thenAnswer(call->{
            var result=new TurnResult("## Finish\n分析完成",false);result.inputTokens=21;result.outputTokens=11;result.cacheReadTokens=5;result.costUsd=0.001;
            call.getArgument(5,TurnListener.class).onResultAvailable(result);return result.text;
        });
        var revision=wiki.build(wikiJob);await(()->!"BUILDING".equals(wiki.status(wikiJob).get("status")));assertEquals("READY",wiki.status(wikiJob).get("status"),revision.getError());
        assertEquals(200,http.getForEntity("/api/supplier-jobs/"+wikiJob.getId()+"/wiki",String.class).getStatusCode().value());
        String page=http.getForObject("/api/supplier-jobs/"+wikiJob.getId()+"/wiki/revisions/"+revision.getId()+"/pages/constraints",String.class);assertTrue(page.contains("../raw/"+sourceId+".md"));
        jobs.runNode(wikiJob.getId(),"n1","");await(()->!knowledge.runs(wikiJob.getId()).isEmpty()&&"COMPLETED".equals(knowledge.runs(wikiJob.getId()).get(0).getStatus()));
        jobs.runNode(textJob.getId(),"n1","");await(()->!knowledge.runs(textJob.getId()).isEmpty()&&"COMPLETED".equals(knowledge.runs(textJob.getId()).get(0).getStatus()));
        var w=knowledge.runs(wikiJob.getId()).get(0);var t=knowledge.runs(textJob.getId()).get(0);
        assertEquals("WIKI",w.getMode());assertEquals("TEXT",t.getMode());assertEquals(w.getSourceDigest(),t.getSourceDigest());
        assertTrue(t.getKnowledgeChars()>w.getKnowledgeChars()*5);assertEquals(21,w.getInputTokens());assertEquals(5,w.getCacheReadTokens());
        assertTrue(w.getPagesJson().contains("constraints"));assertNotEquals(SupplierJobService.supplierLane(wikiJob),SupplierJobService.supplierLane(textJob));
        kb.saveMd(entry.getId(),original+"\n新增约定需要重新编译。\n");assertThrows(IllegalArgumentException.class,()->wiki.context(wikiJob,"probe"));
        assertEquals(original,wiki.read(wikiJob,revision.getId(),sourceId,true));
        var other=create("TEXT","different-supplier",instance.getId(),template);
        assertEquals(400,http.getForEntity("/api/supplier-jobs/"+other.getId()+"/wiki/revisions/"+revision.getId()+"/sources/"+sourceId,String.class).getStatusCode().value());
    }
}
