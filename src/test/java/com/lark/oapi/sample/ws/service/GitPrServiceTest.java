package com.lark.oapi.sample.ws.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 用临时 git 仓库 + 裸远端验证自动 PR 流程（不访问网络；gh 指向不存在的命令或假脚本） */
class GitPrServiceTest {

    @TempDir Path tmp;
    GitPrService svc;
    File work;

    public static String sh(File dir, String... cmd) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(dir).redirectErrorStream(true);
        pb.environment().put("GIT_AUTHOR_NAME", "t"); pb.environment().put("GIT_AUTHOR_EMAIL", "t@t");
        pb.environment().put("GIT_COMMITTER_NAME", "t"); pb.environment().put("GIT_COMMITTER_EMAIL", "t@t");
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes());
        assertEquals(0, p.waitFor(), String.join(" ", cmd) + "\n" + out);
        return out.trim();
    }

    @BeforeEach
    void setUp() throws Exception {
        File bare = tmp.resolve("remote.git").toFile();
        sh(tmp.toFile(), "git", "init", "-q", "--bare", "-b", "trunk", bare.getPath());
        File seed = tmp.resolve("seed").toFile();
        sh(tmp.toFile(), "git", "clone", "-q", bare.getPath(), seed.getPath());
        Files.writeString(seed.toPath().resolve("a.txt"), "1\n");
        sh(seed, "git", "checkout", "-q", "-b", "trunk");
        sh(seed, "git", "add", "-A");
        sh(seed, "git", "commit", "-qm", "init");
        sh(seed, "git", "push", "-q", "origin", "trunk");
        work = tmp.resolve("work").toFile();
        sh(tmp.toFile(), "git", "clone", "-q", bare.getPath(), work.getPath());
        sh(work, "git", "config", "user.name", "t");
        sh(work, "git", "config", "user.email", "t@t");
        svc = new GitPrService();
        svc.ghCommand = tmp.resolve("no-such-gh").toString();
        svc.timeoutSec = 60;
    }

    GitPrService.Result submit(String tag) {
        List<String> steps = new ArrayList<>();
        GitPrService.Result r = svc.submit(work.getPath(), "小明 Bot", tag, "t", null, steps::add);
        assertFalse(steps.isEmpty());
        return r;
    }

    @Test
    void noChangesSkipsCleanly() throws Exception {
        GitPrService.Result r = submit("d1");
        assertEquals(GitPrService.Status.NO_CHANGES, r.status, r.summary());
        assertEquals("trunk", sh(work, "git", "rev-parse", "--abbrev-ref", "HEAD"));
    }

    @Test
    void detachedDirtyTreeGetsBranchUpstreamAndPush() throws Exception {
        sh(work, "git", "checkout", "-q", "--detach");
        Files.writeString(work.toPath().resolve("b.txt"), "x\n");
        GitPrService.Result r = submit("d7");
        assertEquals(GitPrService.Status.OK, r.status, r.summary() + r.steps);
        assertEquals("trunk", r.base);                       // 远端默认分支自动探测
        assertTrue(r.branch.startsWith("feishu-claude/bot-d7"), r.branch);
        assertEquals(r.branch, sh(work, "git", "rev-parse", "--abbrev-ref", "HEAD"));
        // push -u 后跟踪自己的远端分支（GitHub 流程）
        assertEquals("refs/heads/" + r.branch, sh(work, "git", "config", "branch." + r.branch + ".merge"));
        assertTrue(sh(work, "git", "ls-remote", "origin", r.branch).contains(r.branch));
        assertEquals("", sh(work, "git", "status", "--porcelain"));
    }

    @Test
    void branchCollisionGetsSuffix() throws Exception {
        String name = svc.branchName("小明 Bot", "d9");
        sh(work, "git", "branch", name);
        assertEquals(name + "-2", svc.uniqueBranch(work, "origin", name));
    }

    @Test
    void notAGitRepoFailsClearly() throws Exception {
        File plain = tmp.resolve("plain").toFile();
        assertTrue(plain.mkdirs());
        GitPrService.Result r = svc.submit(plain.getPath(), "x", "d1", "t", null, s -> {});
        assertEquals(GitPrService.Status.FAILED, r.status);
        assertNotNull(r.error);
    }

    @Test
    void fakeGhUrlIsReported() throws Exception {
        Path gh = tmp.resolve("gh");
        Files.writeString(gh, "#!/bin/sh\necho https://github.com/o/r/pull/42\n");
        assertTrue(gh.toFile().setExecutable(true));
        svc.ghCommand = gh.toString();
        Files.writeString(work.toPath().resolve("c.txt"), "y\n");
        GitPrService.Result r = submit("d3");
        assertEquals(GitPrService.Status.OK, r.status, r.summary());
        assertEquals("https://github.com/o/r/pull/42", r.url);
    }

    @Test
    void repoProjectRunsUploadWithTrackingBranch() throws Exception {
        assertTrue(tmp.resolve(".repo").toFile().mkdirs());        // work 的祖先目录有 .repo → repo 工程
        Path up = tmp.resolve("fake-upload");
        // 模拟 repo upload：没有跟踪分支就报错，有则输出评审链接
        Files.writeString(up, "#!/bin/sh\ngit rev-parse --abbrev-ref '@{u}' >/dev/null 2>&1 || { echo 'cannot find tracking branch'; exit 1; }\n"
                + "echo remote: https://review.example.com/c/proj/+/123\n");
        assertTrue(up.toFile().setExecutable(true));
        svc.repoCommand = up.toString();
        sh(work, "git", "checkout", "-q", "--detach");               // repo sync 后的典型状态
        Files.writeString(work.toPath().resolve("d.txt"), "z\n");
        GitPrService.Result r = submit("e2e-abc");
        assertEquals(GitPrService.Status.OK, r.status, r.summary() + r.steps);
        assertEquals("https://review.example.com/c/proj/+/123", r.url);
        assertEquals("origin/trunk", sh(work, "git", "rev-parse", "--abbrev-ref", r.branch + "@{u}"));
    }

    @Test
    void helpers() {
        assertEquals("emp", GitPrService.slug("小明"));
        assertEquals("ab-c", GitPrService.slug("  AB c!! "));
        assertEquals("https://github.com/o/r/pull/1",
                GitPrService.firstUrl("see https://example.com/x and https://github.com/o/r/pull/1 ok"));
        assertTrue(GitPrService.authHint("fatal: Authentication failed", "push 失败").contains("push 失败"));
        assertTrue(GitPrService.authHint("fatal: Authentication failed", "push 失败").length() > "push 失败".length());
    }
}
