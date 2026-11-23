package com.lark.oapi.sample.ws.repository;
import com.lark.oapi.sample.ws.model.entity.SupplierWikiRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface SupplierWikiRevisionRepository extends JpaRepository<SupplierWikiRevision,Long> {
    Optional<SupplierWikiRevision> findFirstByKbCodeOrderByIdDesc(String kbCode);
    /** 最新一条指定状态的版本行（11-23-adhoc-append-sources S3：按最新 READY 版判定"尚未进入 Wiki 的新增材料"） */
    Optional<SupplierWikiRevision> findFirstByKbCodeAndStatusOrderByIdDesc(String kbCode, String status);
    List<SupplierWikiRevision> findTop20ByKbCodeOrderByIdDesc(String kbCode);
    List<SupplierWikiRevision> findByStatus(String status);
}
