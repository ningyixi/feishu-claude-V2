package com.lark.oapi.sample.ws.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 同站点 BFS 递归抓取的<b>真实 HTTP 验证</b>（11-24-adhoc-site-crawl 验证方式）：
 * 测试内起 {@link HttpServer}（127.0.0.1 随机端口）搭多层文档站——md/html 混合链接、
 * 201 逻辑页超限站、慢页/失败页/JS 空壳页——喂给<b>生产同一个</b> {@link SiteCrawler#crawl}
 * （ArrayDeque BFS + visited/queued 去重 + 同源同前缀范围，本测试不碰其任何语义）。
 * <b>禁止访问任何真实供应商域名或外网</b>：全部流量都落在本进程回环端口上。
 * <p>
 * Fetcher 说明（如实）：生产链路 {@code SupplierKbService.crawlNow} 的 fetcher 是
 * {@link DocParser#fetchPage}，它入口即跑 {@link DocParser#assertSafeUrl}——SSRF 防线<b>按设计</b>
 * 拒绝一切回环/内网地址，因此本地夹具无法穿过生产 fetcher（这正是它该有的行为，本测试不改它）。
 * 这里用最小 fetcher：md/纯文本直读、HTML 走生产同一个 {@link DocParser#cleanHtmlToMarkdown}
 * 清洗管线（不含 md 直链探测与逐跳 SSRF 复检）；BFS/去重/上限/取消/错误分级全部是生产代码。
 * <p>
 * AdHoc 暴露层的接线（勾选 → site=true 入库 → 未完成不编译）见 {@link AdHocSiteCrawlTest}；
 * 深层页面正文进 Wiki 编译输入见 {@link SiteCrawlWikiInputTest}。
 */
class SiteCrawlHttpFixtureTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    /** 页面响应：contentType + 正文；delayMs>0 = 慢页 */
    private record Page(String contentType, String body, long delayMs) {
        static Page html(String body) { return new Page("text/html;charset=utf-8", body, 0); }
        static Page md(String body) { return new Page("text/markdown;charset=utf-8", body, 0); }
    }

    /** 起本地夹具站：pages 之外的路径一律 404（模拟死链） */
    private String startSite(Map<String, Page> pages) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            Page page = pages.get(path);
            try {
                if (page == null) {
                    byte[] msg = ("not found: " + path).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(404, msg.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(msg); }
                    return;
                }
                if (page.delayMs() > 0) Thread.sleep(page.delayMs());
                byte[] bytes = page.body().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", page.contentType());
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(bytes); }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** 最小 fetcher（回环地址被生产 SSRF 防线按设计拒绝，见类注释）：md/纯文本直读，HTML 走生产清洗管线 */
    private static SiteCrawler.Fetcher loopbackFetcher(AtomicInteger fetches) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        return url -> {
            if (fetches != null) fetches.incrementAndGet();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
                    .header("Accept", "text/markdown,text/html;q=0.9,text/plain;q=0.8,*/*;q=0.5")
                    .GET().build();
            HttpResponse<byte[]> resp;
            try {
                resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("抓取被中断: " + url, ie);
            }
            if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode() + ": " + url);
            String ctype = resp.headers().firstValue("content-type").orElse("");
            String text = new String(resp.body(), StandardCharsets.UTF_8);
            if (ctype.contains("markdown") || !looksLikeHtml(text)) {
                return new SiteCrawler.FetchedPage(url, text.strip());
            }
            return new SiteCrawler.FetchedPage(url, DocParser.cleanHtmlToMarkdown(text, url));
        };
    }

    private static boolean looksLikeHtml(String text) {
        String head = text.stripLeading().substring(0, Math.min(200, text.stripLeading().length())).toLowerCase();
        return head.startsWith("<!doctype html") || head.startsWith("<html");
    }

    /** 夹具正文段落：必须让转换后的 Markdown 稳过 SiteCrawler 的 80 字符空壳判定线（否则会被如实判为 JS 壳页） */
    private static final String BODY_TEXT =
            "这是本地夹具站的文档正文段落，用于跨过多层链接发现与最小正文长度判定；站点抓取要求单页正文不少于八十个字符，不足即按疑似动态渲染空壳页如实记失败。";

    /** html 页面骨架：正文凑够 80 字符（SiteCrawler 的 JS 空壳判定线），links 转 <a href> */
    private static String htmlPage(String title, String... links) {
        StringBuilder sb = new StringBuilder("<!DOCTYPE html><html><head><title>").append(title)
                .append("</title></head><body><h1>").append(title).append("</h1>")
                .append("<p>").append(BODY_TEXT).append("</p>");
        for (String l : links) sb.append("<a href=\"").append(l).append("\">").append(l).append("</a> ");
        return sb.append("</body></html>").toString();
    }

    private static String mdPage(String title, String... links) {
        StringBuilder sb = new StringBuilder("# ").append(title).append("\n\n")
                .append(BODY_TEXT).append('\n');
        for (String l : links) sb.append("\n[").append(l).append("](").append(l).append(")");
        return sb.toString();
    }

    // ===== AC1（抓取层证据）：多层站 + md/html 混合链接 → BFS 全量抓完，深层页正文在产物里 =====

    @Test
    void multiLevelSiteWithMixedMdAndHtmlLinksCrawlsAllPages() throws Exception {
        Map<String, Page> pages = new HashMap<>();
        // 第 1 层（html）：链接有 md 直链、相对路径、fragment/尾斜杠变体（去重）、资源、越界、带参
        pages.put("/docs/start", Page.html(htmlPage("入口",
                "/docs/guide/install.md",           // md 直链（第 2 层）
                "usage",                            // 相对链接 → /docs/usage（第 2 层）
                "/docs/deep/policy#section",        // fragment：与下面同一逻辑页
                "/docs/deep/policy/",               // 尾斜杠：同一逻辑页
                "/docs/asset/logo.png",             // 资源：跳过
                "/guide/outside",                   // 越出入口一级路径 /docs：跳过
                "/docs/deep/policy?x=1")));         // 新发现带查询参数：不入队
        // 第 2 层（md）：正文里继续发现第 3 层链接（BFS 递归的核心证据）
        pages.put("/docs/guide/install.md", Page.md(mdPage("安装指南", "/docs/deep/policy")));
        pages.put("/docs/usage", Page.html(htmlPage("使用说明", "/docs/start")));   // 循环回入口
        // 第 3 层（html）：深层页固定关键词（编译输入断言锚点，与 SiteCrawlWikiInputTest 同一关键词）
        pages.put("/docs/deep/policy", Page.html(htmlPage("取消政策")
                .replace("</p>", "取消政策深层条款 DEEP-PAGE-KEYWORD-42：取消必须携带供应商订单号。</p>")));
        String base = startSite(pages);

        List<List<String>> snapshots = new CopyOnWriteArrayList<>();
        SiteCrawler.Result r = SiteCrawler.crawl(base + "/docs/start", loopbackFetcher(null),
                ps -> snapshots.add(ps.stream().map(p -> p.status).toList()), () -> false);

        assertNull(r.fatal, "多层站应完整成功，实际 fatal=" + r.fatal);
        assertEquals(4, r.pages.size(), "fragment/尾斜杠/循环/资源/越界/带参全部收敛后应为 4 个逻辑页");
        assertEquals(4, r.pages.stream().filter(p -> "loaded".equals(p.status)).count(), "全部载入");
        String deep = r.pages.stream().filter(p -> p.url.endsWith("/docs/deep/policy")).findFirst()
                .orElseThrow().text;
        assertTrue(deep.contains("DEEP-PAGE-KEYWORD-42"), "第 3 层深层页正文必须真的抓到了: " + deep);
        assertTrue(snapshots.stream().anyMatch(s -> s.contains("pending")),
                "BFS 过程中必须出现过 pending（发现未抓取）快照，证明是逐层发现而不是单页; snapshots=" + snapshots);
        System.out.println("[AC1-HttpServer] 逻辑页=" + r.pages.size() + " 全部loaded, 深层页含关键词=true");
        r.pages.forEach(p -> System.out.println("  - " + p.status + " " + p.url + " 「" + p.title + "」"));
    }

    // ===== AC3（抓取层证据）：取消 → 整体中断，loaded 页如实降级"导入中断"，绝不伪装 DONE =====

    @Test
    void cancelDuringSlowCrawlAbortsAndDemotesLoadedPagesHonestly() throws Exception {
        Map<String, Page> pages = new HashMap<>();
        pages.put("/docs/start", Page.html(htmlPage("入口", "/docs/slow")));
        pages.put("/docs/slow", new Page("text/html;charset=utf-8", htmlPage("慢页"), 1500));
        String base = startSite(pages);

        AtomicInteger fetches = new AtomicInteger();
        // 入口页抓完后立刻请求取消（= 用户点了「取消抓取」，cancelCrawl 标记在下一个页边界生效）
        SiteCrawler.Result r = SiteCrawler.crawl(base + "/docs/start", loopbackFetcher(fetches),
                null, () -> fetches.get() >= 1);

        assertEquals("抓取已取消", r.fatal, "取消必须以整体中断收场，fatal 原文=" + r.fatal);
        assertTrue(r.pages.stream().noneMatch(p -> "loaded".equals(p.status)), "不得留下任何 loaded 冒充成功");
        var entry = r.pages.get(0);
        assertEquals("failed", entry.status);
        assertTrue(entry.error.contains("导入中断，已抓取内容未保存（抓取已取消）"),
                "已抓的入口页如实降级并写明原因，实际 error=" + entry.error);
        var slow = r.pages.stream().filter(p -> p.url.endsWith("/docs/slow")).findFirst().orElseThrow();
        assertEquals("pending", slow.status, "未抓到的慢页保持 pending（未抓取），不静默消失");
        assertEquals(1, fetches.get(), "取消在页边界生效：慢页一次都没抓");
        System.out.println("[AC3-HttpServer] fatal=" + r.fatal + "; 入口页=" + entry.status + "/" + entry.error
                + "; 慢页=" + slow.status + "; 实际抓取次数=" + fetches.get());
    }

    // ===== AC4（抓取层证据）：201 逻辑页超限站 → SiteCrawler 原始容量错误逐字、整体失败不冒充部分成功 =====

    @Test
    void overLimitSiteWith201LogicalPagesAbortsWithOriginalCapacityError() throws Exception {
        Map<String, Page> pages = new HashMap<>();
        String[] links = new String[200];
        for (int i = 1; i <= 200; i++) links[i - 1] = "/docs/p" + i;
        pages.put("/docs/start", Page.html(htmlPage("超限入口", links)));
        for (int i = 1; i <= 200; i++) pages.put("/docs/p" + i, Page.html(htmlPage("p" + i)));
        String base = startSite(pages);

        AtomicInteger fetches = new AtomicInteger();
        SiteCrawler.Result r = SiteCrawler.crawl(base + "/docs/start", loopbackFetcher(fetches), null, () -> false);

        // 原始错误逐字透传（SupplierKbService.crawlNow 的 truncErr 400 字符内不截断，AdHoc 段失败原因再原样带上）
        assertEquals("发现第 201 个页面，超出同站点 200 页上限——本次导入不完整，请缩小范围或分批导入", r.fatal);
        assertTrue(r.pages.stream().noneMatch(p -> "loaded".equals(p.status)),
                "超限=整体失败：已载入页全部降级，绝不把前 200 页冒充完整成功");
        assertFalse(r.partial, "fatal 非空时不是部分导入");
        assertEquals(1, fetches.get(), "发现第 201 页即终止：只抓过入口页");
        assertTrue(r.pages.get(0).error.contains("导入中断，已抓取内容未保存"), r.pages.get(0).error);
        System.out.println("[AC4-HttpServer] fatal 原文=" + r.fatal);
        System.out.println("[AC4-HttpServer] 入口页状态=" + r.pages.get(0).status + ", error=" + r.pages.get(0).error
                + ", 实际抓取次数=" + fetches.get());
    }

    // ===== 慢页/失败页/JS 空壳页：单页失败原因逐页如实保留（前 3 条展示的数据源），不静默丢页 =====

    @Test
    void slowFailingAndJsShellPagesKeepHonestPerPageReasons() throws Exception {
        Map<String, Page> pages = new HashMap<>();
        pages.put("/docs/start", Page.html(htmlPage("入口",
                "/docs/broken",      // 404 死链
                "/docs/slow500",     // 慢页：睡 800ms 后 500（独立 context）
                "/docs/spa",         // JS 空壳（正文 <80 字符）
                "/docs/good2")));    // 正常页
        pages.put("/docs/good2", Page.html(htmlPage("正常第二页")));
        pages.put("/docs/spa", Page.html("<!DOCTYPE html><html><body><div id=\"root\"></div></body></html>"));
        String base = startSite(pages);
        // /docs/slow500 独立 context（最长前缀优先于 "/"）：真实拖慢 800ms 后如实 500
        server.createContext("/docs/slow500", exchange -> {
            try {
                Thread.sleep(800);
                byte[] msg = "boom".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(500, msg.length);
                try (OutputStream os = exchange.getResponseBody()) { os.write(msg); }
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                exchange.close();
            }
        });

        long t0 = System.currentTimeMillis();
        SiteCrawler.Result r = SiteCrawler.crawl(base + "/docs/start", loopbackFetcher(null), null, () -> false);
        long elapsed = System.currentTimeMillis() - t0;

        assertNull(r.fatal, "入口页已载入后，单页失败不终止整体（部分导入语义），fatal=" + r.fatal);
        assertTrue(r.partial, "有失败页 → 必须如实标记部分导入");
        assertEquals(2, r.pages.stream().filter(p -> "loaded".equals(p.status)).count(), "入口+正常页载入");
        var byUrl = new HashMap<String, SiteCrawler.PageState>();
        r.pages.forEach(p -> byUrl.put(p.url.substring(p.url.indexOf("/docs/")), p));
        assertTrue(byUrl.get("/docs/broken").error.contains("HTTP 404"), "死链原因: " + byUrl.get("/docs/broken").error);
        assertTrue(byUrl.get("/docs/slow500").error.contains("HTTP 500"), "慢页 500 原因: " + byUrl.get("/docs/slow500").error);
        assertTrue(byUrl.get("/docs/spa").error.contains("JS 动态渲染"),
                "SPA 空壳页如实报告抓不到正文的原因，不许用「已抓到 N 页」掩盖: " + byUrl.get("/docs/spa").error);
        assertTrue(r.pages.stream().filter(p -> "failed".equals(p.status)).allMatch(p -> !p.error.isBlank()),
                "每个失败页都必须带原因（前 3 条展示的数据源），不静默丢页");
        assertTrue(elapsed >= 800, "慢页真实等待过（夹具生效证据）: elapsed=" + elapsed + "ms");
        System.out.println("[慢页/失败页-HttpServer] elapsed=" + elapsed + "ms, partial=" + r.partial);
        r.pages.forEach(p -> System.out.println("  - " + p.status + " " + p.url
                + (p.error.isBlank() ? "" : " 原因=" + p.error)));
    }
}
