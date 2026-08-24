package com.lark.oapi.sample.ws.config;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.lark.oapi.sample.ws.model.entity.LogEntry;
import com.lark.oapi.sample.ws.service.LogService;

public class SseLogAppender extends AppenderBase<ILoggingEvent> {

    private static LogService logService;

    public static void setLogService(LogService service) {
        logService = service;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (logService == null) return;
        if (event.getLoggerName().contains("LogService")) return;

        LogEntry entry = LogEntry.of(
            event.getLevel().toString(),
            event.getLoggerName().substring(event.getLoggerName().lastIndexOf('.') + 1),
            event.getFormattedMessage()
        );
        logService.addLog(entry);
    }
}
