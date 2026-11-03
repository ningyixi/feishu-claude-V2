package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;

@Entity
@Table(name="supplier_workflow_attempt")
public class SupplierWorkflowAttempt {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Long runId;
    public String executionId;
    @Column(unique=true) public String dispatchKey;
    public String nodeId;
    public String status = "QUEUED";
    @Column(length=2147483647) public String instruction = "";
    public java.time.LocalDateTime createdAt = java.time.LocalDateTime.now();
    public java.time.LocalDateTime finishedAt;
}
