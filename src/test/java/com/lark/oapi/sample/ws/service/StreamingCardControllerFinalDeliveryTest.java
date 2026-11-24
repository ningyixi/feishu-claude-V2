package com.lark.oapi.sample.ws.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.Client;
import com.lark.oapi.service.cardkit.CardkitService;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementReq;
import com.lark.oapi.service.cardkit.v1.model.ContentCardElementResp;
import com.lark.oapi.service.cardkit.v1.model.CreateCardReq;
import com.lark.oapi.service.cardkit.v1.model.CreateCardResp;
import com.lark.oapi.service.cardkit.v1.model.CreateCardRespBody;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardReq;
import com.lark.oapi.service.cardkit.v1.model.SettingsCardResp;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardReq;
import com.lark.oapi.service.cardkit.v1.model.UpdateCardResp;
import com.lark.oapi.service.cardkit.v1.resource.Card;
import com.lark.oapi.service.cardkit.v1.resource.CardElement;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.CreateMessageReq;
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody;
import com.lark.oapi.service.im.v1.model.CreateMessageResp;
import com.lark.oapi.service.im.v1.model.CreateMessageRespBody;
import com.lark.oapi.service.im.v1.model.PatchMessageReq;
import com.lark.oapi.service.im.v1.model.PatchMessageResp;
import com.lark.oapi.service.im.v1.resource.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * C2/C3/C4 + AC2/AC3（2026-11-24 P0）：终态送达判定必须真实。
 * <p>
 * 现网事故：终态 card.update 报 200861 → 回退 im.message.patch 又被 230099 拒 →
 * 旧代码不看 patch 返回值就 done=true、messageSent=true，并在 if(done) 外打「终态更新完成」→
 * 上层 FeishuBotService 的 replyText 保险丝被假成功永久跳过，用户"发了没回复"。
 * <p>
 * 本测试用 mock 的飞书 SDK（零真实 HTTP）复现三种失败组合，断言：
 * messageSent/isDelivered 只在真有送达时为 true；卡片通道全失败时确实发出
 * <b>一条携带完整答案正文的普通文本消息</b>（C4）；日志如实（AC3）。
 */
class StreamingCardControllerFinalDeliveryTest {

    /** 含可选项的完整回复 — 复现现网触发面（hasOptions=true 才走选项卡路径） */
    private static final String FULL_ANSWER = """
            我分析了你的问题，有以下两个方案：

            **1. 方案甲** — 白天直接部署，快但有风险
            **2. 方案乙** — 等封板后再部署，稳但要等

            请回复编号选择。""";

    /** 现网 200861 的原始 msg（含元素路径），用于断言失败日志如实携带 */
    private static final String ERR_200861 =
            "ErrPath: ROOT -> elements -> [4](tag: action)\nErrMsg: cards of schema V2 no longer support this capability";

    private Client client;
    private Message imMessage;
    private Card cardResource;

    /** im.message.create 每次调用的记录：[msgType, content, receiveId] */
    private final List<String[]> createdMessages = new CopyOnWriteArrayList<>();
    /** im.message.patch 每次调用的 content */
    private final List<String> patchedContents = new CopyOnWriteArrayList<>();
    /** 文本兜底被尝试的信号 */
    private final CountDownLatch textAttempted = new CountDownLatch(1);

    private Logger controllerLogger;
    private CapturingAppender logAppender;

    @BeforeEach
    void setUp() throws Exception {
        client = mock(Client.class);
        ImService im = mock(ImService.class);
        imMessage = mock(Message.class);
        CardkitService cardkit = mock(CardkitService.class);
        com.lark.oapi.service.cardkit.v1.V1 cardkitV1 = mock(com.lark.oapi.service.cardkit.v1.V1.class);
        cardResource = mock(Card.class);
        CardElement cardElement = mock(CardElement.class);
        when(client.im()).thenReturn(im);
        when(im.message()).thenReturn(imMessage);
        when(client.cardkit()).thenReturn(cardkit);
        when(cardkit.v1()).thenReturn(cardkitV1);
        when(cardkitV1.card()).thenReturn(cardResource);
        when(cardkitV1.cardElement()).thenReturn(cardElement);
        // 流式中间帧不会触发（测试不喂 delta），仍备好桩防意外 NPE 干扰场景
        ContentCardElementResp contentOk = new ContentCardElementResp();
        contentOk.setCode(0);
        when(cardElement.content(any(ContentCardElementReq.class))).thenReturn(contentOk);

        controllerLogger = (Logger) LoggerFactory.getLogger(StreamingCardController.class);
        logAppender = new CapturingAppender();
        logAppender.setContext(controllerLogger.getLoggerContext());
        logAppender.start();
        controllerLogger.addAppender(logAppender);
        controllerLogger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        controllerLogger.detachAppender(logAppender);
        logAppender.stop();
        controllerLogger.setLevel(null);
    }

    // ===== AC2 组合一：update 失败 + patch 成功 → 真实送达，无文本兜底 =====

    @Test
    void updateFailsPatchSucceeds_deliveredByPatch_noTextFallback() throws Exception {
        stubCardCreateOk("card_t1");
        stubImCreate(true, true);
        stubSettingsOk();
        stubUpdateFail(200861, ERR_200861);
        stubPatch(true);

        StreamingCardController c = runTurn();

        assertTrue(awaitLog(m -> m.contains("终态更新完成")),
                "patch 真实成功时必须打「终态更新完成」，logs=" + logs());
        assertTrue(c.isDelivered(), "messageSent 只能由真实成功置位 — patch 成功即真实送达");

        // patch 携带的就是终态卡 JSON：含完整正文与可选项 markdown 列表，且无 action 标签（C1 生效）
        assertEquals(1, patchedContents.size());
        String finalJson = patchedContents.get(0);
        assertFalse(finalJson.contains("\"tag\":\"action\""), "终态卡 JSON 不得含 tag:action");
        assertTrue(finalJson.contains("**可选：**"), "可选项应降级为 markdown 列表");
        assertTrue(finalJson.contains("请回复编号选择。"), "终态卡必须包含完整正文");

        // 未走文本兜底：im.message.create 只有最初那一条卡片消息
        assertEquals(1, createdMessages.size(), "patch 成功时不得再发文本兜底");
        assertEquals("interactive", createdMessages.get(0)[0]);
        assertFalse(logs().stream().anyMatch(m -> m.contains("已回退文本")));
        // C3：update 失败如实记录（错误码 + 元素路径随 msg 落日志）
        assertTrue(logs().stream().anyMatch(m -> m.contains("200861")), "update 失败码必须落日志");
    }

    // ===== AC2 组合二（C4 核心）：update + patch 全失败 → 文本兜底携带完整正文 =====

    @Test
    void updateAndPatchBothFail_textFallbackSendsCompleteAnswer() throws Exception {
        // 现网 2026-11-24 复现：update 200861 → patch 也被拒 → 修复前假成功，用户什么都看不到
        stubCardCreateOk("card_t2");
        stubImCreate(true, true);
        stubSettingsOk();
        stubUpdateFail(200861, ERR_200861);
        stubPatch(false);

        StreamingCardController c = runTurn();

        assertTrue(textAttempted.await(15, TimeUnit.SECONDS),
                "卡片 update 与 patch 全失败后，必须发出普通文本兜底（C4）。logs=" + logs());
        assertTrue(awaitLog(m -> m.contains("已回退文本")), "文本送达后必须如实记「已回退文本」");

        // ===== C4 核心断言：发出的是一条 msgType=text 普通文本消息，携带完整答案正文，一字不差 =====
        List<String[]> texts = createdMessages.stream().filter(m -> "text".equals(m[0])).toList();
        assertEquals(1, texts.size(), "必须恰好发出一条文本兜底消息");
        JsonObject content = JsonParser.parseString(texts.get(0)[1]).getAsJsonObject();
        assertEquals(FULL_ANSWER, content.get("text").getAsString(), "文本兜底必须携带完整答案正文");
        assertEquals("ou_test", texts.get(0)[2], "兜底文本必须发给本会话接收者");

        assertTrue(c.isDelivered(), "文本兜底真实送达 → messageSent 必须为 true");

        // AC3：日志如实 — 不再有「终态更新完成」，失败阶段与两个错误码都在，无「回退也失败」
        assertFalse(logs().stream().anyMatch(m -> m.contains("终态更新完成")),
                "卡片什么都没送达时绝不允许再记「终态更新完成」");
        assertTrue(logs().stream().anyMatch(m -> m.contains("终态卡片通道全部失败")), "必须 error 记录失败阶段");
        assertTrue(logs().stream().anyMatch(m -> m.contains("200861")), "update 失败码必须落日志");
        assertTrue(logs().stream().anyMatch(m -> m.contains("230099")), "patch 失败码必须落日志");
        assertFalse(logs().stream().anyMatch(m -> m.contains("回退也失败")), "文本成功时不得记「回退也失败」");

        // C1 联动：patch 尝试的终态 JSON 已不含 action 标签（200861 的元凶被移除）
        assertEquals(1, patchedContents.size());
        assertFalse(patchedContents.get(0).contains("\"tag\":\"action\""));
    }

    // ===== AC2 组合三：update + patch + 文本兜底全失败 → 日志如实，无假「完成」 =====

    @Test
    void textFallbackAlsoFails_logsHonestly_noFakeComplete() throws Exception {
        stubCardCreateOk("card_t3");
        stubImCreate(true, false);   // 初始卡片消息成功；文本兜底失败
        stubSettingsOk();
        stubUpdateFail(200861, ERR_200861);
        stubPatch(false);

        StreamingCardController c = runTurn();

        assertTrue(awaitLog(m -> m.contains("回退也失败")),
                "文本兜底失败时必须如实记「回退也失败」，logs=" + logs());
        assertFalse(logs().stream().anyMatch(m -> m.contains("终态更新完成")), "任何失败组合都不得假报完成");
        assertFalse(logs().stream().anyMatch(m -> m.contains("已回退文本")), "文本没送达不得记「已回退文本」");
        // 初始卡片消息确实发送成功过（聊天里真实存在一条消息）→ isDelivered=true 是如实值
        assertTrue(c.isDelivered());
        assertEquals(2, createdMessages.size(), "应有 1 次 interactive + 1 次 text 尝试");
        assertEquals("text", createdMessages.get(1)[0]);
    }

    // ===== 保险丝前提：什么都没发出去过 → isDelivered 必须保持 false =====

    @Test
    void nothingEverSent_isDeliveredFalse_upperLayerFuseStaysArmed() throws Exception {
        // CardKit create 失败 → patch 降级发卡也失败 → 本轮没有任何消息真实存在
        CreateCardResp createFail = new CreateCardResp();
        createFail.setCode(400);
        createFail.setMsg("mock: no cardkit permission");
        when(cardResource.create(any(CreateCardReq.class))).thenReturn(createFail);
        stubImCreate(false, false);
        stubPatch(false);

        StreamingCardController c = runTurn();

        assertTrue(awaitLog(m -> m.contains("patch 降级发送也失败")), "创建链路失败必须如实落日志，logs=" + logs());
        // finalize 因 phase=FAILED 提前返回；messageSent 必须保持 false
        // → 上层 FeishuBotService 的 replyText 保险丝（isDelivered()==false 分支）可正常触发
        assertFalse(c.isDelivered(), "从未真实发出任何消息 → isDelivered 必须为 false，不得被假成功短路");
        assertFalse(logs().stream().anyMatch(m -> m.contains("终态更新完成")));
    }

    // ===== 驱动与桩 =====

    /** 走一轮真实生命周期：onTurnStart（异步建卡）→ onTurnComplete（异步终态） */
    private StreamingCardController runTurn() {
        StreamingCardController c = new StreamingCardController(client, "open_id", "ou_test");
        c.onTurnStart();
        c.onTurnComplete(new TurnResult(FULL_ANSWER, false));
        return c;
    }

    private void stubCardCreateOk(String cardId) throws Exception {
        CreateCardResp resp = new CreateCardResp();
        resp.setCode(0);
        CreateCardRespBody body = new CreateCardRespBody();
        body.setCardId(cardId);
        resp.setData(body);
        when(cardResource.create(any(CreateCardReq.class))).thenReturn(resp);
    }

    private void stubSettingsOk() throws Exception {
        SettingsCardResp resp = new SettingsCardResp();
        resp.setCode(0);
        when(cardResource.settings(any(SettingsCardReq.class))).thenReturn(resp);
    }

    private void stubUpdateFail(int code, String msg) throws Exception {
        UpdateCardResp resp = new UpdateCardResp();
        resp.setCode(code);
        resp.setMsg(msg);
        when(cardResource.update(any(UpdateCardReq.class))).thenReturn(resp);
    }

    /** patch：成功 → code=0；失败 → 230099（现网回退被拒的错误码）。两种情况都记录 content */
    private void stubPatch(boolean ok) throws Exception {
        when(imMessage.patch(any(PatchMessageReq.class))).thenAnswer(inv -> {
            PatchMessageReq req = inv.getArgument(0);
            patchedContents.add(req.getPatchMessageReqBody().getContent());
            PatchMessageResp resp = new PatchMessageResp();
            if (ok) {
                resp.setCode(0);
            } else {
                resp.setCode(230099);
                resp.setMsg("mock: patch rejected");
            }
            return resp;
        });
    }

    /**
     * im.message.create：interactive（初始卡/终态卡）与 text（兜底）分别可控成败，全部留痕。
     * 文本兜底无论成败都会触发 {@link #textAttempted}。
     */
    private void stubImCreate(boolean interactiveOk, boolean textOk) throws Exception {
        when(imMessage.create(any(CreateMessageReq.class))).thenAnswer(inv -> {
            CreateMessageReq req = inv.getArgument(0);
            CreateMessageReqBody body = req.getCreateMessageReqBody();
            createdMessages.add(new String[]{body.getMsgType(), body.getContent(), body.getReceiveId()});
            CreateMessageResp resp = new CreateMessageResp();
            boolean ok = "text".equals(body.getMsgType()) ? textOk : interactiveOk;
            if ("text".equals(body.getMsgType())) textAttempted.countDown();
            if (ok) {
                resp.setCode(0);
                CreateMessageRespBody data = new CreateMessageRespBody();
                data.setMessageId("om_mock_" + createdMessages.size());
                resp.setData(data);
            } else {
                resp.setCode(99991663);
                resp.setMsg("mock: message create failed");
            }
            return resp;
        });
    }

    // ===== 日志捕获与等待 =====

    private List<String> logs() {
        List<String> out = new ArrayList<>();
        for (ILoggingEvent e : logAppender.events) out.add(e.getFormattedMessage());
        return out;
    }

    /** 终态链路跑在虚拟线程上：轮询等待满足条件的日志出现（15s 超时） */
    private boolean awaitLog(Predicate<String> pred) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            if (logs().stream().anyMatch(pred)) return true;
            Thread.sleep(50);
        }
        return logs().stream().anyMatch(pred);
    }

    /** 线程安全的 logback 捕获 appender（虚拟线程并发写入） */
    private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {
        final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(ILoggingEvent e) {
            events.add(e);
        }
    }
}
