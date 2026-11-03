package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;

@Entity
@Table(name="supplier_workflow_run")
public class SupplierWorkflowRun {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
    @Column(unique=true,nullable=false) public Long jobId;
    public String processId;
    public String definitionId;
    public String status = "ACTIVE";
    public String recipient = "";
    public Long instanceId;
    public boolean notifyFeishu;
    @Column(length=2147483647) public String error = "";
    public java.time.LocalDateTime createdAt = java.time.LocalDateTime.now();
    public java.time.LocalDateTime updatedAt = java.time.LocalDateTime.now();
    @Version public Long version;
}
