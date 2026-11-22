package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.EvalCaseResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvalCaseResultRepository extends JpaRepository<EvalCaseResult, Long> {
    List<EvalCaseResult> findTop20ByCaseIdOrderByIdDesc(Long caseId);
    void deleteByTaskId(Long taskId);
    void deleteByCaseId(Long caseId);
}
