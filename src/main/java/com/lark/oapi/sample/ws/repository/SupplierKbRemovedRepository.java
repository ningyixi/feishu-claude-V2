package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.SupplierKbRemoved;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface SupplierKbRemovedRepository extends JpaRepository<SupplierKbRemoved, Long> {
    boolean existsByKbCodeAndTypeAndSource(String kbCode, String type, String source);

    /** KD4：用户显式重新入库同一来源 = 想要它回来 → 清除墓碑（否则自动同步路径永久拒绝带回） */
    void deleteByKbCodeAndTypeAndSource(String kbCode, String type, String source);

    /** 某时刻之后新写入的墓碑数（AdHoc「已移除但仍在当前 Wiki 版本里」提示的数据源） */
    long countByKbCodeAndCreatedAtAfter(String kbCode, LocalDateTime since);
}
