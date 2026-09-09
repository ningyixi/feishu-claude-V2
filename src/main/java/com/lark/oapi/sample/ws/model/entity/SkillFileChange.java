package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Skill 文件变更记录 — "skill 被维护修改的记录表"数据源。
 * 以执行前后文件哈希对比为准（真实变更），摘要来自模型自报。
 */
@Entity
@Table(name = "skill_file_change")
public class SkillFileChange {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "run_id")
    private Long runId;

    @Column(name = "hook_id")
    private Long hookId;

    /** 相对 skill 目录的文件路径 */
    @Column(name = "file_path", length = 500)
    private String filePath;

    /** 变更摘要（模型自报；未自报时为"文件内容变化"） */
    @Column(name = "change_summary", length = 2000)
    private String changeSummary;

    @Column(name = "size_before")
    private Long sizeBefore;

    @Column(name = "size_after")
    private Long sizeAfter;

    @Column(name = "at")
    private LocalDateTime at;

    @PrePersist
    void onCreate() { if (at == null) at = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getRunId() { return runId; }
    public void setRunId(Long runId) { this.runId = runId; }
    public Long getHookId() { return hookId; }
    public void setHookId(Long hookId) { this.hookId = hookId; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public String getChangeSummary() { return changeSummary; }
    public void setChangeSummary(String changeSummary) { this.changeSummary = changeSummary; }
    public Long getSizeBefore() { return sizeBefore; }
    public void setSizeBefore(Long sizeBefore) { this.sizeBefore = sizeBefore; }
    public Long getSizeAfter() { return sizeAfter; }
    public void setSizeAfter(Long sizeAfter) { this.sizeAfter = sizeAfter; }
    public LocalDateTime getAt() { return at; }
    public void setAt(LocalDateTime at) { this.at = at; }
}
