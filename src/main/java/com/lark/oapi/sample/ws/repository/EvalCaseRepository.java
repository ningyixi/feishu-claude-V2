package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.EvalCase;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvalCaseRepository extends JpaRepository<EvalCase, Long> {
    List<EvalCase> findByTaskIdOrderBySortOrderAscIdAsc(Long taskId);
    void deleteByTaskId(Long taskId);
    List<EvalCase> findByLastVerdict(String lastVerdict);
}
