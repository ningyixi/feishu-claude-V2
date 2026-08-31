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

        // 主文本（截取选项之前的说明文字）
        String intro = extractIntro(text);
        if (!intro.isEmpty()) {
            JsonObject markdown = new JsonObject();
            markdown.addProperty("tag", "markdown");
            if (intro.length() > 500) intro = intro.substring(0, 500) + "...";
            markdown.addProperty("content", intro);
            elements.add(markdown);
        }

        // 分隔线
        JsonObject hr = new JsonObject();
        hr.addProperty("tag", "hr");
        elements.add(hr);

        // 按钮组
        JsonObject action = new JsonObject();
        action.addProperty("tag", "action");
        action.addProperty("layout", "bisected"); // 按钮两列排列
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
        JsonObject markdown = new JsonObject();
        markdown.addProperty("tag", "markdown");
        if (text.length() > 4000) text = text.substring(0, 4000) + "\n\n... (内容已截断)";
        markdown.addProperty("content", text);
        elements.add(markdown);

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
}
