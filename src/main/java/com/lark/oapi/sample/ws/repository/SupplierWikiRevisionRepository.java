package com.lark.oapi.sample.ws.repository;
import com.lark.oapi.sample.ws.model.entity.SupplierWikiRevision;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;
public interface SupplierWikiRevisionRepository extends JpaRepository<SupplierWikiRevision,Long> {
    Optional<SupplierWikiRevision> findFirstByKbCodeOrderByIdDesc(String kbCode);
    List<SupplierWikiRevision> findTop20ByKbCodeOrderByIdDesc(String kbCode);
    List<SupplierWikiRevision> findByStatus(String status);
}
