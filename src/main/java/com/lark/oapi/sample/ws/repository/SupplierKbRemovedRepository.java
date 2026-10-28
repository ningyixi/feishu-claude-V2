package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.SupplierKbRemoved;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierKbRemovedRepository extends JpaRepository<SupplierKbRemoved, Long> {
    boolean existsByKbCodeAndTypeAndSource(String kbCode, String type, String source);
}
