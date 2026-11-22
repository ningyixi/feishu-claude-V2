package com.lark.oapi.sample.ws.repository;
import com.lark.oapi.sample.ws.model.entity.SupplierWorkflowAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SupplierWorkflowAttemptRepository extends JpaRepository<SupplierWorkflowAttempt,Long> {
    java.util.Optional<SupplierWorkflowAttempt> findByDispatchKey(String dispatchKey);
    java.util.List<SupplierWorkflowAttempt> findByRunIdOrderByIdDesc(Long runId);
    java.util.List<SupplierWorkflowAttempt> findByStatusIn(java.util.Collection<String> status);
    /** T2 防重复派发：按 run+节点查引擎派发台账（区分"手动已有产物"与"驳回返工/卡点续跑"位置） */
    java.util.List<SupplierWorkflowAttempt> findByRunIdAndNodeId(Long runId,String nodeId);
}
