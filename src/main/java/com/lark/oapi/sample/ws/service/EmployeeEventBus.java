package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonObject;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 数字员工实时事件总线（SSE）。
 * <p>
 * 发布方（Claude reader 虚拟线程上的 TurnListener 回调）只做内存入队，<b>绝不在调用线程做网络 IO</b>——
 * 真正的 SseEmitter.send 在专用单线程里执行，慢客户端/断线客户端不会反压 Claude stdout 读取。
 * 每员工保留最近 {@value #REPLAY_CAP} 条事件，新连接（刷新页面/重连）先回放，所以执行过程不会"看不到"。
 */
@Component
public class EmployeeEventBus {

    private static final int REPLAY_CAP = 400;

    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Map<Long, ArrayDeque<JsonObject>> replay = new ConcurrentHashMap<>();
    private final AtomicLong seq = new AtomicLong();
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "emp-sse-sender");
        t.setDaemon(true);
        return t;
    });

    /** 发布一条事件（非阻塞）。kind: status|turn|thinking|text|tool_start|tool_end|done|error|message|queued */
    public void publish(Long employeeId, String kind, Long demandId, String scope, JsonObject data) {
        if (employeeId == null) return;
        JsonObject ev = data != null ? data.deepCopy() : new JsonObject();
        ev.addProperty("id", seq.incrementAndGet());
        ev.addProperty("kind", kind);
        ev.addProperty("ts", System.currentTimeMillis());
        ev.addProperty("scope", scope != null ? scope : "demand");   // demand | chat
        if (demandId != null) ev.addProperty("demandId", demandId);
        ArrayDeque<JsonObject> buf = replay.computeIfAbsent(employeeId, k -> new ArrayDeque<>());
        synchronized (buf) {
            buf.addLast(ev);
            while (buf.size() > REPLAY_CAP) buf.removeFirst();
        }
        List<SseEmitter> list = emitters.get(employeeId);
        if (list == null || list.isEmpty()) return;
        final String payload = ev.toString();
        sender.execute(() -> {
            for (SseEmitter em : list) {
                try {
                    em.send(SseEmitter.event().name("emp").data(payload, org.springframework.http.MediaType.APPLICATION_JSON));
                } catch (Exception e) {
                    list.remove(em);
                }
            }
        });
    }

    /** 订阅：先回放最近事件再实时推送 */
    public SseEmitter subscribe(Long employeeId) {
        SseEmitter em = new SseEmitter(0L);
        List<SseEmitter> list = emitters.computeIfAbsent(employeeId, k -> new CopyOnWriteArrayList<>());
        List<String> snapshot = new ArrayList<>();
        ArrayDeque<JsonObject> buf = replay.get(employeeId);
        if (buf != null) synchronized (buf) { for (JsonObject o : buf) snapshot.add(o.toString()); }
        sender.execute(() -> {
            try {
                em.send(SseEmitter.event().name("hello").data("{\"replay\":" + snapshot.size() + "}"));
                for (String s : snapshot) em.send(SseEmitter.event().name("emp").data(s, org.springframework.http.MediaType.APPLICATION_JSON));
                list.add(em);
            } catch (Exception e) {
                em.completeWithError(e);
            }
        });
        Runnable rm = () -> list.remove(em);
        em.onCompletion(rm);
        em.onTimeout(rm);
        em.onError(e -> rm.run());
        return em;
    }

    /** 心跳：防代理/浏览器空闲断连（由 EmployeeService 定时调用） */
    public void heartbeat() {
        sender.execute(() -> emitters.values().forEach(list -> {
            for (SseEmitter em : list) {
                try { em.send(SseEmitter.event().comment("ping")); } catch (Exception e) { list.remove(em); }
            }
        }));
    }

    /** 取某需求的全部事件（文本/思考增量合并，限 300 条、单条文本 4000 字）→ JSON 数组，用于落库 */
    public String demandTimelineJson(Long employeeId, Long demandId) {
        ArrayDeque<JsonObject> buf = replay.get(employeeId);
        com.google.gson.JsonArray out = new com.google.gson.JsonArray();
        if (buf == null) return out.toString();
        synchronized (buf) {
            JsonObject last = null;
            for (JsonObject e : buf) {
                if (!e.has("demandId") || e.get("demandId").getAsLong() != demandId) continue;
                String kind = e.get("kind").getAsString();
                if ("message".equals(kind)) continue;
                if (("text".equals(kind) || "thinking".equals(kind)) && last != null
                        && kind.equals(last.get("kind").getAsString())) {
                    String merged = last.get("delta").getAsString() + (e.has("delta") ? e.get("delta").getAsString() : "");
                    last.addProperty("delta", merged.length() > 4000 ? merged.substring(merged.length() - 4000) : merged);
                    continue;
                }
                last = e.deepCopy();
                out.add(last);
            }
        }
        while (out.size() > 300) out.remove(0);
        return out.toString();
    }

    public void clear(Long employeeId) {
        replay.remove(employeeId);
    }

    @PreDestroy
    public void shutdown() {
        sender.shutdownNow();
    }
}
