package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 供应商存档对话流水（玩家 ↔ 执行实例）— 执行过程中玩家可随时提问"现在什么情况"，
 * 提问以独立轮次排队进实例会话（当前执行段结束后被回答），问答都留档在这里供回看。
 */
@Entity
@Table(name = "supplier_job_chat")
public class SupplierJobChat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    /** user（玩家）| agent（实例回复） */
    @Column(nullable = false)
    private String role = "user";

    /** 仅 agent 行使用：PENDING（排队/执行中）| DONE | FAILED */
    @Column(nullable = false)
    private String status = "DONE";

    @Lob
    @Column(nullable = false)
    private String text = "";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
