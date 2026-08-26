package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.ClaudeSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ClaudeSessionRepository extends JpaRepository<ClaudeSession, Long> {
    Optional<ClaudeSession> findByOpenIdAndWorkingDir(String openId, String workingDir);
    void deleteByOpenIdAndWorkingDir(String openId, String workingDir);
}
