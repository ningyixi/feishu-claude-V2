package com.lark.oapi.sample.ws.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GitBook 软 404 守卫单测（11-27-gitbook-404）。
 * 夹具全部取自 2026-11-27 对 docs.mews.com 的实测响应原文（见
 * .trellis/tasks/11-27-gitbook-404/research/gitbook-soft-404.md），零网络。
 */
class DocParserSoftNotFoundTest {

    /** 实测：GET /channel-manager-api.md 返回 200 + text/markdown 的兜底壳（节选原文） */
    private static final String MEWS_SOFT_404_SHELL = """
            # Page Not Found

            The URL `channel-manager-api` does not exist. This page may have been moved, renamed, or deleted.

            ## Suggested Pages

            You may be looking for one of the following:
            - [The Mews APIs](https://docs.mews.com/getting-started/the-mews-apis.md)
            - [Changelogs](https://docs.mews.com/staying-up-to-date/changelogs.md)

            ## How to find the correct page

            If the exact page cannot be found, you can still retrieve the information
            using the documentation query interface.
            """;

    /** 实测：GET /channel-manager-api + Accept:text/markdown 的真实正文（节选原文） */
    private static final String MEWS_REAL_PAGE_MD = """
            > For the complete documentation index, see [llms.txt](https://docs.mews.com/llms.txt). \
            Markdown versions of documentation pages are available by appending `.md` to page URLs; \
            this page is available as [Markdown](https://docs.mews.com/channel-manager-api/readme.md).

            # Mews Channel Manager API

            Welcome to the **Mews Channel Manager API**. This is the Mews API for distribution \
            and sale of accommodations.
            """;

    // ===== R1 looksLikeSoftNotFound：双条件合取，正反两侧都必须是实测样本 =====

    @Test
    void gitbookSoftNotFoundShellIsDetected() {
        assertTrue(DocParser.looksLikeSoftNotFound(MEWS_SOFT_404_SHELL),
                "Mews 实测 404 兜底壳必须判为软 404");
    }

    @Test
    void realMarkdownPageIsNotSoftNotFound() {
        assertFalse(DocParser.looksLikeSoftNotFound(MEWS_REAL_PAGE_MD),
                "带 llms.txt 引言的真实正文不得误判");
    }

    @Test
    void docPageAbout404HandlingIsNotSoftNotFound() {
        // 标题不以 not-found 短语开头的合法文档页——正文怎么提 404 都不拦
        String legit = """
                # Handling 404 errors

                When a resource does not exist, the API responds with HTTP 404.
                The body of a "page not found" error contains an error code.
                """;
        assertFalse(DocParser.looksLikeSoftNotFound(legit),
                "标题合法、只是讲 404 的文档页不得误判");
    }

    @Test
    void headingLikePhraseDeepInBodyIsNotSoftNotFound() {
        // 标题命中只允许发生在前 10 行；深埋在代码示例里的不算
        StringBuilder sb = new StringBuilder("# API Reference\n\nIntro paragraph.\n\n");
        for (int i = 0; i < 12; i++) sb.append("line ").append(i).append('\n');
        sb.append("# Page Not Found\n\nThe URL `x` does not exist.\n");
        assertFalse(DocParser.looksLikeSoftNotFound(sb.toString()),
                "前 10 行之外出现的 not-found 标题样式不得误判");
    }

    @Test
    void notFoundSampleInsideCodeFenceIsNotSoftNotFound() {
        // 前 10 行内以围栏代码块展示 404 响应示例的合法文档页——围栏内的行不是
        // markdown 标题行，即使全文含 "does not exist" 也不得误判（fence 口径同 setextToAtx）
        String tutorial = """
                # Error Handling Guide

                A missing resource responds with HTTP 404 and a markdown body:

                ```
                # Page Not Found
                The URL `orders/42` does not exist.
                ```

                Callers should retry with the collection endpoint.
                """;
        assertFalse(DocParser.looksLikeSoftNotFound(tutorial),
                "代码块内的 not-found 示例文案不得判为软 404");
        String tildeFence = tutorial.replace("```", "~~~");
        assertFalse(DocParser.looksLikeSoftNotFound(tildeFence),
                "~~~ 围栏同样须识别");
    }

    @Test
    void headingHitWithoutNotExistPhraseIsNotSoftNotFound() {
        // 只有标题命中、没有"does not exist"自述 → 不拦（合取条件缺一即假）
        String onlyHeading = "# Not Found\n\nThis section documents our 404 campaign page.";
        assertFalse(DocParser.looksLikeSoftNotFound(onlyHeading),
                "缺少 does not exist 自述时不得误判");
    }

    @Test
    void nullBlankAndShortInputsAreNotSoftNotFound() {
        assertFalse(DocParser.looksLikeSoftNotFound(null));
        assertFalse(DocParser.looksLikeSoftNotFound("   "));
        assertFalse(DocParser.looksLikeSoftNotFound("# hi"));
    }

    @Test
    void notFound404StyleHeadingsAllMatch() {
        for (String h : new String[]{"# Page Not Found", "## page not found",
                "# Not Found", "# 404: Page Not Found"}) {
            String doc = h + "\n\nThe URL `x` does not exist.\n";
            assertTrue(DocParser.looksLikeSoftNotFound(doc), "应命中软404: " + h);
        }
    }

    // ===== R2 探测采信判定：软 404 壳视同"无 md 直链"，回退原 URL =====

    @Test
    void mdProbeAdoptsRealMarkdownButRejectsSoft404Shell() {
        assertTrue(DocParser.adoptMdProbe(MEWS_REAL_PAGE_MD), "真实 md 直链应采信");
        assertFalse(DocParser.adoptMdProbe(MEWS_SOFT_404_SHELL), "软 404 壳不得采信，须回退");
    }

    @Test
    void mdProbeRejectsHtmlAsBefore() {
        assertFalse(DocParser.adoptMdProbe("<!DOCTYPE html><html><body>hi</body></html>"),
                "HTML 响应维持既有回退语义");
    }
}
