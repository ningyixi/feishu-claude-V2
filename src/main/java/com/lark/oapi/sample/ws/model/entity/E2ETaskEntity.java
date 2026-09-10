package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;

/**
 * E2E 测试群聊任务（持久化）— 重启后读档恢复。
 * 成员/流水线状态以 JSON 快照存储（membersJson/pipelineJson），对话记录在 e2e_turn 表。
 */
@Entity
@Table(name = "e2e_task")
public class E2ETaskEntity {

    @Id
    @Column(length = 16)
    private String id;

    @Lob
    @Column(name = "members_json")
    private String membersJson = "[]";

    @Lob
    private String requirement = "";

    @Column(name = "auto_fix")
    private boolean autoFix = true;

    @Column(name = "max_rounds")
    private int maxRounds = 3;

    @Column(name = "shared_dir", length = 600)
    private String sharedDir;

    /** 成员通信方式: filesystem(共享文件系统/黑板模式) | a2a(Agent2Agent,开发中) */
    @Column(name = "comm_mode")
    private String commMode = "filesystem";

    /** 流水线状态 JSON: {stage,stageDetail,round,failReason,cases[],rounds[](含results[])} */
    @Lob
    @Column(name = "pipeline_json")
    private String pipelineJson = "{}";

    @Column(name = "pipeline_started")
    private boolean pipelineStarted = false;

    @Column(name = "pipeline_done")
    private boolean pipelineDone = false;

    @Column(name = "created_at")
    private long createdAt;

    @Column(name = "updated_at")
    private long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getMembersJson() { return membersJson; }
    public void setMembersJson(String membersJson) { this.membersJson = membersJson; }
    public String getRequirement() { return requirement; }
    public void setRequirement(String requirement) { this.requirement = requirement; }
    public boolean isAutoFix() { return autoFix; }
    public void setAutoFix(boolean autoFix) { this.autoFix = autoFix; }
    public int getMaxRounds() { return maxRounds; }
    public void setMaxRounds(int maxRounds) { this.maxRounds = maxRounds; }
    public String getSharedDir() { return sharedDir; }
    public void setSharedDir(String sharedDir) { this.sharedDir = sharedDir; }
    public String getCommMode() { return commMode; }
    public void setCommMode(String commMode) { this.commMode = commMode; }
    public String getPipelineJson() { return pipelineJson; }
    public void setPipelineJson(String pipelineJson) { this.pipelineJson = pipelineJson; }
    public boolean isPipelineStarted() { return pipelineStarted; }
    public void setPipelineStarted(boolean pipelineStarted) { this.pipelineStarted = pipelineStarted; }
    public boolean isPipelineDone() { return pipelineDone; }
    public void setPipelineDone(boolean pipelineDone) { this.pipelineDone = pipelineDone; }
    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }
}
