package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.SupplierKbEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierKbEntryRepository extends JpaRepository<SupplierKbEntry, Long> {

    List<SupplierKbEntry> findByKbCodeOrderByCreatedAtAsc(String kbCode);

    boolean existsByKbCodeAndTypeAndSource(String kbCode, String type, String source);

    List<SupplierKbEntry> findByStatusIn(List<String> statuses);
}
