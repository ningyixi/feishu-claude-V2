package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.SupplierNodeChat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierNodeChatRepository extends JpaRepository<SupplierNodeChat, Long> {
    List<SupplierNodeChat> findByJobIdAndNodeIdOrderByIdAsc(Long jobId, String nodeId);
    void deleteByJobId(Long jobId);
}
