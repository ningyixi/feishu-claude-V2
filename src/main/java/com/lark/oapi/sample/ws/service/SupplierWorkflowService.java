package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.*;
import com.lark.oapi.sample.ws.repository.*;
import org.flowable.engine.ProcessEngine;
import org.flowable.task.api.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/** Flowable owns the process token and human tasks; this adapter owns external execution receipts. */
@Service
public class SupplierWorkflowService {
    private static final Logger log=LoggerFactory.getLogger(SupplierWorkflowService.class);
    @jakarta.persistence.PersistenceContext private jakarta.persistence.EntityManager entityManager;
    private final ProcessEngine engine;
    private final SupplierWorkflowRunRepository runs;
    private final SupplierWorkflowAttemptRepository attempts;
    private final SupplierWorkflowNoticeRepository notices;
    private final SupplierWorkflowAttachmentRepository attachments;
    private final SupplierJobRepository jobs;
    private final SupplierJobNodeRepository nodes;
    private final BotInstanceRepository instances;
    private final SupplierKbEntryRepository entries;
    private final SupplierJobService execution;
    private final SupplierWikiService wiki;
    public SupplierWorkflowService(ProcessEngine engine,SupplierWorkflowRunRepository runs,SupplierWorkflowAttemptRepository attempts,
            SupplierWorkflowNoticeRepository notices,SupplierWorkflowAttachmentRepository attachments,SupplierJobRepository jobs,
            SupplierJobNodeRepository nodes,BotInstanceRepository instances,SupplierKbEntryRepository entries,SupplierJobService execution,SupplierWikiService wiki) {
        this.engine=engine;this.runs=runs;this.attempts=attempts;this.notices=notices;this.attachments=attachments;
        this.jobs=jobs;this.nodes=nodes;this.instances=instances;this.entries=entries;this.execution=execution;this.wiki=wiki;
    }
    private SupplierWorkflowRun lock(Long id){var ids=entityManager.createNativeQuery("SELECT id FROM supplier_workflow_run WHERE id = ? FOR UPDATE").setParameter(1,id).getResultList();if(ids.isEmpty())throw new IllegalArgumentException("流程不存在");var row=runs.findById(id).orElseThrow();entityManager.refresh(row);return row;}
    private SupplierWorkflowRun run(Long jobId){return runs.findByJobId(jobId).orElseThrow(()->new IllegalArgumentException("工作尚未启动引擎流程"));}
    /** 活着的流程（ACTIVE/PAUSED）才算托管；CANCELLED/FAILED/COMPLETED 的历史 run 行不再拦截手工操作（T6，11-17-free-node-execution） */
    static boolean liveRun(SupplierWorkflowRun run){return run!=null && ("ACTIVE".equals(run.status) || "PAUSED".equals(run.status));}
    public void assertLegacyWriteAllowed(Long jobId){
        runs.findByJobId(jobId).filter(SupplierWorkflowService::liveRun).ifPresent(r->{
            throw new IllegalArgumentException("该工作由流程引擎管理，请通过流程待办操作；如需自由编辑请新建工作");});
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public SupplierWorkflowRun start(Long jobId,String recipient,boolean notify) {
        // Lock the job as well as the unique run key so concurrent starts cannot deploy two live instances.
        var job=jobs.lockById(jobId).orElseThrow(()->new IllegalArgumentException("工作不存在"));
        // T8（11-17-free-node-execution）：quick（接口建档）任务由 QuickProbeService 四段流水线驱动，禁止再挂 Flowable 引擎双写状态
        if(job.isQuick())throw new IllegalArgumentException("接口建档（快速建档）任务不支持启动持续工作流；请在工作台新建正式工作后再启动");
        if(runs.findByJobId(jobId).isPresent())throw new IllegalArgumentException("该工作已经启动流程");
        var steps=nodes.findByJobIdOrderBySeqAsc(jobId);
        if(steps.isEmpty() || steps.size()>80)throw new IllegalArgumentException("流程需要 1 到 80 个节点");
        if(steps.stream().anyMatch(n->!"PENDING".equals(n.getStatus()) || !n.getArtifact().isBlank() || !n.getPendingQuestion().isBlank() || n.getLastRunAt()!=null))throw new IllegalArgumentException("请对尚未执行的工作启动流程；已有执行记录请保留并新建工作");
        if(steps.stream().anyMatch(n->!Set.of("manual","reminder","agent","agent_check").contains(n.getType()) || n.getSeq()<0))throw new IllegalArgumentException("不支持的节点类型或序号");
        if(steps.stream().map(SupplierJobNode::getSeq).distinct().count()!=steps.size())throw new IllegalArgumentException("节点序号不能重复");
        BotInstance instance=job.getInstanceId()==null?null:instances.findById(job.getInstanceId()).orElseThrow(()->new IllegalArgumentException("执行实例不存在"));
        if(steps.stream().anyMatch(SupplierProcessModel::agent) && (instance==null || !instance.isEnabled()))throw new IllegalArgumentException("自动节点需要绑定已启用的执行实例");
        String target=recipient==null?"":recipient.strip();
        if(target.isBlank() && instance!=null && instance.getAuthorizedOpenId()!=null)target=instance.getAuthorizedOpenId().strip();
        if(notify && (instance==null || target.isBlank()))throw new IllegalArgumentException("飞书通知需要绑定实例并填写接收人的 open_id");
        if(!target.isEmpty() && !target.matches("ou_[a-zA-Z0-9]{8,128}"))throw new IllegalArgumentException("接收人必须是飞书 open_id（ou_ 开头）");
        var row=new SupplierWorkflowRun();row.jobId=jobId;row.instanceId=job.getInstanceId();row.recipient=target;row.notifyFeishu=notify;
        runs.saveAndFlush(row);
        String key="supplier_"+jobId;
        var deployment=engine.getRepositoryService().createDeployment().name(job.getName()).addBpmnModel(key+".bpmn20.xml",SupplierProcessModel.build(key,steps)).deploy();
        var definition=engine.getRepositoryService().createProcessDefinitionQuery().deploymentId(deployment.getId()).singleResult();
        row.definitionId=definition.getId();
        var process=engine.getRuntimeService().startProcessInstanceById(row.definitionId,"supplier-job-"+jobId,Map.of("recipient",target,"outcome","REVIEW","approved",false,"visit",0L));
        row.processId=process.getId();job.setCurrentNodeId(steps.getFirst().getNodeId());job.setStatus("ACTIVE");jobs.save(job);runs.save(row);
        ensureTaskNotices(row);return row;
    }
    /** 节点快照对不上（节点被删/被改 seq）：不可恢复错误，携带 run/job/step 上下文供一次性终态日志（T7②） */
    static final class SnapshotMismatch extends IllegalArgumentException {
        final Long runId;final Long jobId;final String step;
        SnapshotMismatch(Long runId,Long jobId,String step){super("流程节点快照不匹配");this.runId=runId;this.jobId=jobId;this.step=step;}
    }
    private SupplierJobNode node(SupplierWorkflowRun run,String activity) {
        String step=activity.substring(activity.indexOf('_')+1);
        return nodes.findByJobIdOrderBySeqAsc(run.jobId).stream().filter(n->SupplierProcessModel.step(n).equals(step)).findFirst()
                .orElseThrow(()->new SnapshotMismatch(run.id,run.jobId,step));
    }
    private List<Task> tasks(SupplierWorkflowRun run){return engine.getTaskService().createTaskQuery().processInstanceId(run.processId).list();}
    private Task task(SupplierWorkflowRun run,String id){return tasks(run).stream().filter(t->t.getId().equals(id)).findFirst().orElseThrow(()->new IllegalArgumentException("待办不存在或已处理，请刷新"));}
    private void notice(SupplierWorkflowRun run,String key,String taskId,String kind,String text) {
        if(notices.existsByEventKey(key))return;
        var notice=new SupplierWorkflowNotice();notice.runId=run.id;notice.eventKey=key;notice.taskId=taskId;notice.kind=kind;notice.message=text;
        if(!run.notifyFeishu)notice.status="IN_APP";notices.save(notice);
    }
    private void ensureTaskNotices(SupplierWorkflowRun run){
        for(var task:tasks(run)) {
            var node=node(run,task.getTaskDefinitionKey());
            String detail=task.getTaskDefinitionKey().startsWith("input_")?String.valueOf(engine.getRuntimeService().getVariable(run.processId,"question")):node.getDescription();
            notice(run,"task:"+task.getId(),task.getId(),"HUMAN_TASK","工作「"+jobs.findById(run.jobId).orElseThrow().getName()+"」流转到「"+node.getTitle()+"」\n"+task.getName()+"\n"+detail);
        }
    }
    public record Dispatch(Long attemptId,Long jobId,String nodeId,String instruction,boolean resume) {}
    /**
     * T7②（11-17-free-node-execution）：不可恢复错误（工作已删除 / 节点快照不匹配）→ run 置 FAILED 终态并停止推进。
     * 日志只打一条、含 run/job/缺失 step 上下文；FAILED 后 activeRuns() 不再包含该 run，工作器不再每 2 秒无限重试。
     */
    private void failUnrecoverable(SupplierWorkflowRun run,String detail){
        run.status="FAILED";run.error=detail;run.updatedAt=LocalDateTime.now();runs.save(run);
        log.warn("[SupplierWorkflow] 流程停止推进（不可恢复，已置 FAILED）: run={}, job={}, {}",run.id,run.jobId,detail);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public List<Long> plan(Long runId) {
        var run=lock(runId);if(!"ACTIVE".equals(run.status))return List.of();
        // T7②：孤儿 run（job 行已不存在，如历史上 quick 删除不级联）→ 一次性打成终态，杜绝 2 秒一条的 WARN 刷屏
        if(jobs.findById(run.jobId).isEmpty()){failUnrecoverable(run,"工作不存在（可能已被删除），流程停止推进");return List.of();}
        try{return planTokens(run);}
        catch(SnapshotMismatch ex){failUnrecoverable(run,"流程节点快照不匹配：job "+ex.jobId+" 缺失节点 step "+ex.step+"，流程停止推进");return List.of();}
    }
    private List<Long> planTokens(SupplierWorkflowRun run){
        if(engine.getRuntimeService().createProcessInstanceQuery().processInstanceId(run.processId).count()==0){
            run.status="COMPLETED";run.updatedAt=LocalDateTime.now();runs.save(run);
            notice(run,"done:"+run.id,null,"COMPLETED","供应商工作「"+jobs.findById(run.jobId).orElseThrow().getName()+"」已完成全部节点。");return List.of();
        }
        ensureTaskNotices(run);List<Long> queued=new ArrayList<>();
        for(var token:engine.getRuntimeService().createExecutionQuery().processInstanceId(run.processId).list()) {
            String activity=token.getActivityId();if(activity==null || !activity.startsWith("exec_"))continue;
            var node=node(run,activity);var job=jobs.findById(run.jobId).orElseThrow();
            String dispatchKey=token.getId()+":"+engine.getRuntimeService().getVariable(run.processId,"visit");
            var attempt=attempts.findByDispatchKey(dispatchKey).orElse(null);
            // 自愈历史停位（检查阶段修复）：旧版把 SKIPPED 落账后不 trigger，token 永远停在该位置（run 悬挂 ACTIVE，
            // 且活流程闸门锁死工作编辑/删除）。修复后 SKIPPED 与 trigger 同事务提交，走到此分支只可能是历史行 → 补 trigger。
            if(attempt!=null && "SKIPPED".equals(attempt.status)){skipTrigger(run,attempt);continue;}
            final Long existingId=attempt==null?null:attempt.id;
            // T2 防重复派发（11-17-free-node-execution）：复用 dispatchKey/visit + attempt 台账判"引擎是否派发过该节点"。
            // 驳回返工/卡点续跑的位置必有本节点在先 attempt → engineHandledBefore=true → 照常派发（既有语义不变）。
            boolean engineHandledBefore=attempts.findByRunIdAndNodeId(run.id,node.getNodeId()).stream().anyMatch(a->!a.id.equals(existingId));
            boolean manualSatisfied="DONE".equals(node.getStatus()) || !node.getArtifact().isBlank();
            // T2-a：引擎从未派发过该节点，而用户已手动跑到 DONE/已有产物 → 该 exec_ 位置记 SKIPPED（dispatchKey 去重），
            // 不派 attempt、不调实例；SKIPPED 与 skipTrigger 的静默推进同事务提交——推进的是 token，不是待办
            // （前端黄条同口径：手动执行不会推进流程待办与审批）。仅限尚未派发的位置（无/QUEUED）。
            if(manualSatisfied && !engineHandledBefore && (attempt==null || "QUEUED".equals(attempt.status))){
                if(attempt==null){
                    attempt=new SupplierWorkflowAttempt();attempt.runId=run.id;attempt.executionId=token.getId();attempt.dispatchKey=dispatchKey;attempt.nodeId=node.getNodeId();
                    Object instruction=engine.getRuntimeService().getVariable(run.processId,"instruction");attempt.instruction=instruction==null?"":instruction.toString();
                }
                if(!"SKIPPED".equals(attempt.status)){
                    attempt.status="SKIPPED";attempt.finishedAt=LocalDateTime.now();attempts.save(attempt);
                    notice(run,"skip:"+attempt.id,null,"EXECUTION","工作「"+job.getName()+"」节点「"+node.getTitle()+"」已由用户手动完成，引擎不再重复派发。");
                }
                skipTrigger(run,attempt);   // 只停位不推进会让 run 永久 ACTIVE，T6 活流程闸门反过来锁死工作编辑/删除
                continue;
            }
            // T2-b：手动轮次正在执行（节点 RUNNING、引擎从未派发过该节点、本位置尚未派发）→ 本轮暂缓不落账不派发；
            // 下一轮按手动结果再判：产物/DONE → T2-a 跳过；失败回 PENDING 无产物 → 正常建账派发。僵尸手动轮由 S4 巡检兜底复位。
            if((attempt==null || "QUEUED".equals(attempt.status)) && "RUNNING".equals(node.getStatus()) && !engineHandledBefore)continue;
            job.setCurrentNodeId(node.getNodeId());jobs.save(job);
            if(attempt==null){
                attempt=new SupplierWorkflowAttempt();attempt.runId=run.id;attempt.executionId=token.getId();attempt.dispatchKey=dispatchKey;attempt.nodeId=node.getNodeId();
                Object instruction=engine.getRuntimeService().getVariable(run.processId,"instruction");attempt.instruction=instruction==null?"":instruction.toString();
                attempts.save(attempt);
                notice(run,"execute:"+attempt.id,null,"EXECUTION","工作「"+job.getName()+"」开始执行节点「"+node.getTitle()+"」。");
            }
            if("WAITING_WIKI".equals(attempt.status)) {
                String status=wiki.status(job).get("status").toString();
                if("FAILED".equals(status))finish(run,attempt,"INPUT","Wiki 编译失败，请检查 Wiki 面板中的错误，补充材料或修正规则后提交重试。");
                else if(!"BUILDING".equals(status)){attempt.status="QUEUED";attempts.save(attempt);}
            }
            if("QUEUED".equals(attempt.status))queued.add(attempt.id);
            if("RUNNING".equals(attempt.status)) {
                if(!node.getPendingQuestion().isBlank())finish(run,attempt,"INPUT",node.getPendingQuestion());
                else if(!"RUNNING".equals(node.getStatus())) {
                    if(!node.getNote().isBlank() || node.getArtifact().isBlank())finish(run,attempt,"INPUT",node.getNote().isBlank()?"执行未产生有效结果，请补充信息后重试。":node.getNote());
                    else finish(run,attempt,"REVIEW","");
                }
            }
        }
        ensureTaskNotices(run);return queued;
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Dispatch claim(Long attemptId) {
        var attempt=attempts.findById(attemptId).orElseThrow();var run=lock(attempt.runId);
        // Reload after locking: a second worker may have observed the old row before the lock.
        attempt=attempts.findById(attemptId).orElseThrow();entityManager.refresh(attempt);
        if(!"ACTIVE".equals(run.status) || !"QUEUED".equals(attempt.status))return null;
        attempt.status="DISPATCHED";attempts.save(attempt);
        var node=nodes.findByJobIdAndNodeId(run.jobId,attempt.nodeId).orElseThrow();
        return new Dispatch(attempt.id,run.jobId,attempt.nodeId,attempt.instruction,!node.getPendingQuestion().isBlank());
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void dispatched(Long attemptId,String error) {
        var attempt=attempts.findById(attemptId).orElseThrow();var run=lock(attempt.runId);
        entityManager.refresh(attempt);if(!"DISPATCHED".equals(attempt.status))return;
        if("CANCELLED".equals(run.status)){attempt.status="CANCELLED";attempts.save(attempt);return;}
        if(error!=null){if("CANCELLED".equals(run.status)){attempt.status="FAILED";attempts.save(attempt);}else finish(run,attempt,"INPUT",error);}
        else {attempt.status="RUNNING";attempts.save(attempt);}
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void waitForWiki(Long id) {var attempt=attempts.findById(id).orElseThrow();lock(attempt.runId);entityManager.refresh(attempt);if("DISPATCHED".equals(attempt.status)){attempt.status="WAITING_WIKI";attempts.save(attempt);}}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void wikiFailed(Long id,String error) {var attempt=attempts.findById(id).orElseThrow();var run=lock(attempt.runId);entityManager.refresh(attempt);if("WAITING_WIKI".equals(attempt.status) && !"CANCELLED".equals(run.status)){finish(run,attempt,"INPUT",error);}}
    private void finish(SupplierWorkflowRun run,SupplierWorkflowAttempt attempt,String outcome,String question) {
        attempt.status=outcome.equals("INPUT")?"WAITING_INPUT":"SUCCEEDED";attempt.finishedAt=LocalDateTime.now();attempts.save(attempt);
        if("CANCELLED".equals(run.status)){attempt.status="CANCELLED";attempts.save(attempt);return;}
        boolean paused="PAUSED".equals(run.status);if(paused)engine.getRuntimeService().activateProcessInstanceById(run.processId);
        engine.getRuntimeService().setVariables(run.processId,Map.of("outcome",outcome,"question",question));
        engine.getRuntimeService().trigger(attempt.executionId);
        if(paused)engine.getRuntimeService().suspendProcessInstanceById(run.processId);
    }
    /**
     * T2 修复（11-17-free-node-execution）：让已手动完成节点所在的 exec_ ReceiveTask token 静默前进。
     * 旧实现只落 SKIPPED 不 trigger → token 永远停位、run 悬挂 ACTIVE，而 T6 之后活流程闸门只拦 ACTIVE/PAUSED
     * → 该工作再也改不掉删不掉（把用户被挡死的墙换了个位置重砌）。
     * 前进路径沿用 BPMN 唯一的"前进"通道（SupplierProcessModel.build 不改建模）：
     *  1) 照抄 finish() 的变量写法：outcome=REVIEW → route_sN 走默认前进分支（绝非 INPUT 回退分支）；
     *  2) trigger(executionId) 后 token 进入 review_sN 审批待办；
     *  3) 同事务内照 respond() APPROVE 的同款流程变量就地完成该审批（approved=true、instruction=""、visit+1）
     *     → decision_sN 走 approved==true 前进分支 → token 到达下一位置（或流程自然结束）。
     * 审批任务在同一事务内创建并完成：不留任何待办、不发通知（黄条承诺"手动执行不会推进流程待办"——推进的是
     * token，不是待办）；历史里只留一条带 action=APPROVE 本地变量的自动审批供追溯。不排队 attempt、不调
     * execution.*，实例调用次数不因引擎推进而增加。
     * 幂等/安全：plan() 先对 run 行加 FOR UPDATE 锁才进这里；SKIPPED 与 trigger 同事务提交，不存在"落账未推进"
     * 的半状态；执行流已不在本 exec_ 位置（重启重放/实例已结束）则静默返回，绝不双推进。
     * visit 必须 +1（与 respond() 同款）：Flowable 顺序流会复用同一 executionId，visit 不变则下一节点的
     * dispatchKey(executionId:visit) 会撞上本条 SKIPPED 台账，把下一节点误判成已跳过。
     */
    private void skipTrigger(SupplierWorkflowRun run,SupplierWorkflowAttempt attempt){
        var execution=engine.getRuntimeService().createExecutionQuery().processInstanceId(run.processId).executionId(attempt.executionId).singleResult();
        if(execution==null)return;
        String activity=execution.getActivityId();
        if(activity==null || !activity.startsWith("exec_"))return;   // token 已被推进过：幂等静默返回
        engine.getRuntimeService().setVariables(run.processId,Map.of("outcome","REVIEW","question",""));
        engine.getRuntimeService().trigger(attempt.executionId);
        var review=engine.getTaskService().createTaskQuery().processInstanceId(run.processId)
                .taskDefinitionKey(activity.replaceFirst("exec_","review_")).singleResult();
        if(review==null)return;   // 理论上到不了；真到了说明模型变了——token 停在审批位由正常待办通道兜底，不强推
        var local=new HashMap<String,Object>();local.put("action","APPROVE");
        local.put("answer","节点已由用户手动完成，引擎跳过派发自动前进（无需人工审批）。");
        local.put("respondedAt",LocalDateTime.now().toString());
        engine.getTaskService().setVariablesLocal(review.getId(),local);
        long visit=((Number)engine.getRuntimeService().getVariable(run.processId,"visit")).longValue()+1;
        engine.getTaskService().complete(review.getId(),Map.of("approved",true,"instruction","","visit",visit));
        run.updatedAt=LocalDateTime.now();runs.save(run);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void recoverInterrupted() {
        for(var found:attempts.findByStatusIn(List.of("DISPATCHED","RUNNING"))) {
            var run=lock(found.runId);if(List.of("CANCELLED","COMPLETED","FAILED").contains(run.status))continue;
            // T7②：恢复扫描撞上不可恢复状态（job 已删 / 节点行没了）→ attempt 落终态 + run 置 FAILED，绝不让启动恢复或轮询反复撞墙
            boolean jobGone=jobs.findById(run.jobId).isEmpty();
            var interrupted=jobGone?null:nodes.findByJobIdAndNodeId(run.jobId,found.nodeId).orElse(null);
            if(jobGone || interrupted==null){
                found.status="FAILED";found.finishedAt=LocalDateTime.now();attempts.save(found);
                failUnrecoverable(run,jobGone?"工作不存在（可能已被删除），流程停止推进":"节点 "+found.nodeId+" 已不存在（流程节点快照不匹配），流程停止推进");
                continue;
            }
            interrupted.setStatus("PENDING");interrupted.setPendingQuestion("");nodes.save(interrupted);
            finish(run,found,"INPUT","服务重启中断执行，外部操作结果可能未知。请核对接口/仓库实际结果后，明确填写重试或继续指示；系统不会盲目重放。");
            ensureTaskNotices(run);
        }
        // Sending may have succeeded before a crash. Retry with the same provider UUID.
        for(var n:notices.findByStatus("SENDING")){n.status="PENDING";notices.save(n);}
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void respond(Long jobId,String taskId,String action,String answer) {
        var run=lock(run(jobId).id);if(!"ACTIVE".equals(run.status))throw new IllegalArgumentException("流程当前不可处理待办，请先恢复");
        var task=task(run,taskId);var node=node(run,task.getTaskDefinitionKey());
        String text=answer==null?"":answer.strip();if(text.length()>20000)throw new IllegalArgumentException("答复超过 20000 字符，请上传为材料");
        boolean review=task.getTaskDefinitionKey().startsWith("review_");
        if(review && !Set.of("APPROVE","REJECT").contains(action))throw new IllegalArgumentException("审批请选择同意或驳回");
        if(!review && !"SUBMIT".equals(action))throw new IllegalArgumentException("请提交答复或材料");
        var files=attachments.findByRunIdAndTaskIdOrderByIdAsc(run.id,taskId);
        if((!review || "REJECT".equals(action)) && text.isBlank() && files.isEmpty())throw new IllegalArgumentException("请填写答复、修改意见或上传所缺材料");
        for(var file:files){var entry=entries.findById(file.entryId).orElseThrow(()->new IllegalArgumentException("上传材料已删除"));if(!"DONE".equals(entry.getStatus()))throw new IllegalArgumentException("请等待上传材料解析完成");text+="\n补充材料："+file.filename+"，知识库来源 ID "+file.entryId;}
        if("APPROVE".equals(action) || task.getTaskDefinitionKey().startsWith("manual_")) {
            var job=jobs.findById(jobId).orElseThrow(()->new IllegalArgumentException("工作不存在"));
            if(!review && !text.isBlank())execution.setArtifactForCompletion(jobId,node.getNodeId(),text); // 只落工件不入 KB；归档由 completeNode 尾部在门禁校验后统一完成，避免同事务自脏 Wiki 来源
            if(!review && node.isCodegenPrep()) {
                String link=SupplierJobService.extractFeishuDocUrl(text);
                if(link!=null && job.getMeetingDocUrl().isBlank())execution.savePrep(jobId,link,null,null);
            }
            execution.completeNode(jobId,node.getNodeId(),!review && node.isCodegenPrep() && !files.isEmpty()); // Existing Postman/spec/skill/Trellis gates remain authoritative. 待办已上传会议材料=文档已交。
        }
        var local=new HashMap<String,Object>();local.put("action",action);local.put("answer",text);local.put("respondedAt",LocalDateTime.now().toString());
        engine.getTaskService().setVariablesLocal(taskId,local);
        long visit=((Number)engine.getRuntimeService().getVariable(run.processId,"visit")).longValue()+1;
        engine.getTaskService().complete(taskId,Map.of("approved","APPROVE".equals(action),"instruction",text,"visit",visit));
        run.updatedAt=LocalDateTime.now();runs.save(run);ensureTaskNotices(run);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void control(Long jobId,String action) {
        var run=lock(run(jobId).id);
        switch(action) {
            case "PAUSE" -> {if(!"ACTIVE".equals(run.status))throw new IllegalArgumentException("只有运行中的流程可以暂停");engine.getRuntimeService().suspendProcessInstanceById(run.processId);run.status="PAUSED";}
            case "RESUME" -> {if(!"PAUSED".equals(run.status))throw new IllegalArgumentException("流程未暂停");engine.getRuntimeService().activateProcessInstanceById(run.processId);run.status="ACTIVE";}
            case "CANCEL" -> {if(!Set.of("ACTIVE","PAUSED").contains(run.status))throw new IllegalArgumentException("流程已结束");engine.getRuntimeService().deleteProcessInstance(run.processId,"用户取消");run.status="CANCELLED";}
            default -> throw new IllegalArgumentException("无效流程操作");
        }
        run.updatedAt=LocalDateTime.now();runs.save(run);notice(run,"control:"+run.id+":"+run.version,null,action,"流程状态："+run.status+"。已经发出的外部操作不会自动撤销。");
    }
    /**
     * T7①（11-17-free-node-execution）：删除工作级联取消流程——活着的 run 按 CANCEL 语义处理
     * （删 Flowable 流程实例 + run 置 CANCELLED），杜绝"job 行没了、run 还 ACTIVE"的孤儿每 2 秒刷
     * 「流程节点快照不匹配」。已是终态（CANCELLED/FAILED/COMPLETED）的 run 只保留台账不再操作引擎。
     * 由 SupplierJobService.deleteJob 在删除节点/工作行之前调用。
     */
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void cancelForJobDeletion(Long jobId){
        var maybe=runs.findByJobId(jobId);if(maybe.isEmpty())return;
        var run=lock(maybe.get().id);
        if(!liveRun(run))return;
        try{engine.getRuntimeService().deleteProcessInstance(run.processId,"工作已删除");}
        catch(Exception ex){log.warn("[SupplierWorkflow] 删除工作时取消流程实例失败（run 仍置 CANCELLED）: run={}, job={}, {}",run.id,jobId,ex.getMessage());}
        run.status="CANCELLED";run.updatedAt=LocalDateTime.now();runs.save(run);
        log.info("[SupplierWorkflow] 工作删除级联取消流程: run={}, job={}",run.id,jobId);
    }
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String,Object> state(Long jobId) {
        var maybe=runs.findByJobId(jobId);if(maybe.isEmpty())return Map.of("status","NOT_STARTED");
        var run=maybe.get();List<Map<String,Object>> pending=new ArrayList<>();
        for(var task:tasks(run)) {
            var node=node(run,task.getTaskDefinitionKey());var item=new LinkedHashMap<String,Object>();
            item.put("id",task.getId());item.put("name",task.getName());item.put("kind",task.getTaskDefinitionKey().split("_")[0]);item.put("nodeId",node.getNodeId());item.put("nodeTitle",node.getTitle());
            item.put("description",node.getDescription());item.put("artifact",node.getArtifact());
            item.put("question",task.getTaskDefinitionKey().startsWith("input_")?engine.getRuntimeService().getVariable(run.processId,"question"):"");
            var attach=attachments.findByRunIdAndTaskIdOrderByIdAsc(run.id,task.getId());
            item.put("attachments",attach);
            if(task.getTaskDefinitionKey().startsWith("manual_") && node.isCodegenPrep()){
                var job=jobs.findById(run.jobId).orElseThrow(()->new IllegalArgumentException("工作不存在"));
                item.put("prepStatus",Map.of("docDone",!job.getMeetingDocUrl().isBlank() || !attach.isEmpty(),"kvDone",SupplierJobService.configsComplete(job.getConfigJson())));
            }
            pending.add(item);
        }
        var history=engine.getHistoryService().createHistoricTaskInstanceQuery().processInstanceId(run.processId).finished().includeTaskLocalVariables().orderByHistoricTaskInstanceEndTime().desc().listPage(0,100).stream().map(t->{
            Map<String,Object> item=new LinkedHashMap<>();item.put("id",t.getId());item.put("name",t.getName());item.put("finishedAt",t.getEndTime());item.put("response",t.getTaskLocalVariables());return item;
        }).toList();
        return activityState(run,pending,history);
    }
    /** 把 run 的返回体组装成含 activity(现在在干嘛/最后活动/空闲秒/是否疑似卡住) 的 map */
    private Map<String,Object> activityState(SupplierWorkflowRun run,List<Map<String,Object>> pending,List<Map<String,Object>> history){
        Map<String,Object> act=new LinkedHashMap<>();
        java.time.LocalDateTime last=run.updatedAt; String phase="推进中",text="流程推进中",detail="";
        var recent=attempts.findByRunIdOrderByIdDesc(run.id);
        var live=recent.stream()
                .filter(a->"DISPATCHED".equals(a.status)||"RUNNING".equals(a.status)||"WAITING_WIKI".equals(a.status))
                .findFirst().orElse(null);
        // T2 手动接管：存在 SKIPPED 台账=引擎在静默跳过/推进已手动完成的节点；收敛间隙或 T2-b 手动轮暂缓都属预期，不算"卡住"
        boolean parked=recent.stream().anyMatch(a->"SKIPPED".equals(a.status));
        if(!pending.isEmpty()){
            phase="等待你处理"; text="等待你处理待办："+pending.get(0).get("name");
            Object q=pending.get(0).get("question"); detail=q==null?"":q.toString();
        } else if(live!=null && "WAITING_WIKI".equals(live.status)){
            phase="Wiki 编译中"; var job=jobs.findById(run.jobId).orElse(null);
            if(job!=null){ var w=wiki.status(job);
                String ph=String.valueOf(w.getOrDefault("phase",""));
                text="正在编译供应商 Wiki · "+ph+" · "+w.get("status");
                detail="来源覆盖 "+w.get("completedSources")+"/"+w.get("sourceCount")+(w.get("error")==null||"".equals(w.get("error"))?"":" · "+w.get("error"));
                String ua=String.valueOf(w.get("updatedAt")); if(!ua.equals("null")&&!ua.isBlank()){ try{ last=java.time.LocalDateTime.parse(ua.replace(" ","T")); }catch(Exception ignore){} }
            }
        } else if(live!=null){
            phase="实例执行节点中"; var node=nodes.findByJobIdAndNodeId(run.jobId,live.nodeId).orElse(null);
            if(node!=null){ text="执行节点 "+live.nodeId+"："+node.getTitle();
                detail=!node.getThinking().isBlank()?"💭 "+node.getThinking():(!node.getProgress().isBlank()?node.getProgress():"已派发，等待实例响应…");
                if(node.getUpdatedAt()!=null) last=node.getUpdatedAt(); }
        } else if("FAILED".equals(run.status)){
            phase="已停止"; text="流程遇到不可恢复错误，已停止推进"; detail=run.error==null?"":run.error;
        } else if(parked){
            phase="手动接管"; text="节点已由用户手动完成，引擎已跳过派发";
            detail="手动执行不会产生流程待办；全部节点完成后流程会自动收敛为已完成，也可在上方取消流程。";
        } else {
            var nn=notices.findTop100ByRunIdOrderByIdDesc(run.id).stream()
                    .filter(x->"PENDING".equals(x.status)||"SENDING".equals(x.status)||"FAILED".equals(x.status)).findFirst().orElse(null);
            if(nn!=null){ phase="通知投递"; text="飞书通知"+("FAILED".equals(nn.status)?"投递失败（可点重试）":"投递/退避重试中")+" · 第 "+nn.attempts+" 次"; detail=nn.error==null?"":nn.error; }
        }
        long idle = last==null?0:Math.max(0, java.time.Duration.between(last, java.time.LocalDateTime.now()).getSeconds());
        boolean stalled = "ACTIVE".equals(run.status) && pending.isEmpty() && idle>240 && !parked;   // 没待办、>4分钟无心跳 → 疑似卡住（手动接管停位属预期，不算卡住）
        act.put("phase",phase); act.put("text",text); act.put("detail",detail); act.put("idleSeconds",idle); act.put("stalled",stalled);
        Map<String,Object> out=new LinkedHashMap<>();
        out.put("status",run.status); out.put("run",run); out.put("tasks",pending); out.put("history",history);
        out.put("notices",notices.findTop100ByRunIdOrderByIdDesc(run.id)); out.put("attempts",attempts.findByRunIdOrderByIdDesc(run.id));
        out.put("activity",act);
        return out;
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public SupplierWorkflowRun activeTaskForUpload(Long jobId,String taskId){var run=lock(run(jobId).id);if(!"ACTIVE".equals(run.status))throw new IllegalArgumentException("流程不可上传材料");task(run,taskId);return run;}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public SupplierWorkflowNotice claimNotice(Long id) {
        var notice=notices.findById(id).orElseThrow();lock(notice.runId);notice=notices.findById(id).orElseThrow();entityManager.refresh(notice);
        if(!"PENDING".equals(notice.status))return null;
        var run=runs.findById(notice.runId).orElseThrow();
        if(notice.taskId!=null && (List.of("CANCELLED","COMPLETED","FAILED").contains(run.status) || tasks(run).stream().map(Task::getId).toList().contains(notice.taskId)==false)){notice.status="OBSOLETE";notices.save(notice);return null;}
        if("PAUSED".equals(run.status) && notice.taskId!=null){notice.nextAttemptAt=LocalDateTime.now().plusSeconds(60);notices.save(notice);return null;}
        notice.status="SENDING";notice.attempts++;return notices.save(notice);
    }
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void noticeResult(Long id,String error){var n=notices.findById(id).orElseThrow();lock(n.runId);n.status=error==null?"SENT":n.attempts>=6?"FAILED":"PENDING";n.error=error==null?"":error;if(error==null)n.sentAt=LocalDateTime.now();n.nextAttemptAt=LocalDateTime.now().plusSeconds(Math.min(900,10L*(1L<<Math.min(n.attempts,6))));notices.save(n);}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public void retryNotice(Long jobId,Long id){var run=lock(run(jobId).id);var n=notices.findById(id).filter(x->x.runId.equals(run.id)).orElseThrow(()->new IllegalArgumentException("通知不存在"));if(!"FAILED".equals(n.status))throw new IllegalArgumentException("仅失败通知可手动重试");n.attempts=0;n.status="PENDING";n.nextAttemptAt=LocalDateTime.now();notices.save(n);}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public String feishuReply(Long instanceId,String openId,String command) {
        String[] parts=command.strip().split("\\s+",4);if(parts.length<3)throw new IllegalArgumentException("格式：/供应商 <待办ID> 同意/驳回/答复 [内容]");
        var task=engine.getTaskService().createTaskQuery().taskId(parts[1]).singleResult();if(task==null)throw new IllegalArgumentException("待办已处理或不存在");
        var run=runs.findByProcessId(task.getProcessInstanceId()).orElseThrow();
        if(!Objects.equals(run.instanceId,instanceId) || !run.recipient.equals(openId))throw new IllegalArgumentException("仅此流程配置的接收人可通过绑定机器人处理待办");
        String action=switch(parts[2]){case "同意"->"APPROVE";case "驳回"->"REJECT";case "答复"->"SUBMIT";default->throw new IllegalArgumentException("操作应为同意、驳回或答复");};
        respond(run.jobId,task.getId(),action,parts.length==4?parts[3]:"");return "已处理待办，流程将继续推进。";
    }
    public List<Long> activeRuns(){return runs.findByStatus("ACTIVE").stream().map(r->r.id).toList();}
    public SupplierWorkflowRun getRun(Long id){return runs.findById(id).orElseThrow();}
    public List<SupplierWorkflowNotice> dueNotices(){return notices.findTop20ByStatusAndNextAttemptAtBeforeOrderByIdAsc("PENDING",LocalDateTime.now());}
}
