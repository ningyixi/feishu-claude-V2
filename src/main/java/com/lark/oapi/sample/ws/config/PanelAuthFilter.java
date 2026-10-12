package com.lark.oapi.sample.ws.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 面板鉴权：/api/**（含 SSE）与 /ws/**（WebSocket 握手）必须带有效会话 cookie，否则 401。
 * <p>
 * 豁免：
 * <ul>
 *   <li>/api/auth/**（登录/登出/状态）与静态页面（SPA 自己会跳登录）</li>
 *   <li><b>本机非浏览器调用</b>：来源是回环地址（127.0.0.1/::1）且不带任何浏览器特征头
 *       （Origin / Referer / Sec-Fetch-*）且没有 X-Forwarded-For——即数字员工/E2E 成员在本机用 curl
 *       调 http://localhost:8080 的既有流程。浏览器（含经 Vite 代理）一定带 Sec-Fetch-* → 仍需登录；
 *       经本机反向代理转发的外部请求带 X-Forwarded-For → 仍需登录。</li>
 * </ul>
 * 飞书事件走 SDK 长连接（出站 WebSocket），没有入站回调，无需豁免。
 */
@Component
@Order(1)
public class PanelAuthFilter extends OncePerRequestFilter {

    private final PanelAuthService auth;

    public PanelAuthFilter(PanelAuthService auth) {
        this.auth = auth;
    }

    static boolean isLoopback(String addr) {
        return "127.0.0.1".equals(addr) || "::1".equals(addr) || "0:0:0:0:0:0:0:1".equals(addr)
                || (addr != null && addr.startsWith("127."));
    }

    static boolean isLocalNonBrowser(HttpServletRequest req) {
        return isLoopback(req.getRemoteAddr())
                && req.getHeader("Origin") == null
                && req.getHeader("Referer") == null
                && req.getHeader("Sec-Fetch-Site") == null
                && req.getHeader("Sec-Fetch-Mode") == null
                && req.getHeader("X-Forwarded-For") == null
                && req.getHeader("Forwarded") == null;
    }

    static String token(HttpServletRequest req) {
        Cookie[] cs = req.getCookies();
        if (cs == null) return null;
        for (Cookie c : cs) if (PanelAuthService.COOKIE.equals(c.getName())) return c.getValue();
        return null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain chain)
            throws ServletException, IOException {
        String uri = req.getRequestURI();
        boolean protectedPath = (uri.startsWith("/api/") && !uri.startsWith("/api/auth/")) || uri.startsWith("/ws/");
        if (!protectedPath || "OPTIONS".equals(req.getMethod())
                || auth.validToken(token(req)) || isLocalNonBrowser(req)) {
            chain.doFilter(req, resp);
            return;
        }
        resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        resp.setContentType("application/json;charset=UTF-8");
        resp.getWriter().write("{\"error\":\"未登录或登录已过期\",\"needLogin\":true}");
    }
}
