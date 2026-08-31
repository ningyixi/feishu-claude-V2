package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "chat_message", indexes = {
    @Index(name = "idx_chat_instance", columnList = "instance_id"),
    @Index(name = "idx_chat_time", columnList = "created_at")
})
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(name = "open_id")
    private String openId;

    /** user 或 bot */
    @Column(nullable = false)
    private String role;

    @Column(columnDefinition = "TEXT")
    private String content;

    /** p2p 或 group */
    private String chatType;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    public ChatMessage() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getInstanceId() { return instanceId; }
    public void setInstanceId(Long instanceId) { this.instanceId = instanceId; }

    public String getOpenId() { return openId; }
    public void setOpenId(String openId) { this.openId = openId; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getChatType() { return chatType; }
    public void setChatType(String chatType) { this.chatType = chatType; }

    public LocalDateTime getCreatedAt() { return createdAt; }
}
