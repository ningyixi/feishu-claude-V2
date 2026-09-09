package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.HookRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HookRunRepository extends JpaRepository<HookRun, Long> {
    List<HookRun> findTop50ByHookIdOrderByStartedAtDesc(Long hookId);
    HookRun findFirstByHookIdOrderByStartedAtDesc(Long hookId);
    void deleteByHookId(Long hookId);
}
