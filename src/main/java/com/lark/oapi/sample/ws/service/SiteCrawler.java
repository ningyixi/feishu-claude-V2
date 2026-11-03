package com.lark.oapi.sample.ws.service;

import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 同站点文档目录递归抓取（11-03-kb-site-crawl，参考 ant_harness crawl.py 规格）。
 * 广度优先 + 队列/去重/预算 + 页面状态清单；纯逻辑，抓取由 Fetcher 注入（可离线单测）。
 *
 * 硬规则：
 * <ul>
 *   <li>范围=严格同源(协议+主机+有效端口) 且 入口 URL 第一级路径前缀（/docs/api/start → 放行整个 /docs/）</li>
 *   <li>MAX_PAGES 是**发现数**上限：发现第 201 个逻辑页 → 整体容量错误，不把前 200 页冒充完整成功</li>
 *   <li>入口失败/尚无有效内容时任意页失败 → 整体失败；已有内容后单页失败 → 标记 failed 继续（部分导入）</li>
 *   <li>致命错误（安全/范围/容量/截断/取消/累计超限）→ 终止，loaded 页降级 failed"导入中断，已抓取内容未保存"</li>
 *   <li>去重：忽略 fragment；/page 与 /page.md 同一逻辑页；发现链接带查询参数不入队（入口可带）</li>
 * </ul>
 */
public final class SiteCrawler {

    public static final int MAX_PAGES = 200;
    public static final long MAX_TOTAL_CHARS = 1_000_000;
    private static final int MIN_BODY_CHARS = 80;      // JS 空壳页判定
    private static final String[] RESOURCE_EXT = {
            ".pdf", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".svg", ".ico",
            ".zip", ".tar", ".gz", ".rar", ".7z", ".css", ".js", ".mjs", ".json",
            ".xml", ".woff", ".woff2", ".ttf", ".otf", ".mp4", ".webm", ".avi", ".exe", ".dmg"};

    /** 抓到的页面：finalUrl=实际响应地址（相对链接基准），text=Markdown 正文 */
    public record FetchedPage(String finalUrl, String text) {}

    /** 抓取实现抛 Fatal（安全/截断等）→ 终止整个导入；其他异常 → 单页失败（按分级策略处理） */
    public static class FatalCrawlError extends RuntimeException {
        public FatalCrawlError(String message) { super(message); }
    }

    public interface Fetcher {
        FetchedPage fetch(String url) throws Exception;
    }

    /** 页面状态：pending（发现未抓取）| loaded | failed */
    public static final class PageState {
        public final String url;            // 逻辑 URL（去 fragment，保留发现序）
        public String title = "";
        public String status = "pending";
        public String error = "";
        public String text = "";            // loaded 后的 Markdown 正文（持久化阶段消费）
        PageState(String url) { this.url = url; }
    }

    public static final class Result {
        public final List<PageState> pages = new ArrayList<>();
        public boolean partial = false;     // 部分导入（有成功也有失败，队列耗尽）
        public String fatal = null;         // 非 null = 整体失败原因（内容不可保存）
        int loadedCount() { return (int) pages.stream().filter(p -> p.status.equals("loaded")).count(); }
    }

    private SiteCrawler() {}

    /**
     * BFS 抓取。onProgress 在每次页面状态变化时收到当前全量页面清单快照（调用方增量持久化）。
     */
    public static Result crawl(String entryUrl, Fetcher fetcher,
                               Consumer<List<PageState>> onProgress, BooleanSupplier cancelled) {
        Result res = new Result();
        URI entry = safeUri(stripFragment(entryUrl));
        if (entry == null) { abort(res, "入口 URL 非法（需完整 http/https 绝对地址）"); return res; }
        String entryOrigin = originOf(entry);
        String entryScope = firstSegmentOf(entry.getPath());

        Deque<String> queue = new ArrayDeque<>();
        Set<String> queued = new HashSet<>();
        Set<String> visited = new HashSet<>();
        PageState start = new PageState(stripFragment(entryUrl));
        res.pages.add(start);
        queued.add(logicKey(start.url));
        queue.add(start.url);
        int discovered = 1;                   // 入口也算一页
        long totalChars = 0;

        while (!queue.isEmpty()) {
            if (cancelled != null && cancelled.getAsBoolean()) {
                abort(res, "抓取已取消");
                return res;
            }
            String url = queue.poll();
            String key = logicKey(url);
            if (!visited.add(key)) continue;
            PageState page = pageOf(res, key);
            try {
                FetchedPage fp = fetcher.fetch(url);
                if (fp == null || fp.text() == null || fp.text().strip().length() < MIN_BODY_CHARS) {
                    page.status = "failed";
                    page.error = "正文不可读取（疑似 JS 动态渲染页面，静态抓取只能拿到空壳）";
                    if (res.loadedCount() == 0) { abort(res, "入口页起就没有可用内容（首个页面正文为空或被拒绝），导入失败"); return res; }
                    res.partial = true;
                    emit(onProgress, res);
                    continue;
                }
                long len = fp.text().length();
                if (totalChars + len > MAX_TOTAL_CHARS) {
                    throw new FatalCrawlError("累计正文超出 " + MAX_TOTAL_CHARS / 10000 + " 万字符上限，请缩小范围或分批导入");
                }
                totalChars += len;
                page.status = "loaded";
                page.text = fp.text();
                page.title = firstHeading(fp.text());
                if (page.title.isBlank()) page.title = hostOf(fp.finalUrl());

                // 发现下一批链接：以“实际响应 URL”为基准解析
                for (String href : extractMdLinks(fp.text())) {
                    String abs = absolutize(fp.finalUrl(), href);
                    if (abs == null) continue;
                    if (!inScope(entryOrigin, entryScope, abs)) continue;
                    if (isResourceUrl(abs)) continue;
                    URI au = safeUri(abs);
                    if (au != null && au.getRawQuery() != null && !au.getRawQuery().isEmpty()) continue; // 新发现的带参链接不入队
                    String k = logicKey(abs);
                    if (queued.contains(k) || visited.contains(k)) continue;   // 循环/重复链接
                    discovered++;
                    if (discovered > MAX_PAGES) {
                        abort(res, "发现第 " + discovered + " 个页面，超出同站点 " + MAX_PAGES + " 页上限——本次导入不完整，请缩小范围或分批导入");
                        return res;
                    }
                    queued.add(k);
                    PageState np = new PageState(abs);
                    res.pages.add(np);
                    queue.add(abs);
                }
                emit(onProgress, res);
            } catch (FatalCrawlError f) {
                page.status = "failed";
                page.error = f.getMessage();
                abort(res, f.getMessage());
                return res;
            } catch (Exception e) {
                page.status = "failed";
                page.error = brief(e);
                if (res.loadedCount() == 0) { abort(res, "入口页抓取失败，未获得任何有效内容: " + brief(e)); return res; }
                res.partial = true;     // 后续页面失败：保留已有内容继续
                emit(onProgress, res);
            }
        }
        if (res.loadedCount() == 0) abort(res, "所有页面抓取失败，没有可用内容");
        else {
            boolean anyFailed = res.pages.stream().anyMatch(p -> p.status.equals("failed"));
            if (anyFailed) res.partial = true;
        }
        emit(onProgress, res);
        return res;
    }

    /** 整体失败：loaded → failed（内容不保存）；pending 保留"未抓取" */
    private static void abort(Result res, String reason) {
        res.fatal = reason;
        for (PageState p : res.pages) {
            if (p.status.equals("loaded")) {
                p.status = "failed";
                p.error = "导入中断，已抓取内容未保存（" + reason + "）";
                p.text = "";
            }
        }
    }

    // ===== URL 规则（包内可见供单测） =====

    static String stripFragment(String url) {
        int i = url.indexOf('#');
        return i < 0 ? url : url.substring(0, i);
    }

    /** 逻辑去重键：去 fragment、去尾部 .md（/page 与 /page.md 同一逻辑页）、去尾斜杠 */
    static String logicKey(String url) {
        String u = stripFragment(url).toLowerCase();
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        if (u.endsWith(".md")) u = u.substring(0, u.length() - 3);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    static String originOf(URI u) {
        int port = u.getPort() != -1 ? u.getPort() : ("https".equalsIgnoreCase(u.getScheme()) ? 443 : 80);
        return (u.getScheme() + "://" + u.getHost() + ":" + port).toLowerCase();
    }

    /** 第一级非空路径段；根路径返回 ""（允许同源全部） */
    static String firstSegmentOf(String path) {
        if (path == null || path.isBlank()) return "";
        String p = path.startsWith("/") ? path.substring(1) : path;
        int i = p.indexOf('/');
        return (i < 0 ? p : p.substring(0, i)).toLowerCase();
    }

    static boolean inScope(String entryOrigin, String entryScope, String url) {
        URI u = safeUri(url);
        if (u == null || u.getScheme() == null || u.getHost() == null) return false;
        if (!originOf(u).equals(entryOrigin)) return false;
        if (entryScope.isEmpty()) return true;
        String path = u.getPath() == null ? "" : u.getPath();
        if (path.startsWith("/")) path = path.substring(1);
        return path.toLowerCase().equals(entryScope) || path.toLowerCase().startsWith(entryScope + "/");
    }

    static boolean isResourceUrl(String url) {
        String u = logicKey(url);
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        for (String ext : RESOURCE_EXT) if (u.endsWith(ext)) return true;
        return false;
    }

    static URI safeUri(String url) {
        try {
            URI u = URI.create(url);
            return (u.getScheme() != null && u.getHost() != null) ? u : null;
        } catch (Exception e) { return null; }
    }

    /** 相对链接以实际响应 URL 为基准 */
    static String absolutize(String base, String href) {
        try {
            String h = href.trim();
            if (h.isEmpty() || h.startsWith("mailto:") || h.startsWith("javascript:") || h.startsWith("data:")) return null;
            URI resolved = URI.create(base).resolve(h);
            if (resolved.getScheme() == null || resolved.getHost() == null) return null;
            if (!resolved.getScheme().equalsIgnoreCase("http") && !resolved.getScheme().equalsIgnoreCase("https")) return null;
            return stripFragment(resolved.toString());
        } catch (Exception e) { return null; }
    }

    /** Markdown 链接提取 [text](url)（HTML 已由 fetcher 转成 Markdown，链接绝对化过） */
    static List<String> extractMdLinks(String md) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("!?\\[[^\\]]*]\\(([^)\\s]+)").matcher(md);
        while (m.find()) out.add(m.group(1));
        return out;
    }

    static String firstHeading(String md) {
        for (String line : md.split("\n", 30)) {
            if (line.startsWith("# ")) return line.substring(2).strip();
        }
        return "";
    }

    private static String hostOf(String url) {
        URI u = safeUri(url);
        return u != null ? u.getHost() : url;
    }

    private static PageState pageOf(Result res, String key) {
        for (PageState p : res.pages) if (logicKey(p.url).equals(key)) return p;
        PageState p = new PageState(key);
        res.pages.add(p);
        return p;
    }

    private static void emit(Consumer<List<PageState>> cb, Result res) {
        if (cb != null) cb.accept(new ArrayList<>(res.pages));
    }

    private static String brief(Exception e) {
        String m = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        return m.length() > 200 ? m.substring(0, 200) + "…" : m;
    }
}
