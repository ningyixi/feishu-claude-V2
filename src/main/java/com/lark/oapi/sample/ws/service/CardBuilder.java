package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 解析 Claude 回复中的选项，生成飞书交互式卡片 JSON
 */
public class CardBuilder {

    // 匹配多种格式的选项：
    // **1. 标题** / **A. 标题** / 1. emoji **标题** / - **A.** 标题 / A. **标题**
    private static final Pattern OPTION_PATTERN = Pattern.compile(
            "(?:" +
            "\\*\\*(\\d+|[A-Z])[\\.\\)、]\\s*(.+?)\\*\\*" +           // **1. 标题** 或 **A. 标题**
            "|" +
            "(?:^|\\n)\\s*-?\\s*(\\d+|[A-Z])[\\.\\)、]\\s*\\S*\\s*\\*\\*(.+?)\\*\\*" + // - A. emoji **标题**
            "|" +
            "-\\s*\\*\\*(\\d+|[A-Z])[\\.\\)、]\\*\\*\\s*(.+)" +        // - **A.** 标题
            ")",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /**
     * 构建一条或多条卡片 JSON（长文本自动拆分）。
     * 返回的列表中，只有最后一条可能包含按钮。
     */
    public static List<String> buildCards(String text, List<Option> options) {
        List<String> cards = new ArrayList<>();
        int maxPerCard = 8000; // 安全限制（飞书卡片 30KB ≈ 10000 中文字）

        if (text.length() <= maxPerCard) {
            // 不需要拆分
            if (options != null && !options.isEmpty()) {
                cards.add(buildOptionsCard(text, options));
            } else {
                cards.add(buildTextCard(text));
            }
            return cards;
        }

        // 需要拆分：前面的卡片只包含文本，最后一条卡片包含剩余文本 + 按钮
        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(offset + maxPerCard, text.length());
            // 在段落边界分割
            if (end < text.length()) {
                int newline = text.lastIndexOf("\n\n", end);
                if (newline > offset + maxPerCard / 2) {
                    end = newline;
                }
            }
            String chunk = text.substring(offset, end);
            boolean isLast = (end >= text.length());

            if (isLast && options != null && !options.isEmpty()) {
                cards.add(buildOptionsCard(chunk, options));
            } else {
                cards.add(buildTextCard(chunk));
            }
            offset = end;
        }
        return cards;
    }

    /**
     * 检测回复中是否包含可交互选项
     */
    public static boolean hasOptions(String text) {
        Matcher m = OPTION_PATTERN.matcher(text);
        int count = 0;
        while (m.find()) count++;
        return count >= 2; // 至少 2 个选项才生成卡片
    }

    /**
     * 从文本中提取选项列表
     */
    public static List<Option> extractOptions(String text) {
        List<Option> options = new ArrayList<>();
        Matcher m = OPTION_PATTERN.matcher(text);
        while (m.find()) {
            String number = null;
            String title = null;

            // 检查哪组匹配成功
            if (m.group(1) != null) {
                number = m.group(1);
                title = m.group(2);
            } else if (m.group(3) != null) {
                number = m.group(3);
                title = m.group(4);
            } else if (m.group(5) != null) {
                number = m.group(5);
                title = m.group(6);
            }

            if (number == null || title == null) continue;
            title = title.trim();
            // 去掉开头的 emoji
            title = title.replaceAll("^[\\x{1F300}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{FE00}-\\x{FE0F}\\x{1F900}-\\x{1F9FF}\\x{200D}\\x{2702}-\\x{27B0}]\\s*", "");
            // 去掉末尾的 " — xxx" 或 " – xxx" 描述
            title = title.replaceAll("\\s*[—–\\-]\\s*.+$", "");
            // 去掉末尾的 ✅ 等标记
            title = title.replaceAll("\\s*[✅✔☑]\\s*$", "");
            title = title.trim();

            if (!title.isEmpty()) {
                options.add(new Option(number.toUpperCase(), title));
            }
        }
        return options;
    }

    /**
     * 生成飞书交互式卡片 JSON（带按钮选项）
     */
    public static String buildOptionsCard(String text, List<Option> options) {
        JsonObject card = new JsonObject();

        // 卡片头部
        JsonObject header = new JsonObject();
        JsonObject title = new JsonObject();
        title.addProperty("content", "🤖 Claude 需要你选择");
        title.addProperty("tag", "plain_text");
        header.add("title", title);
        header.addProperty("template", "blue");
        card.add("header", header);

        // 卡片内容
        JsonArray elements = new JsonArray();

        // 完整文本（拆分成多个 markdown 元素，每个不超过 3500 字）
        addTextElements(elements, text, 3500);

        // 分隔线
        JsonObject hr = new JsonObject();
        hr.addProperty("tag", "hr");
        elements.add(hr);

        // 按钮组
        JsonObject action = new JsonObject();
        action.addProperty("tag", "action");
        // 不设 layout → 按钮默认一行一个
        JsonArray actions = new JsonArray();

        for (Option opt : options) {
            JsonObject button = new JsonObject();
            button.addProperty("tag", "button");

            JsonObject btnText = new JsonObject();
            btnText.addProperty("tag", "plain_text");
            String label = opt.number + ". " + opt.title;
            if (label.length() > 20) label = label.substring(0, 18) + "..";
            btnText.addProperty("content", label);
            button.add("text", btnText);

            button.addProperty("type", "primary");

            // 点击按钮后发送的值
            JsonObject value = new JsonObject();
            value.addProperty("choice", opt.number);
            button.add("value", value);

            actions.add(button);
        }

        action.add("actions", actions);
        elements.add(action);

        card.add("elements", elements);
        return card.toString();
    }

    /**
     * 将文本拆分成多个 markdown 元素（飞书单个 markdown 元素有长度限制）
     */
    private static void addTextElements(JsonArray elements, String text, int maxPerElement) {
        if (text == null || text.isEmpty()) return;

        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(offset + maxPerElement, text.length());
            // 尝试在段落边界分割，避免切断文字
            if (end < text.length()) {
                int newline = text.lastIndexOf("\n\n", end);
                if (newline > offset + maxPerElement / 2) {
                    end = newline;
                }
            }
            String chunk = text.substring(offset, end);
            JsonObject markdown = new JsonObject();
            markdown.addProperty("tag", "markdown");
            markdown.addProperty("content", chunk);
            elements.add(markdown);
            offset = end;
        }
    }

    /**
     * 生成普通回复卡片
     */
    public static String buildTextCard(String text) {
        JsonObject card = new JsonObject();

        JsonObject header = new JsonObject();
        JsonObject title = new JsonObject();
        title.addProperty("content", "🤖 Claude");
        title.addProperty("tag", "plain_text");
        header.add("title", title);
        header.addProperty("template", "green");
        card.add("header", header);

        JsonArray elements = new JsonArray();
        addTextElements(elements, text, 3500);

        card.add("elements", elements);
        return card.toString();
    }

    private static String extractIntro(String text) {
        // 找到第一个选项之前的文本
        int firstOption = text.length();
        Matcher m = OPTION_PATTERN.matcher(text);
        if (m.find()) {
            firstOption = m.start();
        }
        String intro = text.substring(0, firstOption).trim();
        // 去掉分隔线
        intro = intro.replaceAll("---+", "").trim();
        return intro;
    }

    public static class Option {
        public final String number;
        public final String title;

        Option(String number, String title) {
            this.number = number;
            this.title = title;
        }
    }

    // =======================================================================
    // CardKit 流式卡片（对照 openclaw-lark src/card/builder.ts）
    // =======================================================================

    /** CardKit 流式正文元素 ID — cardElement.content API 的定位目标 */
    public static final String STREAMING_ELEMENT_ID = "streaming_content";
    /** 终态卡正文最大字符数（飞书卡片 30KB 限制的安全值） */
    private static final int MAX_FINAL_TEXT = 20000;
    /** 流式期间思考内容的显示上限（保留尾部） */
    private static final int MAX_STREAM_THINKING = 3000;
    /** 终态卡思考折叠区显示上限 */
    private static final int MAX_FINAL_THINKING = 10000;

    /**
     * 初始流式卡片（CardKit card.create 用）。
     * schema 2.0 + streaming_mode:true + 工具面板占位 + 空的 streaming_content 元素。
     */
    public static String buildStreamingInitialCard() {
        JsonArray elements = new JsonArray();
        elements.add(buildToolPanel(new ArrayList<>(), false, 0));
        elements.add(streamingMarkdownElement(""));

        JsonObject card = new JsonObject();
        card.addProperty("schema", "2.0");
        card.add("config", streamingConfig(true));
        JsonObject body = new JsonObject();
        body.add("elements", elements);
        card.add("body", body);
        return card.toString();
    }

    /**
     * 流式期间的整卡 JSON — 工具面板更新（card.update）和 patch 降级模式共用。
     * 正文始终写入 streaming_content 元素（携带当前累积全文，防止整卡替换时内容回退）。
     */
    public static String buildStreamingCardJson(String answerText, String thinkingText,
                                                List<ToolStep> steps, long elapsedMs, boolean streamingMode) {
        JsonArray elements = new JsonArray();
        elements.add(buildToolPanel(steps, true, elapsedMs));

        if (answerText != null && !answerText.isEmpty()) {
            elements.add(streamingMarkdownElement(answerText));
        } else if (thinkingText != null && !thinkingText.isEmpty()) {
            JsonObject md = streamingMarkdownElement(
                    "💭 **思考中...**\n\n" + truncateTail(thinkingText, MAX_STREAM_THINKING));
            md.addProperty("text_size", "notation");
            elements.add(md);
        } else {
            elements.add(streamingMarkdownElement(""));
        }

        JsonObject card = new JsonObject();
        card.addProperty("schema", "2.0");
        card.add("config", streamingConfig(streamingMode));
        JsonObject body = new JsonObject();
        body.add("elements", elements);
        card.add("body", body);
        return card.toString();
    }

    /**
     * 终态卡片 JSON — result 事件后 card.update / patch 的最终内容。
     * 布局对照 openclaw-lark buildCompleteCard：
     * 工具面板(折叠) → 思考折叠面板 → 正文 → 选项按钮(如有) → footer(状态/耗时/模型 + tokens/缓存/费用)。
     */
    public static String buildFinalCardJson(TurnResult r, List<Option> options) {
        JsonArray elements = new JsonArray();

        // 1. 工具面板（折叠，标题带总耗时）
        if (r.toolSteps != null && !r.toolSteps.isEmpty()) {
            elements.add(buildToolPanel(r.toolSteps, false, r.toolElapsedMs));
        }

        // 2. 思考折叠面板
        if (r.thinkingText != null && !r.thinkingText.isBlank()) {
            elements.add(buildThinkingPanel(r.thinkingText, r.thinkingMs));
        }

        // 3. 正文（超长按段落边界拆多个 markdown 元素）
        String text = r.text != null ? r.text : "";
        if (text.length() > MAX_FINAL_TEXT) {
            text = text.substring(0, MAX_FINAL_TEXT) + "\n\n... (内容过长已截断，共 " + r.text.length() + " 字符)";
        }
        addTextElements(elements, text, 3500);

        // 4. 选项按钮（保留交互能力，回调仍走 onP2CardActionTrigger）
        if (options != null && !options.isEmpty()) {
            JsonObject hr = new JsonObject();
            hr.addProperty("tag", "hr");
            elements.add(hr);
            elements.add(buildOptionButtons(options));
        }

        // 5. footer
        String footer = buildFooterText(r);
        if (!footer.isEmpty()) {
            JsonObject md = new JsonObject();
            md.addProperty("tag", "markdown");
            md.addProperty("content", footer);
            md.addProperty("text_size", "notation");
            elements.add(md);
        }

        JsonObject card = new JsonObject();
        card.addProperty("schema", "2.0");

        JsonObject config = new JsonObject();
        config.addProperty("wide_screen_mode", true);
        config.addProperty("update_multi", true);
        JsonArray locales = new JsonArray();
        locales.add("zh_cn");
        locales.add("en_us");
        config.add("locales", locales);
        // feed 流预览摘要：去掉 markdown 语法取前 120 字
        String summaryText = text.replaceAll("[*_`#>\\[\\]()~]", "").trim();
        if (!summaryText.isEmpty()) {
            JsonObject summary = new JsonObject();
            summary.addProperty("content", summaryText.substring(0, Math.min(120, summaryText.length())));
            config.add("summary", summary);
        }
        card.add("config", config);

        JsonObject body = new JsonObject();
        body.add("elements", elements);
        card.add("body", body);
        return card.toString();
    }

    // ----- 内部构件 -----

    private static JsonObject streamingConfig(boolean streamingMode) {
        JsonObject config = new JsonObject();
        config.addProperty("streaming_mode", streamingMode);
        JsonArray locales = new JsonArray();
        locales.add("zh_cn");
        locales.add("en_us");
        config.add("locales", locales);
        JsonObject summary = new JsonObject();
        summary.addProperty("content", "处理中...");
        JsonObject i18n = new JsonObject();
        i18n.addProperty("zh_cn", "处理中...");
        i18n.addProperty("en_us", "Processing...");
        summary.add("i18n_content", i18n);
        config.add("summary", summary);
        return config;
    }

    private static JsonObject streamingMarkdownElement(String content) {
        JsonObject md = new JsonObject();
        md.addProperty("tag", "markdown");
        md.addProperty("content", content);
        md.addProperty("text_align", "left");
        md.addProperty("text_size", "normal_v2");
        md.addProperty("margin", "0px 0px 0px 0px");
        md.addProperty("element_id", STREAMING_ELEMENT_ID);
        return md;
    }

    /**
     * 工具面板（collapsible_panel）。
     * steps 为空 → 折叠的"等待工具执行"占位面板；否则每步渲染 标题+状态色标+detail。
     */
    private static JsonObject buildToolPanel(List<ToolStep> steps, boolean expanded, long elapsedMs) {
        boolean hasSteps = steps != null && !steps.isEmpty();

        StringBuilder zh = new StringBuilder("🛠️ ");
        if (!hasSteps) {
            zh.append("等待工具执行");
        } else {
            zh.append("工具执行 · ").append(steps.size()).append(" 步");
            if (elapsedMs > 0) {
                zh.append(" · (").append(formatElapsed(elapsedMs)).append(")");
            }
        }

        JsonObject title = new JsonObject();
        title.addProperty("tag", "plain_text");
        title.addProperty("content", zh.toString());
        title.addProperty("text_color", "grey");
        title.addProperty("text_size", "notation");

        JsonObject headerIcon = new JsonObject();
        headerIcon.addProperty("tag", "standard_icon");
        headerIcon.addProperty("token", "down-small-ccm_outlined");
        headerIcon.addProperty("color", "grey");
        headerIcon.addProperty("size", "16px 16px");

        JsonObject header = new JsonObject();
        header.add("title", title);
        header.addProperty("vertical_align", "center");
        header.add("icon", headerIcon);
        header.addProperty("icon_position", "right");
        header.addProperty("icon_expanded_angle", -180);

        JsonObject border = new JsonObject();
        border.addProperty("color", "grey");
        border.addProperty("corner_radius", "5px");

        JsonArray panelElements = new JsonArray();
        if (hasSteps) {
            for (ToolStep step : steps) {
                panelElements.add(buildToolStepTitle(step));
                if (step.detail != null && !step.detail.isBlank()) {
                    JsonObject detailDiv = new JsonObject();
                    detailDiv.addProperty("tag", "div");
                    detailDiv.addProperty("margin", "0px 0px 0px 22px");
                    JsonObject dt = new JsonObject();
                    dt.addProperty("tag", "plain_text");
                    dt.addProperty("content", truncateTail(step.detail.trim(), 120));
                    dt.addProperty("text_color", "grey");
                    dt.addProperty("text_size", "notation");
                    detailDiv.add("text", dt);
                    panelElements.add(detailDiv);
                }
            }
        }

        JsonObject panel = new JsonObject();
        panel.addProperty("tag", "collapsible_panel");
        panel.addProperty("expanded", expanded && hasSteps);
        panel.add("header", header);
        panel.add("border", border);
        panel.addProperty("vertical_spacing", "4px");
        panel.addProperty("padding", "8px 8px 8px 8px");
        panel.add("elements", panelElements);
        return panel;
    }

    private static JsonObject buildToolStepTitle(ToolStep step) {
        String[] desc = toolDescriptor(step.name);
        String[] status = stepStatusDisplay(step.status);

        JsonObject icon = new JsonObject();
        icon.addProperty("tag", "standard_icon");
        icon.addProperty("token", desc[0]);
        icon.addProperty("color", "grey");

        JsonObject text = new JsonObject();
        text.addProperty("tag", "lark_md");
        String md = "**" + escapeMarkdown(desc[1]) + "** · <font color='" + status[1] + "'>" + status[0] + "</font>";
        if (step.status != ToolStep.Status.RUNNING && step.elapsedMs() > 0) {
            md += " · <font color='grey'>" + formatElapsed(step.elapsedMs()) + "</font>";
        }
        text.addProperty("content", md);
        text.addProperty("text_size", "notation");

        JsonObject div = new JsonObject();
        div.addProperty("tag", "div");
        div.add("icon", icon);
        div.add("text", text);
        return div;
    }

    private static JsonObject buildThinkingPanel(String thinkingText, long thinkingMs) {
        String label = thinkingMs > 0 ? "💭 思考了 " + formatElapsed(thinkingMs) : "💭 思考";

        JsonObject title = new JsonObject();
        title.addProperty("tag", "plain_text");
        title.addProperty("content", label);

        JsonObject headerIcon = new JsonObject();
        headerIcon.addProperty("tag", "standard_icon");
        headerIcon.addProperty("token", "down-small-ccm_outlined");
        headerIcon.addProperty("size", "16px 16px");

        JsonObject header = new JsonObject();
        header.add("title", title);
        header.addProperty("vertical_align", "center");
        header.add("icon", headerIcon);
        header.addProperty("icon_position", "follow_text");
        header.addProperty("icon_expanded_angle", -180);

        JsonObject content = new JsonObject();
        content.addProperty("tag", "markdown");
        String t = thinkingText.length() > MAX_FINAL_THINKING
                ? thinkingText.substring(0, MAX_FINAL_THINKING) + "\n\n... (思考内容过长已截断)"
                : thinkingText;
        content.addProperty("content", t);
        content.addProperty("text_size", "notation");

        JsonArray panelElements = new JsonArray();
        panelElements.add(content);

        JsonObject border = new JsonObject();
        border.addProperty("color", "grey");
        border.addProperty("corner_radius", "5px");

        JsonObject panel = new JsonObject();
        panel.addProperty("tag", "collapsible_panel");
        panel.addProperty("expanded", false);
        panel.add("header", header);
        panel.add("border", border);
        panel.addProperty("vertical_spacing", "8px");
        panel.addProperty("padding", "8px 8px 8px 8px");
        panel.add("elements", panelElements);
        return panel;
    }

    private static JsonObject buildOptionButtons(List<Option> options) {
        JsonObject action = new JsonObject();
        action.addProperty("tag", "action");
        JsonArray actions = new JsonArray();
        for (Option opt : options) {
            JsonObject button = new JsonObject();
            button.addProperty("tag", "button");
            JsonObject btnText = new JsonObject();
            btnText.addProperty("tag", "plain_text");
            String label = opt.number + ". " + opt.title;
            if (label.length() > 20) label = label.substring(0, 18) + "..";
            btnText.addProperty("content", label);
            button.add("text", btnText);
            button.addProperty("type", "primary");
            JsonObject value = new JsonObject();
            value.addProperty("choice", opt.number);
            button.add("value", value);
            actions.add(button);
        }
        action.add("actions", actions);
        return action;
    }

    /** footer 两行：状态·耗时·模型 / tokens·缓存·费用（对照 openclaw-lark formatFooterRuntimeSegments） */
    private static String buildFooterText(TurnResult r) {
        List<String> primary = new ArrayList<>();
        if (r.isError) {
            primary.add("<font color='red'>出错</font>");
        } else if (r.aborted) {
            primary.add("<font color='red'>已停止</font>");
        } else {
            primary.add("已完成");
        }
        if (r.durationMs > 0) primary.add("耗时 " + formatElapsed(r.durationMs));
        if (r.model != null && !r.model.isBlank()) primary.add(r.model.trim());

        List<String> detail = new ArrayList<>();
        if (r.inputTokens >= 0 && r.outputTokens >= 0) {
            detail.add("↑ " + compactNumber(r.inputTokens) + " ↓ " + compactNumber(r.outputTokens));
        }
        if (r.cacheReadTokens >= 0 && r.cacheWriteTokens >= 0 && r.inputTokens >= 0) {
            long total = r.cacheReadTokens + r.cacheWriteTokens + r.inputTokens;
            long hit = total > 0 ? Math.round(r.cacheReadTokens * 100.0 / total) : 0;
            detail.add("缓存 " + compactNumber(r.cacheReadTokens) + "/" + compactNumber(r.cacheWriteTokens) + " (" + hit + "%)");
        }
        if (r.costUsd >= 0) {
            detail.add(String.format("$%.4f", r.costUsd));
        }

        StringBuilder sb = new StringBuilder();
        sb.append(String.join(" · ", primary));
        if (!detail.isEmpty()) sb.append("\n").append(String.join(" · ", detail));
        return sb.toString();
    }

    /** 毫秒 → 人类可读时长（<60s 一位小数，否则 Xm Ys） */
    public static String formatElapsed(long ms) {
        double seconds = ms / 1000.0;
        if (seconds < 60) return String.format("%.1fs", seconds);
        return (long) Math.floor(seconds / 60) + "m " + Math.round(seconds % 60) + "s";
    }

    /** 数字压缩显示：1234 → 1.2k，1234567 → 1.2m */
    public static String compactNumber(long value) {
        long abs = Math.abs(value);
        if (abs >= 1_000_000) {
            double m = value / 1_000_000.0;
            return (Math.abs(m) >= 100 ? String.valueOf(Math.round(m)) : String.format("%.1f", m)) + "m";
        }
        if (abs >= 1_000) {
            double k = value / 1_000.0;
            return (Math.abs(k) >= 100 ? String.valueOf(Math.round(k)) : String.format("%.1f", k)) + "k";
        }
        return String.valueOf(value);
    }

    /** 工具名 → [iconToken, 中文标题]（对照 openclaw-lark TOOL_DESCRIPTORS） */
    private static String[] toolDescriptor(String toolName) {
        String n = toolName == null ? "" : toolName.toLowerCase();
        if (n.equals("read")) return new String[]{"file-link-text_outlined", "读取文件"};
        if (n.equals("edit") || n.equals("write") || n.equals("multiedit") || n.equals("notebookedit"))
            return new String[]{"edit_outlined", "编辑文件"};
        if (n.equals("bash") || n.equals("exec") || n.equals("command"))
            return new String[]{"setting_outlined", "执行命令"};
        if (n.equals("grep")) return new String[]{"doc-search_outlined", "搜索文本"};
        if (n.equals("glob")) return new String[]{"folder_outlined", "搜索文件"};
        if (n.equals("websearch") || n.equals("web_search")) return new String[]{"search_outlined", "搜索网页"};
        if (n.equals("webfetch") || n.equals("web_fetch")) return new String[]{"language_outlined", "抓取网页"};
        if (n.equals("task") || n.equals("agent")) return new String[]{"robot_outlined", "子 Agent"};
        if (n.equals("todowrite")) return new String[]{"list-check_outlined", "更新任务清单"};
        return new String[]{"app-default_outlined", toolName == null || toolName.isBlank() ? "工具" : toolName};
    }

    /** 步骤状态 → [中文标签, 飞书色标] */
    private static String[] stepStatusDisplay(ToolStep.Status status) {
        return switch (status) {
            case RUNNING -> new String[]{"执行中", "turquoise"};
            case ERROR -> new String[]{"失败", "red"};
            case SUCCESS -> new String[]{"成功", "green"};
        };
    }

    private static String escapeMarkdown(String s) {
        return s.replace("\\", "\\\\").replaceAll("([`*_{}\\[\\]<>])", "\\\\$1");
    }

    /** 截断保留尾部（流式思考内容显示用），前面加省略号 */
    private static String truncateTail(String s, int max) {
        if (s == null) return "";
        if (s.length() <= max) return s;
        return "..." + s.substring(s.length() - max);
    }
}
