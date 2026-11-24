package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * C1/AC1（2026-11-24 P0）：飞书卡片 schema V2 不再支持 tag:"action"
 * （code=200861 unsupported tag action，现网 ErrPath: ROOT -> elements -> [4](tag: action)），
 * 导致终态 card.update 与 im.message.patch 双双被拒 → 回复"看不见"。
 * 修复方案：可选项降级为 markdown 正文列表（**可选：** 1) … 2) …），不保留交互控件。
 * 本测试断言：任何卡片 JSON 组装结果与 CardBuilder 源码中都不再出现 action 标签。
 */
class CardBuilderTest {

    /** 含可选项的回复 — 复现现网触发面（hasOptions=true 才会走选项卡路径，故故障是间歇性的） */
    private static final String OPTION_TEXT = """
            我分析了你的问题，有以下两个方案：
            **1. 方案甲** — 快但贵
            **2. 方案乙** — 慢但便宜
            请回复编号选择。""";

    @Test
    void finalCardWithOptionsHasNoActionTag() {
        // 前置：该文本确实被判定含可选项（否则测不到病灶路径）
        assertTrue(CardBuilder.hasOptions(OPTION_TEXT), "测试文本必须被检测出可选项");
        List<CardBuilder.Option> options = CardBuilder.extractOptions(OPTION_TEXT);
        assertEquals(2, options.size());

        TurnResult r = new TurnResult(OPTION_TEXT, false);
        r.durationMs = 1234;
        String json = CardBuilder.buildFinalCardJson(r, options);

        assertFalse(json.contains("\"tag\":\"action\""),
                "终态卡 JSON 不得再含 tag:action（schema V2 报 200861）:\n" + json);
        assertFalse(json.contains("\"tag\":\"button\""), "按钮控件一并移除");
        assertNoActionTagDeep(JsonParser.parseString(json));

        // 可选项降级为 markdown 列表，编号与标题保留
        assertTrue(json.contains("**可选：**"), "可选项应降级为 markdown 列表: " + json);
        assertTrue(json.contains("1) 方案甲") && json.contains("2) 方案乙"));
        // 正文完整保留
        assertTrue(json.contains("我分析了你的问题，有以下两个方案："));
        assertTrue(json.contains("请回复编号选择。"));
        assertEquals("2.0", JsonParser.parseString(json).getAsJsonObject().get("schema").getAsString());
    }

    @Test
    void finalCardLongAnswerWithOptionsStillBuilds() {
        // AC1：带可选项的长答案（>8000 字，触发多 markdown 元素拆分）也能正常产出终态卡 JSON。
        // 注意控制在 MAX_FINAL_TEXT(20000) 以内——超长截断是既有设计，不属于本用例断言范围。
        String longBody = ("段落内容重复测试长文本拆分行为。 ".repeat(200) + "\n\n").repeat(3);
        String text = longBody + OPTION_TEXT;
        assertTrue(text.length() > 8000, "测试文本必须足够长，实际=" + text.length());
        assertTrue(text.length() < 20000, "不得触发既有的 20000 字截断，实际=" + text.length());
        assertTrue(CardBuilder.hasOptions(text));

        String json = CardBuilder.buildFinalCardJson(new TurnResult(text, false), CardBuilder.extractOptions(text));

        JsonObject card = JsonParser.parseString(json).getAsJsonObject();   // 合法 JSON
        assertEquals("2.0", card.get("schema").getAsString());
        assertFalse(json.contains("\"tag\":\"action\""));
        assertNoActionTagDeep(card);
        assertTrue(json.contains("**可选：**"));
        assertTrue(json.contains("请回复编号选择。"), "长答案尾部正文不得丢失");
        // 正文按 3500 字上限拆成多个 markdown 元素
        int mdCount = 0;
        for (JsonElement el : card.getAsJsonObject("body").getAsJsonArray("elements")) {
            if ("markdown".equals(el.getAsJsonObject().get("tag").getAsString())) mdCount++;
        }
        assertTrue(mdCount >= 3, "长文本应拆分为多个 markdown 元素，实际=" + mdCount);
    }

    @Test
    void legacyOptionsCardHasNoActionTag() {
        // buildOptionsCard / buildCards（FeishuBotService.replyText 长文本拆卡路径）同样不得产出 action 标签
        List<CardBuilder.Option> options = CardBuilder.extractOptions(OPTION_TEXT);
        String json = CardBuilder.buildOptionsCard(OPTION_TEXT, options);
        assertFalse(json.contains("\"tag\":\"action\""), json);
        assertFalse(json.contains("\"tag\":\"button\""));
        assertNoActionTagDeep(JsonParser.parseString(json));
        assertTrue(json.contains("**可选：**") && json.contains("1) 方案甲"));

        for (String c : CardBuilder.buildCards(OPTION_TEXT, options)) {
            assertFalse(c.contains("\"tag\":\"action\""));
            assertNoActionTagDeep(JsonParser.parseString(c));
        }
        // 长文本拆分路径：最后一条含可选项列表，全部无 action
        String longText = ("段落内容重复测试长文本拆分行为。 ".repeat(200) + "\n\n").repeat(6) + OPTION_TEXT;
        List<String> cards = CardBuilder.buildCards(longText, CardBuilder.extractOptions(longText));
        assertTrue(cards.size() >= 2, "长文本应拆成多条卡片");
        for (String c : cards) assertFalse(c.contains("\"tag\":\"action\""));
        assertTrue(cards.get(cards.size() - 1).contains("**可选：**"), "可选项列表应挂在最后一条卡片");
    }

    @Test
    void cardBuilderSourceHasNoActionTagLiteral() throws Exception {
        // AC1 静态断言：源码层面杜绝 addProperty("tag", "action") 回潮
        Path src = Path.of("src/main/java/com/lark/oapi/sample/ws/service/CardBuilder.java");
        assertTrue(Files.exists(src), "找不到 CardBuilder 源码: " + src.toAbsolutePath());
        String source = Files.readString(src, StandardCharsets.UTF_8);
        assertFalse(source.contains("\"tag\", \"action\""),
                "CardBuilder 源码不得再写入 tag:\"action\"（schema V2 → 200861）");
        assertFalse(source.contains("\"tag\":\"action\""));
    }

    /** 递归断言 JSON 树中任何层级都不存在 tag=action 的元素 */
    private static void assertNoActionTagDeep(JsonElement el) {
        if (el instanceof JsonObject obj) {
            JsonElement tag = obj.get("tag");
            assertFalse(tag != null && tag.isJsonPrimitive() && "action".equals(tag.getAsString()),
                    "卡片元素出现 tag:action（schema V2 报 200861）: " + obj);
            for (var e : obj.entrySet()) assertNoActionTagDeep(e.getValue());
        } else if (el instanceof JsonArray arr) {
            for (JsonElement e : arr) assertNoActionTagDeep(e);
        }
    }
}
