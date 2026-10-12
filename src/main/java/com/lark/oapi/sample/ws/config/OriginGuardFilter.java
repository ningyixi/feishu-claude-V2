package com.lark.oapi.sample.ws.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 跨站请求防护（CSRF / 跨站 WebSocket 劫持）。
 * <p>
 * 面板没有登录，且 /api/employees/{id}/shell、/ws/terminal 等能直接在本机执行命令；
 * 很多接口用 {@code @RequestBody String} 接收任意 Content-Type——任何网页都能对 localhost:8080
 * 发一个"简单请求"POST 触发执行。浏览器跨站请求一定带 Origin 头：这里对写操作与 WebSocket 握手
 * 校验 Origin 必须是同源或白名单（默认 localhost/127.0.0.1 任意端口，含 Vite 3000）。
 * 没有 Origin 头的请求（curl、数字员工在本机调 API、服务端调用）不受影响。
 * 白名单可用 {@code app.security.allowed-origins}（逗号分隔，支持 * 通配端口/子域）扩展。
 */
@Component
public class OriginGuardFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(OriginGuardFilter.class);

    private final List<Pattern> allowed;

    public OriginGuardFilter(@Value("${app.security.allowed-origins:http://localhost:*,http://127.0.0.1:*}") String origins) {
        this.allowed = Arrays.stream(origins.split(","))
                .map(String::trim).filter(s -> !s.isEmpty())
                .map(s -> Pattern.compile(Pattern.quote(s).replace("*", "\\E[^/]*\\Q")))
                .toList();
    }

    public boolean isAllowed(String origin, String hostHeader) {
        if (origin == null || origin.isBlank()) return true;
        if ("null".equals(origin)) return false;
        try {
            URI u = URI.create(origin);
            String hp = u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
            if (hostHeader != null && hostHeader.equalsIgnoreCase(hp)) return true;   // 同源
        } catch (Exception e) {
            return false;
        }
        for (Pattern p : allowed) if (p.matcher(origin).matches()) return true;
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String uri = req.getRequestURI();
        String method = req.getMethod();
        boolean ws = uri.startsWith("/ws/");
        boolean write = !("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method));
        if ((ws || (write && uri.startsWith("/api/"))) && !isAllowed(req.getHeader("Origin"), req.getHeader("Host"))) {
            log.warn("[安全] 拒绝跨站请求: {} {} Origin={}", method, uri, req.getHeader("Origin"));
            resp.sendError(HttpServletResponse.SC_FORBIDDEN, "cross-origin request blocked");
            return;
        }
        chain.doFilter(req, resp);
    }
}
