package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 工作节点实例（存档内的节点状态）— 创建工作时从模板快照而来。
 * 状态流转：PENDING(蓝) → RUNNING(蓝+转圈，agent 执行中) → 玩家点完成后 DONE(绿)。
 * agent 节点执行产物存 artifact（浏览器展示、可手动编辑、可带纠正指令重跑）。
 */
@Entity
@Table(name = "supplier_job_node")
public class SupplierJobNode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "job_id")
    private Long jobId;

    /** 模板节点 id（如 n1） */
    @Column(name = "node_id")
    private String nodeId;

    private Integer seq = 0;

    private String title = "";

    /** manual | reminder | agent | agent_check */
    private String type = "manual";

    @Lob
    private String description = "";

    /** agent/agent_check 节点的 prompt 模板（支持 {supplierName} 等占位符） */
    @Lob
    private String prompt = "";

    /** PENDING | RUNNING | DONE */
    private String status = "PENDING";

    /** agent 执行产物 / 玩家补充的笔记（markdown） */
    @Lob
    private String artifact = "";

    /** 最近一次执行的备注（失败原因等） */
    @Lob
    private String note = "";

    /** 卡点待答（R7）：agent 输出【卡点】后的完整回复，等待玩家答复注入；空=无卡点 */
    @Lob
    @Column(name = "pending_question")
    private String pendingQuestion = "";

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "done_at")
    private LocalDateTime doneAt;

    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long jobId) { this.jobId = jobId; }
    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }
    public Integer getSeq() { return seq; }
    public void setSeq(Integer seq) { this.seq = seq; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getPrompt() { return prompt; }
    public void setPrompt(String prompt) { this.prompt = prompt; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getArtifact() { return artifact; }
    public void setArtifact(String artifact) { this.artifact = artifact; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public String getPendingQuestion() { return pendingQuestion != null ? pendingQuestion : ""; }
    public void setPendingQuestion(String pendingQuestion) { this.pendingQuestion = pendingQuestion; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getDoneAt() { return doneAt; }
    public void setDoneAt(LocalDateTime doneAt) { this.doneAt = doneAt; }
    public LocalDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(LocalDateTime lastRunAt) { this.lastRunAt = lastRunAt; }
}
