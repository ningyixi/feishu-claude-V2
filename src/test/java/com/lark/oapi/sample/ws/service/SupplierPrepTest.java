package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.SkillHook;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 写代码前置门禁（10-29-codegen-prep）+ Hook 维护意图（10-30-hook-maintenance-intent）单测。
 * 全部纯静态/无网络断言。
 */
class SupplierPrepTest {

    // ===== 飞书文档链接校验 =====

    @Test
    void feishuDocUrlAcceptsOnlyFeishuHttps() {
        assertTrue(SupplierJobService.isFeishuDocUrl("https://xxx.feishu.cn/docx/AbCd123"));
        assertTrue(SupplierJobService.isFeishuDocUrl("https://feishu.cn/some/doc"));
        assertTrue(SupplierJobService.isFeishuDocUrl("https://dida.larksuite.com/wiki/xyz"));
        assertFalse(SupplierJobService.isFeishuDocUrl("http://xxx.feishu.cn/docx/a"));        // 非 https
        assertFalse(SupplierJobService.isFeishuDocUrl("https://feishu.cn.evil.com/doc"));      // 后缀伪装
        assertFalse(SupplierJobService.isFeishuDocUrl("https://evil.com/feishu.doc"));
        assertFalse(SupplierJobService.isFeishuDocUrl("D:\\docs\\会议纪要.docx"));
        assertFalse(SupplierJobService.isFeishuDocUrl(""));
    }

    // ===== 门禁达成判定 =====

    @Test
    void prepGateNeedsDocAndCompletePair() {
        assertFalse(SupplierJobService.prepComplete("", "[]"));
        assertFalse(SupplierJobService.prepComplete("https://a.feishu.cn/docx/x", "[]"));
        assertFalse(SupplierJobService.prepComplete("https://a.feishu.cn/docx/x", "[{\"key\":\"k\",\"value\":\"\"}]"));
        assertTrue(SupplierJobService.prepComplete("https://a.feishu.cn/docx/x", "[{\"key\":\"k\",\"value\":\"v\"}]"));
    }

    // ===== 写代码提示词组装 =====

    @Test
    void codegenPromptAggregatesAllSixSources() {
        String p = SupplierJobService.buildCodegenPrompt("易旅", "2226", "D:\\lib\\DCYiLv.csproj",
                "【知识库索引】1. 接口文档 → D:/kb/65.md", "1. D:\\skills\\create-supplier",
                "https://x.feishu.cn/docx/meet", "D:\\spec\\supplier-spec.md",
                "[{\"key\":\"redis.switch\",\"value\":\"on\"}]", "## n2 实测档案\nCollection v2.1 已存档...");
        assertTrue(p.contains("易旅") && p.contains("2226"));
        assertTrue(p.contains("D:/kb/65.md"), "知识库路径必须进提示词");
        assertTrue(p.contains("Collection v2.1"), "实测档案必须进提示词");
        assertTrue(p.contains("https://x.feishu.cn/docx/meet"), "会议文档必须进提示词");
        assertTrue(p.contains("D:\\spec\\supplier-spec.md"), "spec 文档必须进提示词");
        assertTrue(p.contains("redis.switch = on"), "KV 配置必须进提示词");
        assertTrue(p.contains("create-supplier"), "skill 必须进提示词");
        assertTrue(p.contains("redis"), "redis 配置中心提醒必须在");
        // n2 缺失时明示
        String p2 = SupplierJobService.buildCodegenPrompt("X", "1", "dir", "（空）", "", "", "", "[]", "");
        assertTrue(p2.contains("尚无实测档案"));
    }

    // ===== Hook 维护意图 =====

    @Test
    void hookIntentOfFallbackAndValue() {
        SkillHook h = new SkillHook();
        assertEquals("通用经验沉淀（未特别限定意图）", SkillHookService.intentOf(h));
        h.setIntent("维护 review 要点");
        assertEquals("维护 review 要点", SkillHookService.intentOf(h));
    }

    @Test
    void codegenTitleMatchingCoversVariants() {
        assertTrue(SupplierJobService.isCodegenTitle("让实例写代码"));
        assertTrue(SupplierJobService.isCodegenTitle("让实例写供应商代码"));
        assertFalse(SupplierJobService.isCodegenTitle("接口实测"));
    }
}
