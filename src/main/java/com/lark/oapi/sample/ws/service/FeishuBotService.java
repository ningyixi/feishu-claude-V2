package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.dto.TestResultDTO;
import com.lark.oapi.sample.ws.model.entity.AppConfig;
import com.lark.oapi.sample.ws.repository.AppConfigRepository;
import com.google.gson.JsonObject;
import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FeishuBotService {

    private static final Logger log = LoggerFactory.getLogger(FeishuBotService.class);

    private final AppConfigRepository configRepo;
    private final ShellService shellService;
    private final ClaudeCodeService claudeCodeService;

    enum Mode { SHELL, CHAT }
    private final Map<String, Mode> userModes = new ConcurrentHashMap<>();

    private com.lark.oapi.ws.Client wsClient;
    private Client httpClient;

    public FeishuBotService(AppConfigRepository configRepo,
                            ShellService shellService,
                            ClaudeCodeService claudeCodeService) {
        this.configRepo = configRepo;
        this.shellService = shellService;
        this.claudeCodeService = claudeCodeService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        start();
    }

    @PreDestroy
    public void onDestroy() {
        stop();
        shellService.destroy();
    }

    public void start() {
        AppConfig config = getConfig();
        if (config == null || config.getFeishuAppId().isBlank()) {
            log.warn("飞书凭证未配置，请在管理面板配置后重试");
            return;
        }

        String appId = config.getFeishuAppId();
        String appSecret = config.getFeishuAppSecret();

        httpClient = Client.newBuilder(appId, appSecret).build();

        EventDispatcher handler = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) throws Exception {
                        var eventData = event.getEvent();
                        String openId = eventData.getSender().getSenderId().getOpenId();
                        String messageId = eventData.getMessage().getMessageId();
                        String msgType = eventData.getMessage().getMessageType();
                        String rawContent = eventData.getMessage().getContent();

                        log.info("[Feishu] 收到消息: openId={}, msgType={}, content={}",
                                openId, msgType, rawContent);

                        Thread.ofVirtual().name("msg-" + messageId).start(() -> {
                            try {
                                handleMessage(openId, msgType, rawContent);
                            } catch (Exception e) {
                                log.error("[Handler] 处理消息异常: openId={}", openId, e);
                                replyText(openId, "处理消息时出错了: " + e.getMessage());
                            }
                        });
                    }
                })
                .build();

        wsClient = new com.lark.oapi.ws.Client.Builder(appId, appSecret)
                .eventHandler(handler)
                .build();
        try {
            wsClient.start();
            log.info("飞书 WebSocket 连接已建立");
        } catch (Exception e) {
            log.error("飞书 WebSocket 连接失败: {}，应用仍可正常运行，请在管理面板检查飞书凭证", e.getMessage());
            wsClient = null;
        }
    }

    public void stop() {
        if (wsClient != null) {
            // wsClient 没有 close 方法，GC 会处理
            wsClient = null;
            log.info("飞书 WebSocket 连接已断开");
        }
    }

    public void restart() {
        stop();
        start();
    }

    /**
     * 测试飞书连通性（实际 API 调用验证凭证）
     */
    public TestResultDTO testConnection(String appId, String appSecret) {
        try {
            Client testClient = Client.newBuilder(appId, appSecret).build();
            // 发起真实 API 调用验证凭证有效性
            testClient.im().message().list(com.lark.oapi.service.im.v1.model.ListMessageReq.newBuilder()
                    .containerIdType("chat")
                    .containerId("oc_test")
                    .pageSize(1)
                    .build());
            return TestResultDTO.ok("飞书连接成功", "APP ID: " + appId);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            // 凭证无效的典型错误码/关键词
            if (msg.contains("invalid") || msg.contains("99991663") || msg.contains("99991664")
                    || msg.contains("1000040346") || msg.contains("app_id") || msg.contains("app_secret")) {
                return TestResultDTO.fail("飞书凭证无效", msg);
            }
            // 其他错误（如业务权限不足等）说明凭证本身是对的
            return TestResultDTO.ok("飞书凭证有效（API 返回业务错误，但鉴权通过）", "APP ID: " + appId);
        }
    }

    // ========== 消息处理 ==========

    private AppConfig getConfig() {
        return configRepo.findById(1L).orElse(null);
    }

    private void handleMessage(String openId, String msgType, String rawContent) {
        if (!"text".equals(msgType)) {
            replyText(openId, "暂时只支持文本消息。");
            return;
        }

        String text = extractText(rawContent);
        if (text == null || text.isBlank()) {
            replyText(openId, "消息内容为空。");
            return;
        }

        text = text.replaceAll("@_user_\\d+\\s*", "").trim();
        if (text.isEmpty()) return;

        AppConfig config = getConfig();
        if (config != null && !config.getAuthorizedOpenId().isBlank()
                && !config.getAuthorizedOpenId().equals(openId)) {
            replyText(openId, "⛔ 无权访问此机器人。");
            return;
        }

        if (text.startsWith("/") && handleCommand(openId, text)) return;

        Mode mode = userModes.getOrDefault(openId, Mode.SHELL);
        if (mode == Mode.CHAT) {
            log.info("[Chat] 调用 Claude Code: openId={}", openId);
            String reply = claudeCodeService.chat(openId, text);
            replyText(openId, reply);
        } else {
            log.info("[Shell] 执行: openId={}, cmd={}", openId, text);
            String output = shellService.execute(text);
            replyText(openId, output);
        }
    }

    private boolean handleCommand(String openId, String text) {
        String cmd = text.trim().toLowerCase();
        return switch (cmd) {
            case "/shell" -> {
                userModes.put(openId, Mode.SHELL);
                replyText(openId, "🖥️ 已切换到 Shell 模式");
                yield true;
            }
            case "/chat" -> {
                userModes.put(openId, Mode.CHAT);
                replyText(openId, "🤖 已切换到 Claude 对话模式");
                yield true;
            }
            case "/new" -> {
                claudeCodeService.newSession(openId);
                replyText(openId, "✨ 已开启新 session");
                yield true;
            }
            case "/sessions" -> {
                replyText(openId, claudeCodeService.listSessions());
                yield true;
            }
            case "/reset", "/clear" -> {
                claudeCodeService.newSession(openId);
                replyText(openId, "Session 已重置 ✨");
                yield true;
            }
            case "/help" -> {
                Mode current = userModes.getOrDefault(openId, Mode.SHELL);
                String modeLabel = current == Mode.CHAT ? "🤖 Claude" : "🖥️ Shell";
                replyText(openId, "当前模式: " + modeLabel + "\n\n/shell /chat /new /sessions /help");
                yield true;
            }
            default -> false;
        };
    }

    private void replyText(String openId, String content) {
        if (httpClient == null) return;
        String escaped = content.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        String jsonContent = "{\"text\":\"" + escaped + "\"}";

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
            var resp = httpClient.im().message().create(req);
            if (!resp.success()) {
                log.error("[Feishu] 回复失败: code={}, msg={}", resp.getCode(), resp.getMsg());
            }
        } catch (Exception e) {
            log.error("[Feishu] 发送消息异常", e);
        }
    }

    private String extractText(String rawContent) {
        try {
            JsonObject json = Jsons.DEFAULT.fromJson(rawContent, JsonObject.class);
            if (json.has("text")) return json.get("text").getAsString();
            return null;
        } catch (Exception e) {
            return rawContent;
        }
    }
}
