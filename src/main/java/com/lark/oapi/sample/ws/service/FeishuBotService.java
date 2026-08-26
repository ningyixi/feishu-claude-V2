package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.dto.TestResultDTO;
import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.model.entity.UserMode;
import com.lark.oapi.sample.ws.repository.UserModeRepository;
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
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class FeishuBotService {

    private static final Logger log = LoggerFactory.getLogger(FeishuBotService.class);

    private final UserModeRepository userModeRepo;
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
                            ShellService shellService,
                            ClaudeCodeService claudeCodeService) {
        this.userModeRepo = userModeRepo;
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

                        log.info("[Feishu][{}] 收到消息: openId={}, msgType={}", instance.getName(), openId, msgType);

                        Thread.ofVirtual().name("msg-" + messageId).start(() -> {
                            try {
                                handleMessage(instanceId, openId, msgType, rawContent);
                            } catch (Exception e) {
                                log.error("[Handler] 处理消息异常: openId={}", openId, e);
                                replyText(instanceId, openId, "处理消息时出错了: " + e.getMessage());
                            }
                        });
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
            conn.wsClient = null; // GC 会处理
            log.info("[FeishuBot] 实例 id={} 已断开", instanceId);
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

    private void handleMessage(Long instanceId, String openId, String msgType, String rawContent) {
        InstanceConnection conn = connections.get(instanceId);
        if (conn == null) return;

        BotInstance instance = conn.instance;

        if (!"text".equals(msgType)) {
            replyText(instanceId, openId, "暂时只支持文本消息。");
            return;
        }

        String text = extractText(rawContent);
        if (text == null || text.isBlank()) {
            replyText(instanceId, openId, "消息内容为空。");
            return;
        }

        text = text.replaceAll("@_user_\\d+\\s*", "").trim();
        if (text.isEmpty()) return;

        // 权限检查：允许的 openId 或实例配置的 openId
        boolean allowed = ALLOWED_OPEN_IDS.contains(openId)
                || (instance.getAuthorizedOpenId() != null && instance.getAuthorizedOpenId().equals(openId));
        if (!allowed) {
            replyText(instanceId, openId, "⛔ 无权访问此机器人。");
            return;
        }

        // 首次消息推送欢迎
        String greetKey = instanceId + ":" + openId;
        if (!greetedUsers.contains(greetKey)) {
            greetedUsers.add(greetKey);
            Mode mode = loadMode(openId, instance);
            String modeLabel = mode == Mode.CHAT ? "🤖 Claude 对话模式" : "🖥️ Shell 模式";
            replyText(instanceId, openId, "当前模式: " + modeLabel + "\n输入 /shell 或 /chat 切换模式");
        }

        if (text.startsWith("/") && handleCommand(instanceId, openId, text, instance)) return;

        Mode mode = loadMode(openId, instance);
        if (mode == Mode.CHAT) {
            log.info("[Chat][{}] 调用 Claude: openId={}", instance.getName(), openId);
            // 设置 Claude 工作目录为该实例的目录
            String dir = instance.getClaudeWorkingDir();
            if (dir == null || dir.isBlank()) dir = instance.getShellWorkingDir();
            if (dir != null && !dir.isBlank()) {
                claudeCodeService.setWorkingDir(dir);
            }
            String reply = claudeCodeService.chat(openId, text);
            replyText(instanceId, openId, reply);
        } else {
            log.info("[Shell][{}] 执行: openId={}, cmd={}", instance.getName(), openId, text);
            String output = shellService.execute(text);
            replyText(instanceId, openId, output);
        }
    }

    private boolean handleCommand(Long instanceId, String openId, String text, BotInstance instance) {
        String cmd = text.trim().toLowerCase();
        return switch (cmd) {
            case "/shell" -> {
                saveMode(openId, Mode.SHELL, instance);
                replyText(instanceId, openId, "🖥️ 已切换到 Shell 模式");
                yield true;
            }
            case "/chat" -> {
                saveMode(openId, Mode.CHAT, instance);
                replyText(instanceId, openId, "🤖 已切换到 Claude 对话模式");
                yield true;
            }
            case "/new" -> {
                claudeCodeService.newSession(openId);
                replyText(instanceId, openId, "✨ 已开启新 session");
                yield true;
            }
            case "/sessions" -> {
                replyText(instanceId, openId, claudeCodeService.listSessions());
                yield true;
            }
            case "/reset", "/clear" -> {
                claudeCodeService.newSession(openId);
                replyText(instanceId, openId, "Session 已重置 ✨");
                yield true;
            }
            case "/help" -> {
                Mode current = loadMode(openId, instance);
                String modeLabel = current == Mode.CHAT ? "🤖 Claude" : "🖥️ Shell";
                replyText(instanceId, openId, "当前模式: " + modeLabel + "\n\n/shell /chat /new /sessions /help");
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

    private void replyText(Long instanceId, String openId, String content) {
        InstanceConnection conn = connections.get(instanceId);
        if (conn == null || conn.httpClient == null) return;

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
            var resp = conn.httpClient.im().message().create(req);
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
