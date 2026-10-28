package com.lark.oapi.sample.ws.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 请求身份 Filter（2026-10-28 用户决定移除面板密码登录后的形态）：
 * <ul>
 *   <li>带 {@code X-Employee-Token} 的请求 → 数字员工身份（不可伪造；只允许只读 + 员工被授权的
 *       创建/启动/发言接口，管理类写接口 403）</li>
 *   <li>其余请求 → ADMIN 匿名透传（回到无登录时代的行为；跨站防护仍由 {@link OriginGuardFilter} 承担，
 *       面板会话不能冒充数字员工身份——见 {@link RequestIdentity#creator}）</li>
 * </ul>
 * 飞书事件走 SDK 长连接（出站 WebSocket），没有入站回调，无需处理。
 */
@Component
@Order(1)
public class PanelAuthFilter extends OncePerRequestFilter {

    private final com.lark.oapi.sample.ws.service.EmployeeTokenService employeeTokens;

    public PanelAuthFilter(com.lark.oapi.sample.ws.service.EmployeeTokenService employeeTokens) {
        this.employeeTokens = employeeTokens;
    }

    /** 带员工令牌允许调用的写接口（其余写接口员工令牌无权）；GET 一律允许 */
    static final java.util.regex.Pattern EMPLOYEE_WRITE = java.util.regex.Pattern.compile(
            "^/api/(e2e/tasks|e2e/tasks/[^/]+/(start|message)|hooks|supplier-jobs)/?$");

    static boolean readOnly(String method) {
        return "GET".equals(method) || "HEAD".equals(method);
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
        // 1) 数字员工令牌：身份来自令牌，校验失败直接拒绝
        String et = req.getHeader(com.lark.oapi.sample.ws.service.EmployeeTokenService.HEADER);
        if (et != null && !et.isBlank()) {
            Long empId = employeeTokens.resolve(et);
            if (empId == null) { deny(resp, 401, "数字员工令牌无效或已过期（应用重启后会话令牌会重新下发）"); return; }
            if (uri.startsWith("/ws/") || (!readOnly(req.getMethod()) && !EMPLOYEE_WRITE.matcher(uri).matches())) {
                deny(resp, 403, "数字员工令牌无权调用该接口: " + req.getMethod() + " " + uri);
                return;
            }
            req.setAttribute(RequestIdentity.ATTR_KIND, RequestIdentity.Kind.EMPLOYEE);
            req.setAttribute(RequestIdentity.ATTR_EMP, empId);
            chain.doFilter(req, resp);
            return;
        }
        // 2) 其余（浏览器面板/本机 curl）：ADMIN 匿名透传——无登录功能（面板密码已按产品决定移除）
        req.setAttribute(RequestIdentity.ATTR_KIND, RequestIdentity.Kind.ADMIN);
        chain.doFilter(req, resp);
    }

    private static void deny(HttpServletResponse resp, int code, String msg) throws IOException {
        resp.setStatus(code);
        resp.setContentType("application/json;charset=UTF-8");
        com.google.gson.JsonObject o = new com.google.gson.JsonObject();
        o.addProperty("error", msg);
        resp.getWriter().write(o.toString());
    }
}
