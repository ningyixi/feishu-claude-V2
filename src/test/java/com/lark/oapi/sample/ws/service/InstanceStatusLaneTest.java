package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.dto.InstanceStatusDTO;
import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * lane（工作台对话/节点执行/devChat 后台会话）纳入实例状态合成 — 11-05 bug 修复回归。
 * 根因：resolve() 只看主会话（飞书私聊），lane 忙时实例仍显示"空闲中/摸鱼中"。
 */
class InstanceStatusLaneTest {

    private static final long ID = 9L;

    private static BotInstance enabledInstance() {
        BotInstance inst = new BotInstance();
        inst.setId(ID);
        inst.setEnabled(true);
        return inst;
    }

    private static InstanceStatusService service(FeishuBotService feishu, ClaudeCodeService cc) {
        return new InstanceStatusService(mock(BotInstanceRepository.class), feishu, cc);
    }

    private static FeishuBotService onlineFeishu(long lastMsg) {
        FeishuBotService feishu = mock(FeishuBotService.class);
        when(feishu.isWsAlive(ID)).thenReturn(true);
        when(feishu.getLastMessageTime(ID)).thenReturn(lastMsg);
        return feishu;
    }

    private static TerminalSession busyLaneSession(TerminalSession.ActivityPhase phase, String tool, long elapsedMs) {
        TerminalSession lane = mock(TerminalSession.class);
        when(lane.isTurnActive()).thenReturn(true);
        when(lane.getActivityPhase()).thenReturn(phase);
        when(lane.getCurrentToolName()).thenReturn(tool);
        when(lane.getTurnElapsedMs()).thenReturn(elapsedMs);
        when(lane.getQueuedCount()).thenReturn(0);
        return lane;
    }

    @Test
    void laneBusyWhileMainIdleShowsBackgroundWorking() {
        ClaudeCodeService cc = mock(ClaudeCodeService.class);
        when(cc.getSession(ID)).thenReturn(null);   // 主会话不存在（只跑过工作台任务）
        TerminalSession lane = busyLaneSession(TerminalSession.ActivityPhase.TOOL_RUNNING, "Bash", 200_000);
        when(cc.busiestLaneActivity(ID)).thenReturn(
                Optional.of(new ClaudeCodeService.LaneActivity(lane, "supplier-job-1-TEXT-9")));

        InstanceStatusDTO dto = service(onlineFeishu(0), cc).resolve(enabledInstance());

        assertEquals("WORKING", dto.getCode());
        assertTrue(dto.isBusy(), "lane 活跃应判定为忙碌");
        assertEquals("🛠️", dto.getEmoji());
        assertEquals("后台任务中", dto.getLabel());
        assertTrue(dto.getDetail().contains("后台"), "文案应带后台前缀: " + dto.getDetail());
        assertTrue(dto.getDetail().contains("Bash"));
        assertTrue(dto.getDetail().contains("3分20秒"), "应含已耗时: " + dto.getDetail());
    }

    @Test
    void laneThinkingPhaseMapsToThinkingWithBackgroundLabel() {
        ClaudeCodeService cc = mock(ClaudeCodeService.class);
        TerminalSession main = mock(TerminalSession.class);
        when(main.isTurnActive()).thenReturn(false);   // 主会话存在但空闲
        when(main.isAlive()).thenReturn(true);
        when(main.getLastTurnEndTime()).thenReturn(0L);
        when(cc.getSession(ID)).thenReturn(main);
        TerminalSession lane = busyLaneSession(TerminalSession.ActivityPhase.THINKING, null, 40_000);
        when(cc.busiestLaneActivity(ID)).thenReturn(
                Optional.of(new ClaudeCodeService.LaneActivity(lane, "lane-x")));

        InstanceStatusDTO dto = service(onlineFeishu(0), cc).resolve(enabledInstance());

        assertEquals("THINKING", dto.getCode());
        assertTrue(dto.isBusy());
        assertEquals("后台任务中", dto.getLabel());
        assertTrue(dto.getDetail().contains("思考中"));
    }

    @Test
    void mainSessionBusyKeepsExistingCopy() {
        ClaudeCodeService cc = mock(ClaudeCodeService.class);
        TerminalSession main = busyLaneSession(TerminalSession.ActivityPhase.TOOL_RUNNING, "Read", 5_000);
        when(cc.getSession(ID)).thenReturn(main);
        when(cc.busiestLaneActivity(ID)).thenReturn(Optional.empty());

        InstanceStatusDTO dto = service(onlineFeishu(0), cc).resolve(enabledInstance());

        assertEquals("WORKING", dto.getCode());
        assertEquals("工作中", dto.getLabel());
        assertTrue(dto.getDetail().contains("正在执行 Read"));
        assertFalse(dto.getDetail().contains("后台"), "主会话活跃仍用现有文案: " + dto.getDetail());
    }

    @Test
    void noLaneNoSessionRegressionStaysPristineIdle() {
        ClaudeCodeService cc = mock(ClaudeCodeService.class);
        when(cc.getSession(ID)).thenReturn(null);
        when(cc.busiestLaneActivity(ID)).thenReturn(Optional.empty());
        when(cc.lastLaneActivityMs(ID)).thenReturn(0L);

        InstanceStatusDTO dto = service(onlineFeishu(0), cc).resolve(enabledInstance());

        assertEquals("IDLE", dto.getCode());
        assertTrue(dto.getDetail().contains("矜持"), "无任何活动应显示'还没人找它聊过天': " + dto.getDetail());
    }

    @Test
    void laneRecentlyFinishedShowsDone() {
        ClaudeCodeService cc = mock(ClaudeCodeService.class);
        when(cc.getSession(ID)).thenReturn(null);
        when(cc.busiestLaneActivity(ID)).thenReturn(Optional.empty());
        when(cc.lastLaneActivityMs(ID)).thenReturn(System.currentTimeMillis() - 30_000);   // lane 30秒前跑完

        InstanceStatusDTO dto = service(onlineFeishu(0), cc).resolve(enabledInstance());

        assertEquals("DONE", dto.getCode(), "DONE 窗口应把 lane 最后结束时间算进去");
        assertEquals("任务完成", dto.getLabel());
    }

    @Test
    void laneActivityFeedsIdleGrading() {
        ClaudeCodeService cc = mock(ClaudeCodeService.class);
        when(cc.getSession(ID)).thenReturn(null);
        when(cc.busiestLaneActivity(ID)).thenReturn(Optional.empty());
        when(cc.lastLaneActivityMs(ID)).thenReturn(System.currentTimeMillis() - 15 * 60_000L);   // 15分钟前

        InstanceStatusDTO dto = service(onlineFeishu(0), cc).resolve(enabledInstance());

        assertEquals("FISHING", dto.getCode(), "lane 活动时间应并入空闲分级（15分钟 → 摸鱼）");
    }

    @Test
    void laneProcessDeathDoesNotMarkCrashed() {
        ClaudeCodeService cc = mock(ClaudeCodeService.class);
        TerminalSession main = mock(TerminalSession.class);
        when(main.isTurnActive()).thenReturn(false);
        when(main.isAlive()).thenReturn(true);   // 主会话健康
        when(main.getLastTurnEndTime()).thenReturn(0L);
        when(cc.getSession(ID)).thenReturn(main);
        when(cc.busiestLaneActivity(ID)).thenReturn(Optional.empty());   // lane 进程死了 → 不活跃
        when(cc.lastLaneActivityMs(ID)).thenReturn(System.currentTimeMillis() - 5 * 60_000L);

        InstanceStatusDTO dto = service(onlineFeishu(0), cc).resolve(enabledInstance());

        assertNotEquals("CRASHED", dto.getCode(), "lane 是临时性的，其进程死亡不产生 CRASHED");
        assertEquals("IDLE", dto.getCode());
    }
}
