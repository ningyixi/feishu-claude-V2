package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "claude_session",
       uniqueConstraints = @UniqueConstraint(columnNames = {"open_id", "working_dir"}))
public class ClaudeSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "open_id", nullable = false)
    private String openId;

    @Column(name = "working_dir", nullable = false)
    private String workingDir;

    @Column(name = "session_id", nullable = false)
    private String sessionId;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public ClaudeSession() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getOpenId() { return openId; }
    public void setOpenId(String openId) { this.openId = openId; }

    public String getWorkingDir() { return workingDir; }
    public void setWorkingDir(String workingDir) { this.workingDir = workingDir; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
