package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 数字员工的一条需求 — 下发即入队（QUEUED），由调度器指派绑定实例自动执行。
 * <p>
 * instanceId = 实际执行实例（"哪个实例做哪个需求"的映射，调度时写入）；
 * requestedInstanceId = 用户下发时手动指定的实例（可空 = 自动分派）。
 * v1 不提供 workingDir/model 覆盖：一律用实例配置——ClaudeCodeService.getOrCreate
 * 发现目录/模型变化会 destroy 重建会话（杀死活跃轮次+丢 CLI 上下文），覆盖字段是脚枪。
 */
@Entity
@Table(name = "employee_demand")
public class EmployeeDemand {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id")
    private Long employeeId;

    /** 需求标题（下发缺省 = 需求全文前 30 字） */
    private String title = "";

    @Lob
    private String requirement = "";

    /** QUEUED | RUNNING | DONE | FAILED | CANCELLED | INTERRUPTED */
    private String status = "QUEUED";

    /** 实际执行实例（调度指派；重启前 RUNNING 的映射保留供展示） */
    @Column(name = "instance_id")
    private Long instanceId;

    /** 用户下发时手动指定的实例（可空 = 自动分派；重试时保留） */
    @Column(name = "requested_instance_id")
    private Long requestedInstanceId;

    /** 执行完成的汇报全文 */
    @Lob
    private String result;

    @Column(name = "error_message", length = 1000)
    private String errorMessage;

    /** 排队卡住是否已上报过（每个需求只提醒一次；重试时复位） */
    @Column(name = "stuck_reported")
    private boolean stuckReported = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEmployeeId() { return employeeId; }
    public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getRequirement() { return requirement; }
    public void setRequirement(String requirement) { this.requirement = requirement; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getInstanceId() { return instanceId; }
    public void setInstanceId(Long instanceId) { this.instanceId = instanceId; }
    public Long getRequestedInstanceId() { return requestedInstanceId; }
    public void setRequestedInstanceId(Long requestedInstanceId) { this.requestedInstanceId = requestedInstanceId; }
    public String getResult() { return result; }
    public void setResult(String result) { this.result = result; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public boolean isStuckReported() { return stuckReported; }
    public void setStuckReported(boolean stuckReported) { this.stuckReported = stuckReported; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
}
