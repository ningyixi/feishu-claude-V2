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
    private final com.lark.oapi.sample.ws.service.EmployeeTokenService employeeTokens;

    public PanelAuthFilter(PanelAuthService auth, com.lark.oapi.sample.ws.service.EmployeeTokenService employeeTokens) {
        this.auth = auth;
        this.employeeTokens = employeeTokens;
    }

    /** 带员工令牌允许调用的写接口（其余写接口员工令牌无权）；GET 一律允许 */
    static final java.util.regex.Pattern EMPLOYEE_WRITE = java.util.regex.Pattern.compile(
            "^/api/(e2e/tasks|e2e/tasks/[^/]+/(start|message)|hooks|supplier-jobs)/?$");

    static boolean readOnly(String method) {
        return "GET".equals(method) || "HEAD".equals(method);
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
        if (!protectedPath || "OPTIONS".equals(req.getMethod())) {
            chain.doFilter(req, resp);
            return;
        }
        // 1) 数字员工令牌：身份来自令牌；只允许只读 + 员工被授权的创建/启动/发言接口
        String et = req.getHeader(com.lark.oapi.sample.ws.service.EmployeeTokenService.HEADER);
        if (et != null && !et.isBlank()) {
            Long empId = employeeTokens.resolve(et);
            if (empId == null) { deny(resp, 401, "数字员工令牌无效或已过期（应用重启后会话令牌会重新下发）", false); return; }
            if (uri.startsWith("/ws/") || (!readOnly(req.getMethod()) && !EMPLOYEE_WRITE.matcher(uri).matches())) {
                deny(resp, 403, "数字员工令牌无权调用该接口: " + req.getMethod() + " " + uri, false);
                return;
            }
            req.setAttribute(RequestIdentity.ATTR_KIND, RequestIdentity.Kind.EMPLOYEE);
            req.setAttribute(RequestIdentity.ATTR_EMP, empId);
            chain.doFilter(req, resp);
            return;
        }
        // 2) 面板登录 = admin
        if (auth.validToken(token(req))) {
            req.setAttribute(RequestIdentity.ATTR_KIND, RequestIdentity.Kind.ADMIN);
            chain.doFilter(req, resp);
            return;
        }
        // 3) 本机非浏览器、无令牌无登录：只读（GET/HEAD）。写操作不再默认当 admin
        if (isLocalNonBrowser(req) && uri.startsWith("/api/")) {
            if (readOnly(req.getMethod())) {
                req.setAttribute(RequestIdentity.ATTR_KIND, RequestIdentity.Kind.LOCAL);
                chain.doFilter(req, resp);
                return;
            }
            deny(resp, 403, "本机调用缺少数字员工令牌（请求头 X-Employee-Token: $FEISHU_EMPLOYEE_TOKEN）且未登录面板，"
                    + "写操作已拒绝；数字员工请用 fc-api 或在 curl 里带上该请求头", false);
            return;
        }
        deny(resp, 401, "未登录或登录已过期", true);
    }

    private static void deny(HttpServletResponse resp, int code, String msg, boolean needLogin) throws IOException {
        resp.setStatus(code);
        resp.setContentType("application/json;charset=UTF-8");
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("error", msg);
        if (needLogin) o.addProperty("needLogin", true);
        resp.getWriter().write(o.toString());
    }
}
