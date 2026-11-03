package com.lark.oapi.sample.ws.repository;
import com.lark.oapi.sample.ws.model.entity.SupplierWorkflowAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SupplierWorkflowAttemptRepository extends JpaRepository<SupplierWorkflowAttempt,Long> {
    java.util.Optional<SupplierWorkflowAttempt> findByDispatchKey(String dispatchKey);
    java.util.List<SupplierWorkflowAttempt> findByRunIdOrderByIdDesc(Long runId);
    java.util.List<SupplierWorkflowAttempt> findByStatusIn(java.util.Collection<String> status);
}
