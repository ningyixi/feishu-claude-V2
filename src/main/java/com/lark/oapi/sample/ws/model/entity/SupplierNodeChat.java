package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 开发期对话节点流水（三期 R4）：玩家原始话 ↔ 系统重写提示词，按 存档+节点 隔离，无限轮。
 */
@Entity
@Table(name = "supplier_node_chat")
public class SupplierNodeChat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "node_id", nullable = false)
    private String nodeId = "";

    /** user（玩家原始话）| agent（重写出的提示词） */
    @Column(nullable = false)
    private String role = "user";

    /** agent 行：PENDING | DONE | FAILED */
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
    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
