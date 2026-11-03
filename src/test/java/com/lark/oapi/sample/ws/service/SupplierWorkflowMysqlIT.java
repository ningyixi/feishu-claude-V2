package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.*;
import com.lark.oapi.sample.ws.repository.*;
import org.flowable.engine.ProcessEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.profiles.active=mysql","spring.datasource.url=${TEST_MYSQL_URL}","spring.datasource.username=${TEST_MYSQL_USER}",
    "spring.datasource.password=${TEST_MYSQL_PASSWORD}","feishu.kb-dir=${TEST_KB_DIR}","feishu.workflow.worker-enabled=false"})
@DirtiesContext
class SupplierWorkflowMysqlIT {
    @Autowired SupplierWorkflowService flow;
    @Autowired SupplierWorkflowWorker worker;
    @Autowired SupplierWorkflowFiles uploads;
    @Autowired SupplierJobService jobs;
    @Autowired SupplierKbService kb;
    @Autowired SupplierJobRepository jobRepo;
    @Autowired SupplierWikiService wiki;
    @Autowired SupplierWikiSources sources;
    @Autowired SupplierJobNodeRepository nodes;
    @Autowired SupplierWorkflowAttemptRepository attempts;
    @Autowired WorkflowTemplateRepository templates;
    @Autowired BotInstanceRepository instances;
    @Autowired SupplierWorkflowNoticeRepository notices;
    @Autowired ProcessEngine engine;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired TestRestTemplate http;
    @MockitoBean ClaudeCodeService model;
    @MockitoBean FeishuBotService feishu;
    SupplierJob create(String json) {
        var template=new WorkflowTemplate();template.setName("workflow integration");template.setMode("API");template.setNodesJson(json);templates.save(template);
        var instance=new BotInstance();instance.setName("fixture");instance.setEnabled(true);instance.setAuthorizedOpenId("ou_workflowrecipient01");instances.save(instance);
        var draft=new SupplierJob();draft.setSupplierName("fixture");draft.setSupplierCode("flow_"+UUID.randomUUID().toString().substring(0,8));draft.setTemplateId(template.getId());draft.setInstanceId(instance.getId());return jobs.createJob(draft);
    }
    static final String STEPS="""
      [{"id":"m","type":"manual","title":"补充业务材料","description":"上传约束并确认"},
       {"id":"a","type":"agent","title":"业务分析","contextStage":"analysis","prompt":"阅读 {docs}"},
       {"id":"z","type":"manual","title":"最终确认"}]
      """;
    @SuppressWarnings("unchecked") List<Map<String,Object>> pending(Long job){return (List<Map<String,Object>>)flow.state(job).getOrDefault("tasks",List.of());}
    Map<String,Object> task(Long job){var list=pending(job);assertEquals(1,list.size(),flow.state(job).toString());return list.getFirst();}
    String id(Long job){return task(job).get("id").toString();}
    void dispatch(Long run){for(var a:flow.plan(run))worker.dispatch(a);}
    void awaitTask(Long run,Long job,String kind) throws Exception {
        long end=System.nanoTime()+10_000_000_000L;
        while(System.nanoTime()<end){dispatch(run);var tasks=pending(job);if(tasks.size()==1 && kind.equals(tasks.getFirst().get("kind")))return;Thread.sleep(25);}
        fail(flow.state(job).toString());
    }
    @Test void fullHumanFileBlockerReviewReworkAndCompletionWithDurableHistory() throws Exception {
        var job=create(STEPS);var run=flow.start(job.getId(),"",true);String manual=id(job.getId());
        assertThrows(IllegalArgumentException.class,()->flow.start(job.getId(),"",false));
        assertThrows(IllegalArgumentException.class,()->flow.respond(job.getId(),manual,"SUBMIT",""));
        assertEquals(400,http.postForEntity("/api/supplier-jobs/"+job.getId()+"/nodes/m/complete",Map.of(),String.class).getStatusCode().value());
        var upload=uploads.upload(job.getId(),manual,new MockMultipartFile("file","商务约定.md","text/markdown","订单取消必须携带供应商订单号。".getBytes(StandardCharsets.UTF_8)));
        assertTrue(kb.readMd(upload.entryId).contains("供应商订单号"));
        var other=create(STEPS);flow.start(other.getId(),"",false);
        assertThrows(IllegalArgumentException.class,()->uploads.upload(other.getId(),manual,new MockMultipartFile("file","x.txt","text/plain","foreign".getBytes())));
        assertThrows(IllegalArgumentException.class,()->uploads.upload(job.getId(),manual,new MockMultipartFile("file","x.exe","application/octet-stream",new byte[]{1})));
        var calls=new AtomicInteger();when(model.chatInLane(anyString(),eq(job.getInstanceId()),anyString(),anyString(),nullable(String.class),any(TurnListener.class))).thenAnswer(invocation->{
            int count=calls.incrementAndGet();if(count==1)return "【卡点】请确认环境是否为沙箱？";
            assertTrue(invocation.getArgument(2,String.class).contains(count==2?"沙箱":"补上取消说明"));return "分析完成：取消须携带供应商订单号。";
        });
        flow.respond(job.getId(),manual,"SUBMIT","");
        assertThrows(IllegalArgumentException.class,()->flow.respond(job.getId(),manual,"SUBMIT","duplicate"));
        awaitTask(run.id,job.getId(),"input");String input=id(job.getId());
        assertThrows(IllegalArgumentException.class,()->flow.feishuReply(job.getInstanceId(),"ou_otheruser0001","/供应商 "+input+" 答复 沙箱"));
        flow.feishuReply(job.getInstanceId(),run.recipient,"/供应商 "+input+" 答复 沙箱");
        awaitTask(run.id,job.getId(),"review");
        assertThrows(IllegalArgumentException.class,()->flow.respond(job.getId(),id(job.getId()),"REJECT",""));
        flow.respond(job.getId(),id(job.getId()),"REJECT","补上取消说明");
        awaitTask(run.id,job.getId(),"review");
        flow.respond(job.getId(),id(job.getId()),"APPROVE","可以继续");
        assertEquals("manual",task(job.getId()).get("kind"));
        flow.respond(job.getId(),id(job.getId()),"SUBMIT","已完成验收");flow.plan(run.id);
        assertEquals("COMPLETED",flow.state(job.getId()).get("status"));assertEquals(3,calls.get());
        assertEquals(3,attempts.findByRunIdOrderByIdDesc(run.id).size());assertEquals("COMPLETED",jobs.getJob(job.getId()).orElseThrow().getStatus());
        assertTrue(((List<?>)flow.state(job.getId()).get("history")).size()>=5);
    }
    @Test void pauseRecoveryCancellationAndNotificationRetries() throws Exception {
        var job=create(STEPS);var run=flow.start(job.getId(),"",true);var notice=notices.findTop100ByRunIdOrderByIdDesc(run.id).getFirst();
        doThrow(new IllegalStateException("offline")).when(feishu).sendWorkflowNotice(any(),any(),any(),any());worker.send(notice.id);
        var failed=notices.findById(notice.id).orElseThrow();assertEquals("PENDING",failed.status);assertEquals(1,failed.attempts);assertEquals("offline",failed.error);
        for(int i=0;i<5;i++)worker.send(notice.id);
        assertEquals("FAILED",notices.findById(notice.id).orElseThrow().status);assertEquals(6,notices.findById(notice.id).orElseThrow().attempts);
        flow.retryNotice(job.getId(),notice.id);
        doNothing().when(feishu).sendWorkflowNotice(any(),any(),any(),any());worker.send(notice.id);assertEquals("SENT",notices.findById(notice.id).orElseThrow().status);
        var uuids=org.mockito.ArgumentCaptor.forClass(String.class);verify(feishu,times(7)).sendWorkflowNotice(any(),any(),any(),uuids.capture());assertEquals(1,uuids.getAllValues().stream().distinct().count());
        flow.control(job.getId(),"PAUSE");assertThrows(IllegalArgumentException.class,()->flow.respond(job.getId(),id(job.getId()),"SUBMIT","paused"));assertTrue(flow.plan(run.id).isEmpty());
        flow.control(job.getId(),"RESUME");flow.respond(job.getId(),id(job.getId()),"SUBMIT","开始");
        Long attemptId=flow.plan(run.id).getFirst();assertNotNull(flow.claim(attemptId));assertNull(flow.claim(attemptId));
        flow.recoverInterrupted();assertEquals("input",task(job.getId()).get("kind"));assertTrue(task(job.getId()).get("question").toString().contains("不会盲目重放"));
        flow.control(job.getId(),"CANCEL");assertTrue(flow.plan(run.id).isEmpty());assertEquals("CANCELLED",flow.state(job.getId()).get("status"));
        verifyNoInteractions(model);
    }
    @Test void anotherEngineReadsPersistedWaitingTaskAndFailedGateKeepsApprovalOpen() throws Exception {
        var job=create("[{\"id\":\"p\",\"type\":\"agent\",\"title\":\"实测\",\"contextStage\":\"probe\",\"prompt\":\"{docs}\"}]");
        when(model.chatInLane(anyString(),eq(job.getInstanceId()),anyString(),anyString(),nullable(String.class),any(TurnListener.class))).thenReturn("仅有口头报告，没有真实工具执行证据");
        var run=flow.start(job.getId(),"",false);awaitTask(run.id,job.getId(),"review");String taskId=id(job.getId());
        var configuration=new org.flowable.spring.SpringProcessEngineConfiguration();configuration.setDataSource(dataSource);configuration.setTransactionManager(transactionManager);try(var connection=dataSource.getConnection()){configuration.setDatabaseCatalog(connection.getCatalog());}configuration.setDatabaseSchemaUpdate("false");configuration.setAsyncExecutorActivate(false);configuration.setHistory("audit");configuration.setDisableIdmEngine(true);configuration.setDisableEventRegistry(true);
        var reopened=configuration.buildProcessEngine();try {assertEquals(taskId,reopened.getTaskService().createTaskQuery().processInstanceId(run.processId).singleResult().getId());}finally{reopened.close();}
        assertThrows(IllegalArgumentException.class,()->flow.respond(job.getId(),taskId,"APPROVE","looks fine"));assertEquals(taskId,id(job.getId()));assertNotEquals("DONE",nodes.findByJobIdAndNodeId(job.getId(),"p").orElseThrow().getStatus());
        flow.control(job.getId(),"CANCEL");
    }
    @Test void wikiArmAutomaticallyCompilesBeforeDispatchingTheAgent() throws Exception {
        var job=create("[{\"id\":\"a\",\"type\":\"agent\",\"title\":\"业务分析\",\"contextStage\":\"analysis\",\"prompt\":\"{docs}\"}]");job.setKnowledgeMode("WIKI");jobRepo.save(job);
        kb.add(job.getSupplierCode(),"chat",SupplierWikiTest.QUOTE,"业务资料");
        String sourceId=sources.snapshot(job).sources().getFirst().id();
        when(model.chatInLane(anyString(),eq(job.getInstanceId()),anyString(),anyString(),nullable(String.class),any(TurnListener.class))).thenAnswer(invocation->{
            String lane=invocation.getArgument(0);if(lane.startsWith("wiki-source"))return new com.google.gson.Gson().toJson(Map.of("summary","约束","facts",List.of(Map.of("statement",SupplierWikiTest.QUOTE,"quote",SupplierWikiTest.QUOTE))));
            if(lane.startsWith("wiki-synthesis"))return SupplierWikiTest.publication().toString().replace("\"sourceId\":\"e1\"","\"sourceId\":\""+sourceId+"\"");
            assertTrue(invocation.getArgument(2,String.class).contains("供应商 LLM Wiki"));return "业务分析完成";
        });
        var run=flow.start(job.getId(),"",false);awaitTask(run.id,job.getId(),"review");assertEquals("READY",wiki.status(job).get("status"));assertEquals("SUCCEEDED",attempts.findByRunIdOrderByIdDesc(run.id).getFirst().status);
        flow.control(job.getId(),"CANCEL");
    }
    @Test void concurrentResponsesOnlyAdvanceOnce() throws Exception {
        var job=create(STEPS);var run=flow.start(job.getId(),"",false);String task=id(job.getId());
        try(var pool=Executors.newFixedThreadPool(2)){
            var start=new CountDownLatch(1);var success=new AtomicInteger();List<Future<?>> futures=new ArrayList<>();
            for(int i=0;i<2;i++)futures.add(pool.submit(()->{try{start.await();flow.respond(job.getId(),task,"SUBMIT","ready");success.incrementAndGet();}catch(IllegalArgumentException ignored){}catch(Exception ex){throw new RuntimeException(ex);}}));
            start.countDown();for(var f:futures)f.get(15,TimeUnit.SECONDS);assertEquals(1,success.get());
        }
        assertEquals(1,flow.plan(run.id).size());
    }
}
