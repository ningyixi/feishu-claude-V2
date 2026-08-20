package com.lark.oapi.sample.ws;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Claude API 服务
 * <p>
 * 支持：
 * - 多轮对话（按用户 openId 维护历史）
 * - 流式响应（SSE）
 * - 对话历史滑动窗口（保留最近 MAX_HISTORY 条消息）
 */
public class ClaudeService {

    private static final Logger log = LoggerFactory.getLogger(ClaudeService.class);

    private static final int MAX_HISTORY = 20;  // 每个用户最多保留 20 条对话记录
    private static final int MAX_TOKENS = 4096;
    private static final MediaType JSON_TYPE = MediaType.parse("application/json");

    private final String apiKey;
    private final String model;
    private final String baseUrl;
    private final OkHttpClient httpClient;

    /** 用户对话历史: openId -> 消息列表 */
    private final Map<String, List<JsonObject>> conversationHistory = new ConcurrentHashMap<>();

    public ClaudeService(String apiKey, String model, String baseUrl) {
        this.apiKey = apiKey;
        this.model = model;
        this.baseUrl = baseUrl;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)   // Claude 响应可能较慢
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    // ==================== 核心对话方法 ====================

    /**
     * 与 Claude 进行流式对话（推荐）
     * 返回完整的回复文本
     *
     * @param openId 飞书用户的 openId（用于维护对话历史）
     * @param userMessage 用户发送的文本
     * @return Claude 的完整回复
     */
    public String chat(String openId, String userMessage) {
        return streamChat(openId, userMessage);
    }

    /**
     * 流式调用 Claude API，逐步收集回复
     */
    private String streamChat(String openId, String userMessage) {
        // 构建消息数组（历史 + 当前用户消息）
        JsonArray messages = buildMessages(openId, userMessage);

        // 构建请求体（开启 stream）
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("max_tokens", MAX_TOKENS);
        body.addProperty("stream", true);
        body.addProperty("system", buildSystemPrompt());
        body.add("messages", messages);

        Request request = new Request.Builder()
                .url(baseUrl + "/v1/messages")
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .post(RequestBody.create(body.toString(), JSON_TYPE))
                .build();

        StringBuilder fullResponse = new StringBuilder();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String errorBody = response.body() != null ? response.body().string() : "no body";
                log.error("[Claude] API 错误: code={}, body={}", response.code(), errorBody);
                return "调用 Claude 失败 (HTTP " + response.code() + ")，请稍后重试。";
            }

            // 解析 SSE 流
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body().byteStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) {
                        continue;
                    }
                    String data = line.substring(6).trim();
                    if (data.equals("[DONE]")) {
                        break;
                    }

                    try {
                        JsonObject event = com.google.gson.JsonParser.parseString(data).getAsJsonObject();
                        String type = event.get("type").getAsString();

                        if ("content_block_delta".equals(type)) {
                            JsonObject delta = event.getAsJsonObject("delta");
                            if (delta != null && delta.has("text")) {
                                String chunk = delta.get("text").getAsString();
                                fullResponse.append(chunk);
                            }
                        } else if ("message_stop".equals(type)) {
                            break;
                        } else if ("error".equals(type)) {
                            JsonObject error = event.getAsJsonObject("error");
                            String errorMsg = error != null ? error.get("message").getAsString() : "unknown";
                            log.error("[Claude] 流式响应错误: {}", errorMsg);
                            break;
                        }
                    } catch (Exception parseEx) {
                        // 忽略解析异常（可能是 event/ping 等非 data 行）
                        log.trace("[Claude] 忽略 SSE 行: {}", data);
                    }
                }
            }

        } catch (IOException e) {
            log.error("[Claude] 请求异常: openId={}", openId, e);
            return "请求 Claude 时网络异常: " + e.getMessage();
        }

        String result = fullResponse.toString();
        if (result.isBlank()) {
            result = "Claude 返回了空回复，请重试。";
        }

        // 将本轮对话加入历史
        addToHistory(openId, userMessage, result);

        return result;
    }

    // ==================== 对话历史管理 ====================

    /**
     * 构建发送给 Claude 的消息数组
     */
    private JsonArray buildMessages(String openId, String userMessage) {
        JsonArray messages = new JsonArray();

        // 添加历史记录
        List<JsonObject> history = conversationHistory.getOrDefault(openId, new ArrayList<>());
        for (JsonObject msg : history) {
            messages.add(msg);
        }

        // 添加当前用户消息
        JsonObject userMsg = new JsonObject();
        userMsg.addProperty("role", "user");
        userMsg.addProperty("content", userMessage);
        messages.add(userMsg);

        return messages;
    }

    /**
     * 将一轮对话加入历史（滑动窗口）
     */
    private void addToHistory(String openId, String userMsg, String assistantMsg) {
        conversationHistory.compute(openId, (key, history) -> {
            if (history == null) {
                history = new ArrayList<>();
            }

            // 添加用户消息
            JsonObject user = new JsonObject();
            user.addProperty("role", "user");
            user.addProperty("content", userMsg);
            history.add(user);

            // 添加助手回复
            JsonObject assistant = new JsonObject();
            assistant.addProperty("role", "assistant");
            assistant.addProperty("content", assistantMsg);
            history.add(assistant);

            // 滑动窗口：保留最近 MAX_HISTORY 条
            while (history.size() > MAX_HISTORY) {
                history.remove(0);
            }

            return history;
        });
    }

    /**
     * 清除指定用户的对话历史
     */
    public void clearHistory(String openId) {
        conversationHistory.remove(openId);
        log.info("[Claude] 对话历史已清除: openId={}", openId);
    }

    // ==================== 系统提示词 ====================

    private String buildSystemPrompt() {
        return """
                你是一个智能助手，通过飞书即时通讯与用户对话。

                回复要求：
                - 使用中文回复（除非用户明确要求使用其他语言）
                - 回复要简洁、清晰、有条理
                - 适当使用 emoji 让回复更友好
                - 对于代码相关问题，使用 markdown 代码块格式化
                - 如果不确定用户意图，先确认再回答

                当前时间: %s
                """.formatted(Instant.now().toString());
    }
}
