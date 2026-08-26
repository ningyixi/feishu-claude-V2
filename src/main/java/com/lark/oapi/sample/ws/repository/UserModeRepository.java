package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.UserMode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserModeRepository extends JpaRepository<UserMode, Long> {
    Optional<UserMode> findByOpenIdAndWorkingDir(String openId, String workingDir);
}
