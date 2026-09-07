package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.dto.TestResultDTO;
import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.model.entity.ChatMessage;
import com.lark.oapi.sample.ws.model.entity.UserMode;
import com.lark.oapi.sample.ws.repository.ChatMessageRepository;
import com.lark.oapi.sample.ws.repository.UserModeRepository;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.Client;
import com.lark.oapi.core.utils.Jsons;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler;
import com.lark.oapi.event.cardcallback.model.P2CardActionTrigger;
import com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FeishuBotService {

    private static final Logger log = LoggerFactory.getLogger(FeishuBotService.class);

    private final UserModeRepository userModeRepo;
    private final ChatMessageRepository chatMessageRepo;
    private final ShellService shellService;
    private final ClaudeCodeService claudeCodeService;

    enum Mode { SHELL, CHAT }

    // 始终允许访问的 OpenID
    private static final Set<String> ALLOWED_OPEN_IDS = Set.of(
            "ou_0632d12fbcbe5d2ff8f4c5446f271acd"
    );

    // 已发送过欢迎消息的用户（本次服务运行期间）
    private final Set<String> greetedUsers = ConcurrentHashMap.newKeySet();

    // 多实例连接管理：instanceId -> InstanceConnection
    private final Map<Long, InstanceConnection> connections = new ConcurrentHashMap<>();

    public FeishuBotService(UserModeRepository userModeRepo,
                            ChatMessageRepository chatMessageRepo,
                            ShellService shellService,
                            ClaudeCodeService claudeCodeService) {
        this.userModeRepo = userModeRepo;
        this.chatMessageRepo = chatMessageRepo;
        this.shellService = shellService;
        this.claudeCodeService = claudeCodeService;
    }

    @PreDestroy
    public void onDestroy() {
        disconnectAll();
        shellService.destroy();
    }

    // ===== 多实例连接管理 =====

    /**
     * 连接一个实例的飞书 WebSocket
     */
    public void connectInstance(BotInstance instance) {
        if (instance.getFeishuAppId().isBlank()) {
            log.warn("[FeishuBot] 实例「{}」飞书 APP ID 为空，跳过", instance.getName());
            return;
        }

        // 先断开该实例的旧连接
        disconnectInstance(instance.getId());

        String appId = instance.getFeishuAppId();
        String appSecret = instance.getFeishuAppSecret();
        Long instanceId = instance.getId();

        Client httpClient = Client.newBuilder(appId, appSecret).build();

        EventDispatcher handler = EventDispatcher.newBuilder("", "")
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) throws Exception {
                        var eventData = event.getEvent();
                        String openId = eventData.getSender().getSenderId().getOpenId();
                        String messageId = eventData.getMessage().getMessageId();
                        String msgType = eventData.getMessage().getMessageType();
                        String rawContent = eventData.getMessage().getContent();
                        String chatType = eventData.getMessage().getChatType();
                        String chatId = eventData.getMessage().getChatId();

                        log.info("[Feishu][{}] 收到消息: openId={}, chatType={}, msgType={}", instance.getName(), openId, chatType, msgType);

                        Thread.ofVirtual().name("msg-" + messageId).start(() -> {
                            try {
                                handleMessage(instanceId, openId, chatType, chatId, msgType, rawContent);
                            } catch (Exception e) {
                                log.error("[Handler] 处理消息异常: openId={}", openId, e);
                                replyText(instanceId, openId, chatType, chatId, "处理消息时出错了: " + e.getMessage());
                            }
                        });
                    }
                })
                .onP2CardActionTrigger(new P2CardActionTriggerHandler() {
                    @Override
                    public P2CardActionTriggerResponse handle(P2CardActionTrigger event) throws Exception {
                        var data = event.getEvent();
                        String openId = data.getOperator().getOpenId();
                        var actionValue = data.getAction().getValue();
                        String choice = actionValue != null && actionValue.containsKey("choice")
                                ? String.valueOf(actionValue.get("choice")) : "";

                        log.info("[Card][{}] 按钮点击: openId={}, choice={}", instance.getName(), openId, choice);

                        // 先回复一条消息显示用户选了什么
                        Thread.ofVirtual().name("card-" + openId).start(() -> {
                            try {
                                // 发送选择反馈
                                replyText(instanceId, openId, "p2p", "",
                                        "✅ 你选择了: **" + choice + "**");
                                // 将按钮选择作为消息处理
                                handleMessage(instanceId, openId, "p2p", "", "text",
                                        "{\"text\":\"" + choice + "\"}");
                            } catch (Exception e) {
                                log.error("[Card] 处理按钮点击异常", e);
                            }
                        });

                        return new P2CardActionTriggerResponse();
                    }
                })
                .build();

        com.lark.oapi.ws.Client wsClient = new com.lark.oapi.ws.Client.Builder(appId, appSecret)
                .eventHandler(handler)
                .build();
        try {
            wsClient.start();
            connections.put(instanceId, new InstanceConnection(wsClient, httpClient, instance));
            log.info("[FeishuBot] 实例「{}」飞书 WebSocket 已连接 (appId={})", instance.getName(), appId);
        } catch (Exception e) {
            log.error("[FeishuBot] 实例「{}」飞书连接失败: {}", instance.getName(), e.getMessage());
        }
    }

    /**
     * 断开指定实例的连接
     */
    public void disconnectInstance(Long instanceId) {
        InstanceConnection conn = connections.remove(instanceId);
        if (conn != null) {
            closeWsClient(conn.wsClient);
            conn.wsClient = null;
            log.info("[FeishuBot] 实例 id={} 已断开", instanceId);
        }
        // 不再自动销毁终端 — 终端只在 workingDir/model 变更时由 ClaudeCodeService 自动回收
    }

    /**
     * 真正关闭飞书 WS 长连接。
     * SDK 2.5.3 的 ws.Client 没有公开 close API，且 onClosed 会触发自动重连，
     * 因此通过反射：先关 autoReconnect，再调 protected disconnect()，最后停掉内部 executor（pingLoop）。
     * 不关闭的后果：实例更新重连后新旧两个连接同时收事件，消息被重复处理或静默吞掉。
     */
    private void closeWsClient(com.lark.oapi.ws.Client ws) {
        if (ws == null) return;
        try {
            var clazz = com.lark.oapi.ws.Client.class;
            java.lang.reflect.Field ar = clazz.getDeclaredField("autoReconnect");
            ar.setAccessible(true);
            ar.set(ws, Boolean.FALSE);

            java.lang.reflect.Method disconnect = clazz.getDeclaredMethod("disconnect");
            disconnect.setAccessible(true);
            disconnect.invoke(ws);

            java.lang.reflect.Field ex = clazz.getDeclaredField("executor");
            ex.setAccessible(true);
            Object executor = ex.get(ws);
            if (executor instanceof java.util.concurrent.ExecutorService es) {
                es.shutdownNow();
            }
            log.info("[FeishuBot] WS 客户端已关闭（autoReconnect=false + disconnect + executor 停止）");
        } catch (Exception e) {
            log.warn("[FeishuBot] 关闭 WS 客户端失败（SDK 无公开 close API，已尝试反射）: {}", e.getMessage());
        }
    }

    /**
     * 断开所有实例的连接
     */
    public void disconnectAll() {
        connections.keySet().forEach(this::disconnectInstance);
    }

    /**
     * 获取当前连接数
     */
    public int getConnectionCount() {
        return connections.size();
    }

    // ===== 测试连通性 =====

    public TestResultDTO testConnection(String appId, String appSecret) {
        try {
            Client testClient = Client.newBuilder(appId, appSecret).build();
            testClient.im().message().list(com.lark.oapi.service.im.v1.model.ListMessageReq.newBuilder()
                    .containerIdType("chat")
                    .containerId("oc_test")
                    .pageSize(1)
                    .build());
            return TestResultDTO.ok("飞书连接成功", "APP ID: " + appId);
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.toString();
            if (msg.contains("invalid") || msg.contains("99991663") || msg.contains("99991664")
                    || msg.contains("1000040346") || msg.contains("app_id") || msg.contains("app_secret")) {
                return TestResultDTO.fail("飞书凭证无效", msg);
            }
            return TestResultDTO.ok("飞书凭证有效（业务错误，鉴权通过）", "APP ID: " + appId);
        }
    }

    // ===== 消息处理 =====

    private void handleMessage(Long instanceId, String openId, String chatType, String chatId, String msgType, String rawContent) {
        InstanceConnection conn = connections.get(instanceId);
        if (conn == null) return;

        BotInstance instance = conn.instance;

        if (!"text".equals(msgType)) {
            replyText(instanceId, openId, chatType, chatId, "暂时只支持文本消息。");
            return;
        }

        String text = extractText(rawContent);
        if (text == null || text.isBlank()) {
            replyText(instanceId, openId, chatType, chatId, "消息内容为空。");
            return;
        }

        text = text.replaceAll("@_user_\\d+\\s*", "").trim();
        if (text.isEmpty()) return;

        // 保存用户消息到对话历史
        saveChatMessage(instanceId, openId, "user", text, chatType);

        // 权限检查：允许的 openId 或实例配置的 openId
        boolean allowed = ALLOWED_OPEN_IDS.contains(openId)
                || (instance.getAuthorizedOpenId() != null && instance.getAuthorizedOpenId().equals(openId));
        if (!allowed) {
            replyText(instanceId, openId, chatType, chatId, "⛔ 无权访问此机器人。");
            return;
        }

        // 首次消息推送欢迎
        String greetKey = instanceId + ":" + openId;
        if (!greetedUsers.contains(greetKey)) {
            greetedUsers.add(greetKey);
            Mode mode = loadMode(openId, instance);
            String modeLabel = mode == Mode.CHAT ? "🤖 Claude 对话模式" : "🖥️ Shell 模式";
            replyText(instanceId, openId, chatType, chatId, "当前模式: " + modeLabel + "\n输入 /shell 或 /chat 切换模式");
        }

        // 提前准备实例配置（命令处理如 /new 也需要正确的目录）
        String dir = instance.getClaudeWorkingDir();
        if (dir == null || dir.isBlank()) dir = instance.getShellWorkingDir();
        if (dir == null) dir = "";
        String model = instance.getClaudeModel();
        if (model == null) model = "";

        if (text.startsWith("/")) {
            try {
                if (handleCommand(instanceId, openId, chatType, chatId, text, instance, dir, model)) return;
            } catch (Exception e) {
                log.error("[Command] 命令处理异常: {}", text, e);
                replyText(instanceId, openId, chatType, chatId, "❌ 命令执行出错: " + e.getMessage());
                return;
            }
        }

        Mode mode = loadMode(openId, instance);
        if (mode == Mode.CHAT) {
            log.info("[Chat][{}] 调用 Claude: openId={}, dir={}, model={}", instance.getName(), openId, dir, model);
            try {
                // 流式卡片控制器：CardKit 原地增量更新，完成判定由协议 result 事件驱动（零等待）
                boolean isGroup = "group".equals(chatType);
                StreamingCardController controller = new StreamingCardController(
                        conn.httpClient, isGroup ? "chat_id" : "open_id", isGroup ? chatId : openId);
                String reply = claudeCodeService.chat(instanceId, openId, text, dir, model, controller);
                log.info("[Chat][{}] Claude 轮次完成 ({}字, 卡片送达={})",
                        instance.getName(), reply != null ? reply.length() : 0, controller.isDelivered());
                if (controller.isDelivered()) {
                    // 卡片已承载完整回复，只补存历史记录
                    saveChatMessage(instanceId, openId, "bot", reply, chatType);
                } else if (reply != null && !reply.isBlank()) {
                    // 卡片通道完全失败的兜底：退回普通消息
                    replyText(instanceId, openId, chatType, chatId, reply);
                }
            } catch (Exception e) {
                log.error("[Chat][{}] Claude 调用异常", instance.getName(), e);
                replyText(instanceId, openId, chatType, chatId, "❌ Claude 调用失败: " + e.getMessage());
            }
        } else {
            log.info("[Shell][{}] 执行: openId={}, cmd={}", instance.getName(), openId, text);
            String output = shellService.execute(text);
            replyText(instanceId, openId, chatType, chatId, output);
        }
    }

    private boolean handleCommand(Long instanceId, String openId, String chatType, String chatId, String text, BotInstance instance, String dir, String model) {
        String cmd = text.trim().toLowerCase();
        return switch (cmd) {
            case "/shell" -> {
                saveMode(openId, Mode.SHELL, instance);
                replyText(instanceId, openId, chatType, chatId, "🖥️ 已切换到 Shell 模式");
                yield true;
            }
            case "/chat" -> {
                saveMode(openId, Mode.CHAT, instance);
                replyText(instanceId, openId, chatType, chatId, "🤖 已切换到 Claude 对话模式");
                yield true;
            }
            case "/new" -> {
                claudeCodeService.newSession(instanceId, dir, model);
                replyText(instanceId, openId, chatType, chatId, "✨ 已开启新 session");
                yield true;
            }
            case "/sessions" -> {
                replyText(instanceId, openId, chatType, chatId, claudeCodeService.listSessions());
                yield true;
            }
            case "/reset", "/clear" -> {
                claudeCodeService.newSession(instanceId, dir, model);
                replyText(instanceId, openId, chatType, chatId, "Session 已重置 ✨");
                yield true;
            }
            case "/help" -> {
                Mode current = loadMode(openId, instance);
                String modeLabel = current == Mode.CHAT ? "🤖 Claude" : "🖥️ Shell";
                replyText(instanceId, openId, chatType, chatId, "当前模式: " + modeLabel + "\n\n/shell /chat /new /sessions /help");
                yield true;
            }
            default -> false;
        };
    }

    // ===== 模式持久化（按实例目录） =====

    private String getInstanceWorkDir(BotInstance instance) {
        String dir = instance.getClaudeWorkingDir();
        if (dir == null || dir.isBlank()) dir = instance.getShellWorkingDir();
        return dir != null ? dir : "";
    }

    private Mode loadMode(String openId, BotInstance instance) {
        String workDir = getInstanceWorkDir(instance);
        Optional<UserMode> existing = userModeRepo.findByOpenIdAndWorkingDir(openId, workDir);
        if (existing.isPresent()) {
            return "CHAT".equals(existing.get().getMode()) ? Mode.CHAT : Mode.SHELL;
        }
        return Mode.SHELL;
    }

    private void saveMode(String openId, Mode mode, BotInstance instance) {
        String workDir = getInstanceWorkDir(instance);
        Optional<UserMode> existing = userModeRepo.findByOpenIdAndWorkingDir(openId, workDir);
        if (existing.isPresent()) {
            UserMode um = existing.get();
            um.setMode(mode.name());
            um.setUpdatedAt(LocalDateTime.now());
            userModeRepo.save(um);
        } else {
            UserMode um = new UserMode();
            um.setOpenId(openId);
            um.setWorkingDir(workDir);
            um.setMode(mode.name());
            um.setUpdatedAt(LocalDateTime.now());
            userModeRepo.save(um);
        }
        log.info("[Mode] 保存: openId={}, dir={}, mode={}", openId, workDir, mode);
    }

    // ===== 回复消息（使用对应实例的 httpClient） =====

    private void replyText(Long instanceId, String openId, String chatType, String chatId, String content) {
        InstanceConnection conn = connections.get(instanceId);
        if (conn == null || conn.httpClient == null) {
            log.warn("[Feishu] replyText 跳过: instanceId={}, conn={}", instanceId, conn != null ? "有" : "null");
            return;
        }

        // 保存机器人回复到对话历史
        saveChatMessage(instanceId, openId, "bot", content, chatType);

        // 群聊用 chat_id 回复，私聊用 open_id 回复
        boolean isGroup = "group".equals(chatType);
        String receiveId = isGroup ? chatId : openId;
        String receiveIdType = isGroup ? "chat_id" : "open_id";
        log.info("[Feishu] 发送回复: instanceId={}, receiveId={}, {}字", instanceId, receiveId, content.length());

        // 检测是否有选项
        List<CardBuilder.Option> options = CardBuilder.hasOptions(content)
                ? CardBuilder.extractOptions(content) : null;
        // 长文本自动拆分为多条卡片
        List<String> cardJsons = CardBuilder.buildCards(content, options);
        log.info("[Feishu] 发送 {} 条卡片消息{}", cardJsons.size(),
                options != null ? " (" + options.size() + " 个按钮)" : "");

        for (String cardJson : cardJsons) {
            CreateMessageReq req = CreateMessageReq.newBuilder()
                    .receiveIdType(receiveIdType)
                    .createMessageReqBody(CreateMessageReqBody.newBuilder()
                            .receiveId(receiveId)
                            .uuid(UUID.randomUUID().toString())
                            .msgType("interactive")
                            .content(cardJson)
                            .build())
                    .build();
            try {
                var resp = conn.httpClient.im().message().create(req);
                if (!resp.success()) {
                    log.error("[Feishu] 回复失败: code={}, msg={}", resp.getCode(), resp.getMsg());
                }
            } catch (Exception e) {
                log.error("[Feishu] 发送消息异常", e);
            }
        }
    }

    private void saveChatMessage(Long instanceId, String openId, String role, String content, String chatType) {
        try {
            ChatMessage msg = new ChatMessage();
            msg.setInstanceId(instanceId);
            msg.setOpenId(openId);
            msg.setRole(role);
            msg.setContent(content);
            msg.setChatType(chatType);
            chatMessageRepo.save(msg);
        } catch (Exception e) {
            log.warn("[ChatHistory] 保存消息失败: {}", e.getMessage());
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

    // ===== 内部类 =====

    private static class InstanceConnection {
        volatile com.lark.oapi.ws.Client wsClient;
        final Client httpClient;
        final BotInstance instance;

        InstanceConnection(com.lark.oapi.ws.Client wsClient, Client httpClient, BotInstance instance) {
            this.wsClient = wsClient;
            this.httpClient = httpClient;
            this.instance = instance;
        }
    }
}
