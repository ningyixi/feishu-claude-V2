package com.lark.oapi.sample.ws.config;

import com.lark.oapi.sample.ws.service.EmployeeTokenService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class EmployeeIdentityTest {

    @TempDir Path dir;
    PanelAuthService auth;
    EmployeeTokenService tokens;
    PanelAuthFilter filter;

    @BeforeEach
    void setUp() throws Exception {
        auth = new PanelAuthService("pw-123456", dir.resolve("pw.txt").toString());
        Method init = PanelAuthService.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(auth);
        tokens = new EmployeeTokenService();
        filter = new PanelAuthFilter(auth, tokens);
    }

    record Out(int status, MockHttpServletRequest req, boolean passed) {}

    Out call(String method, String uri, String token, boolean cookie) throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest(method, uri);
        req.setRemoteAddr("127.0.0.1");
        if (token != null) req.addHeader(EmployeeTokenService.HEADER, token);
        if (cookie) req.setCookies(new Cookie(PanelAuthService.COOKIE, auth.issueToken()));
        MockHttpServletResponse resp = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        filter.doFilter(req, resp, chain);
        return new Out(resp.getStatus(), req, chain.getRequest() != null);
    }

    @Test
    void localWithoutTokenIsReadOnly() throws Exception {
        assertTrue(call("GET", "/api/e2e/tasks", null, false).passed());
        Out o = call("POST", "/api/e2e/tasks", null, false);
        assertFalse(o.passed());
        assertEquals(403, o.status());
        assertFalse(call("POST", "/api/hooks", null, false).passed());
        assertFalse(call("POST", "/api/supplier-jobs", null, false).passed());
        assertFalse(call("POST", "/api/instances/1/chat", null, false).passed());
        assertFalse(call("GET", "/ws/e2e", null, false).passed());     // WS 握手不再本机豁免
    }

    @Test
    void employeeTokenGivesIdentityOnAllowedEndpoints() throws Exception {
        String t = tokens.tokenFor(7L);
        Out o = call("POST", "/api/e2e/tasks", t, false);
        assertTrue(o.passed());
        assertEquals(7L, RequestIdentity.creator(o.req(), null));
        assertEquals(7L, RequestIdentity.creator(o.req(), 7L));
        assertThrows(SecurityException.class, () -> RequestIdentity.creator(o.req(), 8L));   // 冒充别人
        assertTrue(call("POST", "/api/e2e/tasks/abc/start", t, false).passed());
        assertTrue(call("POST", "/api/e2e/tasks/abc/message", t, false).passed());
        assertTrue(call("POST", "/api/hooks", t, false).passed());
        assertTrue(call("POST", "/api/supplier-jobs", t, false).passed());
        assertTrue(call("GET", "/api/instances", t, false).passed());
        // 其它写接口（管理类）员工令牌无权
        assertEquals(403, call("POST", "/api/employees/1/demands", t, false).status());
        assertEquals(403, call("DELETE", "/api/e2e/tasks/abc", t, false).status());
        assertEquals(403, call("POST", "/api/instances/1/chat", t, false).status());
    }

    @Test
    void badOrRevokedTokenRejected() throws Exception {
        assertEquals(401, call("POST", "/api/e2e/tasks", "fce_forged", false).status());
        String t = tokens.tokenFor(3L);
        tokens.revoke(3L);
        assertEquals(401, call("GET", "/api/e2e/tasks", t, false).status());
        assertNotEquals(tokens.tokenFor(3L), t);
    }

    @Test
    void panelLoginIsAdminAndCannotImpersonate() throws Exception {
        Out o = call("POST", "/api/e2e/tasks", null, true);
        assertTrue(o.passed());
        assertNull(RequestIdentity.creator(o.req(), null));
        assertThrows(SecurityException.class, () -> RequestIdentity.creator(o.req(), 5L));
    }

    @Test
    void localAnonCannotCreate() {
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
