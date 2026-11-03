package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.SupplierKbPage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierKbPageRepository extends JpaRepository<SupplierKbPage, Long> {
    List<SupplierKbPage> findByEntryIdOrderByIdAsc(Long entryId);
    void deleteByEntryId(Long entryId);
}
