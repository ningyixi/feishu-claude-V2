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
}
