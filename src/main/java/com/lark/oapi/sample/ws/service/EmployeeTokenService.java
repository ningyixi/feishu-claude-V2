package com.lark.oapi.sample.ws.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数字员工身份令牌：每个员工一个随机令牌（仅内存，应用重启即全部失效——员工的 Claude 进程也随之重建）。
 * 员工的 Claude 会话启动时经环境变量注入；它回调本系统 API 时带 X-Employee-Token，服务端据此认定身份，
 * 不再信任请求体里的 creatorEmployeeId（可被遗漏或伪造）。
 */
@Service
public class EmployeeTokenService {

    public static final String HEADER = "X-Employee-Token";
    public static final String ENV_TOKEN = "FEISHU_EMPLOYEE_TOKEN";
    public static final String ENV_ID = "FEISHU_EMPLOYEE_ID";
    public static final String ENV_API = "FEISHU_API";

    private final SecureRandom rnd = new SecureRandom();
    private final Map<Long, String> byEmp = new ConcurrentHashMap<>();
    private final Map<String, Long> byToken = new ConcurrentHashMap<>();

    @Value("${server.port:8080}")
    int port = 8080;

    public String tokenFor(Long empId) {
        return byEmp.computeIfAbsent(empId, k -> {
            byte[] b = new byte[32];
            rnd.nextBytes(b);
            String t = "fce_" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            byToken.put(t, k);
            return t;
        });
    }

    /** 令牌 → 员工 id；无效返回 null（常量时间比较由 Map 查找 + 长随机串保证不可猜） */
    public Long resolve(String token) {
        if (token == null || token.isBlank()) return null;
        Long id = byToken.get(token.trim());
        if (id == null) return null;
        // 防御：再做一次常量时间比较，避免哈希碰撞类的边角问题
        String expect = byEmp.get(id);
        return expect != null && MessageDigest.isEqual(expect.getBytes(StandardCharsets.UTF_8),
                token.trim().getBytes(StandardCharsets.UTF_8)) ? id : null;
    }

    /** 员工删除时作废令牌 */
    public void revoke(Long empId) {
        String t = byEmp.remove(empId);
        if (t != null) byToken.remove(t);
    }

    public String apiBase() { return "http://localhost:" + port; }

    /** 注入员工 Claude 会话的环境变量（含把 fc-api 助手脚本目录放到 PATH 最前） */
    public Map<String, String> sessionEnv(Long empId) {
        Map<String, String> env = new LinkedHashMap<>();
        env.put(ENV_TOKEN, tokenFor(empId));
        env.put(ENV_ID, String.valueOf(empId));
        env.put(ENV_API, apiBase());
        String bin = helperDir();
        if (bin != null) env.put("FEISHU_API_BIN", bin);
        return env;
    }

    private volatile String helperDir;

    /** 生成 fc-api 助手脚本（sh；Windows 上 Claude 的 Bash 工具走 Git Bash，同样可用） */
    synchronized String helperDir() {
        if (helperDir != null) return helperDir;
        try {
            Path dir = Path.of(System.getProperty("user.home"), ".feishu-claude", "bin");
            Files.createDirectories(dir);
            Path sh = dir.resolve("fc-api");
            String script = "#!/bin/sh\n"
                    + "# 用法: fc-api METHOD /api/path ['json body']   —— 自动带数字员工身份令牌\n"
                    + "m=\"${1:-GET}\"; p=\"$2\"; b=\"$3\"\n"
                    + "if [ -z \"$FEISHU_EMPLOYEE_TOKEN\" ]; then echo 'FEISHU_EMPLOYEE_TOKEN 未设置（只有数字员工会话可用）' >&2; exit 2; fi\n"
                    + "if [ -n \"$b\" ]; then\n"
                    + "  exec curl -sS -X \"$m\" -H \"X-Employee-Token: $FEISHU_EMPLOYEE_TOKEN\" -H 'Content-Type: application/json' --data-binary \"$b\" \"${FEISHU_API:-http://localhost:8080}$p\"\n"
                    + "else\n"
                    + "  exec curl -sS -X \"$m\" -H \"X-Employee-Token: $FEISHU_EMPLOYEE_TOKEN\" \"${FEISHU_API:-http://localhost:8080}$p\"\n"
                    + "fi\n";
            Files.writeString(sh, script, StandardCharsets.UTF_8);
            new File(sh.toString()).setExecutable(true);
            helperDir = dir.toString();
        } catch (Exception e) {
            helperDir = null;
        }
        return helperDir;
    }
}
