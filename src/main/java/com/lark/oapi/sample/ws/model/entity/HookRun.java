package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Hook 单次触发记录 — "什么时候触发的、做了什么"的台账。
 */
@Entity
@Table(name = "hook_run")
public class HookRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hook_id")
    private Long hookId;

    /** AFTER_CHAT | CRON | MANUAL */
    @Column(name = "trigger_type")
    private String triggerType;

    /** JUDGED_SKIP(LLM判定无需触发) | NO_MSG(窗口无对话) | RUNNING | SUCCESS | FAILED */
    private String status;

    @Column(name = "window_from")
    private LocalDateTime windowFrom;

    @Column(name = "window_to")
    private LocalDateTime windowTo;

    @Column(name = "message_count")
    private Integer messageCount = 0;

    /** LLM 判定理由（AFTER_CHAT）或失败原因 */
    @Column(name = "judge_reason", length = 1000)
    private String judgeReason;

    /** 本次维护摘要（模型 lesson + changes 概述） */
    @Column(name = "summary", length = 4000)
    private String summary;

    @Column(name = "duration_ms")
    private Long durationMs;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getHookId() { return hookId; }
    public void setHookId(Long hookId) { this.hookId = hookId; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public LocalDateTime getWindowFrom() { return windowFrom; }
    public void setWindowFrom(LocalDateTime windowFrom) { this.windowFrom = windowFrom; }
    public LocalDateTime getWindowTo() { return windowTo; }
    public void setWindowTo(LocalDateTime windowTo) { this.windowTo = windowTo; }
    public Integer getMessageCount() { return messageCount; }
    public void setMessageCount(Integer messageCount) { this.messageCount = messageCount; }
    public String getJudgeReason() { return judgeReason; }
    public void setJudgeReason(String judgeReason) { this.judgeReason = judgeReason; }
    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }
    public Long getDurationMs() { return durationMs; }
    public void setDurationMs(Long durationMs) { this.durationMs = durationMs; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
