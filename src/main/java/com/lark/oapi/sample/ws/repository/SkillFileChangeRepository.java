package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.SkillFileChange;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SkillFileChangeRepository extends JpaRepository<SkillFileChange, Long> {
    List<SkillFileChange> findTop100ByOrderByAtDesc();
    List<SkillFileChange> findByRunId(Long runId);
    void deleteByHookId(Long hookId);
}
