package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;

@Entity
@Table(name="supplier_workflow_notice")
public class SupplierWorkflowNotice {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    public Long runId;
    @Column(unique=true) public String eventKey;
    public String taskId;
    public String kind;
    @Column(length=2147483647) public String message = "";
    public String status = "PENDING";
    public int attempts;
    @Column(length=2147483647) public String error = "";
    public java.time.LocalDateTime nextAttemptAt = java.time.LocalDateTime.now();
    public java.time.LocalDateTime createdAt = java.time.LocalDateTime.now();
    public java.time.LocalDateTime sentAt;
}
