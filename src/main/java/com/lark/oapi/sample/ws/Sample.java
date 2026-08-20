package com.lark.oapi.sample.ws;

import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.google.gson.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 飞书机器人 + 本地 Shell 终端
 * <p>
 * 通过飞书 WebSocket 长连接接收消息，在本地终端执行命令并返回结果。
 * 支持会话状态持久化（cd 等状态保留）。
 */
public class Sample {

    private static final Logger log = LoggerFactory.getLogger(Sample.class);

    // ========== 配置 ==========
    private static final String APP_ID = "cli_aa0f06b66478dbd1";
    private static final String APP_SECRET = "***REMOVED***";

    // ========== 授权用户 ==========
    private static final String AUTHORIZED_OPEN_ID = "ou_***REMOVED***";

    // ========== Shell 服务 ==========
    private static final ShellService shellService = new ShellService();

    // ========== 飞书 HTTP 客户端（用于发送消息） ==========
    private static final Client feishuHttpClient = Client.newBuilder(APP_ID, APP_SECRET).build();

    // ========== 消息事件处理器 ==========
    private static final EventDispatcher EVENT_HANDLER = EventDispatcher.newBuilder("", "")
            .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                @Override
                public void handle(P2MessageReceiveV1 event) throws Exception {
                    var eventData = event.getEvent();
                    String openId = eventData.getSender().getSenderId().getOpenId();
                    String messageId = eventData.getMessage().getMessageId();
                    String msgType = eventData.getMessage().getMessageType();
                    String chatType = eventData.getMessage().getChatType();
                    String rawContent = eventData.getMessage().getContent();

                    log.info("[Feishu] 收到消息: openId={}, msgType={}, chatType={}, content={}",
                            openId, msgType, chatType, rawContent);

                    // 用虚拟线程异步处理，不阻塞事件回调
                    Thread.ofVirtual().name("msg-" + messageId).start(() -> {
                        try {
                            handleMessage(openId, messageId, msgType, rawContent);
                        } catch (Exception e) {
                            log.error("[Handler] 处理消息异常: openId={}", openId, e);
                            replyText(openId, "处理消息时出错了: " + e.getMessage());
                        }
                    });
                }
            })
            .build();

    public static void main(String[] args) {
        log.info("========================================");
        log.info("  飞书 Shell Bot 启动中...");
        log.info("========================================");

        // JVM 退出时终止 Shell 进程
        Runtime.getRuntime().addShutdownHook(new Thread(shellService::destroy, "shell-shutdown"));

        // 建立 WebSocket 长连接
        com.lark.oapi.ws.Client cli = new com.lark.oapi.ws.Client.Builder(APP_ID, APP_SECRET)
                .eventHandler(EVENT_HANDLER)
                .build();
        cli.start();

        log.info("飞书 WebSocket 连接已建立，等待消息中...");

        // 保持主线程存活
        try {
            Thread.currentThread().join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("服务已停止");
        }
    }

    // ==================== 消息处理 ====================

    /**
     * 处理接收到的消息
     */
    private static void handleMessage(String openId, String messageId, String msgType, String rawContent) {
        // 只处理文本消息
        if (!"text".equals(msgType)) {
            replyText(openId, "暂时只支持文本消息，请直接发送命令。");
            return;
        }

        // 解析文本内容
        String text = extractText(rawContent);
        if (text == null || text.isBlank()) {
            replyText(openId, "消息内容为空，请重新发送。");
            return;
        }

        // 去掉群聊中 @机器人 的前缀（如 @_user_1）
        text = text.replaceAll("@_user_\\d+\\s*", "").trim();
        if (text.isEmpty()) {
            replyText(openId, "请输入要执行的命令。");
            return;
        }

        // 权限控制：仅授权用户可用
        if (!AUTHORIZED_OPEN_ID.equals(openId)) {
            replyText(openId, "⛔ 无权访问此机器人。");
            log.warn("[Access] 未授权用户尝试访问: openId={}", openId);
            return;
        }

        // 处理 / 命令
        if (text.startsWith("/")) {
            if (handleCommand(openId, text)) {
                return;
            }
        }

        // 执行 Shell 命令
        log.info("[Shell] 执行: openId={}, cmd={}", openId, text);
        String output = shellService.execute(text);
        log.info("[Shell] 输出长度: {}", output.length());
        replyText(openId, output);
    }

    /**
     * 处理 / 命令
     * @return true 表示已处理
     */
    private static boolean handleCommand(String openId, String text) {
        String cmd = text.trim().toLowerCase();
        if ("/help".equals(cmd)) {
            replyText(openId, """
                    🖥️ Shell Bot 使用指南

                    直接发送命令即可在本地终端执行，会话保持持久连接。
                    cd / set 等命令的状态会保留。

                    命令：
                    /help - 显示帮助信息

                    示例：
                    whoami    查看当前用户
                    dir       列出目录
                    cd C:\\   切换目录
                    """);
            return true;
        }
        return false;
    }

    // ==================== 飞书消息发送 ====================

    /**
     * 发送文本消息回复
     */
    private static void replyText(String openId, String content) {
        // 转义 JSON 特殊字符
        String escapedContent = content.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        String jsonContent = "{\"text\":\"" + escapedContent + "\"}";

        CreateMessageReq req = CreateMessageReq.newBuilder()
                .receiveIdType("open_id")
                .createMessageReqBody(CreateMessageReqBody.newBuilder()
                        .receiveId(openId)
                        .uuid(UUID.randomUUID().toString())
                        .msgType("text")
                        .content(jsonContent)
                        .build())
                .build();

        try {
            var resp = feishuHttpClient.im().message().create(req);
            if (resp.success()) {
                log.debug("[Feishu] 回复成功: openId={}", openId);
            } else {
                log.error("[Feishu] 回复失败: code={}, msg={}", resp.getCode(), resp.getMsg());
            }
        } catch (Exception e) {
            log.error("[Feishu] 发送消息异常: openId={}", openId, e);
        }
    }

    // ==================== 工具方法 ====================

    /**
     * 从飞书消息 JSON 中提取文本内容
     * 飞书 text 消息格式: {"text":"实际内容"}
     */
    private static String extractText(String rawContent) {
        try {
            JsonObject json = Jsons.DEFAULT.fromJson(rawContent, JsonObject.class);
            if (json.has("text")) {
                return json.get("text").getAsString();
            }
            return null;
        } catch (Exception e) {
            log.warn("[Parse] 解析消息内容失败: {}", rawContent, e);
            return rawContent; // 降级：直接返回原始内容
        }
    }

}
