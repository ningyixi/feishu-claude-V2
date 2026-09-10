package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.WorkflowTemplate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WorkflowTemplateRepository extends JpaRepository<WorkflowTemplate, Long> {
    List<WorkflowTemplate> findByMode(String mode);
    boolean existsByBuiltinTrueAndMode(String mode);
}
