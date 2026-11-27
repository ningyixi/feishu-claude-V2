package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.SupplierKbPage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface SupplierKbPageRepository extends JpaRepository<SupplierKbPage, Long> {
    List<SupplierKbPage> findByEntryIdOrderByIdAsc(Long entryId);

    /** 派生 delete 必须有事务：调用方 reparse 不在事务内，缺注解线上实测直接
     *  TransactionRequiredException→500（11-27 复现；mock 单测测不出，别再指望它） */
    @Transactional
    void deleteByEntryId(Long entryId);
}
