package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.EvalTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvalTaskRepository extends JpaRepository<EvalTask, Long> {
    List<EvalTask> findAllByOrderByIdDesc();
}
