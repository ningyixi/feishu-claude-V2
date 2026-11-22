package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.model.entity.SupplierJobNode;
import com.lark.oapi.sample.ws.repository.SupplierJobRepository;
import com.lark.oapi.sample.ws.repository.SupplierJobNodeRepository;
import org.junit.jupiter.api.Test;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupplierWorkflowGateTest {
    @Test void changedMaterialsRequireRegenerationBeforeDevelopmentCompletes() {
        var jobs = mock(SupplierJobRepository.class);
        var nodes = mock(SupplierJobNodeRepository.class);
        var kb = mock(SupplierKbService.class);
        var job = new SupplierJob(); job.setId(1L);
        job.setMeetingDocUrl("https://example.feishu.cn/docx/a");
        job.setConfigJson("[{\"key\":\"fixture\",\"value\":\"on\"}]");
        job.setGeneratedPromptDigest(SupplierReferenceContext.digest("old material"));
        var node = new SupplierJobNode(); node.setContextStage("codegen");
        when(jobs.findById(1L)).thenReturn(Optional.of(job));
        when(nodes.findByJobIdAndNodeId(1L, "code")).thenReturn(Optional.of(node));
        var service = spy(new SupplierJobService(jobs, nodes, null, null, null, kb, null, null));
        doReturn("updated material").when(service).codegenPrompt(1L);
        var error = assertThrows(IllegalArgumentException.class, () -> service.completeNode(1L, "code"));
        assertTrue(error.getMessage().contains("重新生成"));
        assertEquals("PENDING", node.getStatus());
        verifyNoInteractions(kb);
    }
    @Test void proseCannotCompleteProbeOrArchiveUnverifiedEvidence() {
        var jobs = mock(SupplierJobRepository.class);
        var nodes = mock(SupplierJobNodeRepository.class);
        var kb = mock(SupplierKbService.class);
        var job = new SupplierJob(); job.setId(1L);
        var node = new SupplierJobNode(); node.setTitle("接口实测"); node.setArtifact("I tested all APIs successfully");
        when(jobs.findById(1L)).thenReturn(Optional.of(job));
        when(nodes.findByJobIdAndNodeId(1L, "n2")).thenReturn(Optional.of(node));
        var service = new SupplierJobService(jobs, nodes, null, null, null, kb, null, null);
        assertThrows(IllegalArgumentException.class, () -> service.completeNode(1L, "n2"));
        verifyNoInteractions(kb);
        node.setToolEvidence("observed request and HTTP response");
        service.completeNode(1L, "n2");
        assertEquals("DONE", node.getStatus());
        verify(kb).archiveArtifact(eq(job), eq("n2"), eq("接口实测"), contains("observed request and HTTP response"));
    }
    @Test void uploadedMeetingMaterialsSatisfyDocGate() throws Exception {
        var jobs = mock(SupplierJobRepository.class);
        var nodes = mock(SupplierJobNodeRepository.class);
        var kb = mock(SupplierKbService.class);
        var job = new SupplierJob(); job.setId(1L);
        job.setConfigJson("[{\"key\":\"fixture\",\"value\":\"on\"}]");   // KV 完整，会议文档链接为空
        var node = new SupplierJobNode(); node.setCodegenPrep(true);    // contextStage 默认 auto → codegen 分支，spy+doReturn 规避
        job.setGeneratedPromptDigest(SupplierReferenceContext.digest("material"));
        when(jobs.findById(1L)).thenReturn(Optional.of(job));
        when(nodes.findByJobIdAndNodeId(1L, "code")).thenReturn(Optional.of(node));
        var entry = new com.lark.oapi.sample.ws.model.entity.SupplierKbEntry();
        entry.setId(9L); entry.setSource("job:1/node:generated-prompt"); entry.setStatus("DONE");
        when(kb.kbCode(job)).thenReturn("supplier-1");
        when(kb.list("supplier-1")).thenReturn(java.util.List.of(entry));
        when(kb.readMd(9L)).thenReturn("# dev prompt");
        var service = spy(new SupplierJobService(jobs, nodes, null, null, null, kb, null, null));
        doReturn("material").when(service).codegenPrompt(1L);
        service.completeNode(1L, "code", true);   // 待办已上传会议材料 → 文档门禁视为已交
        assertEquals("DONE", node.getStatus());
    }
    @Test void prepGateErrorListsMissingItemsSeparately() {
        var jobs = mock(SupplierJobRepository.class);
        var nodes = mock(SupplierJobNodeRepository.class);
        var kb = mock(SupplierKbService.class);
        var job = new SupplierJob(); job.setId(1L);   // doc 空 + KV 空
        var node = new SupplierJobNode(); node.setCodegenPrep(true);
        when(jobs.findById(1L)).thenReturn(Optional.of(job));
        when(nodes.findByJobIdAndNodeId(1L, "code")).thenReturn(Optional.of(node));
        var service = new SupplierJobService(jobs, nodes, null, null, null, kb, null, null);
        var both = assertThrows(IllegalArgumentException.class, () -> service.completeNode(1L, "code", false));
        assertTrue(both.getMessage().contains("未收到三方会议文档") && both.getMessage().contains("配置 key/value"));
        job.setMeetingDocUrl("https://a.feishu.cn/docx/x");   // doc 有 + KV 缺 → 只报配置
        var kvOnly = assertThrows(IllegalArgumentException.class, () -> service.completeNode(1L, "code", false));
        assertTrue(kvOnly.getMessage().contains("配置 key/value"));
        assertFalse(kvOnly.getMessage().contains("未收到"));
        verifyNoInteractions(kb);
    }

    // ===== T4（11-17-free-node-execution）：codegen 门禁"确认可强制" =====

    /** AC4 完成路径：不带 force 仍拦（原话术），带 force=true 放行；写代码准备门禁与实测证据门禁同规则 */
    @Test void codegenGateForceChannelCompletesAfterExplicitConfirmation() {
        var jobs = mock(SupplierJobRepository.class);
        var nodes = mock(SupplierJobNodeRepository.class);
        var kb = mock(SupplierKbService.class);
        var job = new SupplierJob(); job.setId(1L);   // doc 空 + KV 空 → 门禁必拦
        var node = new SupplierJobNode(); node.setCodegenPrep(true);
        when(jobs.findById(1L)).thenReturn(Optional.of(job));
        when(nodes.findByJobIdAndNodeId(1L, "code")).thenReturn(Optional.of(node));
        var service = new SupplierJobService(jobs, nodes, null, null, null, kb, null, null);
        var blocked = assertThrows(SupplierJobService.CodegenGateException.class, () -> service.completeNode(1L, "code", false));
        assertTrue(blocked.getMessage().contains("未收到三方会议文档") && blocked.getMessage().contains("配置 key/value"), "默认仍拦且话术不变");
        assertEquals("PENDING", node.getStatus());
        service.completeNode(1L, "code", false, true);   // 用户已二次确认 → force 放行
        assertEquals("DONE", node.getStatus());
        verify(kb).archiveArtifact(eq(job), eq("code"), any(), any());
        // 实测证据门禁同规则：文字报告不能替代证据 → 默认拦，force 放行
        var probe = new SupplierJobNode(); probe.setJobId(1L); probe.setNodeId("n2"); probe.setTitle("接口实测"); probe.setArtifact("只有文字报告");
        when(nodes.findByJobIdAndNodeId(1L, "n2")).thenReturn(Optional.of(probe));
        assertThrows(SupplierJobService.CodegenGateException.class, () -> service.completeNode(1L, "n2"));
        service.completeNode(1L, "n2", false, true);
        assertEquals("DONE", probe.getStatus());
    }

    /** AC4 执行路径：不带 force 被"会议文档+完整配置"门禁拦（原话术）；force=true 跳过三连门禁照常派发执行 */
    @Test void codegenRunForceBypassesGatesAndDispatches(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tmp) throws Exception {
        var jobs = mock(SupplierJobRepository.class);
        var nodes = mock(SupplierJobNodeRepository.class);
        var instances = mock(com.lark.oapi.sample.ws.repository.BotInstanceRepository.class);
        var claude = mock(ClaudeCodeService.class);
        var kb = mock(SupplierKbService.class);
        // 公司材料（skill/spec/提示词 skill）是 referenceContext 的硬校验（真实文件），不在 T4 三连门禁的强制跳过范围
        java.nio.file.Files.writeString(tmp.resolve("spec.md"), "公司 spec 内容");
        var skill = tmp.resolve("skill"); java.nio.file.Files.createDirectories(skill);
        java.nio.file.Files.writeString(skill.resolve("SKILL.md"), "# create-supplier skill 内容");
        var promptSkill = tmp.resolve("prompt-skill"); java.nio.file.Files.createDirectories(promptSkill);
        java.nio.file.Files.writeString(promptSkill.resolve("SKILL.md"), "# 提示词生成 skill 内容");
        var job = new SupplierJob(); job.setId(1L); job.setInstanceId(7L); job.setKnowledgeMode("TEXT");
        job.setSpecDocPath(tmp.resolve("spec.md").toString());
        job.setPromptSkillPath(promptSkill.toString());
        var skills = new com.google.gson.JsonArray(); skills.add(skill.toString()); job.setSkillsJson(skills.toString());
        var node = new SupplierJobNode(); node.setJobId(1L); node.setNodeId("code"); node.setType("agent");
        node.setContextStage("codegen"); node.setPrompt("为供应商写代码 {docs}");
        when(jobs.findById(1L)).thenReturn(Optional.of(job));
        when(nodes.findByJobIdAndNodeId(1L, "code")).thenReturn(Optional.of(node));
        when(nodes.findByJobIdOrderBySeqAsc(1L)).thenReturn(java.util.List.of(node));
        when(instances.existsById(7L)).thenReturn(true);
        var inst = new com.lark.oapi.sample.ws.model.entity.BotInstance(); inst.setId(7L);
        when(instances.findById(7L)).thenReturn(Optional.of(inst));
        when(kb.kbCode(job)).thenReturn("supplier-1");
        when(kb.contextText(anyString())).thenReturn("");
        when(kb.contextText(anyString(), anyString())).thenReturn("");
        when(kb.snapshotDelivery(anyString(), anyString())).thenReturn("");
        when(claude.chat(anyLong(), anyString(), anyString(), anyString(), nullable(String.class), any(TurnListener.class))).thenReturn("开发完成");
        var service = new SupplierJobService(jobs, nodes, null, instances, claude, kb, null, null);
        // 默认（无 force）：会议文档+KV 门禁原话术拦截，节点不被派发
        var gate = assertThrows(SupplierJobService.CodegenGateException.class, () -> service.runNode(1L, "code", "", false));
        assertTrue(gate.getMessage().contains("请先保存会议文档和完整配置"));
        assertEquals("PENDING", node.getStatus());
        // force=true：三连门禁全部跳过（digest 不匹配 → 不携带生成的开发提示词），照常派发，异步回写产物
        assertDoesNotThrow(() -> service.runNode(1L, "code", "", true));
        long end = System.nanoTime() + 10_000_000_000L;
        while (System.nanoTime() < end && !"开发完成".equals(node.getArtifact())) Thread.sleep(25);
        assertEquals("开发完成", node.getArtifact());
        assertTrue(node.getDeliveryManifest().contains("强制执行"), "交付记录必须留痕：本次为强制跳过门禁的执行");
    }

    // ===== T6（11-17-free-node-execution）：托管判据 = 活着的 run（ACTIVE/PAUSED） =====

    /** AC6：CANCELLED/FAILED/COMPLETED 的历史 run 行不再拦手工操作；只有 ACTIVE/PAUSED 仍拦（工作编辑/删除端点） */
    @Test void legacyWriteGateOnlyBlocksLiveRuns() {
        var runs = mock(com.lark.oapi.sample.ws.repository.SupplierWorkflowRunRepository.class);
        var service = new SupplierWorkflowService(null, runs, null, null, null, null, null, null, null, null, null);
        var run = new com.lark.oapi.sample.ws.model.entity.SupplierWorkflowRun(); run.jobId = 1L;
        when(runs.findByJobId(1L)).thenReturn(Optional.of(run));
        run.status = "ACTIVE";
        assertTrue(assertThrows(IllegalArgumentException.class, () -> service.assertLegacyWriteAllowed(1L)).getMessage().contains("流程引擎管理"));
        run.status = "PAUSED";
        assertThrows(IllegalArgumentException.class, () -> service.assertLegacyWriteAllowed(1L));
        for (String terminal : java.util.List.of("CANCELLED", "FAILED", "COMPLETED")) {
            run.status = terminal;
            assertDoesNotThrow(() -> service.assertLegacyWriteAllowed(1L), terminal + " 的历史 run 不得再拦手工操作");
        }
        when(runs.findByJobId(1L)).thenReturn(Optional.empty());
        assertDoesNotThrow(() -> service.assertLegacyWriteAllowed(1L));
    }

    // ===== T3（11-17-free-node-execution）：{artifact:nX} 缺失降级 =====

    /** AC3：上游无产物不再抛"请先完成上游任务"——渲染为显式"未携带"声明（绝不静默），缺失清单可由 runNode 返回 */
    @Test void missingUpstreamArtifactDegradesWithExplicitDeclaration() {
        var nodes = mock(SupplierJobNodeRepository.class);
        var kb = mock(SupplierKbService.class);
        var job = new SupplierJob(); job.setId(1L);
        when(kb.kbCode(job)).thenReturn("supplier-1");
        when(kb.contextText("supplier-1")).thenReturn("知识库索引");
        when(nodes.findByJobIdAndNodeId(1L, "n1")).thenReturn(Optional.empty());   // 上游节点不存在
        var empty = new SupplierJobNode(); empty.setJobId(1L); empty.setNodeId("n0"); empty.setArtifact("  ");   // 上游存在但产物空白
        when(nodes.findByJobIdAndNodeId(1L, "n0")).thenReturn(Optional.of(empty));
        var service = new SupplierJobService(mock(SupplierJobRepository.class), nodes, null, null, null, kb, null, null);
        String out = service.renderPrompt("报告：{artifact:n1}\n空件：{artifact:n0}\n完毕", job, "");
        assertTrue(out.contains("（引用的 n1 尚无产物，本次未携带该上下文）"), "缺失引用必须显式声明，不得静默置空");
        assertTrue(out.contains("（引用的 n0 尚无产物，本次未携带该上下文）"));
        assertFalse(out.contains("{artifact:"), "占位符不得残留");
    }
}
