package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.AppConfig;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppConfigRepository extends JpaRepository<AppConfig, Long> {
}
