package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.SupplierKbEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupplierKbEntryRepository extends JpaRepository<SupplierKbEntry, Long> {

    List<SupplierKbEntry> findByKbCodeOrderByCreatedAtAsc(String kbCode);

    boolean existsByKbCodeAndTypeAndSource(String kbCode, String type, String source);

    /** 追加式文本入库的内容指纹查重（11-23-adhoc-append-sources AC6：连点不产生重复条目） */
    Optional<SupplierKbEntry> findFirstByKbCodeAndTypeAndSource(String kbCode, String type, String source);

    List<SupplierKbEntry> findByStatusIn(List<String> statuses);
}
