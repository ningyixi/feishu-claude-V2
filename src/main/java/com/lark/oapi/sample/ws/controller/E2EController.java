package com.lark.oapi.sample.ws.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lark.oapi.sample.ws.config.E2EWebSocketHandler;
import com.lark.oapi.sample.ws.service.E2ETaskService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * E2E 测试工作台 REST API。
 * 交互式消息走 WebSocket(/ws/e2e)，这里只负责任务 CRUD 和背景简报预览。
 */
@RestController
@RequestMapping("/api/e2e")
public class E2EController {

    private static final Logger log = LoggerFactory.getLogger(E2EController.class);

    private final E2ETaskService taskService;

    public E2EController(E2ETaskService taskService) {
        this.taskService = taskService;
    }

    /** 创建测试任务（A 测 B）：body {testerInstanceId, targetInstanceId} */
    @PostMapping("/tasks")
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        try {
            long testerId = ((Number) body.get("testerInstanceId")).longValue();
            long targetId = ((Number) body.get("targetInstanceId")).longValue();
            E2ETaskService.E2ETask task = taskService.createTask(testerId, targetId);
            return ResponseEntity.ok(taskSummary(task));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("[E2E] 创建任务失败", e);
            return ResponseEntity.internalServerError().body(Map.of("error", "创建任务失败: " + e.getMessage()));
        }
    }

    /** 任务列表 */
    @GetMapping("/tasks")
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (E2ETaskService.E2ETask t : taskService.listTasks()) {
            out.add(taskSummary(t));
        }
        // 新的在前
        out.sort((a, b) -> Long.compare((Long) b.get("createdAt"), (Long) a.get("createdAt")));
        return out;
    }

    /** 任务详情 + 完整对话快照（WS 断线时的兜底恢复通道） */
    @GetMapping("/tasks/{id}")
    public ResponseEntity<?> get(@PathVariable String id) {
        E2ETaskService.E2ETask task = taskService.getTask(id);
        if (task == null) return ResponseEntity.notFound().build();
        JsonObject detail = new JsonObject();
        // 复用 WS handler 的序列化，保证字段一致
        JsonObject snapshot = new JsonObject();
        snapshot.addProperty("type", "snapshot");
        JsonArray turns = new JsonArray();
        for (E2ETaskService.TurnRecord rec : task.transcript) {
            turns.add(E2EWebSocketHandler.turnJson(rec));
        }
        snapshot.add("turns", turns);
        return ResponseEntity.ok(Map.of(
                "task", taskSummary(task),
                "snapshot", snapshot.toString()   // 前端 JSON.parse 后与 WS snapshot 同构
        ));
    }

    /** 销毁任务（杀专属进程） */
    @DeleteMapping("/tasks/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        if (taskService.getTask(id) == null) return ResponseEntity.notFound().build();
        taskService.destroyTask(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 目标实例的背景简报预览（git 变更 + 最近对话），创建任务前就能看 */
    @GetMapping("/brief/{instanceId}")
    public ResponseEntity<?> brief(@PathVariable long instanceId) {
        try {
            return ResponseEntity.ok(taskService.getBrief(instanceId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    private Map<String, Object> taskSummary(E2ETaskService.E2ETask t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.id);
        m.put("testerInstanceId", t.testerInstanceId);
        m.put("targetInstanceId", t.targetInstanceId);
        m.put("testerName", t.testerName);
        m.put("targetName", t.targetName);
        m.put("workingDir", t.workingDir);
        m.put("model", t.model);
        m.put("createdAt", t.createdAt);
        m.put("busy", t.busy);
        m.put("turnCount", t.transcript.size());
        return m;
    }
}
