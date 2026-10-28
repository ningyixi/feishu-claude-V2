package com.lark.oapi.sample.ws.config;

import com.lark.oapi.sample.ws.service.EmployeeTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 数字员工不可伪造身份 + 无登录透传（2026-10-28 移除面板密码后的行为契约）。
 */
class EmployeeIdentityTest {

    EmployeeTokenService tokens;
    PanelAuthFilter filter;

    @BeforeEach
    void setUp() {
        tokens = new EmployeeTokenService();
        filter = new PanelAuthFilter(tokens);
    }

    record Out(int status, MockHttpServletRequest req, boolean passed) {}

    Out call(String method, String uri, String token) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr("127.0.0.1");
        if (token != null) req.addHeader(EmployeeTokenService.HEADER, token);
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);
        return new Out(resp.getStatus(), req, chain.getRequest() != null);
    }

    @Test
    void noTokenRequestsPassThroughAsAdmin() throws Exception {
        // 无登录时代行为：本机/浏览器请求不拦截，身份=匿名 ADMIN
        assertTrue(call("GET", "/api/e2e/tasks", null).passed());
        assertTrue(call("POST", "/api/e2e/tasks", null).passed());
        assertTrue(call("POST", "/api/supplier-jobs", null).passed());
        assertTrue(call("GET", "/ws/e2e", null).passed());
    }

    @Test
    void employeeTokenGivesIdentityOnAllowedEndpoints() throws Exception {
        String t = tokens.tokenFor(7L);
        Out o = call("POST", "/api/e2e/tasks", t);
        assertTrue(o.passed());
        assertEquals(7L, RequestIdentity.creator(o.req(), null));
        assertEquals(7L, RequestIdentity.creator(o.req(), 7L));
        assertThrows(SecurityException.class, () -> RequestIdentity.creator(o.req(), 8L));   // 冒充别人
        assertTrue(call("POST", "/api/e2e/tasks/abc/start", t).passed());
        assertTrue(call("POST", "/api/e2e/tasks/abc/message", t).passed());
        assertTrue(call("POST", "/api/hooks", t).passed());
        assertTrue(call("POST", "/api/supplier-jobs", t).passed());
        assertTrue(call("GET", "/api/instances", t).passed());
        // 其它写接口（管理类）员工令牌无权
        assertEquals(403, call("POST", "/api/employees/1/demands", t).status());
        assertEquals(403, call("DELETE", "/api/e2e/tasks/abc", t).status());
        assertEquals(403, call("POST", "/api/instances/1/chat", t).status());
    }

    @Test
    void badOrRevokedTokenRejected() throws Exception {
        assertEquals(401, call("POST", "/api/e2e/tasks", "fce_forged").status());
        String t = tokens.tokenFor(3L);
        tokens.revoke(3L);
        assertEquals(401, call("GET", "/api/e2e/tasks", t).status());
        assertNotEquals(tokens.tokenFor(3L), t);
    }

    @Test
    void anonymousAdminCannotImpersonateEmployee() throws Exception {
        Out o = call("POST", "/api/e2e/tasks", null);
        assertTrue(o.passed());
        assertNull(RequestIdentity.creator(o.req(), null));
        assertThrows(SecurityException.class, () -> RequestIdentity.creator(o.req(), 5L));
    }

    @Test
    void requestWithoutAttributeDefaultsToLocalDenied() {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/e2e/tasks");
        assertThrows(SecurityException.class, () -> RequestIdentity.creator(req, 5L));
        assertThrows(SecurityException.class, () -> RequestIdentity.creator(req, null));
    }

    @Test
    void sessionEnvCarriesTokenAndHelper() {
        var env = tokens.sessionEnv(9L);
        assertEquals(tokens.tokenFor(9L), env.get(EmployeeTokenService.ENV_TOKEN));
        assertEquals("9", env.get(EmployeeTokenService.ENV_ID));
        assertTrue(env.get(EmployeeTokenService.ENV_API).startsWith("http://localhost:"));
        assertEquals(9L, tokens.resolve(env.get(EmployeeTokenService.ENV_TOKEN)));
    }
}
