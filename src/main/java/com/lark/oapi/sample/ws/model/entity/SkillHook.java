package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Skill 自进化 Hook — 监控指定实例的对话，触发时用 LLM 从实战对话中提炼经验，
 * 反向维护指定 skill 目录下的文件（如 SKILL.md）。
 */
@Entity
@Table(name = "skill_hook")
public class SkillHook {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name = "";

    /** 监控的实例 ID 列表，逗号分隔（如 "1,2"） */
    @Column(name = "instance_ids")
    private String instanceIds = "";

    /** 维护者会话使用的模型来自该实例的配置 */
    @Column(name = "maintainer_instance_id")
    private Long maintainerInstanceId;

    /** skill 目录（维护者会话的工作目录，直接编辑其中文件） */
    @Column(name = "skill_dir", length = 500)
    private String skillDir = "";

    /** AFTER_CHAT（每次对话后 LLM 判定）| CRON（定时） */
    @Column(name = "trigger_type")
    private String triggerType = "AFTER_CHAT";

    /** triggerType=CRON 时的 Spring cron 表达式（6 位，如 0 0 3 * * ? = 每天凌晨3点） */
    @Column(name = "cron_expr")
    private String cronExpr = "";

    private boolean enabled = true;

    /** 创建者：数字员工 id（null = 管理员用户在面板创建） */
    @Column(name = "created_by_employee_id")
    @com.fasterxml.jackson.annotation.JsonAlias("creatorEmployeeId")
    private Long createdByEmployeeId;

    /** 创建者姓名快照（员工被删/改名后标签仍可展示） */
    @Column(name = "created_by_employee_name")
    private String createdByEmployeeName;

    /** 上次实际执行维护的时间（窗口起点） */
    @Column(name = "last_run_at")
    private LocalDateTime lastRunAt;

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
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getInstanceIds() { return instanceIds; }
    public void setInstanceIds(String instanceIds) { this.instanceIds = instanceIds; }
    public Long getMaintainerInstanceId() { return maintainerInstanceId; }
    public void setMaintainerInstanceId(Long maintainerInstanceId) { this.maintainerInstanceId = maintainerInstanceId; }
    public String getSkillDir() { return skillDir; }
    public void setSkillDir(String skillDir) { this.skillDir = skillDir; }
    public String getTriggerType() { return triggerType; }
    public void setTriggerType(String triggerType) { this.triggerType = triggerType; }
    public String getCronExpr() { return cronExpr; }
    public void setCronExpr(String cronExpr) { this.cronExpr = cronExpr; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Long getCreatedByEmployeeId() { return createdByEmployeeId; }
    public void setCreatedByEmployeeId(Long createdByEmployeeId) { this.createdByEmployeeId = createdByEmployeeId; }
    public String getCreatedByEmployeeName() { return createdByEmployeeName; }
    public void setCreatedByEmployeeName(String createdByEmployeeName) { this.createdByEmployeeName = createdByEmployeeName; }
    public LocalDateTime getLastRunAt() { return lastRunAt; }
    public void setLastRunAt(LocalDateTime lastRunAt) { this.lastRunAt = lastRunAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
