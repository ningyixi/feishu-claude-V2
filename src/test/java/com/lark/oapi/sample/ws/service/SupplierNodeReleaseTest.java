package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.model.entity.SupplierJobNode;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.SupplierJobChatRepository;
import com.lark.oapi.sample.ws.repository.SupplierJobNodeRepository;
import com.lark.oapi.sample.ws.repository.SupplierJobRepository;
import com.lark.oapi.sample.ws.repository.SupplierNodeChatRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 11-17-unblock-instance-chat S3/S4/S5 单测（纯 mockito，不起进程、不进 Spring 上下文）。
 * <ul>
 *   <li>S3 解除占用：RUNNING→PENDING + note 留痕（含操作时间）+ 活跃轮次先中止；quick 记账不被本方法触碰</li>
 *   <li>S4 僵尸巡检：降级判定纯函数四条件矩阵 + 巡检集成（会话活跃绝不降级）</li>
 *   <li>S5/AC7 工作台直通：节点 RUNNING（卡点态）时 ask 无任何拦截，直达 chat()，且 steer 从未被调用</li>
 * </ul>
 */
class SupplierNodeReleaseTest {

    private static final long JOB_ID = 197L;
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
        job.setKnowledgeMode("TEXT");
        return job;
    }

    private SupplierJobNode node(String status, String pendingQuestion) {
        SupplierJobNode n = new SupplierJobNode();
        n.setId(2L);
        n.setJobId(JOB_ID);
        n.setNodeId("n2");
        n.setType("agent");
        n.setTitle("接口实测");
        n.setStatus(status);
        n.setPendingQuestion(pendingQuestion);
        return n;
    }

    /** updatedAt 无 setter（JPA 生命周期维护），测试用反射注入心跳时间 */
    private static void setUpdatedAt(SupplierJobNode n, LocalDateTime t) throws Exception {
        Field f = SupplierJobNode.class.getDeclaredField("updatedAt");
        f.setAccessible(true);
        f.set(n, t);
    }

    // ===== S3 releaseNode =====

    @Test
    void releaseResetsZombieRunningNodeWithTimestampedNote() {
        // AC3 场景：job 197/n2 — RUNNING + pq 空 + 会话空闲（getSession 返回 null=终端都没了）
        SupplierJobNode n = node("RUNNING", "");
        n.setProgress("🔧 Bash · curl …");
        n.setThinking("还要再跑一轮实测…");
        when(jobRepo.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(nodeRepo.findByJobIdAndNodeId(JOB_ID, "n2")).thenReturn(Optional.of(n));
        when(agent.getSession(INSTANCE_ID)).thenReturn(null);

        assertFalse(service().releaseNode(JOB_ID, "n2"));   // 无活跃轮次可中止

        ArgumentCaptor<SupplierJobNode> cap = ArgumentCaptor.forClass(SupplierJobNode.class);
        verify(nodeRepo).save(cap.capture());
        SupplierJobNode saved = cap.getValue();
        assertEquals("PENDING", saved.getStatus());          // 打回待执行 → runNode 不再拒绝重跑
        assertEquals("", saved.getPendingQuestion());
        assertEquals("", saved.getProgress());               // 陈旧进度/思考一并清掉
        assertEquals("", saved.getThinking());
        assertTrue(saved.getNote().contains("解除占用"), "note 必须留痕: " + saved.getNote());
        assertTrue(saved.getNote().contains(String.valueOf(LocalDateTime.now().getYear())), "note 必须含操作时间: " + saved.getNote());
        // quick 任务的 phase 记账不由 release 触碰（由 QuickProbeService 轮询线程看到 note 后自行置段失败）
        verify(jobRepo, never()).save(any());
    }

    @Test
    void releasePersistsReleaseBeforeAbortingActiveTurn() {
        SupplierJobNode n = node("RUNNING", "");
        TerminalSession session = mock(TerminalSession.class);
        when(session.isTurnActive()).thenReturn(true);
        when(jobRepo.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(nodeRepo.findByJobIdAndNodeId(JOB_ID, "n2")).thenReturn(Optional.of(n));
        when(agent.getSession(INSTANCE_ID)).thenReturn(session);

        assertTrue(service().releaseNode(JOB_ID, "n2"));

        // 顺序硬约束：先落库（PENDING+留痕）再中止——abort 会立刻唤醒旧轮次的陈旧回写（"已停止"），
        // 只有留痕先落库，applyNodeReply 的 RUNNING 所有权守卫才必然拦截它（否则留痕被抹、artifact 被污染）
        ArgumentCaptor<SupplierJobNode> cap = ArgumentCaptor.forClass(SupplierJobNode.class);
        InOrder inOrder = inOrder(nodeRepo, session);
        inOrder.verify(nodeRepo).save(cap.capture());
        inOrder.verify(session).abortCurrentTurn();   // S3：会话有活跃轮次则中止（用户显式触发，非自动）
        assertEquals("PENDING", cap.getValue().getStatus());
        assertTrue(cap.getValue().getNote().contains("中止"));
    }

    @Test
    void releaseNeverAbortsIdleSession() {
        SupplierJobNode n = node("RUNNING", "");
        TerminalSession session = mock(TerminalSession.class);
        when(session.isTurnActive()).thenReturn(false);   // 卡点态：轮次已结束、会话空闲
        when(jobRepo.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(nodeRepo.findByJobIdAndNodeId(JOB_ID, "n2")).thenReturn(Optional.of(n));
        when(agent.getSession(INSTANCE_ID)).thenReturn(session);

        assertFalse(service().releaseNode(JOB_ID, "n2"));

        verify(session, never()).abortCurrentTurn();
        verify(nodeRepo).save(any(SupplierJobNode.class));
    }

    @Test
    void releaseRejectsNonRunningNodes() {
        when(jobRepo.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(nodeRepo.findByJobIdAndNodeId(JOB_ID, "n2")).thenReturn(Optional.of(node("PENDING", "")));
        assertThrows(IllegalArgumentException.class, () -> service().releaseNode(JOB_ID, "n2"));

        when(nodeRepo.findByJobIdAndNodeId(JOB_ID, "n2")).thenReturn(Optional.of(node("DONE", "")));
        assertThrows(IllegalArgumentException.class, () -> service().releaseNode(JOB_ID, "n2"));
        verify(nodeRepo, never()).save(any());
        verify(agent, never()).getSession(anyLong());
    }

    @Test
    void releaseAppendsToExistingNoteWithoutOverwriting() {
        SupplierJobNode n = node("RUNNING", "");
        n.setNote("执行异常: 上一轮失败原因");
        when(jobRepo.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(nodeRepo.findByJobIdAndNodeId(JOB_ID, "n2")).thenReturn(Optional.of(n));
        when(agent.getSession(INSTANCE_ID)).thenReturn(null);

        service().releaseNode(JOB_ID, "n2");

        ArgumentCaptor<SupplierJobNode> cap = ArgumentCaptor.forClass(SupplierJobNode.class);
        verify(nodeRepo).save(cap.capture());
        assertTrue(cap.getValue().getNote().startsWith("执行异常: 上一轮失败原因\n"));
        assertTrue(cap.getValue().getNote().contains("解除占用"));
    }

    @Test
    void appendNotePureFunction() {
        assertEquals("新留痕", SupplierJobService.appendNote("", "新留痕"));
        assertEquals("新留痕", SupplierJobService.appendNote(null, "新留痕"));
        assertEquals("旧\n新留痕", SupplierJobService.appendNote("旧", "新留痕"));
        assertEquals("旧\n新留痕", SupplierJobService.appendNote("旧  ", "新留痕"));
    }

    // ===== 陈旧回写守卫：release/巡检接管后，被中止轮次的回写不得覆盖 DB 行 =====

    @Test
    void staleAbortedWritebackAfterReleaseIsDropped() {
        // release 先落库 PENDING+留痕，再 abort → 旧派发线程带着 "已停止" 醒来回写。
        // 若无守卫：留痕被旧实体 note="" 抹掉、artifact 被写成"已停止"，
        // QuickProbe 轮询会把 note 空+artifact 非空的段误判为成功并带垃圾工件续跑。
        SupplierJobNode released = node("PENDING", "");
        released.setNote("2026-11-17T10:00 手动解除占用（RUNNING→PENDING，已中止实例活跃轮次）");
        when(nodeRepo.findByJobIdAndNodeId(JOB_ID, "n2")).thenReturn(Optional.of(released));

        service().applyNodeReply(node("RUNNING", ""), "已停止");   // 派发时旧实体的陈旧回写

        verify(nodeRepo, never()).save(any());                      // 整体丢弃，DB 行不被触碰
        assertTrue(released.getNote().contains("手动解除占用"));     // 留痕完好 → QuickProbe 判段 FAILED 可重试
        assertEquals("", released.getArtifact());                   // 垃圾文本"已停止"未污染工件
    }

    @Test
    void writebackStillAppliesWhileNodeRunning() {
        // 守卫不得误伤正常链路：DB 行仍 RUNNING（无人接管）→ 回写照常落 artifact 回 PENDING
        when(nodeRepo.findByJobIdAndNodeId(JOB_ID, "n2")).thenReturn(Optional.of(node("RUNNING", "")));

        service().applyNodeReply(node("RUNNING", ""), "分析完成。");

        ArgumentCaptor<SupplierJobNode> cap = ArgumentCaptor.forClass(SupplierJobNode.class);
        verify(nodeRepo).save(cap.capture());
        assertEquals("PENDING", cap.getValue().getStatus());
        assertEquals("分析完成。", cap.getValue().getArtifact());
    }

    @Test
    void staleBlockWritebackAfterSweepIsDropped() {
        // 巡检降级同理：迟到的【卡点】回写不得把已打回 PENDING 的节点重新挂起
        SupplierJobNode swept = node("PENDING", "");
        swept.setNote("已自动解除僵尸占用");
        when(nodeRepo.findByJobIdAndNodeId(JOB_ID, "n2")).thenReturn(Optional.of(swept));

        service().applyNodeReply(node("RUNNING", ""), "【卡点】请提供测试凭证");

        verify(nodeRepo, never()).save(any());
        assertEquals("", swept.getPendingQuestion());
    }

    // ===== S4 降级判定纯函数（AC4 矩阵） =====

    @Test
    void zombieDecisionRequiresAllFourConditions() {
        // 到点（≥阈值）+ RUNNING + pq 空 + 会话空闲 → 降级
        assertTrue(SupplierJobService.shouldReleaseZombie("RUNNING", "", false, 10, 10));   // 恰达阈值
        assertTrue(SupplierJobService.shouldReleaseZombie("RUNNING", "", false, 11, 10));
        assertTrue(SupplierJobService.shouldReleaseZombie("RUNNING", null, false, 60, 10)); // pq null 归一为空
        // 未到点 → 不动
        assertFalse(SupplierJobService.shouldReleaseZombie("RUNNING", "", false, 9, 10));
        assertFalse(SupplierJobService.shouldReleaseZombie("RUNNING", "", false, 0, 10));
        // 会话活跃 → 绝不降级（哪怕空闲计时再长、pq 再空）
        assertFalse(SupplierJobService.shouldReleaseZombie("RUNNING", "", true, 600, 10));
        // 有待答问题 → 卡点等用户，不是僵尸
        assertFalse(SupplierJobService.shouldReleaseZombie("RUNNING", "【卡点】用哪个环境？", false, 600, 10));
        // 空白 pq 与空串等价（answerNode 清空后残留空白的僵尸候选）
        assertTrue(SupplierJobService.shouldReleaseZombie("RUNNING", "  ", false, 600, 10));
        // 非 RUNNING → 与本判定无关
        assertFalse(SupplierJobService.shouldReleaseZombie("PENDING", "", false, 600, 10));
        assertFalse(SupplierJobService.shouldReleaseZombie("DONE", "", false, 600, 10));
        assertFalse(SupplierJobService.shouldReleaseZombie(null, "", false, 600, 10));
    }

    // ===== S4 巡检集成 =====

    @Test
    void sweepDemotesIdleZombieAndLeavesNote() throws Exception {
        SupplierJobNode n = node("RUNNING", "");
        setUpdatedAt(n, LocalDateTime.now().minusMinutes(20));   // 心跳停在 20 分钟前（默认阈值 10）
        TerminalSession session = mock(TerminalSession.class);
        when(session.isTurnActive()).thenReturn(false);
        when(nodeRepo.findByStatus("RUNNING")).thenReturn(List.of(n));
        when(jobRepo.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(agent.getSession(INSTANCE_ID)).thenReturn(session);

        service().sweepZombieRunningNodes();

        ArgumentCaptor<SupplierJobNode> cap = ArgumentCaptor.forClass(SupplierJobNode.class);
        verify(nodeRepo).save(cap.capture());
        assertEquals("PENDING", cap.getValue().getStatus());
        assertTrue(cap.getValue().getNote().contains("已自动解除僵尸占用"), cap.getValue().getNote());
        verify(session, never()).abortCurrentTurn();   // 巡检只降级状态，绝不打断任何轮次
        verify(jobRepo, never()).save(any());          // quick 记账同样不被巡检直接触碰
    }

    @Test
    void sweepNeverTouchesNodeWhileSessionTurnActive() throws Exception {
        // AC4 后半：会话活跃 → 不动（哪怕节点心跳停了很久、pq 为空）
        SupplierJobNode n = node("RUNNING", "");
        setUpdatedAt(n, LocalDateTime.now().minusMinutes(120));
        TerminalSession session = mock(TerminalSession.class);
        when(session.isTurnActive()).thenReturn(true);
        when(nodeRepo.findByStatus("RUNNING")).thenReturn(List.of(n));
        when(jobRepo.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(agent.getSession(INSTANCE_ID)).thenReturn(session);

        service().sweepZombieRunningNodes();

        verify(nodeRepo, never()).save(any());
    }

    @Test
    void sweepSkipsStalledNodesWithPendingQuestion() throws Exception {
        // 卡点待答（RUNNING+pq 非空）不是僵尸：等用户答复，巡检必须绕开
        SupplierJobNode n = node("RUNNING", "【卡点】请提供测试凭证");
        setUpdatedAt(n, LocalDateTime.now().minusMinutes(120));
        when(nodeRepo.findByStatus("RUNNING")).thenReturn(List.of(n));

        service().sweepZombieRunningNodes();

        verify(nodeRepo, never()).save(any());
        verify(agent, never()).getSession(anyLong());   // pq 非空直接短路，连会话都不用查
    }

    @Test
    void sweepSkipsNodesBelowIdleThreshold() throws Exception {
        // 刚派发不久的 RUNNING（真在跑）：心跳 5 分钟前 < 阈值 10 → 不动
        SupplierJobNode n = node("RUNNING", "");
        setUpdatedAt(n, LocalDateTime.now().minusMinutes(5));
        when(nodeRepo.findByStatus("RUNNING")).thenReturn(List.of(n));
        when(jobRepo.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(agent.getSession(INSTANCE_ID)).thenReturn(null);

        service().sweepZombieRunningNodes();

        verify(nodeRepo, never()).save(any());
    }

    @Test
    void sweepFallsBackToStartedAtAndSkipsWithoutAnyAnchor() throws Exception {
        SupplierJobNode noAnchor = node("RUNNING", "");   // updatedAt/startedAt 均缺失 → 无法判定，跳过
        SupplierJobNode byStart = node("RUNNING", "");
        byStart.setStartedAt(LocalDateTime.now().minusMinutes(30));   // 旧行无心跳 → 退回 startedAt
        when(nodeRepo.findByStatus("RUNNING")).thenReturn(List.of(noAnchor, byStart));
        when(jobRepo.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(agent.getSession(INSTANCE_ID)).thenReturn(null);

        service().sweepZombieRunningNodes();

        ArgumentCaptor<SupplierJobNode> cap = ArgumentCaptor.forClass(SupplierJobNode.class);
        verify(nodeRepo, times(1)).save(cap.capture());
        assertSame(byStart, cap.getValue());
        assertEquals("PENDING", byStart.getStatus());
    }

    @Test
    void sweepSwallowsRepoFailure() {
        when(nodeRepo.findByStatus("RUNNING")).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> service().sweepZombieRunningNodes());   // 巡检失败不影响主流程，下轮再试
    }

    // ===== S5/AC7：工作台对话链路在卡点态无任何 RUNNING 拦截，直通 chat()，steer 绝不被调用 =====

    @Test
    void askGoesStraightToMainSessionWhileNodeStalledRunning() {
        // 实跑核实（S5 第4条）：节点 RUNNING（卡点态，会话空闲）时 ask 链路
        // SupplierJobController→SupplierJobService.ask 不存在以 RUNNING 为条件的发送拦截或延迟——
        // ask 甚至不读节点状态，落库后立即在虚拟线程里调 chat()。
        when(jobRepo.findById(JOB_ID)).thenReturn(Optional.of(job()));
        when(instanceRepo.findById(INSTANCE_ID)).thenReturn(Optional.of(new BotInstance() {{ setId(INSTANCE_ID); }}));
        when(knowledge.chatContext(any(), anyString()))
                .thenReturn(new SupplierKnowledgeService.Pack("TEXT", "知识库索引", "", null, -1, List.of()));
        when(agent.chat(eq(INSTANCE_ID), anyString(), anyString(), anyString(), nullable(String.class), any(TurnListener.class)))
                .thenReturn("会话空闲，立即回答");

        service().ask(JOB_ID, "现在什么情况");

        // 立即（无门禁、无延迟分支）进入 chat；来源标识仍是工作台 lane 前缀
        verify(agent, timeout(10_000)).chat(eq(INSTANCE_ID), argThat(o -> o.startsWith("supplier-job-")),
                contains("现在什么情况"), anyString(), nullable(String.class), any(TurnListener.class));
        // S5 禁止项①：steer 中途插话在本链路一次都不许调用
        verify(agent, never()).steer(anyLong(), anyString());
        // 也不许借道独立 lane 绕开主会话
        verify(agent, never()).chatInLane(anyString(), anyLong(), anyString(), anyString(), nullable(String.class), any(TurnListener.class));
    }
}
