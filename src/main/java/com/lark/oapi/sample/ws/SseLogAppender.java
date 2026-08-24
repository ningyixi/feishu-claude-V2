package com.lark.oapi.sample.ws;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

/**
 * 自定义 Logback Appender，将日志推送到 LogService
 */
public class SseLogAppender extends AppenderBase<ILoggingEvent> {

    private static LogService logService;

    public static void setLogService(LogService service) {
        logService = service;
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (logService == null) return;
        // 避免循环：不记录 LogService 自身的日志
        if (event.getLoggerName().contains("LogService")) return;

        LogEntry entry = LogEntry.of(
            event.getLevel().toString(),
            event.getLoggerName().substring(event.getLoggerName().lastIndexOf('.') + 1),
            event.getFormattedMessage()
        );
        logService.addLog(entry);
    }
}
