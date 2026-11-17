package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 临时需求任务（11-17-adhoc-requirement-module）——侧边栏「临时需求」模块的独立实体。
 * <p>
 * 与供应商工作台（{@link SupplierJob}）和接口建档（quick 标记的 SupplierJob）<b>完全解耦</b>：
 * 独立表 ad_hoc_task，不写 supplier_job* 任何表、不启动 Flowable、无节点/工件/实测证据。
 * 生命周期 = 两段流水线（来源入库解析 INGEST → 编译 LLM Wiki WIKI）→ READY 对话待命；
 * {@link #error} 非空表示停在 {@link #status} 记录的段上失败（可「重试本段」）。
 * <p>
 * 底座复用（不算耦合）：知识库条目落在 {@code supplier_kb_entry}（kbCode = "adhoc-&lt;id&gt;" 专属命名空间，
 * 经 SupplierKbService 管线）；Wiki 版本行落在 {@code supplier_wiki_revision}（同 kbCode，经
 * SupplierWikiService.build）；对话走 ClaudeCodeService.chat = 绑定实例主会话（与飞书同 session）。
 */
@Entity
@Table(name = "ad_hoc_task")
public class AdHocTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务名（表单必填项，展示用） */
    @Column(nullable = false)
    private String name = "";

    /** 关联文档来源，多行原文（每行一条：http(s) 链接 / .pdf/.html/.htm/.md/.markdown/.txt 本地路径） */
    @Lob
    @Column(length = 2147483647, name = "doc_sources")
    private String docSources = "";

    /** 知识库专属命名空间："adhoc-&lt;id&gt;"——保证不与其他任务共库，删除时可安全整库清理 */
    @Column(name = "kb_code", nullable = false)
    private String kbCode = "";

    @Column(name = "instance_id")
    private Long instanceId;

    /** 流水线状态：INGEST（入库解析）| WIKI（编译中）| READY（对话待命）；配合 error 表达段失败 */
    @Column(nullable = false, length = 20)
    private String status = "INGEST";

    /** 最近一次观察到的 Wiki 版本状态（EMPTY/BUILDING/READY/FAILED），列表页轻量展示用 */
    @Column(name = "wiki_status", length = 20)
    private String wikiStatus = "EMPTY";

    /** 当段失败原因；空串 = 未失败（RUNNING/READY） */
    @Column(length = 4000)
    private String error = "";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public AdHocTask() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    /** 空值归一：存量行 NULL 按空串（ddl-auto=update 加列语义） */
    public String getDocSources() { return docSources == null ? "" : docSources; }
    public void setDocSources(String docSources) { this.docSources = docSources; }

    public String getKbCode() { return kbCode; }
    public void setKbCode(String kbCode) { this.kbCode = kbCode; }

    public Long getInstanceId() { return instanceId; }
    public void setInstanceId(Long instanceId) { this.instanceId = instanceId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getWikiStatus() { return wikiStatus == null ? "EMPTY" : wikiStatus; }
    public void setWikiStatus(String wikiStatus) { this.wikiStatus = wikiStatus; }

    public String getError() { return error == null ? "" : error; }
    public void setError(String error) { this.error = error; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
