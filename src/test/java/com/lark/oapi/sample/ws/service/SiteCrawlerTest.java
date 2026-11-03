package com.lark.oapi.sample.ws.service;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 站点爬虫纯逻辑单测（stub Fetcher，离线无网络）——覆盖 11-03-kb-site-crawl 验收清单。
 */
class SiteCrawlerTest {

    static final String ENTRY = "https://docs.demo.com/docs/api/start";

    /** 页面地图：url → markdown 正文 */
    private static SiteCrawler.Fetcher stub(Map<String, String> site) {
        return url -> {
            String body = site.get(url);
            if (body == null) throw new java.io.IOException("HTTP 404: " + url);
            return new SiteCrawler.FetchedPage(url, body);
        };
    }

    private static SiteCrawler.Result run(Map<String, String> site) {
        return SiteCrawler.crawl(ENTRY, stub(site), null, () -> false);
    }

    private static String page(String title, String... links) {
        StringBuilder sb = new StringBuilder("# " + title + "\n\n" + "正文内容示例。".repeat(20));
        int i = 0;
        for (String l : links) sb.append("\n[链接").append(i++).append("](").append(l).append(")");
        return sb.toString();
    }

    // ===== 范围与过滤 =====

    @Test
    void multiPageSuccessWithinScope() {
        Map<String, String> site = new HashMap<>();
        site.put(ENTRY, page("入口", "/docs/api/booking", "https://docs.demo.com/docs/other"));
        site.put("https://docs.demo.com/docs/api/booking", page("下单"));
        site.put("https://docs.demo.com/docs/other", page("其他"));
        SiteCrawler.Result r = run(site);
        assertNull(r.fatal);
        assertEquals(3, r.pages.size());
        assertTrue(r.pages.stream().allMatch(p -> p.status.equals("loaded")));
    }

    @Test
    void filtersCrossOriginPortsPathScopeResourcesAndQueries() {
        Map<String, String> site = new HashMap<>();
        site.put(ENTRY, page("入口",
                "https://evil.com/docs/x",                                  // 跨域
                "https://docs.demo.com:8443/docs/y",                        // 跨端口
                "https://docs.demo.com/guide/out",                          // 越出一级路径
                "https://docs.demo.com/docs/spec.pdf",                      // 资源
                "https://docs.demo.com/docs/api/q?x=1",                     // 新发现带查询参数
                "https://sub.docs.demo.com/docs/z"));                       // 子域=不同源
        SiteCrawler.Result r = run(site);
        assertNull(r.fatal);
        assertEquals(1, r.pages.size(), "全部候选链接都应在发现阶段被过滤，只留入口页");
    }

    // ===== 去重 =====

    @Test
    void dedupFragmentAndMdSuffixAndCycles() {
        Map<String, String> site = new HashMap<>();
        site.put(ENTRY, page("入口",
                "https://docs.demo.com/docs/api/booking#section",           // fragment
                "https://docs.demo.com/docs/api/booking.md",                // .md 同逻辑页
                "https://docs.demo.com/docs/api/booking/",                  // 尾斜杠
                "https://docs.demo.com/docs/api/booking"));                 // 重复
        site.put("https://docs.demo.com/docs/api/booking", page("下单", ENTRY));  // 循环回入口
        SiteCrawler.Result r = run(site);
        assertEquals(2, r.pages.size(), "fragment/.md/尾斜杠/循环都应收敛为同一逻辑页");
        assertEquals(2, r.pages.stream().filter(p -> p.status.equals("loaded")).count());
    }

    @Test
    void relativeLinksResolveAgainstFinalUrl() {
        Map<String, String> site = new HashMap<>();
        site.put(ENTRY, page("入口", "booking"));    // 相对链接 → /docs/api/booking
        site.put("https://docs.demo.com/docs/api/booking", page("下单"));
        SiteCrawler.Result r = run(site);
        assertEquals(2, r.pages.size());
    }

    // ===== 容量硬上限 =====

    @Test
    void exactly200PagesOk201Aborts() {
        // 入口 + p1..p199 = 恰好 200 页
        Map<String, String> site = new HashMap<>();
        site.put(ENTRY, page("p0", link(1)));
        for (int i = 1; i <= 199; i++) site.put(link(i), page("p" + i, i < 199 ? link(i + 1) : ""));
        SiteCrawler.Result ok = run(site);
        assertNull(ok.fatal, "恰好 200 页应完整成功");
        assertEquals(200, ok.pages.size());

        Map<String, String> site2 = new HashMap<>(site);
        site2.put(link(199), page("p199", link(200)));                 // 发现第 201 个页面
        site2.put("https://docs.demo.com/docs/p200", page("p200"));
        SiteCrawler.Result over = run(site2);
        assertNotNull(over.fatal);
        assertTrue(over.fatal.contains("201"), over.fatal);
        assertTrue(over.pages.stream().noneMatch(p -> p.status.equals("loaded")), "超限整体失败，loaded 全部降级");
    }

    private static String link(int i) {
        return "https://docs.demo.com/docs/p" + i;
    }

    @Test
    void totalCharBudgetExceededAbortsEntireImport() {
        Map<String, String> site = new HashMap<>();
        String huge = "字".repeat((int) SiteCrawler.MAX_TOTAL_CHARS + 1);
        site.put(ENTRY, huge);
        SiteCrawler.Result r = run(site);
        assertNotNull(r.fatal);
        assertTrue(r.fatal.contains("上限"), r.fatal);
    }

    // ===== 错误分级 =====

    @Test
    void entryFailureFailsWholeImport() {
        SiteCrawler.Result r = SiteCrawler.crawl(ENTRY, url -> { throw new java.io.IOException("连接失败"); }, null, () -> false);
        assertNotNull(r.fatal);
        assertTrue(r.fatal.contains("入口"), r.fatal);
    }

    @Test
    void midPageFailureKeepsPartialSuccess() {
        Map<String, String> site = new HashMap<>();
        site.put(ENTRY, page("入口", "https://docs.demo.com/docs/broken", "https://docs.demo.com/docs/good"));
        site.put("https://docs.demo.com/docs/good", page("好页"));
        // broken 不在 map → stub 抛 404
        SiteCrawler.Result r = run(site);
        assertNull(r.fatal, "已有成功内容后单页失败不终止整体");
        assertTrue(r.partial);
        assertEquals(2, r.pages.stream().filter(p -> p.status.equals("loaded")).count());
        assertEquals(1, r.pages.stream().filter(p -> p.status.equals("failed")).count());
    }

    @Test
    void securityViolationIsFatalAndDemotesLoaded() {
        SiteCrawler.Fetcher fetcher = url -> {
            if (url.equals(ENTRY)) return new SiteCrawler.FetchedPage(url, page("入口", "https://docs.demo.com/docs/evil"));
            throw new SiteCrawler.FatalCrawlError("SSRF 拦截：目标解析到内网");
        };
        SiteCrawler.Result r = SiteCrawler.crawl(ENTRY, fetcher, null, () -> false);
        assertNotNull(r.fatal);
        assertTrue(r.fatal.contains("SSRF"));
        assertTrue(r.pages.stream().noneMatch(p -> p.status.equals("loaded")), "安全错误不留可检索半成品");
        assertEquals("failed", r.pages.get(0).status);
    }

    @Test
    void jsShellPageMarkedFailedNotLoaded() {
        Map<String, String> site = new HashMap<>();
        site.put(ENTRY, "<div id=root></div>");   // 空壳，<80 字符有效正文
        SiteCrawler.Result r = run(site);
        assertNotNull(r.fatal, "第一页就空壳=没有有效内容，整体失败");
        assertTrue(r.pages.get(0).error.contains("JS"), r.pages.get(0).error);
    }

    @Test
    void cancelAbortsAndDemotes() {
        Map<String, String> site = new HashMap<>();
        site.put(ENTRY, page("入口", "https://docs.demo.com/docs/p2"));
        site.put("https://docs.demo.com/docs/p2", page("第二页"));
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        SiteCrawler.Result r2 = SiteCrawler.crawl(ENTRY, stub(site), null, () -> checks.incrementAndGet() > 1);
        assertNotNull(r2.fatal);
        assertTrue(r2.fatal.contains("取消"), r2.fatal);
        assertTrue(r2.pages.stream().noneMatch(p -> p.status.equals("loaded")));
    }

    @Test
    void progressCallbackSeesPendingStates() {
        Map<String, String> site = new HashMap<>();
        site.put(ENTRY, page("入口", "https://docs.demo.com/docs/p2"));
        site.put("https://docs.demo.com/docs/p2", page("第二页"));
        // PageState 是可变对象且 emit 为浅拷贝快照——断言捕获即时状态串而非对象引用
        List<List<String>> snapshots = new java.util.concurrent.CopyOnWriteArrayList<>();
        SiteCrawler.Result r = SiteCrawler.crawl(ENTRY, stub(site),
                ps -> snapshots.add(ps.stream().map(p -> p.status).toList()), () -> false);
        assertNull(r.fatal);
        assertTrue(snapshots.stream().anyMatch(s -> s.size() == 2 && s.get(1).equals("pending")),
                "发现时应能看到 pending 快照; snapshots=" + snapshots);
        assertTrue(snapshots.get(snapshots.size() - 1).stream().allMatch("loaded"::equals));
    }

    // ===== URL 规则单测 =====

    @Test
    void urlRules() {
        assertEquals("https://a.com/x", SiteCrawler.stripFragment("https://a.com/x#frag"));
        assertEquals(SiteCrawler.logicKey("https://a.com/docs/p"), SiteCrawler.logicKey("https://a.com/docs/p.md"));
        assertEquals(SiteCrawler.logicKey("https://a.com/docs/p"), SiteCrawler.logicKey("https://a.com/docs/p/#top"));
        assertTrue(SiteCrawler.isResourceUrl("https://a.com/f.PNG"));
        assertFalse(SiteCrawler.isResourceUrl("https://a.com/api/start"));
        assertEquals("docs", SiteCrawler.firstSegmentOf("/docs/api/start"));
        assertEquals("", SiteCrawler.firstSegmentOf("/"));
        assertEquals("http://a.com:80", SiteCrawler.originOf(java.net.URI.create("http://a.com/x")));
        assertNotEquals(SiteCrawler.originOf(java.net.URI.create("https://a.com")),
                SiteCrawler.originOf(java.net.URI.create("http://a.com")), "协议不同不同源");
    }
}
