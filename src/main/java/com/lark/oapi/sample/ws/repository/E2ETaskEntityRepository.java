package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.E2ETaskEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface E2ETaskEntityRepository extends JpaRepository<E2ETaskEntity, String> {
}
