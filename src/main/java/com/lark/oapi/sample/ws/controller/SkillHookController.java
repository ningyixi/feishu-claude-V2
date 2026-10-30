package com.lark.oapi.sample.ws.controller;

import com.lark.oapi.sample.ws.model.entity.HookRun;
import com.lark.oapi.sample.ws.model.entity.SkillFileChange;
import com.lark.oapi.sample.ws.model.entity.SkillHook;
import com.lark.oapi.sample.ws.repository.HookRunRepository;
import com.lark.oapi.sample.ws.repository.SkillFileChangeRepository;
import com.lark.oapi.sample.ws.repository.SkillHookRepository;
import com.lark.oapi.sample.ws.service.SkillHookService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Skill 自进化 Hook 管理 REST API。
 */
@RestController
@RequestMapping("/api/hooks")
public class SkillHookController {

    private static final Logger log = LoggerFactory.getLogger(SkillHookController.class);

    private final SkillHookRepository hookRepo;
    private final HookRunRepository runRepo;
    private final SkillFileChangeRepository changeRepo;
    private final SkillHookService hookService;
    private final com.lark.oapi.sample.ws.service.EmployeeService employeeService;

    public SkillHookController(SkillHookRepository hookRepo, HookRunRepository runRepo,
                               SkillFileChangeRepository changeRepo, SkillHookService hookService,
                               com.lark.oapi.sample.ws.service.EmployeeService employeeService) {
        this.hookRepo = hookRepo;
        this.runRepo = runRepo;
        this.changeRepo = changeRepo;
        this.hookService = hookService;
        this.employeeService = employeeService;
    }

    /** 列表（附带每个 hook 最近一次运行） */
    @GetMapping
    public List<Map<String, Object>> list() {
        return hookRepo.findAll().stream()
                .sorted((a, b) -> Long.compare(b.getId(), a.getId()))
                .map(h -> {
                    Map<String, Object> m = hookMap(h);
                    HookRun last = runRepo.findFirstByHookIdOrderByStartedAtDesc(h.getId());
                    m.put("lastRun", last != null ? runMap(last) : null);
                    return m;
                })
                .toList();
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody SkillHook body, jakarta.servlet.http.HttpServletRequest req) {
        try {
            validate(body);
            // 创建者：数字员工（校验 hook 权限，Jackson 已把 creatorEmployeeId 绑进实体）或管理员用户
            // 身份来自员工令牌 / 面板登录（以前 JSON 里的 creatorEmployeeId 绑不到实体，员工创建会被当成 admin）
            body.setCreatedByEmployeeId(com.lark.oapi.sample.ws.config.RequestIdentity.creator(req, body.getCreatedByEmployeeId()));
            body.setCreatedByEmployeeName(null);
            if (body.getCreatedByEmployeeId() != null) {
                var emp = employeeService.requirePermission(body.getCreatedByEmployeeId(), "hook");
                body.setCreatedByEmployeeName(emp.getName());
            }
            // 实例独占绑定闸门：监控实例 + 维护者实例都不能是员工已独占的（admin 视角/员工视角）
            employeeService.assertInstancesUsable(hookInstanceIds(body), body.getCreatedByEmployeeId());
            body.setId(null);
            SkillHook saved = hookRepo.save(body);
            log.info("[Hook] 创建: id={}, name={}, trigger={}, creator={}", saved.getId(), saved.getName(), saved.getTriggerType(),
                    saved.getCreatedByEmployeeId() != null ? "员工#" + saved.getCreatedByEmployeeId() : "admin");
            return ResponseEntity.ok(hookMap(saved));
        } catch (SecurityException e) {
            return ResponseEntity.status(403).body(Map.of("error", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    /** CSV instanceIds + maintainerInstanceId → 去重 id 列表 */
    private static List<Long> hookInstanceIds(SkillHook h) {
        List<Long> ids = new java.util.ArrayList<>();
        if (h.getInstanceIds() != null) {
            for (String s : h.getInstanceIds().split(",")) {
                try {
                    Long v = Long.parseLong(s.trim());
                    if (!ids.contains(v)) ids.add(v);
                } catch (NumberFormatException ignored) {}
            }
        }
        if (h.getMaintainerInstanceId() != null && !ids.contains(h.getMaintainerInstanceId())) {
            ids.add(h.getMaintainerInstanceId());
        }
        return ids;
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> update(@PathVariable Long id, @RequestBody SkillHook body) {
        return hookRepo.findById(id).map(h -> {
            try {
                validate(body);
                // 实例独占绑定闸门：只校验"新增"的实例（存量监控对象不强制清理，新分配必须未被员工独占）
                List<Long> newIds = hookInstanceIds(body);
                newIds.removeAll(hookInstanceIds(h));
                employeeService.assertInstancesUsable(newIds, h.getCreatedByEmployeeId());
            } catch (IllegalArgumentException e) {
                return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
            }
            h.setName(body.getName());
            h.setIntent(body.getIntent());
            h.setInstanceIds(body.getInstanceIds());
            h.setMaintainerInstanceId(body.getMaintainerInstanceId());
            h.setSkillDir(body.getSkillDir());
            h.setTriggerType(body.getTriggerType());
            h.setCronExpr(body.getCronExpr());
            h.setEnabled(body.isEnabled());
            SkillHook saved = hookRepo.save(h);
            hookService.invalidateSession(id);   // 配置可能变了，下次触发按新配置重建会话
            log.info("[Hook] 更新: id={}", id);
            return ResponseEntity.ok(hookMap(saved));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!hookRepo.existsById(id)) return ResponseEntity.notFound().build();
        hookService.deleteHook(id);
        log.info("[Hook] 删除: id={}", id);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<?> toggle(@PathVariable Long id) {
        return hookRepo.findById(id).map(h -> {
            h.setEnabled(!h.isEnabled());
            hookRepo.save(h);
            return ResponseEntity.ok(hookMap(h));
        }).orElse(ResponseEntity.notFound().build());
    }

    /** 手动触发（异步执行，立即返回） */
    @PostMapping("/{id}/run")
    public ResponseEntity<?> run(@PathVariable Long id) {
        try {
            hookService.runManually(id);
            return ResponseEntity.ok(Map.of("ok", true, "message", "已触发，执行完成后可在执行记录中查看"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/{id}/runs")
    public List<Map<String, Object>> runs(@PathVariable Long id) {
        return runRepo.findTop50ByHookIdOrderByStartedAtDesc(id).stream().map(this::runMap).toList();
    }

    /** skill 修改流水（跨 hook） */
    @GetMapping("/changes")
    public List<SkillFileChange> changes() {
        return changeRepo.findTop100ByOrderByAtDesc();
    }

    // ===== 工具 =====

    private void validate(SkillHook h) {
        if (h.getName() == null || h.getName().isBlank()) throw new IllegalArgumentException("请填写 Hook 名称");
        if (h.getIntent() == null || h.getIntent().isBlank()) throw new IllegalArgumentException("请填写维护意图（本 skill 要沉淀什么业务的经验）");
        if (h.getInstanceIds() == null || h.getInstanceIds().isBlank()) throw new IllegalArgumentException("请至少选择一个监控实例");
        if (h.getSkillDir() == null || h.getSkillDir().isBlank()) throw new IllegalArgumentException("请填写 skill 目录");
        if (!new java.io.File(h.getSkillDir()).isDirectory()) throw new IllegalArgumentException("skill 目录不存在: " + h.getSkillDir());
        if ("CRON".equals(h.getTriggerType()) && (h.getCronExpr() == null || h.getCronExpr().isBlank())) {
            throw new IllegalArgumentException("定时触发需要 cron 表达式");
        }
        if ("CRON".equals(h.getTriggerType())) {
            try {
                org.springframework.scheduling.support.CronExpression.parse(normalizeCron(h.getCronExpr()));
            } catch (Exception e) {
                throw new IllegalArgumentException("cron 表达式无效: " + h.getCronExpr());
            }
        }
    }

    private static String normalizeCron(String expr) {
        String e = expr != null ? expr.trim() : "";
        if (e.split("\\s+").length == 5) e = "0 " + e;
        return e;
    }

    private Map<String, Object> hookMap(SkillHook h) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", h.getId());
        m.put("name", h.getName());
        m.put("intent", h.getIntent());
        m.put("instanceIds", h.getInstanceIds());
        m.put("maintainerInstanceId", h.getMaintainerInstanceId());
        m.put("skillDir", h.getSkillDir());
        m.put("triggerType", h.getTriggerType());
        m.put("cronExpr", h.getCronExpr());
        m.put("enabled", h.isEnabled());
        m.put("creatorEmployeeId", h.getCreatedByEmployeeId());
        m.put("creatorEmployeeName", h.getCreatedByEmployeeName());
        m.put("lastRunAt", h.getLastRunAt());
        m.put("createdAt", h.getCreatedAt());
        return m;
    }

    private Map<String, Object> runMap(HookRun r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", r.getId());
        m.put("hookId", r.getHookId());
        m.put("triggerType", r.getTriggerType());
        m.put("status", r.getStatus());
        m.put("windowFrom", r.getWindowFrom());
        m.put("windowTo", r.getWindowTo());
        m.put("messageCount", r.getMessageCount());
        m.put("judgeReason", r.getJudgeReason());
        m.put("summary", r.getSummary());
        m.put("durationMs", r.getDurationMs());
        m.put("startedAt", r.getStartedAt());
        return m;
    }
}
