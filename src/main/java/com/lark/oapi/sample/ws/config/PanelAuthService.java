package com.lark.oapi.sample.ws.config;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 面板单管理员密码登录。
 * <ul>
 *   <li>密码：环境变量 PANEL_PASSWORD / {@code app.security.panel-password}；未配置时生成随机密码，
 *       写入 {@code ./data/panel-password.txt}（data/ 已 gitignore）并在启动日志醒目打印，重启沿用</li>
 *   <li>会话 token = 过期时间戳 + 随机 nonce + HMAC-SHA256（密钥由密码派生 → 改密码即全部失效），
 *       无需服务端存储、重启不掉线；登出把 nonce 记入吊销表直到过期</li>
 *   <li>防爆破：同 IP 连续失败 5 次锁 15 分钟，每次失败额外延迟 1s；密码比较走常量时间</li>
 * </ul>
 */
@Component
public class PanelAuthService {

    private static final Logger log = LoggerFactory.getLogger(PanelAuthService.class);
    public static final String COOKIE = "PANEL_SESSION";
    public static final long TTL_MS = 7L * 24 * 3600 * 1000;
    private static final int MAX_FAILS = 5;
    private static final long LOCK_MS = 15 * 60 * 1000L;

    private final String configured;
    private final String fileLocation;
    private byte[] passwordDigest;
    private byte[] hmacKey;
    private final SecureRandom rnd = new SecureRandom();
    private final Map<String, Long> revoked = new ConcurrentHashMap<>();
    private final Map<String, long[]> fails = new ConcurrentHashMap<>();   // ip → {count, lockUntil}

    public PanelAuthService(@Value("${app.security.panel-password:${PANEL_PASSWORD:}}") String configured,
                            @Value("${app.security.panel-password-file:data/panel-password.txt}") String fileLocation) {
        this.configured = configured;
        this.fileLocation = fileLocation;
    }

    @PostConstruct
    void init() throws Exception {
        String pw = configured != null ? configured.trim() : "";
        if (pw.isEmpty()) {
            Path f = Path.of(fileLocation).toAbsolutePath();
            if (Files.exists(f)) {
                pw = Files.readString(f, StandardCharsets.UTF_8).trim();
            }
            if (pw.isEmpty()) {
                byte[] b = new byte[12];
                rnd.nextBytes(b);
                pw = Base64.getUrlEncoder().withoutPadding().encodeToString(b);
                Files.createDirectories(f.getParent());
                Files.writeString(f, pw + "\n", StandardCharsets.UTF_8);
                try { f.toFile().setReadable(false, false); f.toFile().setReadable(true, true); } catch (Exception ignored) {}
            }
            log.warn("\n==================================================================\n"
                    + "  面板登录密码（未配置 PANEL_PASSWORD，已自动生成）: {}\n"
                    + "  保存在: {}\n"
                    + "  设置环境变量 PANEL_PASSWORD 可改用自己的密码\n"
                    + "==================================================================", pw, f);
        } else {
            log.info("[安全] 面板密码已由配置提供（PANEL_PASSWORD / app.security.panel-password）");
        }
        passwordDigest = sha256(pw.getBytes(StandardCharsets.UTF_8));
        hmacKey = sha256(("panel-session-v1:" + pw).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] in) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(in);
    }

    /** 剩余锁定毫秒（0 = 未锁） */
    public long lockedFor(String ip) {
        long[] f = fails.get(ip);
        if (f == null) return 0;
        long left = f[1] - System.currentTimeMillis();
        return left > 0 ? left : 0;
    }

    /** 校验密码（常量时间）；失败记数并延迟 */
    public boolean checkPassword(String ip, String pw) {
        boolean ok;
        try {
            ok = MessageDigest.isEqual(passwordDigest, sha256((pw == null ? "" : pw).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            ok = false;
        }
        if (ok) {
            fails.remove(ip);
            return true;
        }
        long[] f = fails.computeIfAbsent(ip, k -> new long[2]);
        synchronized (f) {
            if (f[1] > 0 && f[1] < System.currentTimeMillis()) { f[0] = 0; f[1] = 0; }
            f[0]++;
            if (f[0] >= MAX_FAILS) f[1] = System.currentTimeMillis() + LOCK_MS;
        }
        log.warn("[安全] 面板登录失败: ip={}, 连续失败 {} 次", ip, f[0]);
        try { Thread.sleep(1000); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        return false;
    }

    public String issueToken() {
        long exp = System.currentTimeMillis() + TTL_MS;
        byte[] n = new byte[16];
        rnd.nextBytes(n);
        String body = exp + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(n);
        return body + "." + sign(body);
    }

    public boolean validToken(String token) {
        if (token == null) return false;
        String[] p = token.split("\\.");
        if (p.length != 3) return false;
        String body = p[0] + "." + p[1];
        if (!MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.US_ASCII), p[2].getBytes(StandardCharsets.US_ASCII))) return false;
        long exp;
        try { exp = Long.parseLong(p[0]); } catch (NumberFormatException e) { return false; }
        if (exp < System.currentTimeMillis()) return false;
        return !revoked.containsKey(p[1]);
    }

    public void revoke(String token) {
        if (token == null) return;
        String[] p = token.split("\\.");
        if (p.length == 3) {
            try { revoked.put(p[1], Long.parseLong(p[0])); } catch (NumberFormatException ignored) {}
        }
        long now = System.currentTimeMillis();
        revoked.values().removeIf(exp -> exp < now);
    }

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
