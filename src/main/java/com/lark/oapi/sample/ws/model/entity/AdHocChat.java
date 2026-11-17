package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 临时需求任务对话流水（用户 ↔ 绑定实例主会话）——镜像 {@link SupplierJobChat} 的留档语义：
 * 用户吩咐与实例回复都落库，agent 行 PENDING 期间 text 字段被节流回写"实时进度摘要"，
 * 前端轮询即可见"在做什么"；轮次结束后由最终回复覆盖（靠 status 区分进度/答案）。
 * <p>
 * 独立表 ad_hoc_chat（task_id 指向 ad_hoc_task），不写 supplier_job_chat。
 */
@Entity
@Table(name = "ad_hoc_chat")
public class AdHocChat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** user（用户）| agent（实例回复） */
    @Column(nullable = false)
    private String role = "user";

    /** 仅 agent 行使用：PENDING（排队/执行中）| DONE | FAILED */
    @Column(nullable = false)
    private String status = "DONE";

    @Lob
    @Column(length = 2147483647, nullable = false)
    private String text = "";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getTaskId() { return taskId; }
    public void setTaskId(Long taskId) { this.taskId = taskId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
