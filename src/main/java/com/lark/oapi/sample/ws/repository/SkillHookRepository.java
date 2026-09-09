package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.SkillHook;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SkillHookRepository extends JpaRepository<SkillHook, Long> {
    List<SkillHook> findByEnabledTrue();
}
