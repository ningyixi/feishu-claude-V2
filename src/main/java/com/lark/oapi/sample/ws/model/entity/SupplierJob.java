package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 供应商接入工作（"游戏存档"）— 一个工作 = 接入一个供应商的完整旅程。
 * 创建时从模板实例化节点快照（SupplierJobNode），此后模板修改不影响已有存档。
 */
@Entity
@Table(name = "supplier_job")
public class SupplierJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name = "";

    /** API | CM */
    private String mode = "API";

    @Column(name = "template_id")
    private Long templateId;

    /** 供应商 ID（如 Suppliers 枚举值） */
    @Column(name = "supplier_code")
    private String supplierCode = "";

    @Column(name = "supplier_name")
    private String supplierName = "";

    /** 供应商类库绝对路径 */
    @Column(name = "library_path", length = 600)
    private String libraryPath = "";

    /** 接口文档列表 JSON: [{"type":"url"|"path","value":"..."}] */
    @Lob
    @Column(name = "docs_json")
    private String docsJson = "[]";

    /** 关联的 skill 目录列表 JSON: ["D:\\skills\\create-supplier", ...] — 节点执行时注入 prompt */
    @Lob
    @Column(name = "skills_json")
    private String skillsJson = "[]";

    /** 写代码前置（10-29-codegen-prep）：三方会议文档飞书云文档链接（门禁必需） */
    @Column(name = "meeting_doc_url", length = 600)
    private String meetingDocUrl = "";

    /** 写代码前置：spec 文档位置（本地路径或链接，可选） */
    @Column(name = "spec_doc_path", length = 600)
    private String specDocPath = "";

    /** 写代码前置：当前配置 key/value 数组 JSON: [{"key":"...","value":"..."}]（门禁必需≥1条） */
    @Lob
    @Column(name = "config_json")
    private String configJson = "[]";

    /** 当前绑定的执行实例（可中途切换） */
    @Column(name = "instance_id")
    private Long instanceId;

    /** 当前推荐节点（第一个未完成），由 completeNode 推进 */
    @Column(name = "current_node_id")
    private String currentNodeId;

    /** ACTIVE | COMPLETED */
    private String status = "ACTIVE";

    /** 创建者：数字员工 id（null = 管理员用户在面板创建） */
    @Column(name = "created_by_employee_id")
    private Long createdByEmployeeId;

    /** 创建者姓名快照（员工被删/改名后标签仍可展示） */
    @Column(name = "created_by_employee_name")
    private String createdByEmployeeName;

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
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public Long getTemplateId() { return templateId; }
    public void setTemplateId(Long templateId) { this.templateId = templateId; }
    public String getSupplierCode() { return supplierCode; }
    public void setSupplierCode(String supplierCode) { this.supplierCode = supplierCode; }
    public String getSupplierName() { return supplierName; }
    public void setSupplierName(String supplierName) { this.supplierName = supplierName; }
    public String getLibraryPath() { return libraryPath; }
    public void setLibraryPath(String libraryPath) { this.libraryPath = libraryPath; }
    public String getDocsJson() { return docsJson; }
    public void setDocsJson(String docsJson) { this.docsJson = docsJson; }
    public String getSkillsJson() { return skillsJson; }
    public void setSkillsJson(String skillsJson) { this.skillsJson = skillsJson; }
    public String getMeetingDocUrl() { return meetingDocUrl != null ? meetingDocUrl : ""; }
    public void setMeetingDocUrl(String meetingDocUrl) { this.meetingDocUrl = meetingDocUrl; }
    public String getSpecDocPath() { return specDocPath != null ? specDocPath : ""; }
    public void setSpecDocPath(String specDocPath) { this.specDocPath = specDocPath; }
    public String getConfigJson() { return configJson != null ? configJson : "[]"; }
    public void setConfigJson(String configJson) { this.configJson = configJson; }
    public Long getInstanceId() { return instanceId; }
    public void setInstanceId(Long instanceId) { this.instanceId = instanceId; }
    public String getCurrentNodeId() { return currentNodeId; }
    public void setCurrentNodeId(String currentNodeId) { this.currentNodeId = currentNodeId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Long getCreatedByEmployeeId() { return createdByEmployeeId; }
    public void setCreatedByEmployeeId(Long createdByEmployeeId) { this.createdByEmployeeId = createdByEmployeeId; }
    public String getCreatedByEmployeeName() { return createdByEmployeeName; }
    public void setCreatedByEmployeeName(String createdByEmployeeName) { this.createdByEmployeeName = createdByEmployeeName; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
