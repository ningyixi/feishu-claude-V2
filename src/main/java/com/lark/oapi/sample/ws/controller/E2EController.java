package com.lark.oapi.sample.ws.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.config.E2EWebSocketHandler;
import com.lark.oapi.sample.ws.service.E2EOrchestrator;
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
    private final E2EOrchestrator orchestrator;
    private final com.lark.oapi.sample.ws.service.EmployeeService employeeService;

    public E2EController(E2ETaskService taskService, E2EOrchestrator orchestrator,
                         com.lark.oapi.sample.ws.service.EmployeeService employeeService) {
        this.taskService = taskService;
        this.orchestrator = orchestrator;
        this.employeeService = employeeService;
    }

    /**
     * 创建群聊任务。
     * body: {members:[{instanceId, role(programmer|designer|executor)}], requirement, autoFix?, maxRounds?}
     * 注意：收原始 String 自行 Gson 解析 —— Spring 默认 Jackson 不认识 Gson JsonObject，
     * 直接 @RequestBody JsonObject 会被静默反序列化成空对象（members 丢失）。
     */
    @PostMapping(value = "/tasks", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> create(@RequestBody String rawBody, jakarta.servlet.http.HttpServletRequest req) {
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
            String commMode = body.has("commMode") && !body.get("commMode").isJsonNull()
                    ? body.get("commMode").getAsString() : "filesystem";
            // 创建者：数字员工（校验 e2e 权限）或管理员用户（缺省）
            // 身份来自员工令牌 / 面板登录，请求体 creatorEmployeeId 只做一致性校验
            Long creatorEmployeeId = com.lark.oapi.sample.ws.config.RequestIdentity.creator(req,
                    body.has("creatorEmployeeId") && !body.get("creatorEmployeeId").isJsonNull()
                            ? body.get("creatorEmployeeId").getAsLong() : null);
            String creatorName = null;
            if (creatorEmployeeId != null) {
                creatorName = employeeService.requirePermission(creatorEmployeeId, "e2e").getName();
            }
            // 实例独占绑定闸门：admin 不能用员工已绑定的实例；员工只能用自己绑定的实例
            List<Long> memberIds = new java.util.ArrayList<>();
            for (E2ETaskService.MemberSpec s : members) {
                if (!memberIds.contains(s.instanceId)) memberIds.add(s.instanceId);
            }
            employeeService.assertInstancesUsable(memberIds, creatorEmployeeId);

            E2ETaskService.E2ETask task = taskService.createTask(members, requirement, autoFix, maxRounds, commMode,
                    creatorEmployeeId, creatorName);
            return ResponseEntity.ok(E2EWebSocketHandler.taskJson(task).toString());
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
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

    /** 断点续跑（应用重启打断流水线后） */
    @PostMapping(value = "/tasks/{id}/resume", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> resume(@PathVariable String id) {
        E2ETaskService.E2ETask task = taskService.getTask(id);
        if (task == null) return ResponseEntity.notFound().build();
        orchestrator.resumePipeline(task);
        return ResponseEntity.ok("{\"ok\":true}");
    }

    /**
     * 启动流水线（幂等）——纯 API 触发，无需前端连 WS。
     * 数字员工调用（带 creatorEmployeeId）时：需 e2e 权限，且只能启动自己创建的任务。
     */
    @PostMapping(value = "/tasks/{id}/start", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> start(@PathVariable String id, @RequestBody(required = false) String rawBody,
                                        jakarta.servlet.http.HttpServletRequest req) {
        E2ETaskService.E2ETask task = taskService.getTask(id);
        if (task == null) return ResponseEntity.notFound().build();
        try {
            Long empId = com.lark.oapi.sample.ws.config.RequestIdentity.creator(req, parseCreator(rawBody));
            if (empId != null) {
                employeeService.requirePermission(empId, "e2e");
                assertTaskCreator(task, empId);
            }
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        orchestrator.start(task);
        return ResponseEntity.ok("{\"ok\":true}");
    }

    /**
     * 群聊发言（与前端输入框等价）：to=auto 驱动流水线/回答提问，to=角色 则单聊该成员。
     * 数字员工发言带 creatorEmployeeId（需 e2e 权限 + 只能操作自己创建的任务），群聊里显示 👔 员工身份。
     */
    @PostMapping(value = "/tasks/{id}/message", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> message(@PathVariable String id, @RequestBody String rawBody,
                                          jakarta.servlet.http.HttpServletRequest req) {
        E2ETaskService.E2ETask task = taskService.getTask(id);
        if (task == null) return ResponseEntity.notFound().build();
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            String text = body.has("text") && !body.get("text").isJsonNull() ? body.get("text").getAsString() : "";
            if (text.isBlank()) return badRequest("消息内容不能为空");
            String to = body.has("to") && !body.get("to").isJsonNull() ? body.get("to").getAsString() : "auto";
            String authorName = "用户";
            Long empId = com.lark.oapi.sample.ws.config.RequestIdentity.creator(req, body.has("creatorEmployeeId") && !body.get("creatorEmployeeId").isJsonNull()
                    ? body.get("creatorEmployeeId").getAsLong() : null);
            if (empId != null) {
                var emp = employeeService.requirePermission(empId, "e2e");
                assertTaskCreator(task, empId);
                authorName = emp.getEmoji() + " " + emp.getName() + "（数字员工）";
            }
            orchestrator.postUserMessage(task, text, to, authorName);
            return ResponseEntity.ok("{\"ok\":true}");
        } catch (SecurityException e) {
            return forbidden(e.getMessage());
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    private static ResponseEntity<String> forbidden(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg);
        return ResponseEntity.status(403).body(o.toString());
    }

    private Long parseCreator(String rawBody) {
        if (rawBody == null || rawBody.isBlank()) return null;
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            return body.has("creatorEmployeeId") && !body.get("creatorEmployeeId").isJsonNull()
                    ? body.get("creatorEmployeeId").getAsLong() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void assertTaskCreator(E2ETaskService.E2ETask task, Long empId) {
        if (task.creatorEmployeeId == null || !task.creatorEmployeeId.equals(empId)) {
            throw new IllegalArgumentException("数字员工只能操作自己创建的 E2E 任务");
        }
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
