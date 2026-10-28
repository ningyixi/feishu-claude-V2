package com.lark.oapi.sample.ws.service;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 供应商知识库解析管线（R2，Java 移植自"内容获取 → 文本提取与清洗 → 分块存储"参考方案）：
 * <ul>
 *   <li>URL：HttpClient 手动重定向（≤3 跳，每跳复核 SSRF），优先探测 .md 直链；
 *       HTML 经 Jsoup 去 script/style/隐藏元素 + 链接绝对化，再 flexmark 转 Markdown</li>
 *   <li>PDF：文件头/大小/页数校验，PDFBox 按页提取（sortByPosition≈layout 模式）；
 *       无可提取文字的页标注疑似扫描件（OCR 出范围）；URL 指向 PDF 下载时走字节流同管线</li>
 *   <li>分块：按标题/空行优先、约 {@value #CHUNK_CHARS} 字符切块，保留原文行号定位</li>
 * </ul>
 * 纯静态、可单测；网络与 SSRF 校验集中在本类。
 */
public final class DocParser {

    public static final int CHUNK_CHARS = 6000;
    public static final int MAX_FETCH_BYTES = 5 * 1024 * 1024;      // 网页正文上限 5MB
    public static final long MAX_PDF_BYTES = 50L * 1024 * 1024;     // 本地/下载 PDF 上限 50MB
    public static final int MAX_PDF_PAGES = 800;
    public static final int MAX_REDIRECTS = 3;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration MD_PROBE_TIMEOUT = Duration.ofSeconds(4);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)   // 手动跟随：每一跳都复核 SSRF
            .build();

    private DocParser() {}

    /** 抓取结果：Markdown 全文、页面标题、最终 URL（重定向后） */
    public record Fetched(String markdown, String title, String finalUrl) {}

    // ===== URL =====

    /** SSRF 防护：仅 http(s)，拒绝回环/私网/链路本地/任意地址（含 IPv6） */
    public static void assertSafeUrl(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL 非法: " + url);
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equals("http") || scheme.equals("https"))) {
            throw new IllegalArgumentException("仅支持 http/https，拒绝: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) throw new IllegalArgumentException("URL 无主机名: " + url);
        try {
            for (java.net.InetAddress addr : java.net.InetAddress.getAllByName(host)) {
                if (addr.isLoopbackAddress() || addr.isSiteLocalAddress() || addr.isLinkLocalAddress()
                        || addr.isAnyLocalAddress() || addr.isMulticastAddress()) {
                    throw new IllegalArgumentException("SSRF 拦截：目标解析到内网/保留地址 " + addr.getHostAddress());
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("域名无法解析: " + host);
        }
    }

    /**
     * URL → Markdown。策略：先探测 url+".md" 直链（参考方案"优先 Markdown 地址"），
     * 失败/回 HTML 则抓原页清洗转换；content-type 或 .pdf 后缀识别 PDF 下载并转字节流管线。
     */
    public static Fetched fetchUrlToMarkdown(String url) throws IOException {
        assertSafeUrl(url);
        // 1) 优先探测同路径 .md 直链（参考方案"优先 Markdown 地址"；只在 path 后追加，避免拼坏域名）
        String mdProbe = mdProbeUrl(url);
        if (mdProbe != null) {
            try {
                Body probe = httpGet(mdProbe, MAX_FETCH_BYTES, MD_PROBE_TIMEOUT);
                String text = new String(probe.bytes(), StandardCharsets.UTF_8);
                if (!looksLikeHtml(text)) {
                    String title = firstHeadingOr(text, url);
                    return new Fetched(chunk(header("来源URL(MD直链)", probe.finalUrl()) + text.strip() + "\n"),
                            title, probe.finalUrl());
                }
            } catch (IOException ignored) { /* 无 md 直链或解析失败，回退原网页 */ }
        }

        // 2) 抓原页
        Body resp = httpGet(url, MAX_FETCH_BYTES, READ_TIMEOUT);
        String ctype = resp.contentType();
        String name = URI.create(resp.finalUrl()).getPath() == null ? "" : URI.create(resp.finalUrl()).getPath().toLowerCase();
        if (ctype.contains("application/pdf") || name.endsWith(".pdf")) {
            String md = pdfBytesToMarkdown(resp.bytes(), resp.finalUrl());
            return new Fetched(md, "PDF: " + resp.finalUrl(), resp.finalUrl());
        }
        if (ctype.contains("text/markdown") || (ctype.startsWith("text/") && !looksLikeHtml(new String(resp.bytes(), StandardCharsets.UTF_8)))) {
            String text = new String(resp.bytes(), StandardCharsets.UTF_8);
            return new Fetched(header("来源URL(纯文本)", resp.finalUrl()) + chunk(text.strip()), firstHeadingOr(text, url), resp.finalUrl());
        }
        String html = new String(resp.bytes(), StandardCharsets.UTF_8);
        String md = cleanHtmlToMarkdown(html, resp.finalUrl());
        // 空正文守卫：JS 渲染的 SPA 文档站抓不到内容，宁可 FAILED 提示，不能让 agent 误信"已解析"
        if (md.strip().length() < 80) {
            throw new IOException("页面正文提取为空（疑似 JS 动态渲染站点，需浏览器渲染——不在当前支持范围；"
                    + "建议改用导出 PDF 入库，或把文档内容粘贴为「笔记」条目）");
        }
        Document doc = Jsoup.parse(html, resp.finalUrl());
        String title = doc.title() != null && !doc.title().isBlank()
                ? doc.title() : firstHeadingOr(md, url);
        return new Fetched(header("来源URL", resp.finalUrl()) + chunk(md), title, resp.finalUrl());
    }

    /** HTML → 清洗 → Markdown（无网络，纯函数，供单测）。标题最终为 ATX(#)风格，保证分块边界可识别 */
    public static String cleanHtmlToMarkdown(String html, String baseUrl) {
        Document doc = Jsoup.parse(html, baseUrl == null ? "" : baseUrl);
        doc.outputSettings().prettyPrint(false);
        doc.select("script, style, noscript, template, svg, canvas, iframe, form, button, input, select, textarea, nav, "
                + "[aria-hidden=true], [hidden], [style*=display:none]").remove();
        doc.select("a[href]").forEach(a -> { String abs = a.absUrl("href"); if (!abs.isBlank()) a.attr("href", abs); });
        doc.select("img[src]").forEach(i -> { String abs = i.absUrl("src"); if (!abs.isBlank()) i.attr("src", abs); });
        // 只留 body，去掉页头杂讯
        String body = doc.body() != null ? doc.body().html() : doc.html();
        String md = FlexmarkHtmlConverter.builder().build().convert(body).strip();
        return setextToAtx(md);
    }

    /**
     * setext 标题（标题行 + ===/--- 下划线，flexmark 对 h1/h2 的默认输出）归一为 ATX # 风格。
     * 判定依据 Markdown 规范：非围栏内、上一行非空文本、当前行纯 =/- 重复串 ⇒ 必为 setext 标题；
     * 独立出现的 ---（前面是空行）是分割线，不动。
     */
    public static String setextToAtx(String md) {
        String[] lines = md.split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String t = line.strip();
            if (t.startsWith("```") || t.startsWith("~~~")) inFence = !inFence;
            boolean underline = !inFence && i > 0 && t.matches("[=-]+")
                    && (t.contains("=") || t.length() >= 2) && !lines[i - 1].isBlank();
            if (underline) {
                int prevNl = out.lastIndexOf("\n", Math.max(0, out.length() - 2));
                if (out.length() >= 1 && prevNl < 0) prevNl = -1;
                String heading = out.substring(prevNl + 1, out.length() - 1);
                out.setLength(prevNl + 1);
                out.append(t.charAt(0) == '=' ? "# " : "## ").append(heading.strip()).append('\n');
                continue;   // 下划线被吸收
            }
            out.append(line).append('\n');
        }
        return out.toString().stripTrailing() + "\n";
    }

    /** 构造同 path 的 .md 探测地址：path 为空/根时不探测；保留原 query/fragment */
    static String mdProbeUrl(String url) {
        try {
            URI u = new URI(url);
            String path = u.getPath() == null ? "" : u.getPath();
            if (path.isEmpty() || path.equals("/")) return null;
            return new URI(u.getScheme(), u.getUserInfo(), u.getHost(), u.getPort(), path + ".md", u.getQuery(), null).toString();
        } catch (URISyntaxException e) {
            return null;
        }
    }

    // ===== PDF =====

    /** 本地 PDF 路径 → 校验 → 按页提取（layout≈sortByPosition）→ 分块 Markdown */
    public static String pdfToMarkdown(Path file) throws IOException {
        long size = Files.size(file);
        if (size > MAX_PDF_BYTES) throw new IOException("PDF 超过上限 " + (MAX_PDF_BYTES / 1024 / 1024) + "MB: " + size);
        byte[] head = new byte[5];
        try (InputStream in = Files.newInputStream(file)) {
            if (in.read(head) < 5) throw new IOException("文件过小，非 PDF");
        }
        if (!new String(head, StandardCharsets.US_ASCII).startsWith("%PDF-")) {
            throw new IOException("文件头校验失败（非 PDF）");
        }
        return pdfBytesToMarkdown(Files.readAllBytes(file), file.toString());
    }

    public static String pdfBytesToMarkdown(byte[] pdf, String sourceLabel) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            int pages = doc.getNumberOfPages();
            if (pages > MAX_PDF_PAGES) throw new IOException("PDF 页数 " + pages + " 超过上限 " + MAX_PDF_PAGES);
            sb.append(header("来源PDF", sourceLabel + "（共 " + pages + " 页）"));
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);   // 按坐标排序≈layout，保留排版阅读顺序
            int pagesWithText = 0;
            for (int p = 1; p <= pages; p++) {
                stripper.setStartPage(p);
                stripper.setEndPage(p);
                String text = stripper.getText(doc).strip();
                if (!text.isEmpty()) pagesWithText++;
                sb.append("## [PDF 第 ").append(p).append(" 页]\n\n");
                if (text.isEmpty()) {
                    sb.append("> （此页无可提取文字，疑似扫描件/图片页——OCR 不在当前支持范围）\n\n");
                } else {
                    sb.append(text).append("\n\n");
                }
            }
            if (pagesWithText == 0) {
                throw new IOException("PDF 全部 " + pages + " 页均无可提取文字（整本扫描件，需 OCR——不在当前支持范围）");
            }
        } catch (IOException e) { throw e; }
        catch (Exception e) { throw new IOException("PDF 解析失败: " + e.getMessage(), e); }
        return chunk(sb.toString());
    }

    // ===== 分块 =====

    /**
     * 约 6000 字符分块：切点优先 标题 > 空行 > 强制行边界；每块插入
     * "## ▍分块 N（原文第 X 行起）" 定位标题。返回分块后的 Markdown。
     */
    public static String chunk(String markdown) {
        String[] lines = markdown.split("\n", -1);
        // 末尾空行不额外续行，避免 rejoin 产生多余尾部换行
        int end = lines.length;
        if (end > 0 && lines[end - 1].isEmpty()) end--;
        StringBuilder out = new StringBuilder();
        int cur = 0;
        int chunkNo = 1;
        for (int i = 0; i < end; i++) {
            String line = lines[i];
            boolean boundary = line.startsWith("#") || line.isBlank();
            boolean mustCut = cur >= CHUNK_CHARS && (boundary || cur >= CHUNK_CHARS * 1.3);
            if (mustCut) {
                chunkNo++;
                out.append("\n## ▍分块 ").append(chunkNo).append("（原文第 ").append(i + 1).append(" 行起）\n\n");
                cur = 0;
            }
            out.append(line).append('\n');
            cur += line.length() + 1;
        }
        // 发生分块时，给第 1 块也补定位标题（保持块编号连续可引用）
        if (chunkNo > 1) {
            int idx = out.indexOf("## ▍分块 2");
            if (idx >= 0) out.insert(idx, "## ▍分块 1（原文第 1 行起）\n\n");
        }
        return out.toString();
    }

    // ===== HTTP 工具 =====

    private record Body(byte[] bytes, String contentType, String finalUrl) {}

    private static Body httpGet(String url, int maxBytes, Duration timeout) throws IOException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            assertSafeUrl(current);
            HttpRequest req;
            try {
                req = HttpRequest.newBuilder(new URI(current))
                        .timeout(timeout)
                        .header("User-Agent", "Mozilla/5.0 (compatible; SupplierKbBot/1.0)")
                        .header("Accept", "text/markdown,text/html;q=0.9,text/plain;q=0.8,*/*;q=0.5")
                        .GET().build();
            } catch (URISyntaxException | IllegalArgumentException e) {
                throw new IOException("URL 非法: " + current + "（" + e.getMessage() + "）", e);
            }
            try {
                HttpResponse<InputStream> resp = CLIENT.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int code = resp.statusCode();
                if (code >= 300 && code < 400) {
                    String loc = resp.headers().firstValue("location").orElse(null);
                    closeQuietly(resp.body());
                    if (loc == null || loc.isBlank()) throw new IOException("3xx 无 Location: " + current);
                    try {
                        current = new URI(current).resolve(loc).toString();
                    } catch (URISyntaxException e) {
                        throw new IOException("非法重定向 Location: " + loc, e);
                    }
                    continue;
                }
                if (code != 200) {
                    closeQuietly(resp.body());
                    throw new IOException("HTTP " + code + ": " + current);
                }
                byte[] bytes = readCapped(resp.body(), maxBytes);
                String ctype = resp.headers().firstValue("content-type").orElse("");
                return new Body(bytes, ctype, current);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("抓取被中断: " + current, e);
            }
        }
        throw new IOException("重定向超过 " + MAX_REDIRECTS + " 跳: " + url);
    }

    private static byte[] readCapped(InputStream in, int maxBytes) throws IOException {
        try (InputStream stream = in) {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] tmp = new byte[8192];
            int n, total = 0;
            while ((n = stream.read(tmp)) > 0) {
                total += n;
                if (total > maxBytes) throw new IOException("响应超过上限 " + (maxBytes / 1024 / 1024) + "MB");
                buf.write(tmp, 0, n);
            }
            return buf.toByteArray();
        }
    }

    private static void closeQuietly(InputStream in) { try { in.close(); } catch (Exception ignored) {} }

    // ===== 小工具 =====

    private static String header(String kind, String source) {
        return "> " + kind + ": " + source + "（解析于 " + LocalDateTime.now().toString().substring(0, 19) + "）\n\n";
    }

    private static boolean looksLikeHtml(String text) {
        String head = text.stripLeading().substring(0, Math.min(200, text.stripLeading().length())).toLowerCase();
        return head.startsWith("<!doctype html") || head.startsWith("<html") || head.contains("<html ");
    }

    private static String firstHeadingOr(String md, String fallback) {
        for (String line : md.split("\n", 20)) {
            if (line.startsWith("#")) return line.replaceFirst("^#+\\s*", "").strip();
        }
        return fallback;
    }
}
