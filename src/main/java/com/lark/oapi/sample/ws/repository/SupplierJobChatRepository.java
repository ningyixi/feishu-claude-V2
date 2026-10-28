package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.SupplierJobChat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierJobChatRepository extends JpaRepository<SupplierJobChat, Long> {
    List<SupplierJobChat> findByJobIdOrderByIdAsc(Long jobId);
}
