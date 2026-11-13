package com.lark.oapi.sample.ws.controller;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.config.RequestIdentity;
import com.lark.oapi.sample.ws.service.EmployeeService;
import com.lark.oapi.sample.ws.service.QuickProbeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 供应商接口快速建档工具 REST API（11-13-standalone-doc-probe）。
 * 请求体统一收原始 String 用 Gson 解析、错误走 err()——与 {@link SupplierJobController} 同风格。
 * 任务本体是 quick 标记的 SupplierJob：删除复用 deleteJob 语义，详情页 /supplier?job=&lt;id&gt; 照常可开。
 */
@RestController
public class QuickProbeController {

    private static final Logger log = LoggerFactory.getLogger(QuickProbeController.class);

    private final QuickProbeService quickProbes;
    private final EmployeeService employeeService;

    public QuickProbeController(QuickProbeService quickProbes, EmployeeService employeeService) {
        this.quickProbes = quickProbes;
        this.employeeService = employeeService;
    }

    /** 快速建档任务列表（不含工作台正式工作） */
    @GetMapping(value = "/api/quick-probes", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> list() {
        return ResponseEntity.ok(quickProbes.listJson().toString());
    }

    /**
     * 创建快速建档任务并立即启动四段流水线。
     * body: {identifier?, name?, instanceId, sources:[来源行…], siteCrawl?, creatorEmployeeId?}
     */
    @PostMapping(value = "/api/quick-probes", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> create(@RequestBody String rawBody, jakarta.servlet.http.HttpServletRequest req) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            if (!body.has("instanceId") || body.get("instanceId").isJsonNull()) {
                return err("请绑定执行实例");
            }
            Long instanceId = body.get("instanceId").getAsLong();
            List<String> sources = new ArrayList<>();
            if (body.has("sources") && body.get("sources").isJsonArray()) {
                body.getAsJsonArray("sources").forEach(el -> {
                    if (el.isJsonPrimitive()) sources.add(el.getAsString());
                });
            }
            boolean siteCrawl = body.has("siteCrawl") && !body.get("siteCrawl").isJsonNull()
                    && body.get("siteCrawl").getAsBoolean();
            // 创建者与实例独占闸门：与工作台创建同规则（admin 不能占用员工独占实例；员工只能用自己绑定的实例）
            Long creator = RequestIdentity.creator(req,
                    body.has("creatorEmployeeId") && !body.get("creatorEmployeeId").isJsonNull()
                            ? body.get("creatorEmployeeId").getAsLong() : null);
            Long creatorId = null;
            String creatorName = null;
            if (creator != null) {
                var emp = employeeService.requirePermission(creator, "supplier");
                creatorId = emp.getId();
                creatorName = emp.getName();
            }
            employeeService.assertInstancesUsable(List.of(instanceId), creatorId);

            var job = quickProbes.create(opt(body, "identifier"), opt(body, "name"), instanceId,
                    sources, siteCrawl, creatorId, creatorName);
            return ResponseEntity.ok(quickProbes.detail(job.getId()).toString());
        } catch (SecurityException e) {
            JsonObject fo = new JsonObject();
            fo.addProperty("error", e.getMessage());
            return ResponseEntity.status(403).body(fo.toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (Exception e) {
            log.error("[QuickProbe] 创建失败", e);
            return err("创建失败: " + e.getMessage());
        }
    }

    /** 详情：四段状态 + 节点实时进度 + Wiki 状态 + 知识库条目（页面 3s 轮询） */
    @GetMapping(value = "/api/quick-probes/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> detail(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(quickProbes.detail(id).toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
    }

    /** 从指定段重试（step: kb | analysis | probe | wiki；仅失败/中断状态可用，幂等续跑） */
    @PostMapping(value = "/api/quick-probes/{id}/retry/{step}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> retry(@PathVariable Long id, @PathVariable String step) {
        try {
            quickProbes.retry(id, step);
            return ResponseEntity.ok(quickProbes.detail(id).toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
    }

    /** 删除（复用 deleteJob 语义：节点/对话级联清） */
    @DeleteMapping(value = "/api/quick-probes/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> delete(@PathVariable Long id) {
        try {
            quickProbes.delete(id);
            return ResponseEntity.ok("{\"ok\":true}");
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
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
