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
    private final SkillHookService skillHookService;

    enum Mode { SHELL, CHAT }

    // 始终允许访问的 OpenID
    private static final Set<String> ALLOWED_OPEN_IDS = Set.of(
            "ou_0632d12fbcbe5d2ff8f4c5446f271acd"
    );

    // 已发送过欢迎消息的用户（本次服务运行期间）
    private final Set<String> greetedUsers = ConcurrentHashMap.newKeySet();

    // 多实例连接管理：instanceId -> InstanceConnection
    private final Map<Long, InstanceConnection> connections = new ConcurrentHashMap<>();

    // 每实例最后收到消息的时间（实例状态"空闲/摸鱼/睡觉"分级依据）
    private final Map<Long, Long> lastMessageTime = new ConcurrentHashMap<>();

    /** 实例的飞书 WS 是否在线 */
    public boolean isInstanceConnected(Long instanceId) {
        return connections.containsKey(instanceId);
    }

    /**
     * WS 长连接的<b>真实</b>存活状态 — 反射读 SDK 内部的 conn 字段（null=断线重连中）。
     * connections 里有对象只代表"连过"，云桌面夜间断网时 SDK 会静默重连数小时，
     * 表面状态与真实状态脱节，管理面板需要这个真实信号（2026-09-08 断线9.5小时无人察觉的教训）。
     */
    public boolean isWsAlive(Long instanceId) {
        InstanceConnection conn = connections.get(instanceId);
        if (conn == null || conn.wsClient == null) return false;
        try {
            java.lang.reflect.Field f = com.lark.oapi.ws.Client.class.getDeclaredField("conn");
            f.setAccessible(true);
            return f.get(conn.wsClient) != null;
        } catch (Exception e) {
            log.debug("[FeishuBot] 反射探测 WS 状态失败，乐观视为在线: {}", e.getMessage());
            return true;   // 反射失败不阻塞状态展示
        }
    }

    /** 实例最后一次收到消息的时间戳（0=从未） */
    public long getLastMessageTime(Long instanceId) {
        return lastMessageTime.getOrDefault(instanceId, 0L);
    }

    public FeishuBotService(UserModeRepository userModeRepo,
                            ChatMessageRepository chatMessageRepo,
                            ShellService shellService,
                            ClaudeCodeService claudeCodeService,
                            SkillHookService skillHookService) {
        this.userModeRepo = userModeRepo;
        this.chatMessageRepo = chatMessageRepo;
        this.shellService = shellService;
        this.claudeCodeService = claudeCodeService;
        this.skillHookService = skillHookService;
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
                        // 机器人发送方的 sender_id 可能为空：判空防 NPE（否则整个事件处理抛异常）
                        var sid = eventData.getSender() != null ? eventData.getSender().getSenderId() : null;
                        String openId = sid != null ? sid.getOpenId() : null;
                        String messageId = eventData.getMessage().getMessageId();
                        String msgType = eventData.getMessage().getMessageType();
                        String rawContent = eventData.getMessage().getContent();
                        String chatType = eventData.getMessage().getChatType();
                        String chatId = eventData.getMessage().getChatId();

                        log.info("[Feishu][{}] 收到消息: openId={}, chatType={}, msgType={}", instance.getName(), openId, chatType, msgType);

                        // 幂等：飞书长连接在重连/未及时 ack 时会重投同一条消息 → 同一需求被 Claude 执行两遍
                        if (!firstDelivery(instanceId, messageId)) {
                            log.info("[Feishu][{}] 重复投递，忽略: messageId={}", instance.getName(), messageId);
                            return;
                        }
                        // 机器人互 @ 防死循环：发送方是机器人（sender_type=app，或配置的已知机器人 open_id）则计数，
                        // 连续超过上限就不再回复，直到真人发言
                        String senderType = eventData.getSender().getSenderType();
                        String loopKey = BotLoopGuard.key(instanceId, chatId, eventData.getMessage().getThreadId());
                        if (isBotSender(senderType, openId)) {
                            BotLoopGuard.Verdict v = botLoopGuard.noteBotTurn(loopKey, System.currentTimeMillis());
                            if (!v.allowed()) {
                                log.warn("[Feishu][{}] 机器人互聊防循环触发：chat={} 连续 {} 条机器人消息（上限 {}），暂停回复直到有人发言",
                                        instance.getName(), chatId, v.count(), v.limit());
                                if (v.justTripped()) {
                                    final String notifyOpenId = openId;
                                    Thread.ofVirtual().start(() -> replyText(instanceId, notifyOpenId, chatType, chatId,
                                            "⏸ 检测到机器人之间连续对话，为避免死循环已暂停回复；有人发言后自动恢复。"));
                                }
                                return;
                            }
                        } else {
                            botLoopGuard.reset(loopKey);
                        }

                        // 长时间断线后重连会补投积压的旧消息：超过 30 分钟的不再执行（避免半夜醒来跑一堆过期指令）
                        String createTime = eventData.getMessage().getCreateTime();
                        if (isExpired(createTime)) {
                            log.warn("[Feishu][{}] 消息已过期（{}），忽略: messageId={}", instance.getName(), createTime, messageId);
                            return;
                        }

                        Thread.ofVirtual().name("msg-" + messageId).start(() -> {
                            try {
                                handleMessage(instanceId, openId, chatType, chatId, messageId, msgType, rawContent);
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
                                handleMessage(instanceId, openId, "p2p", "", "", "text",
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
            // 网络/DNS 类异常必须如实报失败 —— 旧逻辑把它们吞成"凭证有效"，
            // 断网整夜时测试按钮依然显示绿色（2026-09-10 实际误导过排查）
            Throwable root = e;
            while (root.getCause() != null && root.getCause() != root) root = root.getCause();
            boolean network = root instanceof java.net.UnknownHostException
                    || root instanceof java.net.ConnectException
                    || root instanceof java.net.SocketTimeoutException
                    || root instanceof java.net.NoRouteToHostException
                    || root instanceof java.net.SocketException
                    || msg.contains("UnknownHost") || msg.contains("不知道这样的主机")
                    || msg.contains("Connection reset") || msg.contains("timed out");
            if (network) {
                return TestResultDTO.fail("无法连接飞书服务器（网络/DNS 故障，与凭证无关）",
                        root.getClass().getSimpleName() + ": " + msg);
            }
            if (msg.contains("invalid") || msg.contains("99991663") || msg.contains("99991664")
                    || msg.contains("1000040346") || msg.contains("app_id") || msg.contains("app_secret")) {
                return TestResultDTO.fail("飞书凭证无效", msg);
            }
            return TestResultDTO.ok("飞书凭证有效（业务错误，鉴权通过）", "APP ID: " + appId);
        }
    }

    // ===== 消息处理 =====

    /** 单张图片大小上限（base64 前的原始字节数） */
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;

    // ===== 入站消息幂等（参考 larksuite/openclaw-lark src/messaging/inbound/dedup.ts） =====

    @org.springframework.beans.factory.annotation.Value("${feishu.loop-guard.max-bot-turns:10}")
    private int loopGuardMaxTurns = 10;
    @org.springframework.beans.factory.annotation.Value("${feishu.loop-guard.idle-reset-minutes:10}")
    private long loopGuardIdleMinutes = 10;
    /** 额外视为机器人的 open_id（逗号分隔，如自家其他实例机器人；sender_type=app 的天然识别） */
    @org.springframework.beans.factory.annotation.Value("${feishu.loop-guard.bot-open-ids:}")
    private String loopGuardBotOpenIds = "";
    private volatile BotLoopGuard botLoopGuard = new BotLoopGuard(10, 10 * 60_000L);
    private volatile java.util.Set<String> knownBotOpenIds = java.util.Set.of();

    @jakarta.annotation.PostConstruct
    void initLoopGuard() {
        botLoopGuard = new BotLoopGuard(loopGuardMaxTurns, loopGuardIdleMinutes * 60_000L);
        knownBotOpenIds = java.util.Arrays.stream(loopGuardBotOpenIds.split(","))
                .map(String::trim).filter(x -> !x.isEmpty()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        log.info("[Feishu] 机器人互聊防循环: 连续上限 {} 条, 空闲 {} 分钟重置, 额外机器人 open_id {} 个",
                loopGuardMaxTurns, loopGuardIdleMinutes, knownBotOpenIds.size());
    }

    /** 飞书事件 sender_type：user=真人，app=机器人（含其他应用机器人）；另可配置已知机器人 open_id 兜底 */
    boolean isBotSender(String senderType, String openId) {
        return isBotSenderType(senderType) || (openId != null && knownBotOpenIds.contains(openId));
    }

    static boolean isBotSenderType(String senderType) {
        return senderType != null && "app".equalsIgnoreCase(senderType.trim());
    }

    private static final long MSG_EXPIRY_MS = 30L * 60 * 1000;
    private final MessageDedup dedup = new MessageDedup(12L * 3600 * 1000, 5000);

    boolean firstDelivery(Long instanceId, String messageId) {
        return messageId == null || messageId.isBlank() || dedup.firstSeen(instanceId + ":" + messageId);
    }

    static boolean isExpired(String createTimeMs) {
        if (createTimeMs == null || createTimeMs.isBlank()) return false;
        try {
            return System.currentTimeMillis() - Long.parseLong(createTimeMs.trim()) > MSG_EXPIRY_MS;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private void handleMessage(Long instanceId, String openId, String chatType, String chatId, String messageId, String msgType, String rawContent) {
        InstanceConnection conn = connections.get(instanceId);
        if (conn == null) return;

        lastMessageTime.put(instanceId, System.currentTimeMillis());   // 活跃时间（状态分级用）

        BotInstance instance = conn.instance;

        // ===== 解析消息内容：text / image / sticker =====
        boolean isText = "text".equals(msgType);
        boolean isImage = "image".equals(msgType);
        boolean isSticker = "sticker".equals(msgType);
        if (!isText && !isImage && !isSticker) {
            replyText(instanceId, openId, chatType, chatId, "暂时只支持文本、图片和表情包消息。");
            return;
        }

        String text = "";
        if (isText) {
            text = extractText(rawContent);
            if (text == null || text.isBlank()) {
                replyText(instanceId, openId, chatType, chatId, "消息内容为空。");
                return;
            }
            text = text.replaceAll("@_user_\\d+\\s*", "").trim();
            if (text.isEmpty()) return;
        }

        // 保存用户消息到对话历史
        saveChatMessage(instanceId, openId, "user",
                isText ? text : (isImage ? "[图片]" : "[表情包]"), chatType);

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

        if (isText && text.startsWith("/")) {
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
            // ===== 媒体消息：下载图片并转成 image content block =====
            List<TerminalSession.ImageAttachment> images = List.of();
            if (isImage || isSticker) {
                String fileKey = extractMediaKey(rawContent, isImage ? "image_key" : "file_key");
                if (fileKey == null || fileKey.isBlank()) {
                    replyText(instanceId, openId, chatType, chatId, "❌ 无法解析" + (isImage ? "图片" : "表情包") + "资源。");
                    return;
                }
                MediaData media = downloadMedia(conn.httpClient, messageId, fileKey, isImage ? "image" : "file");
                if (media == null) {
                    replyText(instanceId, openId, chatType, chatId, isSticker
                            ? "😅 飞书接口暂不支持下载商店表情包资源。可以截图后以图片形式发给我，我就能看到啦。"
                            : "❌ 图片下载失败。请确认应用已开通「获取消息资源」权限（im:resource），且机器人与消息在同一会话中。");
                    return;
                }
                if (!media.mediaType.startsWith("image/")) {
                    replyText(instanceId, openId, chatType, chatId, "暂只支持图片类文件（收到的是 " + media.mediaType + "）。");
                    return;
                }
                if (media.bytes.length > MAX_IMAGE_BYTES) {
                    replyText(instanceId, openId, chatType, chatId, "❌ 图片过大（" + (media.bytes.length / 1024 / 1024) + "MB > 10MB），请压缩后重发。");
                    return;
                }
                images = List.of(new TerminalSession.ImageAttachment(
                        media.mediaType, java.util.Base64.getEncoder().encodeToString(media.bytes)));
                text = isSticker
                        ? "用户发来一个表情包，请理解表情包的画面内容和情绪，并作出回应。"
                        : "用户发来一张图片，请查看图片内容并作出回应。";
                log.info("[Chat][{}] 媒体消息: type={}, {}KB, mediaType={}",
                        instance.getName(), isImage ? "image" : "sticker", media.bytes.length / 1024, media.mediaType);
            }

            log.info("[Chat][{}] 调用 Claude: openId={}, dir={}, model={}, 图片={}张",
                    instance.getName(), openId, dir, model, images.size());
            try {
                // 流式卡片控制器：CardKit 原地增量更新，完成判定由协议 result 事件驱动（零等待）
                boolean isGroup = "group".equals(chatType);
                StreamingCardController controller = new StreamingCardController(
                        conn.httpClient, isGroup ? "chat_id" : "open_id", isGroup ? chatId : openId);
                String reply = claudeCodeService.chat(instanceId, openId, text, images, dir, model, controller);
                log.info("[Chat][{}] Claude 轮次完成 ({}字, 卡片送达={})",
                        instance.getName(), reply != null ? reply.length() : 0, controller.isDelivered());
                if (controller.isDelivered()) {
                    // 卡片已承载完整回复，只补存历史记录
                    saveChatMessage(instanceId, openId, "bot", reply, chatType);
                } else if (reply != null && !reply.isBlank()) {
                    // 卡片通道完全失败的兜底：退回普通消息
                    replyText(instanceId, openId, chatType, chatId, reply);
                }
                // Skill 自进化 Hook：轮次完成后异步判定（内部吞异常，绝不影响对话链路）
                skillHookService.onTurnCompleted(instanceId, text, reply);
            } catch (Exception e) {
                log.error("[Chat][{}] Claude 调用异常", instance.getName(), e);
                replyText(instanceId, openId, chatType, chatId, "❌ Claude 调用失败: " + e.getMessage());
            }
        } else {
            if (!isText) {
                replyText(instanceId, openId, chatType, chatId, "🖥️ Shell 模式不支持图片消息，发送 /chat 切换到对话模式。");
                return;
            }
            log.info("[Shell][{}] 执行: openId={}, cmd={}", instance.getName(), openId, text);
            String output = shellService.execute(text);
            replyText(instanceId, openId, chatType, chatId, output);
        }
    }

    // ===== 媒体下载与识别 =====

    private record MediaData(String mediaType, byte[] bytes) {}

    /** 从消息 content JSON 提取 image_key / file_key（对照 openclaw-lark converters/image.ts、sticker.ts） */
    private String extractMediaKey(String rawContent, String keyName) {
        try {
            JsonObject json = Jsons.DEFAULT.fromJson(rawContent, JsonObject.class);
            if (json != null && json.has(keyName)) return json.get(keyName).getAsString();
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 下载消息中的媒体资源（im.message.resource API，需 im:resource 权限）。
     * 注意：飞书 API 明确「暂不支持表情包资源下载」，sticker 会走 type=file 尝试，失败返回 null 由上层友好提示。
     */
    private MediaData downloadMedia(Client client, String messageId, String fileKey, String type) {
        try {
            var resp = client.im().messageResource().get(
                    com.lark.oapi.service.im.v1.model.GetMessageResourceReq.newBuilder()
                            .messageId(messageId)
                            .fileKey(fileKey)
                            .type(type)
                            .build());
            if (resp.getData() == null) {
                log.warn("[Media] 下载失败: code={}, msg={}, fileKey={}", resp.getCode(), resp.getMsg(), fileKey);
                return null;
            }
            byte[] bytes = resp.getData().toByteArray();
            String mediaType = detectMediaType(bytes, resp.getFileName());
            log.info("[Media] 下载成功: {}KB, mediaType={}, fileName={}", bytes.length / 1024, mediaType, resp.getFileName());
            return new MediaData(mediaType, bytes);
        } catch (Exception e) {
            log.warn("[Media] 下载异常: fileKey={}, {}", fileKey, e.getMessage());
            return null;
        }
    }

    /** 按魔数识别图片 MIME（飞书响应不一定带可靠文件名） */
    static String detectMediaType(byte[] b, String fileName) {
        if (b.length >= 4 && b[0] == (byte) 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return "image/png";
        if (b.length >= 3 && b[0] == (byte) 0xFF && b[1] == (byte) 0xD8 && b[2] == (byte) 0xFF) return "image/jpeg";
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8') return "image/gif";
        if (b.length >= 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') return "image/webp";
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".png")) return "image/png";
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
            if (lower.endsWith(".gif")) return "image/gif";
            if (lower.endsWith(".webp")) return "image/webp";
        }
        return "application/octet-stream";
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

    /**
     * 主动向指定实例的某个用户推送文本（E2E 流水线终报等系统通知用）。
     * 连接不存在或 openId 为空时静默跳过。
     */
    public void notifyUser(Long instanceId, String openId, String content) {
        if (openId == null || openId.isBlank()) return;
        if (!connections.containsKey(instanceId)) {
            log.warn("[Feishu] notifyUser 跳过: 实例 {} 未连接", instanceId);
            return;
        }
        replyText(instanceId, openId, "p2p", "", content);
    }

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
