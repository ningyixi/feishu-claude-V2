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

    @Test void injectedBoilerplateRemovedFromSources() {
        String src = "> 来源URL: https://x/docs （解析于 2026-11-04T10:00:00）\n\n真实业务约定：取消必须传订单号。\n";
        String cleaned = SupplierWikiSources.stripInjected(src);
        assertFalse(cleaned.contains("来源URL"), "程序注入的头部应被剥离");
        assertTrue(cleaned.contains("真实业务约定：取消必须传订单号"), "真实正文应保留");
        // 站点 index 清单里的 "载入 N 页）" 外壳不应再作为可引用内容
        assertFalse(SupplierWikiSources.stripInjected("> 来源入口: https://x （同站点抓取：载入 1 页）\n").contains("载入 1 页"));
    }
}
