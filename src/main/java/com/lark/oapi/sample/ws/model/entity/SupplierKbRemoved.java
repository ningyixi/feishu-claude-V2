package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 知识库删除墓碑：删除过的 (kbCode,type,source) 不会被 docs_json 幂等同步再次带回。
 * 手动重新添加同一条 URL 不受墓碑影响（墓碑只抑制自动同步路径）。
 */
@Entity
@Table(name = "supplier_kb_removed")
public class SupplierKbRemoved {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "kb_code", nullable = false)
    private String kbCode = "";

    @Column(nullable = false)
    private String type = "";

    @Column(length = 500)
    private String source = "";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public String getKbCode() { return kbCode; }
    public void setKbCode(String kbCode) { this.kbCode = kbCode; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public LocalDateTime getCreatedAt() { return createdAt; }
}
