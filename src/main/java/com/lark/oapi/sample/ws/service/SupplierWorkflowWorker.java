package com.lark.oapi.sample.ws.service;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.slf4j.LoggerFactory;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Component
public class SupplierWorkflowWorker {
    private final SupplierWorkflowService workflow;
    private final SupplierJobService execution;
    private final FeishuBotService feishu;
    private final SupplierWikiService wiki;
    private final String baseUrl;
    @Value("${feishu.workflow.worker-enabled:true}") private boolean enabled;
    @Value("${feishu.workflow.poll-ms:2000}") private long pollMs;
    private volatile boolean ready;
    private final java.util.concurrent.ScheduledExecutorService timer=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("supplier-workflow-").factory());
    private final java.util.concurrent.ExecutorService delivery=new java.util.concurrent.ThreadPoolExecutor(2,2,0L,java.util.concurrent.TimeUnit.SECONDS,new java.util.concurrent.ArrayBlockingQueue<>(20),Thread.ofPlatform().name("supplier-notice-",0).factory());
    private final java.util.Set<Long> sending=java.util.concurrent.ConcurrentHashMap.newKeySet();
    public SupplierWorkflowWorker(SupplierWorkflowService workflow,SupplierJobService execution,FeishuBotService feishu,SupplierWikiService wiki,
            @Value("${feishu.workflow.public-url:http://localhost:18080}") String baseUrl){this.workflow=workflow;this.execution=execution;this.feishu=feishu;this.wiki=wiki;this.baseUrl=baseUrl.replaceAll("/+$","");}
    @EventListener(ApplicationReadyEvent.class)
    public void recover(){workflow.recoverInterrupted();ready=true;if(enabled)timer.scheduleWithFixedDelay(this::safeTick,0,Math.max(250,pollMs),java.util.concurrent.TimeUnit.MILLISECONDS);}
    @jakarta.annotation.PreDestroy public void close(){ready=false;timer.shutdownNow();delivery.shutdownNow();}
    private void safeTick(){try{tick();}catch(Exception ex){LoggerFactory.getLogger(getClass()).warn("供应商流程轮询失败：{}",ex.getMessage());}}
    public void tick(){
        if(!ready || !enabled)return;
        for(var runId:workflow.activeRuns())try{
            for(var attempt:workflow.plan(runId))dispatch(attempt);
        }catch(Exception ex){LoggerFactory.getLogger(getClass()).warn("供应商流程推进失败 run={}: {}",runId,ex.getMessage());}
        for(var notice:workflow.dueNotices())if(sending.add(notice.id))try{
            delivery.execute(()->{try{send(notice.id);}catch(Exception ex){LoggerFactory.getLogger(getClass()).warn("供应商流程通知失败 id={}: {}",notice.id,ex.getMessage());}finally{sending.remove(notice.id);}});
        }catch(java.util.concurrent.RejectedExecutionException ex){sending.remove(notice.id);}
    }
    public void dispatch(Long id){
        var action=workflow.claim(id);if(action==null)return;
        var job=execution.getJob(action.jobId()).orElseThrow();
        if("WIKI".equals(job.getKnowledgeMode()) && !"READY".equals(wiki.status(job).get("status"))) {
            workflow.waitForWiki(id);
            try{if(!"BUILDING".equals(wiki.status(job).get("status")))wiki.build(job);}
            catch(Exception ex){workflow.wikiFailed(id,"Wiki 准备失败："+ex.getMessage());}
            return;
        }
        String error=null;
        try{if(action.resume())execution.answerNode(action.jobId(),action.nodeId(),action.instruction());else execution.runNode(action.jobId(),action.nodeId(),action.instruction());}
        catch(Exception ex){error="执行前置检查或派发失败："+ex.getMessage();}
        workflow.dispatched(id,error);
    }
    public void send(Long id){
        var notice=workflow.claimNotice(id);if(notice==null)return;var run=workflow.getRun(notice.runId);
        String link=baseUrl+"/supplier?job="+run.jobId+(notice.taskId==null?"":"&task="+notice.taskId);
        String message=notice.message+"\n打开工作台处理/上传文件："+link;
        if(notice.taskId!=null)message+="\n也可回复：/供应商 "+notice.taskId+" 同意\n或：/供应商 "+notice.taskId+" 驳回 修改意见\n或：/供应商 "+notice.taskId+" 答复 补充信息";
        String error=null;
        try{feishu.sendWorkflowNotice(run.instanceId,run.recipient,message,UUID.nameUUIDFromBytes(("supplier:"+notice.eventKey).getBytes(StandardCharsets.UTF_8)).toString());}
        catch(Exception ex){error=ex.getMessage()==null?"飞书发送失败":ex.getMessage();}
        workflow.noticeResult(id,error);
    }
}
