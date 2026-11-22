package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * eval 单次执行结果（11-21-supplier-eval-loop）：每次试跑/轮跑落一行，构成状态轨迹的原始记录
 * （首跑结果、第几轮转通过、是否曾通过又失败——轨迹归纳属后续 loop 范围，本轮只保证记录不丢）。
 * verdict 只来自进程执行结果，绝无模型判断通道。
 */
@Entity
@Table(name = "eval_case_result")
public class EvalCaseResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "case_id", nullable = false)
    private Long caseId;

    /** TRIAL（单条试跑）| RUN（全量轮跑，MVP-2） */
    @Column(name = "trigger_mode", length = 16)
    private String triggerMode = "TRIAL";

    /** PASS | FAIL | TIMEOUT | SKIPPED | ERROR（终态，不存 RUNNING） */
    @Column(length = 16, nullable = false)
    private String verdict = "";

    /** 进程退出码；超时强杀 = null（或 -1 由 runner 记），SKIPPED = null */
    @Column(name = "exit_code")
    private Integer exitCode;

    @Column(name = "elapsed_ms", nullable = false)
    private long elapsedMs;

    /** 判定依据摘要（失败原因/跳过原因/断言明细），PASS 时为空串 */
    @Lob
    @Column(length = 2147483647, name = "reason")
    private String reason = "";

    /** 输出摘要（头尾截断） */
    @Lob
    @Column(length = 2147483647, name = "output_summary")
    private String outputSummary = "";

    /** 完整（截断上限内）输出落盘文件绝对路径 */
    @Column(name = "output_file", length = 600)
    private String outputFile = "";

    @Column(name = "ran_at")
    private LocalDateTime ranAt;

    @PrePersist
    void onCreate() { if (ranAt == null) ranAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public Long getCaseId() { return caseId; }
    public void setCaseId(Long caseId) { this.caseId = caseId; }
    public String getTriggerMode() { return triggerMode == null ? "TRIAL" : triggerMode; }
    public void setTriggerMode(String triggerMode) { this.triggerMode = triggerMode; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String verdict) { this.verdict = verdict; }
    public Integer getExitCode() { return exitCode; }
    public void setExitCode(Integer exitCode) { this.exitCode = exitCode; }
    public long getElapsedMs() { return elapsedMs; }
    public void setElapsedMs(long elapsedMs) { this.elapsedMs = elapsedMs; }
    public String getReason() { return reason == null ? "" : reason; }
    public void setReason(String reason) { this.reason = reason; }
    public String getOutputSummary() { return outputSummary == null ? "" : outputSummary; }
    public void setOutputSummary(String outputSummary) { this.outputSummary = outputSummary; }
    public String getOutputFile() { return outputFile == null ? "" : outputFile; }
    public void setOutputFile(String outputFile) { this.outputFile = outputFile; }
    public LocalDateTime getRanAt() { return ranAt; }
    public void setRanAt(LocalDateTime ranAt) { this.ranAt = ranAt; }
}
