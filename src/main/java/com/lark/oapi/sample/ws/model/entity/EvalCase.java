package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 单条可执行 eval（11-21-supplier-eval-loop MVP-1）。
 * <p>
 * 判定纪律：verdict 只来自 {@code EvalRunner} 的进程执行结果（退出码 + expect 断言），
 * 本实体不存任何主观评分字段。{@code target} 为空 = <b>未落地</b>（尚无可执行形式），
 * 未落地与 SKIPPED 一律不进通过率分母（PRD"分母诚实"纪律）。
 * <p>
 * kind 取值：build | test | script | http | static（全部客观可执行，见 PRD 数据模型表）。
 */
@Entity
@Table(name = "eval_case")
public class EvalCase {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(nullable = false)
    private String title = "";

    /** build | test | script | http | static */
    @Column(length = 16, nullable = false)
    private String kind = "script";

    /** 完整命令行（Windows 走 cmd /c，Unix 走 bash -lc）；空 = 未落地，不可执行 */
    @Lob
    @Column(length = 2147483647, name = "target")
    private String target = "";

    /**
     * 输出断言（每行一个条件，全部满足且退出码符合才 PASS）：
     * {@code exit=<n>} / {@code stdout~<正则>} / {@code stdout=<包含子串>} / {@code json:<点路径>=<期望值>}。
     * 空 = 只看退出码 0。语法见 EvalRunner.parseExpect。
     */
    @Lob
    @Column(length = 2147483647, name = "expect")
    private String expect = "";

    /** 自然语言预期/来源摘录（草稿生成时存 review skill 原文；翻译成可执行形式前保持未落地） */
    @Lob
    @Column(length = 2147483647, name = "notes")
    private String notes = "";

    /** 超时强杀秒数（可配；默认 120） */
    @Column(name = "timeout_sec", nullable = false)
    private int timeoutSec = 120;

    /** 需要供应商沙箱凭据；无凭据时试跑判 SKIPPED（单列，不进分母） */
    @Column(name = "requires_sandbox", nullable = false)
    private boolean requiresSandbox = false;

    /**
     * 是否计入通过率分母（MVP-1.7）。false = 可用性探针：可跑、可看输出，但只回答
     * 「供应商活着吗/集合签名对不对」（如直连供应商网关、绕过被测适配器代码的检查），
     * <b>不证明本次交付的代码正确</b>——verdict 单独统计，绝不混进 executed/passed/failed。
     * <p>
     * 用包装类型 + getter 里 null→true：ddl-auto=update 对存量表加的是可空列，
     * 现网存量行（如用户真任务 id=2 已 PASS 的 case）读出来是 NULL，一律按默认值 true 处理——
     * 不许让加列把存量计分项静默变成探针（分母缩水 = 通过率造假，方向同样是 dishonest）。
     */
    @Column(name = "counts_toward_score")
    private Boolean countsTowardScore = Boolean.TRUE;

    @Column(nullable = false)
    private boolean enabled = true;

    /** 列表展示排序（草稿批量导入按生成顺序） */
    @Column(name = "sort_order", nullable = false)
    private int sortOrder = 0;

    // ===== 最近一次执行快照（详情列表直接展示；完整历史在 eval_case_result） =====

    /** PASS | FAIL | TIMEOUT | SKIPPED | ERROR | RUNNING（执行中，瞬态）；null = 未跑过 */
    @Column(name = "last_verdict", length = 16)
    private String lastVerdict;

    @Column(name = "last_exit_code")
    private Integer lastExitCode;

    @Column(name = "last_elapsed_ms")
    private Long lastElapsedMs;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    /** 判定失败原因（超时/断言不匹配明细/跳过原因），PASS 时为空 */
    @Lob
    @Column(length = 2147483647, name = "last_reason")
    private String lastReason;

    /** 输出摘要（头尾截断，落库展示用）；完整截断输出另存磁盘 lastOutputFile */
    @Lob
    @Column(length = 2147483647, name = "last_output_summary")
    private String lastOutputSummary;

    @Column(name = "last_output_file", length = 600)
    private String lastOutputFile;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getTitle() { return title == null ? "" : title; }
    public void setTitle(String title) { this.title = title; }
    public String getKind() { return kind == null || kind.isBlank() ? "script" : kind; }
    public void setKind(String kind) { this.kind = kind; }
    public String getTarget() { return target == null ? "" : target; }
    public void setTarget(String target) { this.target = target; }
    public String getExpect() { return expect == null ? "" : expect; }
    public void setExpect(String expect) { this.expect = expect; }
    public String getNotes() { return notes == null ? "" : notes; }
    public void setNotes(String notes) { this.notes = notes; }
    public int getTimeoutSec() { return timeoutSec <= 0 ? 120 : timeoutSec; }
    public void setTimeoutSec(int timeoutSec) { this.timeoutSec = timeoutSec; }
    public boolean isRequiresSandbox() { return requiresSandbox; }
    public void setRequiresSandbox(boolean requiresSandbox) { this.requiresSandbox = requiresSandbox; }
    /** null（存量行加列前落库的）一律按默认值 true 处理 */
    public boolean isCountsTowardScore() { return countsTowardScore == null || countsTowardScore; }
    public void setCountsTowardScore(Boolean countsTowardScore) { this.countsTowardScore = countsTowardScore; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public String getLastVerdict() { return lastVerdict; }
    public void setLastVerdict(String lastVerdict) { this.lastVerdict = lastVerdict; }
    public Integer getLastExitCode() { return lastExitCode; }
    public void setLastExitCode(Integer lastExitCode) { this.lastExitCode = lastExitCode; }
    public Long getLastElapsedMs() { return lastElapsedMs; }
    public void setLastElapsedMs(Long lastElapsedMs) { this.lastElapsedMs = lastElapsedMs; }
    public LocalDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(LocalDateTime lastRunAt) { this.lastRunAt = lastRunAt; }
    public String getLastReason() { return lastReason == null ? "" : lastReason; }
    public void setLastReason(String lastReason) { this.lastReason = lastReason; }
    public String getLastOutputSummary() { return lastOutputSummary == null ? "" : lastOutputSummary; }
    public void setLastOutputSummary(String lastOutputSummary) { this.lastOutputSummary = lastOutputSummary; }
    public String getLastOutputFile() { return lastOutputFile == null ? "" : lastOutputFile; }
    public void setLastOutputFile(String lastOutputFile) { this.lastOutputFile = lastOutputFile; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
