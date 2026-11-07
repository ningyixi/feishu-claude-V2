package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.model.entity.SupplierJobNode;
import com.lark.oapi.sample.ws.model.entity.WorkflowTemplate;
import com.lark.oapi.sample.ws.repository.SupplierJobNodeRepository;
import com.lark.oapi.sample.ws.repository.WorkflowTemplateRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * 提示词禁词硬约束（2026-11-07 独立需求）：所有会进入发给实例的 claude 会话的话术
 * （内置模板 seed JSON、协议/规范文本常量、Wiki SCHEMA）不得出现"玩家"，统一用"用户"。
 * 断言面只覆盖提示词/规范常量——UI、E2E、数据层不进本测试（各自有对应改动，不做全局扫描）。
 * <p>
 * 另含存量数据归位逻辑（{@link SupplierJobService#normalizePlayerWording} /
 * {@link SupplierJobService#normalizePlayerWordingInDb}）的纯函数级单测：替换后 JSON 合法且幂等。
 */
class SupplierPromptWordingTest {

    private static void assertNoPlayerWording(String text, String what) {
        assertNotNull(text, what + " 不应为 null");
        assertFalse(text.contains("玩家"), what + " 含禁词\"玩家\"（提示词统一用\"用户\"）:\n" + text);
    }

    // ===== 防回归扫描：内置模板 seed JSON =====

    @Test
    void builtinSeedTemplatesContainNoPlayerWording() {
        WorkflowTemplateRepository templates = mock(WorkflowTemplateRepository.class);
        when(templates.existsByBuiltinTrueAndMode(anyString())).thenReturn(false);
        var service = new SupplierJobService(null, null, templates, null, null, null, null, null);

        service.seedTemplates();

        ArgumentCaptor<WorkflowTemplate> captor = ArgumentCaptor.forClass(WorkflowTemplate.class);
        verify(templates, times(2)).save(captor.capture());   // API 内置路径 + CM 占位
        for (WorkflowTemplate t : captor.getAllValues()) {
            assertNoPlayerWording(t.getName(), "模板名 " + t.getName());
            assertNoPlayerWording(t.getDescription(), "模板描述");
            assertNoPlayerWording(t.getNodesJson(), "模板 nodesJson");
            assertDoesNotThrow(() -> JsonParser.parseString(t.getNodesJson()), "nodesJson 必须是合法 JSON");
        }
    }

    // ===== 防回归扫描：协议/规范文本常量 + Wiki SCHEMA =====

    @Test
    void protocolConstantsAndWikiSchemaContainNoPlayerWording() {
        assertNoPlayerWording(SupplierJobService.ACTION_SPEC, "ACTION_SPEC 执行动作规范（含卡点协议/Postman 实测协议）");
        assertNoPlayerWording(SupplierJobService.N45_TITLE, "N45_TITLE");
        assertNoPlayerWording(SupplierJobService.N45_DESC, "N45_DESC");
        assertNoPlayerWording(SupplierJobService.N9_TITLE, "N9_TITLE");
        assertNoPlayerWording(SupplierJobService.N9_DESC, "N9_DESC");
        assertNoPlayerWording(SupplierJobService.N9_PROMPT, "N9_PROMPT（review 节点 seed 提示词）");
        assertNoPlayerWording(SupplierJobService.N8_APPEND, "N8_APPEND");
        assertNoPlayerWording(SupplierWikiService.SCHEMA, "SupplierWikiService.SCHEMA");
    }

    // ===== 归位逻辑纯函数级单测 =====

    @Test
    void normalizePlayerWordingReplacesAndKeepsJsonValidAndIdempotent() {
        String legacyJson = "[{\"id\":\"n2\",\"description\":\"文档常有滞后，以真实响应为准。玩家确认后点完成\","
                + "\"prompt\":\"找不到就列出\\\"需要玩家提供\\\"清单并跳过该接口\"}]";

        String once = SupplierJobService.normalizePlayerWording(legacyJson);
        String twice = SupplierJobService.normalizePlayerWording(once);

        assertFalse(once.contains("玩家"), "替换后不得残留\"玩家\"");
        assertTrue(once.contains("需要用户提供"), "字面替换为\"用户\"");
        assertEquals(once, twice, "幂等：重复归位结果不变");
        assertDoesNotThrow(() -> JsonParser.parseString(once), "替换后 JSON 仍合法（不触碰结构/转义）");
        assertEquals(JsonParser.parseString(legacyJson).getAsJsonArray().size(),
                JsonParser.parseString(once).getAsJsonArray().size(), "节点数量不变");
    }

    @Test
    void normalizePlayerWordingPassesThroughCleanAndNullText() {
        assertNull(SupplierJobService.normalizePlayerWording(null), "null 原样返回");
        assertEquals("", SupplierJobService.normalizePlayerWording(""), "空串原样返回");
        String clean = "用 Postman CLI 真实调用，需要用户提供的材料以【卡点】提出";
        assertSame(clean, SupplierJobService.normalizePlayerWording(clean), "不含禁词原样返回（不产生新字符串）");
    }

    @Test
    void normalizePlayerWordingInDbUpdatesOnlyDirtyRowsAndIsIdempotent() {
        WorkflowTemplateRepository templates = mock(WorkflowTemplateRepository.class);
        SupplierJobNodeRepository nodes = mock(SupplierJobNodeRepository.class);
        var service = new SupplierJobService(null, nodes, templates, null, null, null, null, null);

        WorkflowTemplate dirty = new WorkflowTemplate();
        dirty.setName("旧内置模板");
        dirty.setNodesJson("[{\"id\":\"n9\",\"prompt\":\"请使用玩家在补充指示中提供的 review skill\"}]");
        WorkflowTemplate clean = new WorkflowTemplate();
        clean.setName("干净模板");
        clean.setNodesJson("[{\"id\":\"n1\",\"prompt\":\"\"}]");

        SupplierJobNode dirtyNode = new SupplierJobNode();
        dirtyNode.setJobId(193L);
        dirtyNode.setNodeId("n2");
        dirtyNode.setPrompt("列出\"需要玩家提供\"清单");
        dirtyNode.setDescription("玩家确认后点完成");
        SupplierJobNode cleanNode = new SupplierJobNode();
        cleanNode.setJobId(193L);
        cleanNode.setNodeId("n3");
        cleanNode.setPrompt("核对存档信息");
        cleanNode.setDescription("缺什么先去申请");

        when(templates.findAll()).thenReturn(List.of(dirty, clean));
        when(nodes.findAll()).thenReturn(List.of(dirtyNode, cleanNode));

        service.normalizePlayerWordingInDb();

        assertFalse(dirty.getNodesJson().contains("玩家"));
        assertTrue(dirty.getNodesJson().contains("请使用用户在补充指示中提供的 review skill"));
        assertEquals("列出\"需要用户提供\"清单", dirtyNode.getPrompt());
        assertEquals("用户确认后点完成", dirtyNode.getDescription());
        verify(templates, times(1)).save(dirty);      // 只更新含禁词的行
        verify(templates, never()).save(clean);
        verify(nodes, times(1)).save(dirtyNode);
        verify(nodes, never()).save(cleanNode);

        service.normalizePlayerWordingInDb();          // 第二次跑：全部已干净，零写入（幂等）

        verify(templates, times(1)).save(any(WorkflowTemplate.class));
        verify(nodes, times(1)).save(any(SupplierJobNode.class));
    }
}
