package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.model.entity.SupplierJobChat;
import com.lark.oapi.sample.ws.model.entity.SupplierJobNode;
import com.lark.oapi.sample.ws.model.entity.SupplierNodeChat;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.SupplierJobChatRepository;
import com.lark.oapi.sample.ws.repository.SupplierJobNodeRepository;
import com.lark.oapi.sample.ws.repository.SupplierJobRepository;
import com.lark.oapi.sample.ws.repository.SupplierNodeChatRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 11-05 独立架构统一：存档绑定实例后，工作台一切对话入口（节点执行/卡点答复/对话区/DevChat/生成提示词）
 * 都走该实例与飞书对话共用的<b>同一个主会话</b>（{@link ClaudeCodeService#chat}：同 sessionID、同串行队列、
 * 同上下文），不再开独立 lane；主会话绝不因单个任务销毁（never destroyLane）。
 * openId 继续用 supplierLane 字符串（supplier-job- 前缀 → 排队面板"工作台"标签）。
 */
class SupplierSharedSessionTest {

    private static final long JOB_ID = 1L;
    private static final long INSTANCE_ID = 9L;

    private final SupplierJobRepository jobRepo = mock(SupplierJobRepository.class);
    private final SupplierJobNodeRepository nodeRepo = mock(SupplierJobNodeRepository.class);
    private final BotInstanceRepository instanceRepo = mock(BotInstanceRepository.class);
    private final ClaudeCodeService agent = mock(ClaudeCodeService.class);
    private final SupplierKbService kb = mock(SupplierKbService.class);
    private final SupplierJobChatRepository chatRepo = mock(SupplierJobChatRepository.class);
    private final SupplierNodeChatRepository nodeChatRepo = mock(SupplierNodeChatRepository.class);
    private final SupplierKnowledgeService knowledge = mock(SupplierKnowledgeService.class);

    private SupplierJobService service() {
        return new SupplierJobService(jobRepo, nodeRepo, null, instanceRepo, agent, kb, chatRepo, nodeChatRepo, knowledge);
    }

    private SupplierJob job() {
        SupplierJob job = new SupplierJob();
        job.setId(JOB_ID);
        job.setInstanceId(INSTANCE_ID);
        job.setName("接入 Acme");
        job.setSupplierName("Acme");
        job.setSupplierCode("ACME");
        return job;
    }

    private BotInstance instance() {
        BotInstance inst = new BotInstance();
        inst.setId(INSTANCE_ID);
        return inst;
    }

    private static SupplierKnowledgeService.Pack pack() {
        return new SupplierKnowledgeService.Pack("TEXT", "知识库索引", "", null, -1, List.of());
    }

    private SupplierJobNode agentNode(String status, String pendingQuestion) {
        SupplierJobNode n = new SupplierJobNode();
        n.setId(100L);
        n.setJobId(JOB_ID);
        n.setNodeId("n1");
        n.setType("agent");
        n.setTitle("业务分析");
        n.setPrompt("分析 {supplierName} 接口文档");
        n.setStatus(status);
        n.setPendingQuestion(pendingQuestion);
        return n;
    }

    private void stubBasics(SupplierJob job, BotInstance inst) {
        when(jobRepo.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(instanceRepo.existsById(INSTANCE_ID)).thenReturn(true);
        when(instanceRepo.findById(INSTANCE_ID)).thenReturn(Optional.of(inst));
        when(knowledge.context(any(), anyString())).thenReturn(pack());
        when(knowledge.chatContext(any(), anyString())).thenReturn(pack());
        when(knowledge.record(any(), anyString(), anyString(), any(), anyString())).thenReturn(5L);
    }

    private void stubChat(String reply) {
        when(agent.chat(eq(INSTANCE_ID), anyString(), anyString(), anyString(), nullable(String.class), any(TurnListener.class)))
                .thenReturn(reply);
    }

    private void verifyNoLaneEver() {
        verify(agent, never()).chatInLane(anyString(), anyLong(), anyString(), anyString(), nullable(String.class), any(TurnListener.class));
        verify(agent, never()).destroyLane(anyString());
    }

    @Test
    void runNodeGoesThroughMainSessionWithWorkbenchOpenId() {
        SupplierJob job = job();
        stubBasics(job, instance());
        when(nodeRepo.findByJobIdAndNodeId(JOB_ID, "n1")).thenReturn(Optional.of(agentNode("PENDING", "")));
        stubChat("分析完成。");

        service().runNode(JOB_ID, "n1", null);

        // 主会话：openId 保持 supplier-job- 前缀（"工作台"排队标签），不再 chatInLane
        verify(agent, timeout(10_000)).chat(eq(INSTANCE_ID), eq(SupplierJobService.supplierLane(job)),
                contains("分析 Acme"), anyString(), nullable(String.class), any(TurnListener.class));
        verifyNoLaneEver();
        // 知识审计行为不变：派发前 record、拿到回复后 finish
        verify(knowledge).record(eq(job), eq("n1"), eq("analysis"), any(), anyString());
        verify(knowledge, timeout(10_000)).finish(eq(5L), nullable(TurnResult.class), eq("分析完成。"));
    }

    @Test
    void answerNodeInjectsIntoSameMainSession() {
        SupplierJob job = job();
        stubBasics(job, instance());
        when(nodeRepo.findByJobIdAndNodeId(JOB_ID, "n1")).thenReturn(Optional.of(agentNode("RUNNING", "【卡点】请确认环境")));
        stubChat("续跑完成。");

        service().answerNode(JOB_ID, "n1", "是沙箱环境");

        verify(agent, timeout(10_000)).chat(eq(INSTANCE_ID), argThat(o -> o.startsWith("supplier-job-")),
                argThat(m -> m.contains("【卡点答复】") && m.contains("是沙箱环境")),
                anyString(), nullable(String.class), any(TurnListener.class));
        verifyNoLaneEver();
    }

    @Test
    void askUsesMainSessionAndWritesFinalReplyBack() {
        SupplierJob job = job();
        stubBasics(job, instance());
        stubChat("一切正常。");

        service().ask(JOB_ID, "进度如何");

        // user 行 + agent PENDING 预占 + 轮次结束 agent DONE 回写 = 3 次 save（第 3 次发生在虚拟线程里）
        ArgumentCaptor<SupplierJobChat> captor = ArgumentCaptor.forClass(SupplierJobChat.class);
        verify(chatRepo, timeout(10_000).times(3)).save(captor.capture());
        SupplierJobChat last = captor.getAllValues().get(2);
        assertEquals("DONE", last.getStatus());
        assertEquals("一切正常。", last.getText());
        verify(agent).chat(eq(INSTANCE_ID), argThat(o -> o.startsWith("supplier-job-")),
                contains("【玩家提问】"), anyString(), nullable(String.class), any(TurnListener.class));
        verifyNoLaneEver();
    }

    @Test
    void askNodeDevChatUsesMainSession() {
        SupplierJob job = job();
        stubBasics(job, instance());
        SupplierJobNode n = agentNode("PENDING", "");
        n.setDevChat(true);
        when(nodeRepo.findByJobIdAndNodeId(JOB_ID, "n1")).thenReturn(Optional.of(n));
        when(nodeChatRepo.findByJobIdAndNodeIdOrderByIdAsc(JOB_ID, "n1")).thenReturn(List.of());
        stubChat("```prompt\n重写后的可执行提示词\n```");

        service().askNode(JOB_ID, "n1", "帮我写查价实现");

        ArgumentCaptor<SupplierNodeChat> captor = ArgumentCaptor.forClass(SupplierNodeChat.class);
        verify(nodeChatRepo, timeout(10_000).times(3)).save(captor.capture());
        SupplierNodeChat last = captor.getAllValues().get(2);
        assertEquals("DONE", last.getStatus());
        assertEquals("重写后的可执行提示词", last.getText());
        verify(agent).chat(eq(INSTANCE_ID), argThat(o -> o.startsWith("supplier-job-")),
                contains("【开发期提示词重写】"), anyString(), nullable(String.class), any(TurnListener.class));
        verifyNoLaneEver();
    }

    @Test
    void generateCodegenPromptUsesMainSessionAndNeverDestroysIt(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        java.nio.file.Files.createDirectories(root.resolve(".trellis"));
        java.nio.file.Files.writeString(root.resolve(".trellis/workflow.md"), "workflow");
        SupplierJob job = job();
        job.setLibraryPath(root.toString());
        when(jobRepo.findById(JOB_ID)).thenReturn(Optional.of(job));
        when(instanceRepo.findById(INSTANCE_ID)).thenReturn(Optional.of(instance()));
        stubChat("generated development instructions");
        // knowledgeService=null 的轻量构造（与 SupplierPromptGenerationTest 同法）：spy 掉 codegenPrompt
        var service = spy(new SupplierJobService(jobRepo, null, null, instanceRepo, agent, kb, null, null));
        doReturn("mandatory original supplier context").when(service).codegenPrompt(JOB_ID);

        String prompt = service.generateCodegenPrompt(JOB_ID);

        assertTrue(prompt.contains("generated development instructions"));
        assertTrue(prompt.contains("mandatory original supplier context"));
        verify(agent).chat(eq(INSTANCE_ID), argThat(o -> o.startsWith("supplier-job-")),
                contains("mandatory original supplier context"), anyString(), nullable(String.class), any(TurnListener.class));
        verifyNoLaneEver();   // 旧 prompt lane 的 finally destroyLane 已移除——主会话不可销毁
    }

    @Test
    void knowledgeAuditCollectsUsageFromMainSessionTurn() {
        SupplierJob job = job();
        stubBasics(job, instance());
        when(nodeRepo.findByJobIdAndNodeId(JOB_ID, "n1")).thenReturn(Optional.of(agentNode("PENDING", "")));
        TurnResult result = new TurnResult("产物", false);
        result.inputTokens = 21;
        result.outputTokens = 11;
        result.cacheReadTokens = 5;
        result.costUsd = 0.001;
        when(agent.chat(eq(INSTANCE_ID), anyString(), anyString(), anyString(), nullable(String.class), any(TurnListener.class)))
                .thenAnswer(call -> {
                    call.getArgument(5, TurnListener.class).onResultAvailable(result);
                    return result.text;
                });

        service().runNode(JOB_ID, "n1", null);

        // usage 采集（onResultAvailable → finish 带 TurnResult）与现状一致，仅通道换成主会话
        verify(knowledge, timeout(10_000)).finish(eq(5L), same(result), eq("产物"));
        verifyNoLaneEver();
    }
}
