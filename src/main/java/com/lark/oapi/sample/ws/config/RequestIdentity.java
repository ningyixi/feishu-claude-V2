package com.lark.oapi.sample.ws.config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 请求身份（由 {@link PanelAuthFilter} 写入 request attribute）：
 * <ul>
 *   <li>EMPLOYEE：带有效 X-Employee-Token 的数字员工（身份来自令牌，不可伪造）</li>
 *   <li>ADMIN：面板已登录（有效会话 cookie）</li>
 *   <li>LOCAL：本机非浏览器、无令牌无 cookie——只读</li>
 * </ul>
 */
public final class RequestIdentity {
    private RequestIdentity() {}

    public static final String ATTR_KIND = "auth.kind";
    public static final String ATTR_EMP = "auth.employeeId";

    public enum Kind { ADMIN, EMPLOYEE, LOCAL }

    public static Kind kind(HttpServletRequest req) {
        Object k = req.getAttribute(ATTR_KIND);
        return k instanceof Kind kk ? kk : Kind.LOCAL;
    }

    /**
     * 解析创建者：员工令牌 → 员工 id（请求体 creatorEmployeeId 若给出必须一致）；面板管理员 → null（admin）。
     * 其它（本机无令牌调用）→ 拒绝。
     *
     * @throws SecurityException 身份不符/缺失
     */
    public static Long creator(HttpServletRequest req, Long bodyEmployeeId) {
        switch (kind(req)) {
            case EMPLOYEE -> {
                Long id = (Long) req.getAttribute(ATTR_EMP);
                if (bodyEmployeeId != null && !bodyEmployeeId.equals(id)) {
                    throw new SecurityException("creatorEmployeeId=" + bodyEmployeeId + " 与员工令牌身份（员工#" + id + "）不符，已拒绝");
                }
                return id;
            }
            case ADMIN -> {
                if (bodyEmployeeId != null) {
                    throw new SecurityException("面板会话不能以数字员工身份操作（请去掉 creatorEmployeeId）；员工身份只能来自其会话令牌");
                }
                return null;
            }
            default -> throw new SecurityException("本机调用缺少数字员工令牌（X-Employee-Token）且未登录面板，无权执行此操作");
        }
    }
}
