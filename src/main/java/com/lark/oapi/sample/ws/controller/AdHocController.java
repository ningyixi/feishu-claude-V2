package com.lark.oapi.sample.ws.controller;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.config.RequestIdentity;
import com.lark.oapi.sample.ws.model.entity.AdHocChat;
import com.lark.oapi.sample.ws.service.AdHocService;
import com.lark.oapi.sample.ws.service.EmployeeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 临时需求模块 REST API（11-17-adhoc-requirement-module）。
 * 请求体统一收原始 String 用 Gson 解析、错误走 err()——与 {@link QuickProbeController} 同风格。
 * 任务是独立实体 ad_hoc_task（{@link AdHocService}），与工作台/接口建档零共用行。
 */
@RestController
public class AdHocController {

    private static final Logger log = LoggerFactory.getLogger(AdHocController.class);

    private final AdHocService adHocTasks;
    private final EmployeeService employeeService;

    public AdHocController(AdHocService adHocTasks, EmployeeService employeeService) {
        this.adHocTasks = adHocTasks;
        this.employeeService = employeeService;
    }

    /** 任务列表 */
    @GetMapping(value = "/api/ad-hoc-tasks", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> list() {
        return ResponseEntity.ok(adHocTasks.listJson().toString());
    }

    /**
     * 创建任务并立即启动两段流水线（入库解析 → 编译 Wiki → READY 对话待命）。
     * body: {name?, instanceId, docSources: "多行来源原文"}
     */
    @PostMapping(value = "/api/ad-hoc-tasks", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> create(@RequestBody String rawBody, jakarta.servlet.http.HttpServletRequest req) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            if (!body.has("instanceId") || body.get("instanceId").isJsonNull()) {
                return err("请绑定执行实例");
            }
            Long instanceId = body.get("instanceId").getAsLong();
            // 创建者与实例独占闸门：与接口建档/工作台创建同规则（admin 不能占用员工独占实例）
            Long creator = RequestIdentity.creator(req, null);
            Long creatorId = null;
            if (creator != null) {
                var emp = employeeService.requirePermission(creator, "supplier");
                creatorId = emp.getId();
            }
            employeeService.assertInstancesUsable(List.of(instanceId), creatorId);

            var task = adHocTasks.create(opt(body, "name"), instanceId, opt(body, "docSources"));
            return ResponseEntity.ok(adHocTasks.detail(task.getId()).toString());
        } catch (SecurityException e) {
            JsonObject fo = new JsonObject();
            fo.addProperty("error", e.getMessage());
            return ResponseEntity.status(403).body(fo.toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (Exception e) {
            log.error("[AdHoc] 创建失败", e);
            return err("创建失败: " + e.getMessage());
        }
    }

    /** 详情：两段状态 + Wiki 状态卡 + 知识库条目（页面 3s 轮询） */
    @GetMapping(value = "/api/ad-hoc-tasks/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> detail(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(adHocTasks.detail(id).toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
    }

    /** 重试本段（仅失败/中断状态可用；入库去重 + Wiki READY 跳过 → 幂等续跑） */
    @PostMapping(value = "/api/ad-hoc-tasks/{id}/retry-stage", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> retryStage(@PathVariable Long id) {
        try {
            adHocTasks.retryStage(id);
            return ResponseEntity.ok(adHocTasks.detail(id).toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
    }

    /** 删除任务（清掉本模块记录：任务行 + 对话流水 + 专属 kbCode 的 KB 条目/产物文件） */
    @DeleteMapping(value = "/api/ad-hoc-tasks/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> delete(@PathVariable Long id) {
        try {
            adHocTasks.delete(id);
            return ResponseEntity.ok("{\"ok\":true}");
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
    }

    /** 对话框：向绑定实例主会话吩咐一句（异步，回复随会话轮次落库；与飞书私聊同 session） */
    @PostMapping(value = "/api/ad-hoc-tasks/{id}/chat", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> chat(@PathVariable Long id, @RequestBody String rawBody) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            adHocTasks.ask(id, opt(body, "question"));
            return ResponseEntity.ok("{\"ok\":true}");
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
    }

    /** 对话历史（Jackson 直出实体列表，前端 2.5s 轮询） */
    @GetMapping("/api/ad-hoc-tasks/{id}/chat")
    public List<AdHocChat> chatHistory(@PathVariable Long id) {
        return adHocTasks.chatHistory(id);
    }

    private static String opt(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private ResponseEntity<String> err(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg != null ? msg : "请求无效");
        return ResponseEntity.badRequest().body(o.toString());
    }
}
