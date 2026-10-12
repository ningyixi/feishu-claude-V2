package com.lark.oapi.sample.ws;

import com.lark.oapi.sample.ws.config.OriginGuardFilter;
import com.lark.oapi.sample.ws.config.PanelAuthService;
import com.lark.oapi.sample.ws.service.EmployeeService;
import com.lark.oapi.sample.ws.service.MessageDedup;
import com.lark.oapi.sample.ws.service.BotLoopGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class UnitTests {

    @Test
    void dedupRejectsRepeatsAndCaps() {
        MessageDedup d = new MessageDedup(60_000, 3);
        assertTrue(d.firstSeen("a"));
        assertFalse(d.firstSeen("a"));
        assertTrue(d.firstSeen("b"));
        assertTrue(d.firstSeen("c"));
        assertTrue(d.firstSeen("d"));
        assertTrue(d.size() <= 3);
    }

    @Test
    void originGuard() {
        OriginGuardFilter f = new OriginGuardFilter("http://localhost:*,http://127.0.0.1:*");
        assertTrue(f.isAllowed(null, "x:8080"));
        assertTrue(f.isAllowed("http://localhost:3000", "localhost:8080"));
        assertTrue(f.isAllowed("http://10.0.0.5:8080", "10.0.0.5:8080"));
        assertFalse(f.isAllowed("https://evil.com", "localhost:8080"));
        assertFalse(f.isAllowed("http://localhost.evil.com", "localhost:8080"));
        assertFalse(f.isAllowed("null", "localhost:8080"));
    }

    @Test
    void authTokens(@TempDir Path dir) throws Exception {
        PanelAuthService a = new PanelAuthService("secret-pw", dir.resolve("pw.txt").toString());
        Method init = PanelAuthService.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(a);
        assertTrue(a.checkPassword("1.1.1.1", "secret-pw"));
        String t = a.issueToken();
        assertTrue(a.validToken(t));
        assertFalse(a.validToken(t + "x"));
        assertFalse(a.validToken("123.abc.def"));
        a.revoke(t);
        assertFalse(a.validToken(t));
    }

    @Test
    void generatedPasswordPersists(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("data/pw.txt");
        PanelAuthService a = new PanelAuthService("", f.toString());
        Method init = PanelAuthService.class.getDeclaredMethod("init");
        init.setAccessible(true);
        init.invoke(a);
        String pw = java.nio.file.Files.readString(f).trim();
        assertTrue(pw.length() >= 12);
        PanelAuthService b = new PanelAuthService("", f.toString());
        init.invoke(b);
        assertTrue(b.checkPassword("2.2.2.2", pw));
    }

    @Test
    void supportNeedParsing() throws Exception {
        Method m = EmployeeService.class.getDeclaredMethod("parseSupportNeed", String.class);
        m.setAccessible(true);
        assertNull(m.invoke(null, "4. 需要老板支持：无"));
        assertNotNull(m.invoke(null, "4. 需要老板支持：请提供 GitHub token"));
    }

    @Test
    void botLoopGuardTripsOnceAndResetsOnHuman() {
        BotLoopGuard g = new BotLoopGuard(3, 600_000);
        String k = BotLoopGuard.key(1L, "oc_1", null);
        long t = 1_000_000;
        for (int i = 1; i <= 3; i++) assertTrue(g.noteBotTurn(k, t + i).allowed());
        BotLoopGuard.Verdict v = g.noteBotTurn(k, t + 4);
        assertFalse(v.allowed());
        assertTrue(v.justTripped());
        BotLoopGuard.Verdict v2 = g.noteBotTurn(k, t + 5);
        assertFalse(v2.allowed());
        assertFalse(v2.justTripped());
        g.reset(k);
        assertTrue(g.noteBotTurn(k, t + 6).allowed());
    }

    @Test
    void botLoopGuardIdleResetAndIsolation() {
        BotLoopGuard g = new BotLoopGuard(2, 60_000);
        String a = BotLoopGuard.key(1L, "oc_a", null), b = BotLoopGuard.key(1L, "oc_b", null);
        g.noteBotTurn(a, 0); g.noteBotTurn(a, 1);
        assertFalse(g.noteBotTurn(a, 2).allowed());
        assertTrue(g.noteBotTurn(b, 3).allowed());
        assertTrue(g.noteBotTurn(a, 100_000).allowed());
        assertNotEquals(BotLoopGuard.key(1L, "oc_a", "t1"), BotLoopGuard.key(1L, "oc_a", null));
    }

    @Test
    void botSenderTypeDetection() throws Exception {
        Method m = com.lark.oapi.sample.ws.service.FeishuBotService.class.getDeclaredMethod("isBotSenderType", String.class);
        m.setAccessible(true);
        assertEquals(true, m.invoke(null, "app"));
        assertEquals(false, m.invoke(null, "user"));
        assertEquals(false, m.invoke(null, (Object) null));
    }
}
