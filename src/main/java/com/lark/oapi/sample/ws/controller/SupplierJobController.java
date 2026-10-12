package com.lark.oapi.sample.ws.controller;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.model.entity.WorkflowTemplate;
import com.lark.oapi.sample.ws.repository.WorkflowTemplateRepository;
import com.lark.oapi.sample.ws.service.SupplierJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供应商接入工作台 REST API — 存档(工作) + 节点操作 + 工作流模板。
 * 请求体统一收原始 String 用 Gson 解析（Spring 默认 Jackson 与 Gson 类型不互通）。
 */
@RestController
public class SupplierJobController {

    private static final Logger log = LoggerFactory.getLogger(SupplierJobController.class);

    private final SupplierJobService jobService;
    private final WorkflowTemplateRepository templateRepo;
    private final com.lark.oapi.sample.ws.service.EmployeeService employeeService;

    public SupplierJobController(SupplierJobService jobService, WorkflowTemplateRepository templateRepo,
                                 com.lark.oapi.sample.ws.service.EmployeeService employeeService) {
        this.jobService = jobService;
        this.templateRepo = templateRepo;
        this.employeeService = employeeService;
    }

    // ===== 工作（存档） =====

    @GetMapping("/api/supplier-jobs")
    public List<SupplierJob> list() {
        return jobService.listJobs();
    }

    @PostMapping(value = "/api/supplier-jobs", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> create(@RequestBody String rawBody, jakarta.servlet.http.HttpServletRequest req) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            SupplierJob draft = new SupplierJob();
            draft.setName(opt(body, "name"));
            draft.setSupplierCode(opt(body, "supplierCode"));
            draft.setSupplierName(opt(body, "supplierName"));
            draft.setLibraryPath(opt(body, "libraryPath"));
            draft.setDocsJson(body.has("docs") ? body.get("docs").toString() : "[]");
            draft.setSkillsJson(body.has("skills") ? body.get("skills").toString() : "[]");
            if (body.has("templateId") && !body.get("templateId").isJsonNull()) {
                draft.setTemplateId(body.get("templateId").getAsLong());
            }
            if (body.has("instanceId") && !body.get("instanceId").isJsonNull()) {
                draft.setInstanceId(body.get("instanceId").getAsLong());
            }
            // 创建者：数字员工（校验 supplier 权限）或管理员用户（缺省）
            Long creator = com.lark.oapi.sample.ws.config.RequestIdentity.creator(req,
                    body.has("creatorEmployeeId") && !body.get("creatorEmployeeId").isJsonNull()
                            ? body.get("creatorEmployeeId").getAsLong() : null);
            if (creator != null) {
                var emp = employeeService.requirePermission(creator, "supplier");
                draft.setCreatedByEmployeeId(emp.getId());
                draft.setCreatedByEmployeeName(emp.getName());
            }
            // 实例独占绑定闸门：admin 不能占用员工已绑定的实例；员工只能用自己绑定的实例
            if (draft.getInstanceId() != null) {
                employeeService.assertInstancesUsable(List.of(draft.getInstanceId()), draft.getCreatedByEmployeeId());
            }
            SupplierJob job = jobService.createJob(draft);
            return ResponseEntity.ok(detailJson(job));
        } catch (SecurityException e) {
            JsonObject fo = new JsonObject(); fo.addProperty("error", e.getMessage());
            return ResponseEntity.status(403).body(fo.toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (Exception e) {
            log.error("[SupplierJob] 创建失败", e);
            return err("创建失败: " + e.getMessage());
        }
    }

    /** 详情 = 存档 + 节点快照列表（前端轮询此接口获取执行状态） */
    @GetMapping(value = "/api/supplier-jobs/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> detail(@PathVariable Long id) {
        return jobService.getJob(id)
                .map(job -> ResponseEntity.ok(detailJson(job)))
                .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping(value = "/api/supplier-jobs/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> update(@PathVariable Long id, @RequestBody String rawBody) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            SupplierJob patch = new SupplierJob();
            if (body.has("name")) patch.setName(opt(body, "name"));
            if (body.has("supplierCode")) patch.setSupplierCode(opt(body, "supplierCode"));
            if (body.has("supplierName")) patch.setSupplierName(opt(body, "supplierName"));
            if (body.has("libraryPath")) patch.setLibraryPath(opt(body, "libraryPath"));
            if (body.has("docs")) patch.setDocsJson(body.get("docs").toString());
            if (body.has("skills")) patch.setSkillsJson(body.get("skills").toString());
            if (body.has("instanceId") && !body.get("instanceId").isJsonNull()) {
                patch.setInstanceId(body.get("instanceId").getAsLong());
            }
            // 切换执行实例也过独占闸门（沿用该工作创建者视角）
            if (patch.getInstanceId() != null) {
                SupplierJob existing = jobService.getJob(id)
                        .orElseThrow(() -> new IllegalArgumentException("工作不存在: " + id));
                employeeService.assertInstancesUsable(List.of(patch.getInstanceId()), existing.getCreatedByEmployeeId());
            }
            SupplierJob job = jobService.updateJob(id, patch);
            return ResponseEntity.ok(detailJson(job));
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
    }

    @DeleteMapping("/api/supplier-jobs/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        jobService.deleteJob(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    // ===== 节点操作 =====

    /** 执行 agent 节点（异步；body 可带 instruction 作为纠正/补充指示） */
    @PostMapping(value = "/api/supplier-jobs/{id}/nodes/{nodeId}/run", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> runNode(@PathVariable Long id, @PathVariable String nodeId,
                                          @RequestBody(required = false) String rawBody) {
        try {
            String instruction = "";
            if (rawBody != null && !rawBody.isBlank()) {
                JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
                instruction = opt(body, "instruction");
            }
            jobService.runNode(id, nodeId, instruction);
            return ResponseEntity.ok("{\"ok\":true}");
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
    }

    /** 玩家确认节点完成：蓝→绿 */
    @PostMapping(value = "/api/supplier-jobs/{id}/nodes/{nodeId}/complete", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> completeNode(@PathVariable Long id, @PathVariable String nodeId) {
        try {
            jobService.completeNode(id, nodeId);
            return ResponseEntity.ok("{\"ok\":true}");
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
    }

    /** 手动编辑工件（纠正报告） */
    @PutMapping(value = "/api/supplier-jobs/{id}/nodes/{nodeId}/artifact", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> saveArtifact(@PathVariable Long id, @PathVariable String nodeId,
                                               @RequestBody String rawBody) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            jobService.saveArtifact(id, nodeId, opt(body, "artifact"));
            return ResponseEntity.ok("{\"ok\":true}");
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
    }

    // ===== 工作流模板 =====

    @GetMapping("/api/workflows")
    public List<WorkflowTemplate> listTemplates() {
        return templateRepo.findAll();
    }

    @PostMapping(value = "/api/workflows", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> createTemplate(@RequestBody String rawBody) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            WorkflowTemplate t = new WorkflowTemplate();
            applyTemplate(t, body);
            return ResponseEntity.ok(templateRepo.save(t).getId().toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
    }

    @PutMapping(value = "/api/workflows/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> updateTemplate(@PathVariable Long id, @RequestBody String rawBody) {
        return templateRepo.findById(id).map(t -> {
            try {
                JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
                applyTemplate(t, body);
                templateRepo.save(t);
                return ResponseEntity.ok("{\"ok\":true}");
            } catch (IllegalArgumentException e) {
                return err(e.getMessage());
            }
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping(value = "/api/workflows/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> deleteTemplate(@PathVariable Long id) {
        return templateRepo.findById(id).map(t -> {
            if (t.isBuiltin()) return err("内置模板不可删除（可修改或复制后自定义）");
            templateRepo.deleteById(id);
            return ResponseEntity.ok("{\"ok\":true}");
        }).orElse(ResponseEntity.notFound().build());
    }

    // ===== 工具 =====

    private void applyTemplate(WorkflowTemplate t, JsonObject body) {
        if (body.has("name")) {
            String name = opt(body, "name");
            if (name.isBlank()) throw new IllegalArgumentException("模板名称不能为空");
            t.setName(name);
        }
        if (body.has("description")) t.setDescription(opt(body, "description"));
        if (body.has("mode")) t.setMode(opt(body, "mode"));
        if (body.has("nodesJson")) {
            String nodesJson = opt(body, "nodesJson");
            try {
                JsonParser.parseString(nodesJson).getAsJsonArray();   // 校验合法 JSON 数组
            } catch (Exception e) {
                throw new IllegalArgumentException("nodesJson 不是合法的 JSON 数组");
            }
            t.setNodesJson(nodesJson);
        }
    }

    private String detailJson(SupplierJob job) {
        JsonObject o = new JsonObject();
        o.addProperty("id", job.getId());
        o.addProperty("name", job.getName());
        o.addProperty("mode", job.getMode());
        o.addProperty("templateId", job.getTemplateId());
        o.addProperty("supplierCode", job.getSupplierCode());
        o.addProperty("supplierName", job.getSupplierName());
        o.addProperty("libraryPath", job.getLibraryPath());
        o.addProperty("docsJson", job.getDocsJson());
        o.addProperty("skillsJson", job.getSkillsJson() != null ? job.getSkillsJson() : "[]");
        if (job.getInstanceId() != null) o.addProperty("instanceId", job.getInstanceId());
        o.addProperty("currentNodeId", job.getCurrentNodeId());
        o.addProperty("status", job.getStatus());
        if (job.getCreatedByEmployeeId() != null) {
            o.addProperty("createdByEmployeeId", job.getCreatedByEmployeeId());
            o.addProperty("createdByEmployeeName", job.getCreatedByEmployeeName() != null ? job.getCreatedByEmployeeName() : "");
        }
        o.addProperty("createdAt", String.valueOf(job.getCreatedAt()));
        o.addProperty("updatedAt", String.valueOf(job.getUpdatedAt()));
        // nodes 直接由 Jackson 混排不方便，这里手动拼
        var nodes = jobService.getNodes(job.getId());
        com.google.gson.JsonArray arr = new com.google.gson.JsonArray();
        nodes.forEach(n -> {
            JsonObject no = new JsonObject();
            no.addProperty("nodeId", n.getNodeId());
            no.addProperty("seq", n.getSeq());
            no.addProperty("title", n.getTitle());
            no.addProperty("type", n.getType());
            no.addProperty("description", n.getDescription());
            no.addProperty("prompt", n.getPrompt());
            no.addProperty("status", n.getStatus());
            no.addProperty("artifact", n.getArtifact());
            no.addProperty("note", n.getNote());
            no.addProperty("lastRunAt", String.valueOf(n.getLastRunAt()));
            no.addProperty("doneAt", String.valueOf(n.getDoneAt()));
            arr.add(no);
        });
        o.add("nodes", arr);
        return o.toString();
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
