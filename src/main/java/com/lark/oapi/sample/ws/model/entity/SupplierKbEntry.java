package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 供应商知识库条目（R1/R2）— 一供应商(kbCode)一库，条目类型：
 * url(URL 文档) | pdf(本地 PDF) | chat(聊天记录) | note(笔记)。
 * url/pdf 入库后异步解析为 Markdown 落盘（mdPath），后续节点执行直接 Read 产物，不再重抓来源；
 * chat/note 入库即 DONE（原文即内容）。解析状态机：PENDING → PARSING → DONE | FAILED。
 */
@Entity
@Table(name = "supplier_kb_entry")
public class SupplierKbEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 知识库归属码：供应商 supplierCode，缺省回退 "job-<存档id>" */
    @Column(name = "kb_code", nullable = false)
    private String kbCode = "";

    /** url | pdf | chat | note */
    @Column(nullable = false)
    private String type = "url";

    /** 来源：URL / 本地 PDF 绝对路径 /（chat、note 存标题或摘要锚点） */
    @Column(length = 500)
    private String source = "";

    @Column(length = 200)
    private String title = "";

    /** PENDING | PARSING | DONE | FAILED */
    @Column(nullable = false)
    private String status = "PENDING";

    @Column(length = 500)
    private String error = "";

    /** 解析产物 Markdown 的绝对路径（DONE 后可读） */
    @Column(name = "md_path", length = 600)
    private String mdPath = "";

    /** 解析元数据 JSON：chars/pages/finalUrl/fetchedAt/editedAt… */
    @Lob
    @Column(name = "meta_json")
    private String metaJson = "{}";

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
    public String getKbCode() { return kbCode; }
    public void setKbCode(String kbCode) { this.kbCode = kbCode; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getMdPath() { return mdPath; }
    public void setMdPath(String mdPath) { this.mdPath = mdPath; }
    public String getMetaJson() { return metaJson; }
    public void setMetaJson(String metaJson) { this.metaJson = metaJson; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
