package com.lark.oapi.sample.ws.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.config.E2EWebSocketHandler;
import com.lark.oapi.sample.ws.service.E2ETaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * E2E 测试群聊 REST API。
 * 交互式消息走 WebSocket(/ws/e2e)，这里负责任务 CRUD 和背景简报预览。
 * 响应统一用 Gson 序列化（与 WS 快照同构，前端共用类型）。
 */
@RestController
@RequestMapping("/api/e2e")
public class E2EController {

    private static final Logger log = LoggerFactory.getLogger(E2EController.class);

    private final E2ETaskService taskService;

    public E2EController(E2ETaskService taskService) {
        this.taskService = taskService;
    }

    /**
     * 创建群聊任务。
     * body: {members:[{instanceId, role(programmer|designer|executor)}], requirement, autoFix?, maxRounds?}
     * 注意：收原始 String 自行 Gson 解析 —— Spring 默认 Jackson 不认识 Gson JsonObject，
     * 直接 @RequestBody JsonObject 会被静默反序列化成空对象（members 丢失）。
     */
    @PostMapping(value = "/tasks", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> create(@RequestBody String rawBody) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            List<E2ETaskService.MemberSpec> members = new java.util.ArrayList<>();
            JsonArray arr = body.has("members") ? body.getAsJsonArray("members") : null;
            if (arr != null) {
                for (var el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    E2ETaskService.MemberSpec spec = new E2ETaskService.MemberSpec();
                    spec.instanceId = o.get("instanceId").getAsLong();
                    spec.role = o.get("role").getAsString();
                    members.add(spec);
                }
            }
            String requirement = body.has("requirement") ? body.get("requirement").getAsString() : "";
            boolean autoFix = !body.has("autoFix") || body.get("autoFix").getAsBoolean();
            int maxRounds = body.has("maxRounds") ? body.get("maxRounds").getAsInt() : 3;

            E2ETaskService.E2ETask task = taskService.createTask(members, requirement, autoFix, maxRounds);
            return ResponseEntity.ok(E2EWebSocketHandler.taskJson(task).toString());
        } catch (IllegalArgumentException | IllegalStateException e) {
            return badRequest(e.getMessage());
        } catch (Exception e) {
            log.error("[E2E] 创建任务失败", e);
            JsonObject err = new JsonObject();
            err.addProperty("error", "创建任务失败: " + e.getMessage());
            return ResponseEntity.internalServerError().body(err.toString());
        }
    }

    /** 任务列表（含成员与流水线状态，按创建时间倒序） */
    @GetMapping(value = "/tasks", produces = "application/json;charset=UTF-8")
    public String list() {
        JsonArray out = new JsonArray();
        taskService.listTasks().stream()
                .sorted((a, b) -> Long.compare(b.createdAt, a.createdAt))
                .forEach(t -> out.add(E2EWebSocketHandler.taskJson(t)));
        return out.toString();
    }

    /** 任务详情 + 完整群聊快照（WS 之外的兜底恢复通道） */
    @GetMapping(value = "/tasks/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> get(@PathVariable String id) {
        E2ETaskService.E2ETask task = taskService.getTask(id);
        if (task == null) return ResponseEntity.notFound().build();
        JsonObject snap = new JsonObject();
        snap.addProperty("type", "snapshot");
        snap.add("task", E2EWebSocketHandler.taskJson(task));
        JsonArray turns = new JsonArray();
        for (E2ETaskService.TurnRecord rec : task.transcript) {
            turns.add(E2EWebSocketHandler.turnJson(rec));
        }
        snap.add("turns", turns);
        return ResponseEntity.ok(snap.toString());
    }

    /** 删除任务 */
    @DeleteMapping(value = "/tasks/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> delete(@PathVariable String id) {
        if (taskService.getTask(id) == null) return ResponseEntity.notFound().build();
        taskService.destroyTask(id);
        return ResponseEntity.ok("{\"ok\":true}");
    }

    /** 目标实例的背景简报预览（git 变更 + 最近对话） */
    @GetMapping("/brief/{instanceId}")
    public ResponseEntity<?> brief(@PathVariable long instanceId) {
        try {
            return ResponseEntity.ok(taskService.getBrief(instanceId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private ResponseEntity<String> badRequest(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg != null ? msg : "请求无效");
        return ResponseEntity.badRequest().body(o.toString());
    }
}
