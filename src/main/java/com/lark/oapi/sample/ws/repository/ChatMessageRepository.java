package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByInstanceIdOrderByCreatedAtAsc(Long instanceId);
    List<ChatMessage> findTop100ByInstanceIdOrderByCreatedAtDesc(Long instanceId);
}
