package com.lark.oapi.sample.ws.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.lark.oapi.sample.ws.service.GitPrServiceTest.sh;
import static org.junit.jupiter.api.Assertions.*;

/** 工作区流程：临时仓库 + 裸远端 + 假 git qpr（git alias → 脚本，记录分支 / HEAD / upstream） */
class WorkspaceGitServiceTest {

    @TempDir Path tmp;
    WorkspaceGitService svc;
    Path qprScript;

    @BeforeEach
    void setUp() throws Exception {
        GitPrService g = new GitPrService();
        g.timeoutSec = 60;
        svc = new WorkspaceGitService(g);
        qprScript = tmp.resolve("fake-qpr.sh");
        Files.writeString(qprScript, "#!/bin/sh\n"
                + "echo \"$(git rev-parse --abbrev-ref HEAD) $(git rev-parse HEAD) $(git rev-parse --abbrev-ref '@{u}')\" >> \"$(git rev-parse --show-toplevel)/../qpr-$(basename \"$(git rev-parse --show-toplevel)\").log\"\n"
                + "echo 'remote: https://review.example.com/c/proj/+/77'\n");
        assertTrue(qprScript.toFile().setExecutable(true));
    }

    /** 建裸远端（分支 trunk + dev），克隆出工作副本（只有 trunk，本地没有 dev → 覆盖“缺本地跟踪分支”） */
    File repo(String name) throws Exception {
        File bare = tmp.resolve(name + ".git").toFile();
        sh(tmp.toFile(), "git", "init", "-q", "--bare", "-b", "trunk", bare.getPath());
        File seed = tmp.resolve(name + "-seed").toFile();
        sh(tmp.toFile(), "git", "clone", "-q", bare.getPath(), seed.getPath());
        sh(seed, "git", "checkout", "-q", "-b", "trunk");
        Files.writeString(seed.toPath().resolve("a.txt"), "base\n");
        sh(seed, "git", "add", "-A");
        sh(seed, "git", "commit", "-qm", "init");
        sh(seed, "git", "push", "-q", "origin", "trunk");
        sh(seed, "git", "checkout", "-q", "-b", "dev");
        Files.writeString(seed.toPath().resolve("dev.txt"), "dev\n");
        sh(seed, "git", "add", "-A");
        sh(seed, "git", "commit", "-qm", "dev");
        sh(seed, "git", "push", "-q", "origin", "dev");
        File work = tmp.resolve(name).toFile();
        sh(tmp.toFile(), "git", "clone", "-q", "-b", "trunk", bare.getPath(), work.getPath());
        sh(work, "git", "config", "user.name", "t");
        sh(work, "git", "config", "user.email", "t@t");
        sh(work, "git", "config", "alias.qpr", "!" + qprScript);
        return work;
    }

    List<String> qprLog(String name) throws Exception {
        Path p = tmp.resolve("qpr-" + name + ".log");
        return Files.exists(p) ? Files.readAllLines(p) : List.of();
    }

    @Test
    void multiWorkspaceOnlyChangedOneIsQprd() throws Exception {
        File fe = repo("fe"), be = repo("be");
        var wf = new WorkspaceGitService.Workspace(fe.getPath(), "origin/dev");
        var wb = new WorkspaceGitService.Workspace(be.getPath(), "dev");     // 省略远端 → origin
        List<String> steps = new ArrayList<>();
        assertNull(svc.prepare(wf, "Bot", "d5", steps::add), steps.toString());
        assertNull(svc.prepare(wb, "Bot", "d5", steps::add), steps.toString());
        // 缺失的本地跟踪分支被创建并跟踪 origin/dev
        assertEquals("origin/dev", sh(fe, "git", "rev-parse", "--abbrev-ref", "dev@{u}"));
        assertTrue(wf.workBranch.startsWith("feishu-claude/bot-d5-"));
        assertEquals(wf.workBranch, sh(fe, "git", "rev-parse", "--abbrev-ref", "HEAD"));
        assertTrue(new File(fe, "dev.txt").exists());

        Files.writeString(fe.toPath().resolve("feature.txt"), "new\n");   // 只改前端，且不提交
        svc.finish(wf, "feat", steps::add);
        svc.finish(wb, "feat", steps::add);

        assertEquals("OK", wf.status, wf.error + steps);
        assertEquals("https://review.example.com/c/proj/+/77", wf.url);
        List<String> log = qprLog("fe");
        assertEquals(1, log.size());
        String[] f = log.get(0).split(" ");
        assertEquals("dev", f[0]);                                          // 在跟踪分支上执行
        assertEquals(sh(fe, "git", "rev-parse", "dev"), f[1]);              // HEAD = 合并后的提交
        assertEquals("origin/dev", f[2]);                                   // 推往最初配置的远端分支
        assertTrue(sh(fe, "git", "show", "--name-only", "--format=", "dev").contains("feature.txt"));

        assertEquals("NO_CHANGES", wb.status);
        assertTrue(qprLog("be").isEmpty());
        assertEquals("dev", sh(be, "git", "rev-parse", "--abbrev-ref", "HEAD"));
        assertFalse(sh(be, "git", "branch", "--list", wb.workBranch).contains(wb.workBranch));

        // 状态可序列化回读
        var back = WorkspaceGitService.parse(WorkspaceGitService.toJson(List.of(wf, wb)));
        assertEquals("OK", back.get(0).status);
        assertEquals(wf.url, back.get(0).url);
    }

    @Test
    void mergeConflictAbortsCleanly() throws Exception {
        File fe = repo("fe");
        var w = new WorkspaceGitService.Workspace(fe.getPath(), "origin/dev");
        assertNull(svc.prepare(w, "Bot", "d6", s -> {}));
        // 工作期间跟踪分支被别人动了同一行 → 合并冲突
        Files.writeString(fe.toPath().resolve("a.txt"), "mine\n");
        sh(fe, "git", "stash", "-q");
        sh(fe, "git", "checkout", "-q", "dev");
        Files.writeString(fe.toPath().resolve("a.txt"), "theirs\n");
        sh(fe, "git", "commit", "-qam", "theirs");
        sh(fe, "git", "checkout", "-q", w.workBranch);
        sh(fe, "git", "stash", "pop", "-q");

        svc.finish(w, "feat", s -> {});
        assertEquals("FAILED", w.status);
        assertTrue(w.error.contains("冲突"), w.error);
        assertFalse(new File(fe, ".git/MERGE_HEAD").exists());              // 没留半合并状态
        assertEquals("", sh(fe, "git", "status", "--porcelain"));
        assertEquals(w.workBranch, sh(fe, "git", "rev-parse", "--abbrev-ref", "HEAD"));
        assertTrue(qprLog("fe").isEmpty());
    }

    @Test
    void existingTrackingBranchIsFastForwarded() throws Exception {
        File fe = repo("fe");
        sh(fe, "git", "fetch", "-q", "origin");
        sh(fe, "git", "branch", "dev", "origin/dev~1");                     // 本地 dev 落后且没设 upstream
        var w = new WorkspaceGitService.Workspace(fe.getPath(), "origin/dev");
        assertNull(svc.prepare(w, "Bot", "d7", s -> {}));
        assertEquals(sh(fe, "git", "rev-parse", "origin/dev"), sh(fe, "git", "rev-parse", "dev"));
        assertEquals("origin/dev", sh(fe, "git", "rev-parse", "--abbrev-ref", "dev@{u}"));
    }

    @Test
    void validation() throws Exception {
        File fe = repo("fe");
        assertNotNull(svc.validate("", "origin/dev"));
        assertNotNull(svc.validate(fe.getPath(), ""));
        assertNotNull(svc.validate(tmp.resolve("nope").toString(), "origin/dev"));
        File plain = tmp.resolve("plain").toFile();
        assertTrue(plain.mkdirs());
        assertTrue(svc.validate(plain.getPath(), "origin/dev").contains("git"));
        assertNull(svc.validate(fe.getPath(), "origin/dev"));
        // 有未提交改动 → 开工前拒绝，不丢改动
        Files.writeString(fe.toPath().resolve("x.txt"), "x");
        var w = new WorkspaceGitService.Workspace(fe.getPath(), "origin/dev");
        assertNotNull(svc.prepare(w, "Bot", "d8", s -> {}));
        assertTrue(new File(fe, "x.txt").exists());
    }
}
