package com.lark.oapi.sample.ws.repository;
import com.lark.oapi.sample.ws.model.entity.SupplierWorkflowAttachment;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SupplierWorkflowAttachmentRepository extends JpaRepository<SupplierWorkflowAttachment,Long> {
    java.util.List<SupplierWorkflowAttachment> findByRunIdAndTaskIdOrderByIdAsc(Long runId,String taskId);
}
