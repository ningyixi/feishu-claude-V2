package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.BotInstance;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotInstanceRepository extends JpaRepository<BotInstance, Long> {
}
