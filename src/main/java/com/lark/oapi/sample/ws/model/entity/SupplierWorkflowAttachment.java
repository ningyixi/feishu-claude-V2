package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;

@Entity
@Table(name="supplier_workflow_attachment")
public class SupplierWorkflowAttachment {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Long runId;
    public String taskId;
    public String filename;
    public String hash;
    public Long entryId;
    public long size;
    @com.fasterxml.jackson.annotation.JsonIgnore @Column(length=2000) public String path;
    public java.time.LocalDateTime createdAt = java.time.LocalDateTime.now();
}
