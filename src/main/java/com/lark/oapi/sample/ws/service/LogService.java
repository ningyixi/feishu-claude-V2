package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.LogEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class LogService {

    private static final Logger log = LoggerFactory.getLogger(LogService.class);
    private static final int MAX_HISTORY = 200;

    private final LinkedList<LogEntry> history = new LinkedList<>();
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    public synchronized void addLog(LogEntry entry) {
        history.addLast(entry);
        while (history.size() > MAX_HISTORY) {
            history.removeFirst();
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().data(entry));
            } catch (Exception e) {
                // 不在此处打日志，避免触发 SseLogAppender 循环
                emitters.remove(emitter);
            }
        }
    }

    public synchronized List<LogEntry> getHistory() {
        return List.copyOf(history);
    }

    public SseEmitter createEmitter() {
        SseEmitter emitter = new SseEmitter(0L);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        return emitter;
    }
}
