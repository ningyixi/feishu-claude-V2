package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.SupplierJobNode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupplierJobNodeRepository extends JpaRepository<SupplierJobNode, Long> {
    List<SupplierJobNode> findByJobIdOrderBySeqAsc(Long jobId);
    Optional<SupplierJobNode> findByJobIdAndNodeId(Long jobId, String nodeId);
    Optional<SupplierJobNode> findFirstByJobIdAndStatusOrderBySeqAsc(Long jobId, String status);
    void deleteByJobId(Long jobId);
}
