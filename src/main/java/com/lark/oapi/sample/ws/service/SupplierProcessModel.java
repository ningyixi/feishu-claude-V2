package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.SupplierJobNode;
import org.flowable.bpmn.model.*;
import java.util.List;

/** Application-owned BPMN; no user-supplied expressions, scripts or delegates. */
public final class SupplierProcessModel {
    private SupplierProcessModel() {}
    public static boolean agent(SupplierJobNode node) { return node.getType().startsWith("agent"); }
    public static String step(SupplierJobNode node) { return "s"+node.getSeq(); }
    public static BpmnModel build(String key, List<SupplierJobNode> nodes) {
        var model=new BpmnModel(); var process=new org.flowable.bpmn.model.Process();
        process.setId(key);process.setName("供应商对接工作流");process.setExecutable(true);model.addProcess(process);
        var start=new StartEvent();start.setId("start");process.addFlowElement(start);
        var end=new EndEvent();end.setId("end");process.addFlowElement(end);
        String previous="start";
        for(var node:nodes) {
            String s=step(node), entry=(agent(node)?"exec_":"manual_")+s;
            link(process,previous,entry,null);
            if(!agent(node)) {
                user(process,entry,node.getTitle(),node.getDescription());previous=entry;continue;
            }
            var receive=new ReceiveTask();receive.setId(entry);receive.setName(node.getTitle());process.addFlowElement(receive);
            var route=new ExclusiveGateway();route.setId("route_"+s);route.setDefaultFlow("route_"+s+"_to_review_"+s);process.addFlowElement(route);
            user(process,"input_"+s,"补充信息 / 重试："+node.getTitle(),node.getDescription());
            user(process,"review_"+s,"审批："+node.getTitle(),node.getDescription());
            var decision=new ExclusiveGateway();decision.setId("decision_"+s);process.addFlowElement(decision);
            link(process,entry,route.getId(),null);
            link(process,route.getId(),"input_"+s,"${outcome == 'INPUT'}");
            link(process,route.getId(),"review_"+s,null);
            link(process,"input_"+s,entry,null);
            link(process,"review_"+s,decision.getId(),null);
            link(process,decision.getId(),entry,"${approved == false}");
            previous=decision.getId();
        }
        link(process,previous,"end",null);
        // Every review decision has two outgoing paths: approval moves forward, rejection re-enters this step.
        for(var node:nodes) if(agent(node)) {
            var decision=(ExclusiveGateway)process.getFlowElement("decision_"+step(node));
            var forward=(SequenceFlow)process.getFlowElements().stream().filter(e->e instanceof SequenceFlow f && f.getSourceRef().equals(decision.getId()) && !f.getTargetRef().equals("exec_"+step(node))).findFirst().orElseThrow();
            forward.setConditionExpression("${approved == true}");
        }
        return model;
    }
    private static void user(org.flowable.bpmn.model.Process p,String id,String title,String description) {
        var task=new UserTask();task.setId(id);task.setName(title);task.setDocumentation(description);task.setAssignee("${recipient}");p.addFlowElement(task);
    }
    private static void link(org.flowable.bpmn.model.Process p,String from,String to,String condition) {
        var flow=new SequenceFlow();flow.setId(from+"_to_"+to);flow.setSourceRef(from);flow.setTargetRef(to);flow.setConditionExpression(condition);p.addFlowElement(flow);
    }
}
