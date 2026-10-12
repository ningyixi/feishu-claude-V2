package com.lark.oapi.sample.ws.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数字员工自动提交评审（PR / Code Review）。
 * <p>
 * 根因背景：原先直接在工作目录执行 {@code git qpr}（= {@code repo upload --single ...}）。repo upload 只上传
 * "带跟踪分支（branch.&lt;b&gt;.merge）的本地分支"；repo sync 后工作区是<b>分离 HEAD</b>，Claude 在上面提交，
 * 或自己 {@code git checkout -b} 建的分支没设 upstream → repo 报 "upload failed: cannot find tracking branch"。
 * <p>
 * 现在每次提交都走确定性流程：
 * <ol>
 *   <li>检查是 git 仓库；确定远端（origin 优先）和基线分支（配置 &gt; 远端默认分支 &gt; main/master）</li>
 *   <li>fetch 基线；无未提交改动且 HEAD 相对基线无新提交 → 跳过（不建分支）</li>
 *   <li>从当前 HEAD 新建专属分支 feishu-claude/&lt;员工&gt;-&lt;任务&gt;-&lt;时间&gt;（重名自动加后缀），
 *       并把 upstream 设为 远端/基线（repo upload 需要的"跟踪分支"）</li>
 *   <li>有未提交改动 → git add -A + commit</li>
 *   <li>repo 工程（祖先目录有 .repo）→ 执行 employee.pr.repo-command（默认 git qpr）；
 *       否则 git push -u 远端 分支，再用 gh pr create 开 PR（gh 不可用时报告已推送的分支）</li>
 * </ol>
 * 所有命令都用参数数组直接起进程（不经 shell），Windows / Linux 一致。
 */
@Service
public class GitPrService {

    private static final Logger log = LoggerFactory.getLogger(GitPrService.class);
    private static final Pattern URL = Pattern.compile("https?://[^\\s\"'<>\\[\\]]+");

    @Value("${employee.pr.repo-command:git qpr}")
    String repoCommand = "git qpr";
    @Value("${employee.pr.branch-prefix:feishu-claude}")
    String branchPrefix = "feishu-claude";
    @Value("${employee.pr.default-base:}")
    String defaultBase = "";
    @Value("${employee.pr.timeout-seconds:180}")
    int timeoutSec = 180;
    @Value("${employee.pr.gh-command:gh}")
    String ghCommand = "gh";

    public enum Status { OK, NO_CHANGES, FAILED }

    public static class Result {
        public Status status = Status.FAILED;
        public String branch;
        public String base;
        public String url;
        public String error;
        public final List<String> steps = new ArrayList<>();

        public String summary() {
            return switch (status) {
                case NO_CHANGES -> "ℹ️ 没有需要提交的改动，已跳过 PR";
                case OK -> "✅ 已提交评审：分支 " + branch + " → " + base + (url != null ? "\n🔗 " + url : "（未拿到 PR 链接，见输出）");
                case FAILED -> "❌ 提交 PR 失败" + (branch != null ? "（分支 " + branch + "）" : "") + "：" + error;
            };
        }
    }

    /** 一次命令执行结果 */
    record Out(int code, String text) {
        boolean ok() { return code == 0; }
    }

    /**
     * @param workDir   仓库工作目录
     * @param who       员工名（进分支名）
     * @param taskTag   任务标识，如 d12 / e2e-abc
     * @param title     提交信息/PR 标题
     * @param baseOverride 基线分支（空 = 自动探测）
     * @param onStep    每一步的进度回调（推到时间线）
     */
    public Result submit(String workDir, String who, String taskTag, String title, String baseOverride, Consumer<String> onStep) {
        Result r = new Result();
        Consumer<String> step = s -> { r.steps.add(s); if (onStep != null) try { onStep.accept(s); } catch (Exception ignored) {} };
        File dir = workDir == null || workDir.isBlank() ? null : new File(workDir);
        try {
            if (dir == null || !dir.isDirectory()) return fail(r, "工作目录不存在: " + workDir);
            if (!git(dir, "rev-parse", "--is-inside-work-tree").ok()) return fail(r, "工作目录不是 git 仓库: " + workDir);

            // 远端
            String remote = pickRemote(dir);
            if (remote == null) return fail(r, "仓库没有配置远端（git remote 为空）");

            // 基线
            String base = firstNonBlank(baseOverride, defaultBase, detectDefaultBranch(dir, remote));
            if (base == null) return fail(r, "无法确定基线分支：请在员工设置里填写「PR 目标分支」");
            r.base = base;
            step.accept("基线分支 " + remote + "/" + base);

            Out f = git(dir, "fetch", remote, base);
            if (!f.ok()) {
                return fail(r, authHint(f.text, "git fetch " + remote + " " + base + " 失败"));
            }
            String baseRef = remote + "/" + base;
            boolean dirty = !git(dir, "status", "--porcelain").text.isBlank();
            Out ahead = git(dir, "rev-list", "--count", baseRef + "..HEAD");
            int aheadN = ahead.ok() ? parseInt(ahead.text.trim()) : 0;
            if (!dirty && aheadN == 0) {
                r.status = Status.NO_CHANGES;
                step.accept("没有未提交改动，也没有相对 " + baseRef + " 的新提交，跳过");
                return r;
            }
            String cur = git(dir, "rev-parse", "--abbrev-ref", "HEAD").text.trim();
            step.accept("当前 " + ("HEAD".equals(cur) ? "分离 HEAD" : "分支 " + cur) + "，未提交改动: " + (dirty ? "有" : "无")
                    + "，领先基线 " + aheadN + " 个提交");

            // 新分支（重名加后缀）
            String branch = uniqueBranch(dir, remote, branchName(who, taskTag));
            Out co = git(dir, "checkout", "-b", branch);
            if (!co.ok()) return fail(r, "创建分支失败: " + co.text.trim());
            r.branch = branch;
            git(dir, "branch", "--set-upstream-to=" + baseRef, branch);   // repo upload 需要的跟踪分支
            step.accept("已创建分支 " + branch + "（跟踪 " + baseRef + "）");

            if (dirty) {
                git(dir, "add", "-A");
                Out c = git(dir, "commit", "-m", title == null || title.isBlank() ? "feishu-claude: " + taskTag : title);
                if (!c.ok()) return fail(r, "提交失败: " + tail(c.text));
                step.accept("已提交改动");
            }

            if (isRepoProject(dir)) {
                step.accept("检测到 repo 工程，执行 " + repoCommand);
                Out u = runCommandLine(dir, repoCommand);
                r.url = firstUrl(u.text);
                if (!u.ok()) return fail(r, authHint(u.text, repoCommand + " 失败: " + tail(u.text)));
                r.status = Status.OK;
                return r;
            }

            Out p = git(dir, "push", "-u", remote, branch);
            if (!p.ok()) return fail(r, authHint(p.text, "git push 失败: " + tail(p.text)));
            step.accept("已推送 " + remote + "/" + branch);
            String pushUrl = firstUrl(p.text);

            Out gh = run(dir, List.of(ghCommand, "pr", "create", "--base", base, "--head", branch,
                    "--title", title == null || title.isBlank() ? branch : title,
                    "--body", "由数字员工自动提交（" + taskTag + "）"));
            if (gh.ok()) {
                r.url = firstUrl(gh.text);
                step.accept("已创建 PR");
            } else if (gh.code == 127) {
                r.url = pushUrl;
                step.accept("未安装 gh，分支已推送" + (pushUrl != null ? "，可用推送输出里的链接创建 PR" : "，请手动创建 PR"));
            } else {
                r.url = firstUrl(gh.text) != null ? firstUrl(gh.text) : pushUrl;
                if (gh.text.contains("already exists")) step.accept("该分支已有 PR");
                else return fail(r, authHint(gh.text, "gh pr create 失败: " + tail(gh.text)));
            }
            r.status = Status.OK;
            return r;
        } catch (Exception e) {
            return fail(r, "异常: " + e.getMessage());
        } finally {
            log.info("[PR] {} dir={} branch={} url={} err={}", r.status, workDir, r.branch, r.url, r.error);
        }
    }

    // ===== helpers =====

    private static Result fail(Result r, String msg) {
        r.status = Status.FAILED;
        r.error = msg;
        r.steps.add("❌ " + msg);
        return r;
    }

    static String authHint(String out, String msg) {
        String o = out == null ? "" : out.toLowerCase();
        if (o.contains("authentication") || o.contains("permission denied") || o.contains("could not read username")
                || o.contains("403") || o.contains("401") || o.contains("gh auth login") || o.contains("publickey")) {
            return msg + "（认证失败：请在这台机器上配置 git 凭据 / SSH key，GitHub 还需 gh auth login）";
        }
        return msg;
    }

    String pickRemote(File dir) throws Exception {
        String[] rs = git(dir, "remote").text.trim().split("\\s+");
        String first = null;
        for (String x : rs) {
            if (x.isBlank()) continue;
            if ("origin".equals(x)) return x;
            if (first == null) first = x;
        }
        return first;
    }

    /** 远端默认分支：refs/remotes/<remote>/HEAD → ls-remote --symref → 常见名 */
    String detectDefaultBranch(File dir, String remote) throws Exception {
        // repo 工程：manifest 分支以 refs/remotes/m/<manifest> 符号引用指向 远端/修订
        Out m0 = git(dir, "for-each-ref", "--format=%(symref)", "refs/remotes/m/");
        for (String line : m0.text.split("\\R")) {
            String t = line.trim();
            if (t.startsWith("refs/remotes/" + remote + "/")) return t.substring(("refs/remotes/" + remote + "/").length());
        }
        Out s = git(dir, "symbolic-ref", "--short", "refs/remotes/" + remote + "/HEAD");
        if (s.ok() && s.text.trim().startsWith(remote + "/")) return s.text.trim().substring(remote.length() + 1);
        Out ls = git(dir, "ls-remote", "--symref", remote, "HEAD");
        Matcher m = Pattern.compile("ref: refs/heads/(\\S+)\\s+HEAD").matcher(ls.text);
        if (m.find()) return m.group(1);
        for (String b : new String[]{"main", "master", "develop"}) {
            if (git(dir, "rev-parse", "--verify", "--quiet", "refs/remotes/" + remote + "/" + b).ok()) return b;
        }
        return null;
    }

    String branchName(String who, String taskTag) {
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        return branchPrefix + "/" + slug(who) + "-" + slug(taskTag) + "-" + ts;
    }

    String uniqueBranch(File dir, String remote, String base) throws Exception {
        String b = base;
        for (int i = 2; i < 50; i++) {
            boolean local = git(dir, "rev-parse", "--verify", "--quiet", "refs/heads/" + b).ok();
            boolean rem = git(dir, "rev-parse", "--verify", "--quiet", "refs/remotes/" + remote + "/" + b).ok();
            if (!local && !rem) return b;
            b = base + "-" + i;
        }
        return base + "-" + System.currentTimeMillis();
    }

    /** 分支名安全化：只留 ASCII 字母数字和 -，中文名等会变成 emp（git 允许中文但很多评审系统不友好） */
    static String slug(String s) {
        if (s == null) return "x";
        String t = s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (t.length() > 24) t = t.substring(0, 24).replaceAll("-+$", "");
        return t.isEmpty() ? "emp" : t;
    }

    static boolean isRepoProject(File dir) {
        for (File d = dir.getAbsoluteFile(); d != null; d = d.getParentFile()) {
            if (new File(d, ".repo").isDirectory()) return true;
        }
        return false;
    }

    static String firstUrl(String s) {
        if (s == null) return null;
        Matcher m = URL.matcher(s);
        String last = null;
        while (m.find()) {
            String u = m.group().replaceAll("[.,;:!?，。；)）]+$", "");
            if (u.contains("/pull/") || u.contains("merge_requests") || u.contains("/c/") || u.contains("/+/")) return u;
            if (last == null) last = u;
        }
        return last;
    }

    private static String tail(String s) {
        s = s == null ? "" : s.trim();
        return s.length() > 600 ? "…" + s.substring(s.length() - 600) : s;
    }

    private static int parseInt(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return 0; }
    }

    private static String firstNonBlank(String... v) {
        for (String x : v) if (x != null && !x.isBlank()) return x.trim();
        return null;
    }

    Out git(File dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.addAll(List.of(args));
        return run(dir, cmd);
    }

    /** 自定义命令行（如 "git qpr"）：按空白拆参数直接执行，不经 shell */
    Out runCommandLine(File dir, String line) throws Exception {
        return run(dir, List.of(line.trim().split("\\s+")));
    }

    Out run(File dir, List<String> cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");   // 缺凭据时立刻失败，不卡在交互输入
        pb.environment().put("GH_PROMPT_DISABLED", "1");
        Process p;
        try {
            p = pb.start();
        } catch (java.io.IOException e) {
            return new Out(127, cmd.get(0) + " 未安装或不在 PATH: " + e.getMessage());
        }
        p.getOutputStream().close();
        StringBuilder out = new StringBuilder();
        Thread reader = Thread.ofVirtual().start(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = br.readLine()) != null) if (out.length() < 50_000) out.append(l).append('\n');
            } catch (Exception ignored) {}
        });
        if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
            TerminalSession.killDescendants(p);
            p.destroyForcibly();
            reader.join(2000);
            return new Out(124, "命令超时（" + timeoutSec + "s）: " + String.join(" ", cmd) + "\n" + out);
        }
        reader.join(2000);
        return new Out(p.exitValue(), out.toString());
    }
}
