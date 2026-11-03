package com.lark.oapi.sample.ws.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 三期（11-03-supplier-flow-polish-p3）：提示词自检/修正 + prompt 块提取 + 迁移幂等工具 */
class SupplierFlowP3Test {

    @Test
    void checkFlagsMissingItems() {
        String weak = "请实现供应商代码";
        var checks = SupplierJobService.checkCodegenPrompt(weak);
        assertEquals(8, checks.size());
        assertTrue(checks.stream().noneMatch(SupplierJobService.PromptCheck::pass), "空泛提示词应全项缺失");
        assertTrue(SupplierJobService.checkCodegenPrompt(
                "用 trellis 管理，按 skill 与 spec 开发，先读知识库索引 xx.md，会议文档 https://x.feishu.cn/docx/a，"
              + "配置 key=redis.switch，用到 redis 去配置中心，接口对照实测 Postman Collection").stream()
                .allMatch(SupplierJobService.PromptCheck::pass), "要素齐全应全过");
    }

    @Test
    void fixAppendsOnlyMissingAndIsIdempotent() {
        String base = "请实现供应商代码，trellis 管理开发";
        String fixed = SupplierJobService.fixCodegenPrompt(base);
        assertTrue(fixed.contains("== 补充规范（自动检查补齐） =="));
        assertTrue(fixed.contains("skill"));
        // 幂等：修正后再修正不重复追加
        String fixed2 = SupplierJobService.fixCodegenPrompt(fixed);
        assertEquals(fixed, fixed2, "修正应一次到位（补齐后复检全过）");
        // 全过的提示词原样返回
        assertEquals(fixed, SupplierJobService.fixCodegenPrompt(fixed));
    }

    @Test
    void extractPromptBlockPrefersCodeFence() {
        assertEquals("正文 ABC", SupplierJobService.extractPromptBlock("说明文字\n```prompt\n正文 ABC\n```尾巴"));
        assertEquals("无块就直接用", SupplierJobService.extractPromptBlock("无块就直接用"));
    }

    @Test
    void codegenTitleMatcherStaysCorrect() {
        assertTrue(SupplierJobService.isCodegenTitle("让实例写供应商代码"));
        assertFalse(SupplierJobService.isCodegenTitle(SupplierJobService.N45_TITLE));
        assertFalse(SupplierJobService.isCodegenTitle(SupplierJobService.N9_TITLE));
    }
}
