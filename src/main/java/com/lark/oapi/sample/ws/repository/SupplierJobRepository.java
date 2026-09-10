package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierJobRepository extends JpaRepository<SupplierJob, Long> {
    List<SupplierJob> findAllByOrderByUpdatedAtDesc();
}
