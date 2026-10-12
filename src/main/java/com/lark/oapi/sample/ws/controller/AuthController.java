package com.lark.oapi.sample.ws.controller;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.config.PanelAuthService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

/** 面板登录 / 登出 / 状态 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final PanelAuthService auth;

    public AuthController(PanelAuthService auth) {
        this.auth = auth;
    }

    private static String token(HttpServletRequest req) {
        if (req.getCookies() == null) return null;
        for (Cookie c : req.getCookies()) if (PanelAuthService.COOKIE.equals(c.getName())) return c.getValue();
        return null;
    }

    private static ResponseCookie cookie(HttpServletRequest req, String value, Duration maxAge) {
        return ResponseCookie.from(PanelAuthService.COOKIE, value)
                .httpOnly(true).sameSite("Lax").path("/").secure(req.isSecure()).maxAge(maxAge).build();
    }

    private static String json(String k, Object v) {
        JsonObject o = new JsonObject();
        if (v instanceof Boolean b) o.addProperty(k, b); else o.addProperty(k, String.valueOf(v));
        return o.toString();
    }

    @GetMapping(value = "/me", produces = "application/json;charset=UTF-8")
    public String me(HttpServletRequest req) {
        return json("authenticated", auth.validToken(token(req)));
    }

    @PostMapping(value = "/login", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> login(HttpServletRequest req, @RequestBody(required = false) String body) {
        String ip = req.getRemoteAddr();
        long locked = auth.lockedFor(ip);
        if (locked > 0) {
            return ResponseEntity.status(429).body(json("error", "失败次数过多，请 " + (locked / 60000 + 1) + " 分钟后再试"));
        }
        String pw = null;
        try { pw = JsonParser.parseString(body).getAsJsonObject().get("password").getAsString(); } catch (Exception ignored) {}
        if (!auth.checkPassword(ip, pw)) {
            return ResponseEntity.status(401).body(json("error", "密码错误"));
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(req, auth.issueToken(), Duration.ofMillis(PanelAuthService.TTL_MS)).toString())
                .body(json("ok", true));
    }

    @PostMapping(value = "/logout", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> logout(HttpServletRequest req) {
        auth.revoke(token(req));
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie(req, "", Duration.ZERO).toString())
                .body(json("ok", true));
    }
}
