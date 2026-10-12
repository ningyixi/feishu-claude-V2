package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 需求「工作区」的 git 流程（每个工作区 = 本地仓库目录 + 它跟踪的远端分支，如 origin/dev）。
 * <ol>
 *   <li>{@link #prepare}：fetch 远端分支 → 更新（缺失则创建并设 upstream）本地跟踪分支 → 从它新建工作分支
 *       feishu-claude/&lt;员工&gt;-&lt;需求&gt;-&lt;时间&gt;，Claude 在工作分支上改代码；</li>
 *   <li>{@link #finish}：提交工作分支上的未提交改动 → 切回本地跟踪分支并合并工作分支（冲突则 abort，不留半合并状态）
 *       → 在跟踪分支上执行 git qpr（employee.pr.repo-command），推到最初配置的远端分支，抓取评审链接。</li>
 * </ol>
 * 无改动的工作区干净跳过。所有命令不经 shell、禁交互、带超时（复用 {@link GitPrService} 的进程封装）。
 */
@Service
public class WorkspaceGitService {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceGitService.class);

    final GitPrService git;

    public WorkspaceGitService(GitPrService git) { this.git = git; }

    /** 一个工作区的配置 + 运行时状态（序列化进 EmployeeDemand.workspacesJson） */
    public static class Workspace {
        public String path;
        public String branch;          // 用户配置的远端分支，如 origin/dev 或 manifest 分支 m/main
        public String remote;          // 解析后：origin
        public String remoteBranch;    // 解析后：dev
        public String trackBranch;     // 本地跟踪分支：dev
        public String workBranch;      // 本轮工作分支
        public String status;          // PREPARED / OK / NO_CHANGES / FAILED
        public String url;
        public String error;
        public String output;

        public Workspace() {}
        public Workspace(String path, String branch) { this.path = path; this.branch = branch; }

        public String label() { return new File(path).getName() + "（" + branch + "）"; }

        public String summary() {
            return switch (status == null ? "" : status) {
                case "OK" -> "✅ " + label() + "：" + workBranch + " → 合并入 " + trackBranch + " → git qpr "
                        + (url != null ? url : "已执行（输出里未找到链接）");
                case "NO_CHANGES" -> "➖ " + label() + "：无代码改动，已跳过";
                case "FAILED" -> "❌ " + label() + "：" + error;
                default -> "… " + label() + "：" + status;
            };
        }
    }

    // ===== JSON =====

    public static List<Workspace> parse(String json) {
        List<Workspace> out = new ArrayList<>();
        if (json == null || json.isBlank()) return out;
        try {
            for (JsonElement e : JsonParser.parseString(json).getAsJsonArray()) {
                JsonObject o = e.getAsJsonObject();
                Workspace w = new Workspace(str(o, "path"), str(o, "branch"));
                w.remote = str(o, "remote"); w.remoteBranch = str(o, "remoteBranch");
                w.trackBranch = str(o, "trackBranch"); w.workBranch = str(o, "workBranch");
                w.status = str(o, "status"); w.url = str(o, "url"); w.error = str(o, "error");
                if (w.path != null && !w.path.isBlank()) out.add(w);
            }
        } catch (Exception ex) {
            log.warn("[工作区] workspacesJson 解析失败: {}", ex.getMessage());
        }
        return out;
    }

    public static String toJson(List<Workspace> ws) {
        JsonArray a = new JsonArray();
        for (Workspace w : ws) a.add(toJson(w));
        return a.toString();
    }

    public static JsonObject toJson(Workspace w) {
        JsonObject o = new JsonObject();
        put(o, "path", w.path); put(o, "branch", w.branch);
        put(o, "remote", w.remote); put(o, "remoteBranch", w.remoteBranch);
        put(o, "trackBranch", w.trackBranch); put(o, "workBranch", w.workBranch);
        put(o, "status", w.status); put(o, "url", w.url); put(o, "error", w.error);
        return o;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString().trim() : null;
    }

    private static void put(JsonObject o, String k, String v) { if (v != null) o.addProperty(k, v); }

    // ===== 校验 =====

    /** 下发前校验：路径必填且是 git 仓库，分支必填且能解析成 远端/分支。返回 null = 通过，否则错误文案 */
    public String validate(String path, String branch) {
        if (path == null || path.isBlank()) return "代码路径必填";
        if (branch == null || branch.isBlank()) return "远端分支必填（如 origin/dev）";
        if (!branch.trim().matches("[A-Za-z0-9._/\\-]+") || branch.contains("..")) return "远端分支名不合法: " + branch;
        File dir = new File(path.trim());
        if (!dir.isDirectory()) return "路径不存在或不是目录: " + path;
        try {
            GitPrService.Out top = git.git(dir, "rev-parse", "--show-toplevel");
            if (!top.ok()) return "不是 git 仓库: " + path;
            Workspace w = new Workspace(path.trim(), branch.trim());
            String err = resolve(dir, w);
            return err;
        } catch (Exception e) {
            return "校验失败: " + e.getMessage();
        }
    }

    /** 把用户写的分支解析成 remote + remoteBranch：origin/dev、dev（默认远端）、m/main（repo manifest 符号引用） */
    String resolve(File dir, Workspace w) throws Exception {
        String b = w.branch.trim().replaceFirst("^refs/remotes/", "");
        // manifest 分支等符号引用 → 解析到真实的 远端/分支
        GitPrService.Out sym = git.git(dir, "symbolic-ref", "--quiet", "refs/remotes/" + b);
        if (sym.ok() && sym.text().trim().startsWith("refs/remotes/")) b = sym.text().trim().substring("refs/remotes/".length());
        List<String> remotes = new ArrayList<>();
        for (String r : git.git(dir, "remote").text().split("\\s+")) if (!r.isBlank()) remotes.add(r.trim());
        if (remotes.isEmpty()) return "仓库没有配置远端: " + w.path;
        String remote = null, name = b;
        for (String r : remotes) {
            if (b.startsWith(r + "/") && (remote == null || r.length() > remote.length())) { remote = r; name = b.substring(r.length() + 1); }
        }
        if (remote == null) remote = remotes.contains("origin") ? "origin" : remotes.get(0);
        if (name.isBlank()) return "远端分支名为空: " + w.branch;
        w.remote = remote;
        w.remoteBranch = name;
        w.trackBranch = name;
        return null;
    }

    // ===== 1. 开工前准备 =====

    /** 成功返回 null 并把 w 置为 PREPARED；失败返回错误文案（w.status=FAILED）。不会丢弃任何已有改动 */
    public String prepare(Workspace w, String who, String taskTag, Consumer<String> step) {
        try {
            File dir = new File(w.path);
            String v = validate(w.path, w.branch);
            if (v != null) return failed(w, v);
            resolve(dir, w);
            if (!git.git(dir, "status", "--porcelain").text().isBlank()) {
                return failed(w, "工作区有未提交的改动，请先提交或清理后再下发: " + w.path);
            }
            String rb = w.remote + "/" + w.remoteBranch;
            step.accept("[" + w.label() + "] git fetch " + w.remote + " " + w.remoteBranch);
            GitPrService.Out f = git.git(dir, "fetch", w.remote,
                    "+refs/heads/" + w.remoteBranch + ":refs/remotes/" + rb);
            if (!f.ok()) return failed(w, GitPrService.authHint(f.text(), "git fetch " + rb + " 失败: " + tail(f.text())));

            boolean exists = git.git(dir, "rev-parse", "--verify", "--quiet", "refs/heads/" + w.trackBranch).ok();
            if (!exists) {
                GitPrService.Out c = git.git(dir, "checkout", "-q", "-b", w.trackBranch, "--track", rb);
                if (!c.ok()) return failed(w, "创建本地跟踪分支 " + w.trackBranch + " 失败: " + tail(c.text()));
                step.accept("[" + w.label() + "] 新建本地分支 " + w.trackBranch + "（跟踪 " + rb + "）");
            } else {
                GitPrService.Out c = git.git(dir, "checkout", "-q", w.trackBranch);
                if (!c.ok()) return failed(w, "切换到 " + w.trackBranch + " 失败: " + tail(c.text()));
                git.git(dir, "branch", "--set-upstream-to=" + rb, w.trackBranch);
                GitPrService.Out m = git.git(dir, "merge", "--ff-only", "-q", rb);
                if (!m.ok()) {
                    return failed(w, "本地分支 " + w.trackBranch + " 与 " + rb + " 已分叉（本地有未上传的提交），无法快进更新，请先处理");
                }
                step.accept("[" + w.label() + "] 本地分支 " + w.trackBranch + " 已更新到 " + rb);
            }
            String wb = git.uniqueBranch(dir, w.remote, git.branchName(who, taskTag));
            GitPrService.Out nb = git.git(dir, "checkout", "-q", "-b", wb);
            if (!nb.ok()) return failed(w, "创建工作分支失败: " + tail(nb.text()));
            w.workBranch = wb;
            w.status = "PREPARED";
            w.error = null; w.url = null;
            step.accept("[" + w.label() + "] 工作分支 " + wb + "（基于 " + w.trackBranch + "）");
            return null;
        } catch (Exception e) {
            return failed(w, "准备工作区异常: " + e.getMessage());
        }
    }

    // ===== 2. 完工后合并 + git qpr =====

    public void finish(Workspace w, String commitMsg, Consumer<String> step) {
        if (!"PREPARED".equals(w.status)) return;
        String tag = "[" + w.label() + "] ";
        try {
            File dir = new File(w.path);
            String cur = git.git(dir, "rev-parse", "--abbrev-ref", "HEAD").text().trim();
            boolean dirty = !git.git(dir, "status", "--porcelain").text().isBlank();
            if (!w.workBranch.equals(cur)) {
                if (dirty) { failed(w, "Claude 切走了工作分支（当前 " + cur + "）且有未提交改动，已停止，请人工处理"); step.accept(tag + "❌ " + w.error); return; }
                GitPrService.Out c = git.git(dir, "checkout", "-q", w.workBranch);
                if (!c.ok()) { failed(w, "切回工作分支失败: " + tail(c.text())); step.accept(tag + "❌ " + w.error); return; }
            }
            if (dirty) {
                git.git(dir, "add", "-A");
                GitPrService.Out cm = git.git(dir, "commit", "-q", "-m", commitMsg);
                if (!cm.ok()) { failed(w, "提交失败: " + tail(cm.text())); step.accept(tag + "❌ " + w.error); return; }
                step.accept(tag + "已提交工作分支上的改动");
            }
            int ahead = parseInt(git.git(dir, "rev-list", "--count", w.trackBranch + ".." + w.workBranch).text());
            if (ahead == 0) {
                git.git(dir, "checkout", "-q", w.trackBranch);
                git.git(dir, "branch", "-q", "-D", w.workBranch);
                w.status = "NO_CHANGES";
                step.accept(tag + "无代码改动，跳过 git qpr");
                return;
            }
            GitPrService.Out co = git.git(dir, "checkout", "-q", w.trackBranch);
            if (!co.ok()) { failed(w, "切换到 " + w.trackBranch + " 失败: " + tail(co.text())); step.accept(tag + "❌ " + w.error); return; }
            GitPrService.Out mg = git.git(dir, "merge", "--no-edit", w.workBranch);
            if (!mg.ok()) {
                git.git(dir, "merge", "--abort");
                git.git(dir, "checkout", "-q", w.workBranch);
                failed(w, "合并 " + w.workBranch + " → " + w.trackBranch + " 冲突，已 abort（改动保留在工作分支上）: " + tail(mg.text()));
                step.accept(tag + "❌ " + w.error);
                return;
            }
            step.accept(tag + "已合并 " + w.workBranch + " → " + w.trackBranch + "（" + ahead + " 个提交）");
            step.accept(tag + "执行 " + git.repoCommand);
            GitPrService.Out q = git.runCommandLine(dir, git.repoCommand);
            w.output = tail(q.text());
            w.url = GitPrService.firstUrl(q.text());
            if (!q.ok()) {
                failed(w, GitPrService.authHint(q.text(), git.repoCommand + " 失败（exit " + q.code() + "）: " + tail(q.text())));
                step.accept(tag + "❌ " + w.error);
                return;
            }
            w.status = "OK";
            step.accept(tag + "✅ " + git.repoCommand + " 完成" + (w.url != null ? "：" + w.url : ""));
        } catch (Exception e) {
            failed(w, "收尾异常: " + e.getMessage());
            step.accept(tag + "❌ " + w.error);
        } finally {
            log.info("[工作区] {} status={} url={} err={}", w.path, w.status, w.url, w.error);
        }
    }

    private static String failed(Workspace w, String msg) { w.status = "FAILED"; w.error = msg; return msg; }

    private static int parseInt(String s) { try { return Integer.parseInt(s.trim()); } catch (Exception e) { return 0; } }

    private static String tail(String s) {
        s = s == null ? "" : s.trim();
        return s.length() > 600 ? "…" + s.substring(s.length() - 600) : s;
    }
}
