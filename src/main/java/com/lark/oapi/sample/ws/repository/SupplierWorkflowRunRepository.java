package com.lark.oapi.sample.ws.repository;
import com.lark.oapi.sample.ws.model.entity.SupplierWorkflowRun;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SupplierWorkflowRunRepository extends JpaRepository<SupplierWorkflowRun,Long> {
    java.util.Optional<SupplierWorkflowRun> findByJobId(Long jobId);
    java.util.List<SupplierWorkflowRun> findByStatus(String status);
    java.util.Optional<SupplierWorkflowRun> findByProcessId(String processId);
}
