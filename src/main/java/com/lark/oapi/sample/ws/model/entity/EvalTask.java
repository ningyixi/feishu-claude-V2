package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 代码质量测评任务（11-21-supplier-eval-loop MVP-1）：一个任务 = 针对某供应商维护一组可执行 eval。
 * <p>
 * 硬纪律（PRD）：eval 的 PASS/FAIL 只来自进程执行结果（退出码 + 输出断言），
 * 不存在任何"让模型判断这条过没过"的通道。本表只存任务级配置，eval 条目见 {@link EvalCase}。
 * <p>
 * 独立表：不碰 supplier_job* / ad_hoc_* / QuickProbe / Flowable 任何表（用户"新东西不要耦合"要求）。
 */
@Entity
@Table(name = "eval_task")
public class EvalTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务名（必填） */
    @Column(nullable = false)
    private String name = "";

    /** 供应商标识（任意文本，如枚举值/代号） */
    @Column(name = "supplier_code", length = 128)
    private String supplierCode = "";

    /** 绑定实例（MVP-1 仅记录 + 提供执行工作目录；改代码循环属 MVP-2） */
    @Column(name = "instance_id")
    private Long instanceId;

    /** review-supplier skill 目录绝对路径（"从 review skill 生成初版 eval"读取此目录） */
    @Column(name = "review_skill_path", length = 600)
    private String reviewSkillPath = "";

    /** create-supplier skill 目录绝对路径（MVP-1 仅记录，经验写回属后续范围） */
    @Column(name = "create_skill_path", length = 600)
    private String createSkillPath = "";

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
    public String getSupplierCode() { return supplierCode == null ? "" : supplierCode; }
    public void setSupplierCode(String value) { supplierCode = value; }
    public Long getInstanceId() { return instanceId; }
    public void setInstanceId(Long instanceId) { this.instanceId = instanceId; }
    public String getReviewSkillPath() { return reviewSkillPath == null ? "" : reviewSkillPath; }
    public void setReviewSkillPath(String value) { reviewSkillPath = value; }
    public String getCreateSkillPath() { return createSkillPath == null ? "" : createSkillPath; }
    public void setCreateSkillPath(String value) { createSkillPath = value; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
