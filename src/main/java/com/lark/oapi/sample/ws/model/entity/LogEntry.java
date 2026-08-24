package com.lark.oapi.sample.ws.model.entity;

import java.time.LocalDateTime;

public record LogEntry(String timestamp, String level, String logger, String message) {
    public static LogEntry of(String level, String logger, String message) {
        return new LogEntry(LocalDateTime.now().toString(), level, logger, message);
    }
}
