package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.E2ETurnEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface E2ETurnEntityRepository extends JpaRepository<E2ETurnEntity, Long> {
    List<E2ETurnEntity> findByTaskIdOrderByTurnIndexAsc(String taskId);
    void deleteByTaskId(String taskId);
}
