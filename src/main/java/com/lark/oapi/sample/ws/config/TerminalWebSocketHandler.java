package com.lark.oapi.sample.ws.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

@Component
public class TerminalWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TerminalWebSocketHandler.class);
    private static final int MAX_BUFFER_LINES = 500;

    private final Set<WebSocketSession> sessions = new CopyOnWriteArraySet<>();
    private final StringBuilder buffer = new StringBuilder();
    private int lineCount = 0;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.add(session);
        log.info("[Terminal WS] 客户端连接: {}", session.getId());
        // 发送历史缓冲区
        if (!buffer.isEmpty()) {
            try {
                session.sendMessage(new TextMessage(buffer.toString()));
            } catch (IOException e) {
                log.warn("[Terminal WS] 发送历史缓冲失败", e);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        log.info("[Terminal WS] 客户端断开: {}", session.getId());
    }

    public void broadcast(String text) {
        // 追加到缓冲区
        buffer.append(text).append("\r\n");
        lineCount++;
        // 限制缓冲区大小
        while (lineCount > MAX_BUFFER_LINES) {
            int idx = buffer.indexOf("\n");
            if (idx >= 0) {
                buffer.delete(0, idx + 1);
                lineCount--;
            } else {
                break;
            }
        }

        // 推送给所有连接的客户端
        TextMessage msg = new TextMessage(text + "\r\n");
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(msg);
                } catch (IOException e) {
                    sessions.remove(session);
                }
            }
        }
    }

    public void clear() {
        buffer.setLength(0);
        lineCount = 0;
    }
}
