package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.ChatMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByInstanceIdOrderByCreatedAtAsc(Long instanceId);
    List<ChatMessage> findTop100ByInstanceIdOrderByCreatedAtDesc(Long instanceId);

    /** Hook 时间窗口查询：多个实例在 [from, to] 内的对话，旧→新 */
    List<ChatMessage> findByInstanceIdInAndCreatedAtBetweenOrderByCreatedAtAsc(
            Collection<Long> instanceIds, LocalDateTime from, LocalDateTime to);
}
