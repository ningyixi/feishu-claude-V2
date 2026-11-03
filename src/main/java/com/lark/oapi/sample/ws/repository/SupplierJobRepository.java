package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierJobRepository extends JpaRepository<SupplierJob, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select j from SupplierJob j where j.id = :id")
    java.util.Optional<SupplierJob> lockById(@org.springframework.data.repository.query.Param("id") Long id);
    List<SupplierJob> findAllByOrderByUpdatedAtDesc();
}
