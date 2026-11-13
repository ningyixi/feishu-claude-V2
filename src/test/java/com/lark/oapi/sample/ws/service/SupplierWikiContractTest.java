package com.lark.oapi.sample.ws.service;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 引文归一化比对（11-04 wiki 门禁顺滑化）：容忍空白/全半角，改写仍视为不同。 */
class SupplierWikiContractTest {
    @Test void ignoresWhitespaceAndNewlines() {
        assertEquals(SupplierWikiContract.normalize("取消订单必须传供应商订单号"),
                     SupplierWikiContract.normalize("取消订单 必须\n传供应商订单号\r\n"));
    }
    @Test void ignoresFullWidthDigits() {
        assertEquals("ABC123", SupplierWikiContract.normalize("ＡＢＣ１２３"));
    }
    @Test void doesNotHideParaphrase() {
        String src = SupplierWikiContract.normalize("取消订单必须传供应商订单号，不能传内部流水号");
        // 逐字子串 → 命中
        assertTrue(src.contains(SupplierWikiContract.normalize("必须传供应商订单号，不能传内部流水号")));
        // 改写(“必须”→“应该”) → 归一化后依然不是子串
        assertFalse(src.contains(SupplierWikiContract.normalize("应该传供应商订单号")));
    }
    @Test void emptyOrZeroWidthStripped() {
        assertEquals("ab", SupplierWikiContract.normalize("a​ \n b　")); // 零宽空格 + 全角空格
    }

    @Test void injectedBoilerplateRemovedFromSources() {        String src = "> 来源URL: https://x/docs （解析于 2026-11-04T10:00:00）\n\n真实业务约定：取消必须传订单号。\n";
        String cleaned = SupplierWikiSources.stripInjected(src);
        assertFalse(cleaned.contains("来源URL"), "程序注入的头部应被剥离");
        assertTrue(cleaned.contains("真实业务约定：取消必须传订单号"), "真实正文应保留");
        // 站点 index 清单里的 "载入 N 页）" 外壳不应再作为可引用内容
        assertFalse(SupplierWikiSources.stripInjected("> 来源入口: https://x （同站点抓取：载入 1 页）\n").contains("载入 1 页"));
        // 本地 HTML 解析产物的来源头同样属于注入样板（含空格/括号的文件名整行剥离）
        String htmlSrc = "> 来源HTML文件: D:\\supplier-docs\\DIRS\\OTA Default Interface (1).html\n\n# 酒店接口\n\n真实字段表：hotelId。\n";
        String cleanedHtml = SupplierWikiSources.stripInjected(htmlSrc);
        assertFalse(cleanedHtml.contains("来源HTML文件"), "HTML 来源头应被剥离");
        assertFalse(cleanedHtml.contains("OTA Default Interface"), "注入头里的路径不应作为可引用内容");
        assertTrue(cleanedHtml.contains("真实字段表：hotelId"), "真实正文应保留");
    }

    @Test void quoteAcceptsDroppedEscapeViaSubsequenceButRejectsFabrication() {
        var src = new SupplierWikiSources.Source("e1","t","url","all","o","h",
                "**签名原文格式**\n使用换行符 `\\n` 拼接，最后一行后面不额外追加换行：");
        // 模型漏抄反引号里的 \n → 严格子串失败，但按序子序列命中 → 放行(不抛)
        SupplierWikiContract.quote("使用换行符 ` ` 拼接，最后一行后面不额外追加换行", src);
        // 编造源中没有的结论 → 仍拒
        assertThrows(IllegalArgumentException.class,
                () -> SupplierWikiContract.quote("取消订单必须传供应商订单号，不能传内部流水号，且限时五秒", src));
    }
}
