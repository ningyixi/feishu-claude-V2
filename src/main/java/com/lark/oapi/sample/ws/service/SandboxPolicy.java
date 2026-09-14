package com.lark.oapi.sample.ws.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * L1 策略沙箱 — 基于 Claude CLI 原生权限系统的分级策略（旗标组合已在 CLI 2.1.267 实测验证）。
 * <p>
 * 参考 axocoatl（Rust, Apache-2.0）沙箱的威胁模型定位：<b>"限制失误/失控 agent 的爆炸半径"</b>，
 * 不是内核级隔离。防：误操作越权（白名单外命令一律自动拒绝并记录 permission_denials）、
 * 跨目录文件访问（严格档为结构性圈禁）、仓库自带 .claude 配置自我提权（严格档忽略项目/本地配置）。
 * 不防：prompt injection 本身（"隔离圈住爆炸半径，防不了 agent 被说服做错事"——axocoatl 原话）、
 * 白名单内构建工具/解释器的任意代码执行（mvn exec / node -e 等，业务必需，诚实接受）、
 * 工作目录内的破坏（与 axocoatl 同款取舍）。内核级隔离 = 容器沙箱（level 9 预留位，
 * 见 docs/沙箱方案与威胁模型.md 的 WSL2+Podman 迁移路径）。
 * <p>
 * <b>fail-closed 原则</b>（axocoatl 理念）：未知档位按最严格处理，绝不静默回退全权限。
 * <p>
 * 实测记录（verify/sandbox，qwen3.8-flash-anthropic 驱动真实 CLI）：
 * <ul>
 *   <li>宽松档：Bash(git:*) 白名单放行 git rev-parse；cmd /c time /t 自动拒绝且记入 result.permission_denials</li>
 *   <li>严格档：工作目录内 Write/Edit/Read 经白名单放行（磁盘验证）；白名单外命令拒绝；
 *       Read C:\Windows\win.ini 被 --restricted 结构性拦截；网关认证（用户 settings env）不受影响</li>
 *   <li>两档均无卡死：拒绝以 tool_result 错误回到模型，result 事件正常，模型可自适应改道</li>
 * </ul>
 */
public final class SandboxPolicy {

    private static final Logger log = LoggerFactory.getLogger(SandboxPolicy.class);

    private SandboxPolicy() {}

    /** 🔓 全权限（现状默认）：bypassPermissions，admin 信任场景 */
    public static final int LEVEL_FULL = 0;
    /** 🛡 宽松策略沙箱：acceptEdits + 白名单放行 + 其余自动拒绝（目录边界靠权限行为，非结构性） */
    public static final int LEVEL_SOFT = 1;
    /** 🔒 严格策略沙箱：--restricted 结构性圈禁 + 点名工具 + 白名单 + 自动拒绝；员工绑定实例默认档 */
    public static final int LEVEL_STRICT = 2;
    /** 📦 容器沙箱（axocoatl 式 WSL2+rootless Podman）——预留位，未实现，选择即报错提示 */
    public static final int LEVEL_CONTAINER = 9;

    /**
     * Bash 命令白名单（两档共用）。刻意排除纯 shell（cmd/powershell）与破坏性命令
     * （rm/del/taskkill）：cmd /c 等于放行一切，白名单就失去意义；
     * 保留的构建工具/解释器（mvn/node/python）本身可执行任意代码——这是业务必需，威胁模型里如实声明。
     * 语法 Bash(git:*) 为前缀匹配，已实测生效。
     */
    private static final String BASH_ALLOW = String.join(" ",
            "Bash(git:*)", "Bash(mvn:*)", "Bash(npm:*)", "Bash(npx:*)", "Bash(node:*)",
            "Bash(java:*)", "Bash(javac:*)", "Bash(curl:*)", "Bash(python:*)", "Bash(pip:*)",
            "Bash(ls:*)", "Bash(dir:*)", "Bash(cat:*)", "Bash(type:*)", "Bash(mkdir:*)",
            "Bash(cp:*)", "Bash(mv:*)", "Bash(grep:*)", "Bash(findstr:*)", "Bash(echo:*)");

    /** 文件/组织类工具白名单（工作目录内放行；严格档下 --restricted 提供结构性目录圈禁） */
    private static final String FILE_TOOLS_ALLOW = "Read Edit Write Glob Grep Task TodoWrite";

    /** 严格档点名保留的工具集（--tools 是"可用工具全集"语义；不点名 WebFetch/WebSearch = 摘除网络工具） */
    private static final String STRICT_TOOLS = "Bash,Read,Edit,Write,Glob,Grep,Task,TodoWrite";

    /** 按档位把权限旗标追加到 claude 命令行（TerminalSession.start 调用；实测组合，勿凭 help 文本改动） */
    public static void appendFlags(List<String> cmd, int level) {
        switch (level) {
            case LEVEL_FULL -> {
                // bypassPermissions：无头(-p)模式下没有交互界面，这是"全信任"档
                cmd.add("--permission-mode"); cmd.add("bypassPermissions");
            }
            case LEVEL_SOFT -> {
                cmd.add("--permission-mode"); cmd.add("acceptEdits");
                cmd.add("--permission-prompts"); cmd.add("none");   // 会弹审批的一律自动拒绝，绝不卡死
                cmd.add("--allowedTools"); cmd.add(BASH_ALLOW + " " + FILE_TOOLS_ALLOW + " WebFetch");
            }
            case LEVEL_STRICT -> {
                cmd.add("--restricted");   // 结构性圈禁文件工具到工作目录；忽略仓库 .claude 配置防提权；拒绝 bypass
                cmd.add("--tools"); cmd.add(STRICT_TOOLS);
                cmd.add("--permission-prompts"); cmd.add("none");
                cmd.add("--allowedTools"); cmd.add(BASH_ALLOW + " " + FILE_TOOLS_ALLOW);
            }
            default -> {
                // fail-closed：未知/未实现的档位（含 LEVEL_CONTAINER）按最严格处理，绝不静默回退全权限
                log.warn("[Sandbox] 未知沙箱档位 {}，fail-closed 按严格档处理", level);
                cmd.add("--restricted");
                cmd.add("--tools"); cmd.add(STRICT_TOOLS);
                cmd.add("--permission-prompts"); cmd.add("none");
                cmd.add("--allowedTools"); cmd.add(BASH_ALLOW + " " + FILE_TOOLS_ALLOW);
            }
        }
    }

    /** 档位合法性（控制器入参校验用；9=容器沙箱预留但未实现，不允许配置） */
    public static boolean isValidLevel(int level) {
        return level == LEVEL_FULL || level == LEVEL_SOFT || level == LEVEL_STRICT;
    }

    public static String label(int level) {
        return switch (level) {
            case LEVEL_FULL -> "🔓 全权限";
            case LEVEL_SOFT -> "🛡 宽松沙箱";
            case LEVEL_STRICT -> "🔒 严格沙箱";
            default -> "❓ 未知(" + level + ")";
        };
    }
}
