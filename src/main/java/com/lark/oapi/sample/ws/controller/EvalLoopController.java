package com.lark.oapi.sample.ws.controller;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.config.RequestIdentity;
import com.lark.oapi.sample.ws.service.EmployeeService;
import com.lark.oapi.sample.ws.service.EvalDrafts;
import com.lark.oapi.sample.ws.service.EvalLoopService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

/**
 * 代码质量测评 REST API（11-21-supplier-eval-loop MVP-1）。
 * 请求体统一收原始 String 用 Gson 解析、错误走 err()——与 {@link QuickProbeController} 同风格。
 * 判定只发生在 EvalLoopService/EvalRunner（进程退出码 + 输出断言）；本控制器无任何模型通道。
 */
@RestController
public class EvalLoopController {

    private static final Logger log = LoggerFactory.getLogger(EvalLoopController.class);

    private final EvalLoopService evals;
    private final EmployeeService employeeService;

    public EvalLoopController(EvalLoopService evals, EmployeeService employeeService) {
        this.evals = evals;
        this.employeeService = employeeService;
    }

    // ===== 任务 =====

    @GetMapping(value = "/api/eval-tasks", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> list() {
        return ResponseEntity.ok(evals.listJson().toString());
    }

    /**
     * 创建测评任务。body: {name, supplierCode?, instanceId?, reviewSkillPath?, createSkillPath?, creatorEmployeeId?}
     * 实例独占闸门与工作台/建档同规则（admin 不能占用员工独占实例；员工只能用自己绑定的实例）。
     */
    @PostMapping(value = "/api/eval-tasks", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> create(@RequestBody String rawBody, jakarta.servlet.http.HttpServletRequest req) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            Long instanceId = body.has("instanceId") && !body.get("instanceId").isJsonNull()
                    ? body.get("instanceId").getAsLong() : null;
            Long creator = RequestIdentity.creator(req,
                    body.has("creatorEmployeeId") && !body.get("creatorEmployeeId").isJsonNull()
                            ? body.get("creatorEmployeeId").getAsLong() : null);
            if (instanceId != null) {
                employeeService.assertInstancesUsable(List.of(instanceId), creator);
            }
            var task = evals.createTask(opt(body, "name"), opt(body, "supplierCode"), instanceId,
                    opt(body, "reviewSkillPath"), opt(body, "createSkillPath"));
            return ResponseEntity.ok(evals.detail(task.getId()).toString());
        } catch (SecurityException e) {
            JsonObject fo = new JsonObject();
            fo.addProperty("error", e.getMessage());
            return ResponseEntity.status(403).body(fo.toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (Exception e) {
            log.error("[Eval] 创建任务失败", e);
            return err("创建失败: " + e.getMessage());
        }
    }

    @GetMapping(value = "/api/eval-tasks/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> detail(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(evals.detail(id).toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
    }

    @PutMapping(value = "/api/eval-tasks/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> update(@PathVariable Long id, @RequestBody String rawBody,
                                         jakarta.servlet.http.HttpServletRequest req) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            // instanceId 显式携带 null = 解绑（eval 改在后端进程目录执行）；缺键 = 保持原绑定
            boolean setInstance = body.has("instanceId");
            Long instanceId = setInstance && !body.get("instanceId").isJsonNull()
                    ? body.get("instanceId").getAsLong() : null;
            if (instanceId != null) {
                Long creator = RequestIdentity.creator(req, null);
                employeeService.assertInstancesUsable(List.of(instanceId), creator);
            }
            evals.updateTask(id,
                    body.has("name") ? opt(body, "name") : null,
                    body.has("supplierCode") ? opt(body, "supplierCode") : null,
                    setInstance, instanceId,
                    body.has("reviewSkillPath") ? opt(body, "reviewSkillPath") : null,
                    body.has("createSkillPath") ? opt(body, "createSkillPath") : null,
                    body.has("contextJson") ? opt(body, "contextJson") : null);
            return ResponseEntity.ok(evals.detail(id).toString());
        } catch (SecurityException e) {
            JsonObject fo = new JsonObject();
            fo.addProperty("error", e.getMessage());
            return ResponseEntity.status(403).body(fo.toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
    }

    @DeleteMapping(value = "/api/eval-tasks/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> delete(@PathVariable Long id) {
        try {
            evals.deleteTask(id);
            return ResponseEntity.ok("{\"ok\":true}");
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        }
    }

    // ===== eval 条目 =====

    @PostMapping(value = "/api/eval-tasks/{id}/cases", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> createCase(@PathVariable Long id, @RequestBody String rawBody) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            var c = evals.createCase(id, opt(body, "title"), opt(body, "kind"), opt(body, "target"),
                    opt(body, "expect"), opt(body, "notes"),
                    body.has("timeoutSec") && !body.get("timeoutSec").isJsonNull() ? body.get("timeoutSec").getAsInt() : null,
                    body.has("requiresSandbox") && body.get("requiresSandbox").getAsBoolean(),
                    !body.has("enabled") || body.get("enabled").getAsBoolean());
            return ResponseEntity.ok(evals.detail(id).toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
    }

    @PutMapping(value = "/api/eval-tasks/{id}/cases/{caseId}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> updateCase(@PathVariable Long id, @PathVariable Long caseId,
                                             @RequestBody String rawBody) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            evals.updateCase(id, caseId, opt(body, "title"), opt(body, "kind"), opt(body, "target"),
                    opt(body, "expect"), opt(body, "notes"),
                    body.has("timeoutSec") && !body.get("timeoutSec").isJsonNull() ? body.get("timeoutSec").getAsInt() : null,
                    body.has("requiresSandbox") && body.get("requiresSandbox").getAsBoolean(),
                    !body.has("enabled") || body.get("enabled").getAsBoolean());
            return ResponseEntity.ok(evals.detail(id).toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        }
    }

    @DeleteMapping(value = "/api/eval-tasks/{id}/cases/{caseId}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> deleteCase(@PathVariable Long id, @PathVariable Long caseId) {
        try {
            evals.deleteCase(id, caseId);
            return ResponseEntity.ok("{\"ok\":true}");
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        }
    }

    /** 单条试跑：异步启动（页面轮询 detail 拿 verdict/退出码/耗时/输出摘要），同任务串行 */
    @PostMapping(value = "/api/eval-tasks/{id}/cases/{caseId}/trial", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> trial(@PathVariable Long id, @PathVariable Long caseId) {
        try {
            evals.trial(id, caseId);
            return ResponseEntity.ok(evals.detail(id).toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (IllegalStateException e) {
            return conflict(e.getMessage());
        } catch (Exception e) {
            log.error("[Eval] 试跑启动失败 task={} case={}", id, caseId, e);
            return err("试跑启动失败: " + e.getMessage());
        }
    }

    /** 单条 eval 的最近执行记录（含每次退出码/耗时/判定依据/输出摘要） */
    @GetMapping(value = "/api/eval-tasks/{id}/cases/{caseId}/results", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> caseResults(@PathVariable Long id, @PathVariable Long caseId) {
        try {
            return ResponseEntity.ok(evals.caseResults(id, caseId).toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
    }

    // ===== 草稿（从 review skill 生成 → 用户确认 → 导入） =====

    /** 生成草稿（只读 skill 目录，不落库）：{drafts:[…]}，前端展示供用户确认/编辑 */
    @PostMapping(value = "/api/eval-tasks/{id}/drafts/generate", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> generateDrafts(@PathVariable Long id) {
        try {
            JsonObject o = new JsonObject();
            o.add("drafts", evals.generateDrafts(id));
            return ResponseEntity.ok(o.toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (Exception e) {
            log.error("[Eval] 草稿生成失败 task={}", id, e);
            return err("草稿生成失败: " + e.getMessage());
        }
    }

    /** 导入用户确认后的草稿：body {drafts:[{title,kind,target?,expect?,notes?,timeoutSec?,requiresSandbox?,enabled?,source?}]} */
    @PostMapping(value = "/api/eval-tasks/{id}/drafts/import", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> importDrafts(@PathVariable Long id, @RequestBody String rawBody) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            if (!body.has("drafts") || !body.get("drafts").isJsonArray()) return err("缺少 drafts 数组");
            List<EvalDrafts.Draft> drafts = new ArrayList<>();
            body.getAsJsonArray("drafts").forEach(el -> {
                if (!el.isJsonObject()) return;
                JsonObject d = el.getAsJsonObject();
                drafts.add(new EvalDrafts.Draft(
                        opt(d, "title"),
                        opt(d, "kind").isBlank() ? "script" : opt(d, "kind"),
                        opt(d, "target"), opt(d, "expect"),
                        d.has("timeoutSec") && !d.get("timeoutSec").isJsonNull() ? d.get("timeoutSec").getAsInt() : 120,
                        d.has("requiresSandbox") && d.get("requiresSandbox").getAsBoolean(),
                        d.has("enabled") && d.get("enabled").getAsBoolean(),
                        opt(d, "notes"), opt(d, "source"), true));
            });
            if (drafts.isEmpty()) return err("没有勾选任何草稿");
            int n = evals.importDrafts(id, drafts);
            JsonObject o = new JsonObject();
            o.addProperty("ok", true);
            o.addProperty("imported", n);
            return ResponseEntity.ok(o.toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (Exception e) {
            log.error("[Eval] 草稿导入失败 task={}", id, e);
            return err("草稿导入失败: " + e.getMessage());
        }
    }

    // ===== 内置通用模板（MVP-1.5）：列表（含缺变量提示）+ 导入 =====

    /** 模板库列表：实时读模板文件，按该任务上下文变量标注每条缺哪些变量（页面直接提示） */
    @GetMapping(value = "/api/eval-tasks/{id}/templates", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> templates(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(evals.templatesJson(id).toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (Exception e) {
            log.error("[Eval] 模板库读取失败 task={}", id, e);
            return err("模板库读取失败: " + e.getMessage());
        }
    }

    /** 导入勾选的模板：body {keys:["config-shape",…]}；变量缺失的落成未落地（不计入通过率） */
    @PostMapping(value = "/api/eval-tasks/{id}/templates/import", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> importTemplates(@PathVariable Long id, @RequestBody String rawBody) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            if (!body.has("keys") || !body.get("keys").isJsonArray()) return err("缺少 keys 数组");
            List<String> keys = new ArrayList<>();
            body.getAsJsonArray("keys").forEach(el -> {
                if (el.isJsonPrimitive()) keys.add(el.getAsString());
            });
            return ResponseEntity.ok(evals.importTemplates(id, keys).toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (Exception e) {
            log.error("[Eval] 模板导入失败 task={}", id, e);
            return err("模板导入失败: " + e.getMessage());
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

    private ResponseEntity<String> conflict(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg != null ? msg : "当前状态不允许该操作");
        return ResponseEntity.status(409).body(o.toString());
    }
}
