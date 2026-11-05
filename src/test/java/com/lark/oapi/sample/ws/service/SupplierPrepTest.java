package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.SkillHook;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 写代码前置门禁（10-29-codegen-prep）+ Hook 维护意图（10-30-hook-maintenance-intent）单测。
 * 全部纯静态/无网络断言。
 */
class SupplierPrepTest {

    @Test
    void customNodeAlwaysReceivesSupplierKnowledgeIndex() {
        SupplierKbService kb = mock(SupplierKbService.class);
        var job = new com.lark.oapi.sample.ws.model.entity.SupplierJob();
        when(kb.kbCode(job)).thenReturn("supplier-1");
        when(kb.contextText("supplier-1")).thenReturn("供应商专属知识库索引");
        var service = new SupplierJobService(null, null, null, null, null, kb, null, null);
        assertTrue(service.renderPrompt("执行自定义任务", job, "").contains("供应商专属知识库索引"));
        String explicit = service.renderPrompt("材料：{docs}", job, "");
        assertEquals(explicit.indexOf("供应商专属知识库索引"), explicit.lastIndexOf("供应商专属知识库索引"));
    }

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

    // ===== 门禁拆分与飞书链接提取（写代码准备三通道修复） =====

    @Test
    void extractFeishuDocUrlScansFreeText() {
        assertEquals("https://x.feishu.cn/docx/abc", SupplierJobService.extractFeishuDocUrl("https://x.feishu.cn/docx/abc"));
        assertEquals("https://x.feishu.cn/docx/abc", SupplierJobService.extractFeishuDocUrl("参考 https://x.feishu.cn/docx/abc 开会定的"));
        assertEquals("https://xxx.feishu.cn/wiki/W123", SupplierJobService.extractFeishuDocUrl("纪要见 https://xxx.feishu.cn/wiki/W123 请查收"));
        assertEquals("https://dida.larksuite.com/docx/d1", SupplierJobService.extractFeishuDocUrl("https://dida.larksuite.com/docx/d1"));
        assertEquals("https://x.feishu.cn/docx/abc", SupplierJobService.extractFeishuDocUrl("链接：https://x.feishu.cn/docx/abc。请查收"), "中文标点紧跟链接");
        assertNull(SupplierJobService.extractFeishuDocUrl("https://feishu.cn.evil.com/a"), "后缀伪装域必须 null");
        assertNull(SupplierJobService.extractFeishuDocUrl("https://sub.feishu.cn.evil.com/a"), "子域前缀截断伪装也必须 null");
        assertNull(SupplierJobService.extractFeishuDocUrl("http://x.feishu.cn/docx/abc"), "非 https 必须 null");
        assertNull(SupplierJobService.extractFeishuDocUrl("今天开会讨论了接口，没有链接"));
        assertNull(SupplierJobService.extractFeishuDocUrl(""));
        assertNull(SupplierJobService.extractFeishuDocUrl(null));
        assertEquals("https://a.feishu.cn/docx/1", SupplierJobService.extractFeishuDocUrl("https://a.feishu.cn/docx/1 和 https://b.feishu.cn/docx/2"), "多链接返回第一个");
        assertEquals("https://b.feishu.cn/docx/2", SupplierJobService.extractFeishuDocUrl("https://feishu.cn.evil.com/a 和 https://b.feishu.cn/docx/2"), "伪装域跳过取下一个合法链接");
    }

    @Test
    void configsCompleteChecksPairsIndependently() {
        assertFalse(SupplierJobService.configsComplete("[]"));
        assertFalse(SupplierJobService.configsComplete(""));
        assertFalse(SupplierJobService.configsComplete(null));
        assertFalse(SupplierJobService.configsComplete("[{\"key\":\"k\",\"value\":\"\"}]"));
        assertTrue(SupplierJobService.configsComplete("[{\"key\":\"k\",\"value\":\"v\"}]"));
    }

    @Test
    void prepMissingMessageListsOnlyMissingItems() {
        String docMissing = SupplierJobService.prepMissingMessage(false, true);
        assertTrue(docMissing.contains("未收到三方会议文档"));
        assertFalse(docMissing.contains("配置"), "只缺文档时不能提配置，避免用户误判链接没提交上");
        String kvMissing = SupplierJobService.prepMissingMessage(true, false);
        assertTrue(kvMissing.contains("配置 key/value"));
        assertFalse(kvMissing.contains("未收到三方会议文档"));
        String both = SupplierJobService.prepMissingMessage(false, false);
        assertTrue(both.contains("未收到三方会议文档") && both.contains("配置 key/value"));
        assertTrue(both.startsWith("【写代码准备】未完成："));
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

    // ===== 实测执行通道判定（11-04-postman-cli-spec） =====

    @Test void probeExecutionChannelAcceptsMcpAndPostmanCliOnly() {
        com.google.gson.JsonElement bashRun = com.google.gson.JsonParser.parseString("{\"command\":\"postman collection run c.json -e env.json\"}");
        com.google.gson.JsonElement curlCmd = com.google.gson.JsonParser.parseString("{\"command\":\"curl -X POST https://api.supplier/quote\"}");
        assertTrue(SupplierProbeEvidence.isExecutionTool("mcp__postman__runCollection"));
        assertTrue(SupplierProbeEvidence.isExecutionTool("Bash", bashRun), "官方 postman CLI 是合规通道");
        assertFalse(SupplierProbeEvidence.isExecutionTool("Bash", curlCmd), "自由 curl 不合规");
        assertFalse(SupplierProbeEvidence.isExecutionTool("Read", bashRun));
    }
}
