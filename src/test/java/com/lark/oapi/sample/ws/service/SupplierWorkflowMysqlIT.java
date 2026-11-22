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
    /** 11-17-free-node-execution 夹具：n2 引用 {artifact:n1}（复刻内置模板 n2 的上游引用结构），验证乱序/降级执行 */
    static final String CHAIN="""
      [{"id":"n1","type":"agent","title":"接口映射分析","contextStage":"analysis","prompt":"分析 {docs}"},
       {"id":"n2","type":"agent","title":"接口响应整理","contextStage":"analysis","prompt":"基于上游报告：{artifact:n1} 整理响应示例"}]
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
        // T1（11-17-free-node-execution）语义变更：原断言此处为 400（托管工作的节点完成被 manualOnly 闸门拦截，
        // "请通过流程待办操作"）。T1 明确放开托管工作的节点级手动操作（run/answer/complete/artifact），闸门仅保留在
        // 工作信息编辑/删除且只拦 ACTIVE/PAUSED（T6）；引擎侧防重复派发由 plan() 的 SKIPPED 机制保证（T2，见
        // manualRunAndCompleteUnderActiveRunIsAllowedAndEngineSkipsRedispatch）。此处 400→200 是新语义的正向断言，非掩盖回归。
        assertEquals(200,http.postForEntity("/api/supplier-jobs/"+job.getId()+"/nodes/m/complete",Map.of(),String.class).getStatusCode().value());
        var upload=uploads.upload(job.getId(),manual,new MockMultipartFile("file","商务约定.md","text/markdown","订单取消必须携带供应商订单号。".getBytes(StandardCharsets.UTF_8)));
        assertTrue(kb.readMd(upload.entryId).contains("供应商订单号"));
        var other=create(STEPS);flow.start(other.getId(),"",false);
        assertThrows(IllegalArgumentException.class,()->uploads.upload(other.getId(),manual,new MockMultipartFile("file","x.txt","text/plain","foreign".getBytes())));
        assertThrows(IllegalArgumentException.class,()->uploads.upload(job.getId(),manual,new MockMultipartFile("file","x.exe","application/octet-stream",new byte[]{1})));
        var calls=new AtomicInteger();when(model.chat(eq(job.getInstanceId()),anyString(),anyString(),anyString(),nullable(String.class),any(TurnListener.class))).thenAnswer(invocation->{
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
        when(model.chat(eq(job.getInstanceId()),anyString(),anyString(),anyString(),nullable(String.class),any(TurnListener.class))).thenReturn("仅有口头报告，没有真实工具执行证据");
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
            // 存量夹具修复（非本任务回归）：11-06(087a7b3) 把综合 lane 改名为 "wiki-syn-<rev>-b<批>-a<次>"，
            // 本 stub 仍匹配旧前缀 "wiki-synthesis" → 必然抛 IllegalStateException → 批次全灭 → 编译 FAILED。
            // 对齐现行 lane 命名，恢复"Wiki 编译先于派发"的原有覆盖；生产代码零改动。
            String lane=invocation.getArgument(0);if(lane.startsWith("wiki-source"))return new com.google.gson.Gson().toJson(Map.of("summary","约束","facts",List.of(Map.of("statement",SupplierWikiTest.QUOTE,"quote",SupplierWikiTest.QUOTE))));
            if(lane.startsWith("wiki-syn"))return SupplierWikiTest.publication().toString().replace("\"sourceId\":\"e1\"","\"sourceId\":\""+sourceId+"\"");
            throw new IllegalStateException("lane 只应承载 Wiki 编译，出现: "+lane);
        });
        // 11-05：agent 节点执行走实例主会话（Wiki 编译仍走独立 lane）
        when(model.chat(eq(job.getInstanceId()),anyString(),anyString(),anyString(),nullable(String.class),any(TurnListener.class))).thenAnswer(invocation->{
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
    // ===== 11-17-free-node-execution：T1/T2/T3/T6/T7/T8 =====
    void awaitNode(Long job,String nodeId,java.util.function.Predicate<SupplierJobNode> ok) throws Exception {
        long end=System.nanoTime()+15_000_000_000L;
        while(System.nanoTime()<end){var n=nodes.findByJobIdAndNodeId(job,nodeId).orElseThrow();if(ok.test(n))return;Thread.sleep(25);}
        fail("节点 "+nodeId+" 超时未达期望状态: "+nodes.findByJobIdAndNodeId(job,nodeId).orElseThrow().getStatus());
    }
    @Test void manualRunAndCompleteUnderActiveRunIsAllowedAndEngineSkipsRedispatch() throws Exception {
        // AC1（T1）：ACTIVE 托管下直接手动执行/完成任意 agent 节点，不报"请通过流程待办操作"
        var job=create(CHAIN);var run=flow.start(job.getId(),"",false);
        var calls=new AtomicInteger();
        when(model.chat(eq(job.getInstanceId()),anyString(),anyString(),anyString(),nullable(String.class),any(TurnListener.class)))
            .thenAnswer(invocation->{calls.incrementAndGet();return "手动产物：接口映射完成";});
        assertEquals(200,http.postForEntity("/api/supplier-jobs/"+job.getId()+"/nodes/n1/run",Map.of("instruction",""),String.class).getStatusCode().value());
        awaitNode(job.getId(),"n1",n->"PENDING".equals(n.getStatus()) && !n.getArtifact().isBlank());
        assertEquals(1,calls.get());
        assertEquals(200,http.postForEntity("/api/supplier-jobs/"+job.getId()+"/nodes/n1/complete",Map.of(),String.class).getStatusCode().value());
        assertEquals("DONE",nodes.findByJobIdAndNodeId(job.getId(),"n1").orElseThrow().getStatus());
        // AC2（T2）：手动跑到 DONE 后引擎把该 exec_ 位置记 SKIPPED 并静默推进——不重派 n1、不覆盖产物、
        // 不留审批/补充待办（手动执行不推进流程待办）；token 前进后，尚未手动完成的 n2 由引擎照常接管
        assertTrue(flow.plan(run.id).isEmpty(),"已手动完成的节点不得再进派发队列");
        assertTrue(pending(job.getId()).isEmpty(),"跳过推进不得留下任何待办："+flow.state(job.getId()).get("activity"));
        var n1Ledger=attempts.findByRunIdAndNodeId(run.id,"n1");
        assertEquals(1,n1Ledger.size(),"n1 只允许一条 SKIPPED 台账，不得出现新的派发 attempt");
        assertEquals("SKIPPED",n1Ledger.getFirst().status);assertEquals("n1",n1Ledger.getFirst().nodeId);
        var queued=flow.plan(run.id);   // token 已静默前进到 n2 位置 → 引擎照常接管未手动完成的节点
        assertEquals(1,queued.size());
        assertEquals("n2",attempts.findById(queued.getFirst()).orElseThrow().nodeId,"引擎前进后派发的是尚未手动完成的 n2，而不是重派 n1");
        worker.dispatch(queued.getFirst());
        awaitNode(job.getId(),"n2",n->!n.getArtifact().isBlank());
        assertEquals(2,calls.get(),"只有 n2 被派发一次；n1 不得被引擎按 token 位置二次派发");
        assertEquals("手动产物：接口映射完成",nodes.findByJobIdAndNodeId(job.getId(),"n1").orElseThrow().getArtifact(),"n1 产物不被二次执行覆盖");
        flow.control(job.getId(),"CANCEL");
    }
    @Test void manualCompletionOfAllNodesConvergesRunToCompletedInsteadOfParkingForever() throws Exception {
        // AC13：ACTIVE 托管 job 把全部 agent 节点手动跑成 DONE（乱序）→ 引擎 SKIPPED + skipTrigger 静默推进，
        //   run 必须收敛 COMPLETED 而不是永久 ACTIVE。旧实现只落 SKIPPED 不 trigger：token 永远停在 exec_ 位置，
        //   run 悬挂 ACTIVE（无待办、无推进），而 T6 只对终态 run 放开工作编辑/删除闸门 → job 被锁死，
        //   必须先取消流程——等于把"被流程引擎挡死"的墙换个位置重砌。
        // AC14：引擎推进全程不得增加实例调用次数（skipTrigger 不派 attempt、不触发新 chat 轮次——T2 原始承诺）。
        var job=create(CHAIN);var run=flow.start(job.getId(),"",false);
        var put=new org.springframework.http.HttpEntity<>(Map.of("name","手动跑完的工作"));
        assertEquals(400,http.exchange("/api/supplier-jobs/"+job.getId(),org.springframework.http.HttpMethod.PUT,put,String.class).getStatusCode().value(),"对照前置：ACTIVE 托管时工作编辑闸门是关的");
        var calls=new AtomicInteger();
        when(model.chat(eq(job.getInstanceId()),anyString(),anyString(),anyString(),nullable(String.class),any(TurnListener.class)))
            .thenAnswer(invocation->{calls.incrementAndGet();return "手动产物 "+calls.get();});
        // 故意乱序：先跑 n2（此时 n1 无产物 → {artifact:n1} 走 T3 降级），再跑 n1
        for(String nodeId:List.of("n2","n1")){
            assertEquals(200,http.postForEntity("/api/supplier-jobs/"+job.getId()+"/nodes/"+nodeId+"/run",Map.of("instruction",""),String.class).getStatusCode().value());
            awaitNode(job.getId(),nodeId,n->"PENDING".equals(n.getStatus()) && !n.getArtifact().isBlank());
            assertEquals(200,http.postForEntity("/api/supplier-jobs/"+job.getId()+"/nodes/"+nodeId+"/complete",Map.of(),String.class).getStatusCode().value());
        }
        assertEquals(2,calls.get());
        // 引擎收敛：每轮 plan 把一个 SKIPPED 位置静默推进（无待办、无派发），直至实例自然结束 → COMPLETED + 完成通知
        long end=System.nanoTime()+15_000_000_000L;
        while(System.nanoTime()<end && !"COMPLETED".equals(flow.getRun(run.id).status)){
            assertTrue(flow.plan(run.id).isEmpty(),"跳过推进不得产生任何派发（AC14）");
            assertTrue(pending(job.getId()).isEmpty(),"手动执行不推进流程待办：不得出现审批/补充待办（AC13）："+flow.state(job.getId()).get("activity"));
            Thread.sleep(25);
        }
        assertEquals("COMPLETED",flow.getRun(run.id).status,"全部节点手动完成后 run 必须收敛 COMPLETED，而不是永久 ACTIVE");
        assertEquals(2,calls.get(),"AC14：引擎推进不得增加实例调用次数（不重复派发、不触发新 chat 轮次）");
        for(String nodeId:List.of("n1","n2")){
            var ledger=attempts.findByRunIdAndNodeId(run.id,nodeId);
            assertEquals(1,ledger.size(),nodeId+" 只允许一条 SKIPPED 台账");
            assertEquals("SKIPPED",ledger.getFirst().status);
        }
        assertEquals("手动产物 1",nodes.findByJobIdAndNodeId(job.getId(),"n2").orElseThrow().getArtifact(),"产物由手动执行产生，引擎推进不得覆盖");
        assertTrue(notices.findTop100ByRunIdOrderByIdDesc(run.id).stream().anyMatch(n->"COMPLETED".equals(n.kind)),"收敛时落完成通知");
        // 终态 run → T6 闸门放开：工作编辑（PUT）与删除（DELETE）都通过（400 → 200），无需先"取消流程"解锁
        assertDoesNotThrow(()->flow.assertLegacyWriteAllowed(job.getId()));
        assertEquals(200,http.exchange("/api/supplier-jobs/"+job.getId(),org.springframework.http.HttpMethod.PUT,put,String.class).getStatusCode().value());
        assertEquals(200,http.exchange("/api/supplier-jobs/"+job.getId(),org.springframework.http.HttpMethod.DELETE,null,String.class).getStatusCode().value());
        assertTrue(jobRepo.findById(job.getId()).isEmpty());
    }
    @Test void reworkRejectionStillRedispatchesAfterManualSkipRule() throws Exception {
        // T2 反向保护：驳回返工位置有引擎在先 attempt（engineHandledBefore）→ 照常再派，SKIPPED 规则不得误伤正常引擎闭环
        var job=create("[{\"id\":\"a\",\"type\":\"agent\",\"title\":\"业务分析\",\"contextStage\":\"analysis\",\"prompt\":\"阅读 {docs}\"}]");
        var calls=new AtomicInteger();
        when(model.chat(eq(job.getInstanceId()),anyString(),anyString(),anyString(),nullable(String.class),any(TurnListener.class)))
            .thenAnswer(invocation->{calls.incrementAndGet();return "第 "+calls.get()+" 版分析";});
        var run=flow.start(job.getId(),"",false);
        awaitTask(run.id,job.getId(),"review");
        flow.respond(job.getId(),id(job.getId()),"REJECT","补充取消场景");
        awaitTask(run.id,job.getId(),"review");   // 返工重新派发并再次到达审批 → 未被 SKIPPED 规则拦截
        assertEquals(2,calls.get());
        assertEquals("SUCCEEDED",attempts.findByRunIdOrderByIdDesc(run.id).getFirst().status);
        flow.respond(job.getId(),id(job.getId()),"APPROVE","");
        flow.plan(run.id);
        assertEquals("COMPLETED",flow.state(job.getId()).get("status"));
    }
    @Test void missingUpstreamArtifactDegradesInsteadOfBlocking() throws Exception {
        // AC3（T3）：n1 无产物时执行 n2 不再抛"请先完成上游任务"；prompt 显式声明未携带上下文，接口响应带缺失清单
        var job=create(CHAIN);
        var sent=new java.util.concurrent.atomic.AtomicReference<String>();
        when(model.chat(eq(job.getInstanceId()),anyString(),anyString(),anyString(),nullable(String.class),any(TurnListener.class)))
            .thenAnswer(invocation->{sent.set(invocation.getArgument(2));return "整理完成";});
        var res=http.postForEntity("/api/supplier-jobs/"+job.getId()+"/nodes/n2/run",Map.of("instruction",""),String.class);
        assertEquals(200,res.getStatusCode().value());
        assertTrue(res.getBody().contains("missingArtifacts") && res.getBody().contains("n1"),"接口响应需提示上游 n1 无产物");
        awaitNode(job.getId(),"n2",n->"PENDING".equals(n.getStatus()) && !n.getArtifact().isBlank());
        assertTrue(sent.get().contains("（引用的 n1 尚无产物，本次未携带该上下文）"),"prompt 必须显式声明，不得静默");
        assertFalse(sent.get().contains("{artifact:n1}"));
        assertTrue(nodes.findByJobIdAndNodeId(job.getId(),"n2").orElseThrow().getDeliveryManifest().contains("上游产物缺失"));
    }
    @Test void cancelledRunUnblocksJobEditAndDelete() {
        // AC6（T6）：托管判据 = 活着的 run。ACTIVE 仍拦工作信息编辑；CANCEL 后编辑/删除放行
        var job=create(STEPS);var run=flow.start(job.getId(),"",false);
        var put=new org.springframework.http.HttpEntity<>(Map.of("name","改名后的工作"));
        var blocked=http.exchange("/api/supplier-jobs/"+job.getId(),org.springframework.http.HttpMethod.PUT,put,String.class);
        assertEquals(400,blocked.getStatusCode().value());
        assertTrue(blocked.getBody().contains("流程引擎管理"),"ACTIVE 流程仍托管工作信息编辑");
        flow.control(job.getId(),"CANCEL");
        assertEquals(200,http.exchange("/api/supplier-jobs/"+job.getId(),org.springframework.http.HttpMethod.PUT,put,String.class).getStatusCode().value());
        assertEquals(200,http.exchange("/api/supplier-jobs/"+job.getId(),org.springframework.http.HttpMethod.DELETE,null,String.class).getStatusCode().value());
        assertEquals("CANCELLED",flow.getRun(run.id).status);
        assertTrue(jobRepo.findById(job.getId()).isEmpty());
    }
    @Test void deleteJobCascadesWorkflowCancellation() {
        // AC7（T7①）：删除带流程的工作 → Flowable 实例被取消、run 置 CANCELLED、不再参与推进（杜绝再造孤儿）
        var job=create(STEPS);var run=flow.start(job.getId(),"",true);String processId=run.processId;
        jobs.deleteJob(job.getId());
        assertEquals("CANCELLED",flow.getRun(run.id).status);
        assertEquals(0,engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(processId).count());
        assertTrue(flow.plan(run.id).isEmpty());
        assertFalse(flow.activeRuns().contains(run.id));
    }
    @Test void orphanRunFailsOnceAndStopsRetrying() {
        // AC8（T7②）：job 行已没的孤儿 run → 一轮之内打成 FAILED 终态并停嘴，不再每 2 秒无限重试
        var job=create(STEPS);var run=flow.start(job.getId(),"",false);
        jobRepo.deleteById(job.getId());   // 模拟历史孤儿（T7① 级联之后不会再产生）
        flow.plan(run.id);
        var failed=flow.getRun(run.id);
        assertEquals("FAILED",failed.status);
        assertTrue(failed.error.contains("工作不存在"));
        assertFalse(flow.activeRuns().contains(run.id),"终态 run 不再进轮询");
        flow.plan(run.id);   // 第二轮静默：状态与原因均不再变化
        assertEquals("FAILED",flow.getRun(run.id).status);
        assertEquals(failed.error,flow.getRun(run.id).error);
    }
    @Test void snapshotMismatchRunFailsOnceWithStepContext() {
        // T7②（现场 run=3 刷 2.7 万条 WARN 的根因）：节点快照不匹配 → FAILED 终态，原因含缺失 step
        var job=create(STEPS);var run=flow.start(job.getId(),"",false);
        nodes.deleteAll(nodes.findByJobIdOrderBySeqAsc(job.getId()));
        assertDoesNotThrow(()->flow.plan(run.id));
        var failed=flow.getRun(run.id);
        assertEquals("FAILED",failed.status);
        assertTrue(failed.error.contains("快照不匹配") && failed.error.contains("s0"),"终态原因需含缺失 step 上下文");
        assertFalse(flow.activeRuns().contains(run.id));
    }
    @Test void quickProbeJobCannotStartWorkflow() {
        // AC9（T8）：quick（接口建档）任务拒绝启动持续工作流，文案可读
        var job=create(STEPS);job.setQuick(true);jobRepo.save(job);
        var ex=assertThrows(IllegalArgumentException.class,()->flow.start(job.getId(),"",false));
        assertTrue(ex.getMessage().contains("接口建档"));
    }
}
