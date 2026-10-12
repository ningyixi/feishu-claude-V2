package com.lark.oapi.sample.ws.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final TerminalWebSocketHandler terminalHandler;
    private final E2EWebSocketHandler e2eHandler;

    public WebSocketConfig(TerminalWebSocketHandler terminalHandler, E2EWebSocketHandler e2eHandler) {
        this.terminalHandler = terminalHandler;
        this.e2eHandler = e2eHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(terminalHandler, "/ws/terminal")
                .setAllowedOriginPatterns("*");   // 真正的 Origin 校验在 OriginGuardFilter（同源 + 白名单）
        registry.addHandler(e2eHandler, "/ws/e2e")
                .setAllowedOriginPatterns("*");   // 真正的 Origin 校验在 OriginGuardFilter（同源 + 白名单）
    }
}
