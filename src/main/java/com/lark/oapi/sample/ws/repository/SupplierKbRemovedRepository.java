package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.SupplierKbRemoved;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

public interface SupplierKbRemovedRepository extends JpaRepository<SupplierKbRemoved, Long> {
    boolean existsByKbCodeAndTypeAndSource(String kbCode, String type, String source);

    /** KD4：用户显式重新入库同一来源 = 想要它回来 → 清除墓碑（否则自动同步路径永久拒绝带回）。
     *  派生 delete 必须 @Transactional：clearRemoved 调用点无事务且 catch 吞 RuntimeException，
     *  缺注解时墓碑永远清不掉只剩 WARN（11-27 与 deleteByEntryId 同根因排查发现） */
    @Transactional
    void deleteByKbCodeAndTypeAndSource(String kbCode, String type, String source);

    /** 某时刻之后新写入的墓碑数（AdHoc「已移除但仍在当前 Wiki 版本里」提示的数据源） */
    long countByKbCodeAndCreatedAtAfter(String kbCode, LocalDateTime since);
}
