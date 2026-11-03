package com.lark.oapi.sample.ws.repository;
import com.lark.oapi.sample.ws.model.entity.SupplierWorkflowNotice;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SupplierWorkflowNoticeRepository extends JpaRepository<SupplierWorkflowNotice,Long> {
    java.util.List<SupplierWorkflowNotice> findByStatus(String status);
    boolean existsByEventKey(String eventKey);
    java.util.List<SupplierWorkflowNotice> findTop100ByRunIdOrderByIdDesc(Long runId);
    java.util.List<SupplierWorkflowNotice> findTop20ByStatusAndNextAttemptAtBeforeOrderByIdAsc(String status, java.time.LocalDateTime time);
}
