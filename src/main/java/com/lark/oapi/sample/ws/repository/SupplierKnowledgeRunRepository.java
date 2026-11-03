package com.lark.oapi.sample.ws.repository;
import com.lark.oapi.sample.ws.model.entity.SupplierKnowledgeRun;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
public interface SupplierKnowledgeRunRepository extends JpaRepository<SupplierKnowledgeRun,Long> {
    List<SupplierKnowledgeRun> findTop200ByJobIdOrderByIdDesc(Long jobId);
    List<SupplierKnowledgeRun> findByStatus(String status);
}
