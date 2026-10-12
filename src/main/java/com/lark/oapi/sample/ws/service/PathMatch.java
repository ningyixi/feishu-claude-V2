package com.lark.oapi.sample.ws.service;

import java.util.List;

/**
 * 实例工作目录 ↔ 需求工作区路径的匹配规则。
 * 规范化：反斜杠转 /、合并重复分隔符、去掉末尾分隔符、去掉 ./ 段；Windows 路径（盘符开头 / UNC，或运行在 Windows 上）大小写不敏感。
 * 匹配：两者相等，或一方是另一方的祖先目录（按路径段比较，/a/b 不匹配 /a/bc）。
 */
public final class PathMatch {
    private PathMatch() {}

    static final boolean WINDOWS_HOST = System.getProperty("os.name", "").toLowerCase().contains("win");

    public static String normalize(String p) {
        if (p == null) return "";
        String s = p.trim().replace('\\', '/');
        boolean unc = s.startsWith("//");
        s = s.replaceAll("/+", "/").replaceAll("(^|/)\\./", "$1");
        if (s.endsWith("/.")) s = s.substring(0, s.length() - 2);
        while (s.length() > 1 && s.endsWith("/") && !s.matches("^[A-Za-z]:/$")) s = s.substring(0, s.length() - 1);
        if (unc) s = "/" + s;
        boolean win = WINDOWS_HOST || unc || s.matches("^[A-Za-z]:(/.*)?$");
        return win ? s.toLowerCase() : s;
    }

    /** a 是 b 本身或 b 的祖先目录 */
    static boolean covers(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        String pre = a.endsWith("/") ? a : a + "/";
        return b.startsWith(pre);
    }

    /** 实例目录与工作区路径一致：相等，或实例目录包含工作区，或实例目录在工作区里面 */
    public static boolean matches(String instanceDir, String workspacePath) {
        String a = normalize(instanceDir), b = normalize(workspacePath);
        return covers(a, b) || covers(b, a);
    }

    /** 实例目录与任一工作区一致 */
    public static boolean matchesAny(String instanceDir, List<String> workspacePaths) {
        for (String w : workspacePaths) if (matches(instanceDir, w)) return true;
        return false;
    }

    /**
     * 匹配分数：2 = 与主工作区（第一个）一致，1 = 与其它某个工作区一致，0 = 都不一致。
     * 调度时优先 2，再 1。
     */
    public static int score(String instanceDir, List<String> workspacePaths) {
        if (workspacePaths.isEmpty()) return 0;
        if (matches(instanceDir, workspacePaths.get(0))) return 2;
        return matchesAny(instanceDir, workspacePaths) ? 1 : 0;
    }
}
