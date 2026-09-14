package com.lark.oapi.sample.ws.controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.model.entity.DigitalEmployee;
import com.lark.oapi.sample.ws.model.entity.EmployeeDemand;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.DigitalEmployeeRepository;
import com.lark.oapi.sample.ws.repository.EmployeeDemandRepository;
import com.lark.oapi.sample.ws.service.EmployeeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数字员工 REST API。
 * 请求体统一收原始 String 用 Gson 解析（Spring 默认 Jackson 与 Gson 类型不互通，
 * 直接 @RequestBody JsonObject 会被静默反序列化成空对象——既有约定）。
 * 详情页 3s 轮询 GET /api/employees/{id}（RUNNING 需求内嵌实时进度快照，一份响应搞定）。
 */
@RestController
@RequestMapping("/api/employees")
public class EmployeeController {

    private static final Logger log = LoggerFactory.getLogger(EmployeeController.class);

    private final EmployeeService employeeService;
    private final DigitalEmployeeRepository employeeRepo;
    private final EmployeeDemandRepository demandRepo;
    private final com.lark.oapi.sample.ws.repository.EmployeeMessageRepository msgRepo;
    private final BotInstanceRepository instanceRepo;

    public EmployeeController(EmployeeService employeeService,
                              DigitalEmployeeRepository employeeRepo,
                              EmployeeDemandRepository demandRepo,
                              com.lark.oapi.sample.ws.repository.EmployeeMessageRepository msgRepo,
                              BotInstanceRepository instanceRepo) {
        this.employeeService = employeeService;
        this.employeeRepo = employeeRepo;
        this.demandRepo = demandRepo;
        this.msgRepo = msgRepo;
        this.instanceRepo = instanceRepo;
    }

    // ===== 员工 CRUD =====

    /** 员工列表（含需求统计与运行中摘要） */
    @GetMapping(value = "", produces = "application/json;charset=UTF-8")
    public String list() {
        Map<Long, String> instNames = instanceNames();
        JsonArray out = new JsonArray();
        for (DigitalEmployee e : employeeService.listEmployees()) {
            List<EmployeeDemand> demands = demandRepo.findByEmployeeIdOrderByCreatedAtAsc(e.getId());
            JsonObject o = employeeJson(e, instNames);
            JsonObject stats = new JsonObject();
            int queued = 0, running = 0, done = 0, failed = 0;
            JsonArray runningSummary = new JsonArray();
            for (EmployeeDemand d : demands) {
                switch (d.getStatus()) {
                    case "QUEUED" -> queued++;
                    case "RUNNING" -> {
                        running++;
                        JsonObject rs = new JsonObject();
                        rs.addProperty("demandId", d.getId());
                        rs.addProperty("title", d.getTitle());
                        rs.addProperty("instanceName", d.getInstanceId() != null
                                ? instNames.getOrDefault(d.getInstanceId(), "#" + d.getInstanceId()) : "");
                        runningSummary.add(rs);
                    }
                    case "DONE" -> done++;
                    case "FAILED", "INTERRUPTED" -> failed++;
                    default -> {}
                }
            }
            stats.addProperty("queued", queued);
            stats.addProperty("running", running);
            stats.addProperty("done", done);
            stats.addProperty("failed", failed);
            o.add("stats", stats);
            o.add("runningSummary", runningSummary);
            // 未读上报消息数（前端红点）
            o.addProperty("unreadCount", msgRepo.countByEmployeeIdAndReadFlagFalse(e.getId()));
            out.add(o);
        }
        return out.toString();
    }

    @PostMapping(value = "", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> create(@RequestBody String rawBody) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            DigitalEmployee e = employeeService.createEmployee(
                    opt(body, "name"), opt(body, "emoji"), opt(body, "roleDesc"),
                    longList(body, "instanceIds"), strList(body, "permissions"),
                    body.has("reportIntervalMin") && !body.get("reportIntervalMin").isJsonNull()
                            ? body.get("reportIntervalMin").getAsInt() : null);
            return ResponseEntity.ok(employeeJson(e, instanceNames()).toString());
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        } catch (Exception ex) {
            log.error("[员工API] 创建失败", ex);
            return badRequest("创建失败: " + ex.getMessage());
        }
    }

    /** 员工详情 = 员工 + 全部需求（RUNNING 内嵌实时进度快照 + 实例名回填） */
    @GetMapping(value = "/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> detail(@PathVariable Long id) {
        DigitalEmployee e = employeeService.getEmployee(id);
        if (e == null) return ResponseEntity.notFound().build();
        Map<Long, String> instNames = instanceNames();
        JsonObject out = new JsonObject();
        out.add("employee", employeeJson(e, instNames));
        JsonArray demands = new JsonArray();
        // 新需求在后（下发序）；前端表格自行倒序展示
        for (EmployeeDemand d : demandRepo.findByEmployeeIdOrderByCreatedAtAsc(id)) {
            JsonObject o = demandJson(d, instNames);
            if ("RUNNING".equals(d.getStatus())) {
                JsonObject p = employeeService.progressJson(d.getId(), d.getInstanceId());
                if (p != null) o.add("progress", p);
            }
            demands.add(o);
        }
        out.add("demands", demands);
        // 上报消息（聊天框数据源，新→旧前 50 条，前端自行倒序渲染）+ 未读数
        JsonArray msgs = new JsonArray();
        for (var m : msgRepo.findTop100ByEmployeeIdOrderByCreatedAtDesc(id).stream().limit(50).toList()) {
            JsonObject mo = new JsonObject();
            mo.addProperty("id", m.getId());
            mo.addProperty("type", m.getType());
            mo.addProperty("sender", m.getSender());   // employee | boss（聊天框双向气泡）
            mo.addProperty("content", m.getContent());
            if (m.getDemandId() != null) mo.addProperty("demandId", m.getDemandId());
            mo.addProperty("read", m.isReadFlag());
            mo.addProperty("feishuPushed", m.isFeishuPushed());
            mo.addProperty("createdAt", timeStr(m.getCreatedAt()));
            msgs.add(mo);
        }
        out.add("messages", msgs);
        out.addProperty("unreadCount", msgRepo.countByEmployeeIdAndReadFlagFalse(id));
        // 进行中的聊天轮次进度（"员工正在回复…"指示器）
        JsonObject chat = employeeService.chatProgressJson(id);
        if (chat != null) {
            if (chat.has("instanceId") && !chat.get("instanceId").isJsonNull()) {
                chat.addProperty("instanceName", instNames.getOrDefault(chat.get("instanceId").getAsLong(), ""));
            }
            out.add("chatProgress", chat);
        }
        return ResponseEntity.ok(out.toString());
    }

    /** 老板 → 员工聊天框消息：员工在绑定实例上真实执行一轮（可干活、共享需求会话记忆），回复进聊天框+飞书 */
    @PostMapping(value = "/{id}/chat", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> chat(@PathVariable Long id, @RequestBody String rawBody) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            Long demandId = body.has("demandId") && !body.get("demandId").isJsonNull()
                    ? body.get("demandId").getAsLong() : null;
            employeeService.sendChat(id, opt(body, "text"), demandId);
            return ResponseEntity.ok("{\"ok\":true}");
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }
    }

    /** 全局未读上报数（侧边栏 🏭 红点数字，App 级轮询） */
    @GetMapping(value = "/messages/unread", produces = "application/json;charset=UTF-8")
    public String globalUnread() {
        JsonObject o = new JsonObject();
        o.addProperty("total", msgRepo.countByReadFlagFalse());
        return o.toString();
    }

    /** 打开员工聊天框 → 全部已读（红点清零） */
    @PostMapping(value = "/{id}/messages/read", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> markRead(@PathVariable Long id) {
        if (employeeService.getEmployee(id) == null) return ResponseEntity.notFound().build();
        int n = employeeService.markMessagesRead(id);
        return ResponseEntity.ok("{\"ok\":true,\"marked\":" + n + "}");
    }

    @PutMapping(value = "/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> update(@PathVariable Long id, @RequestBody String rawBody) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            DigitalEmployee e = employeeService.updateEmployee(id,
                    body.has("name") ? opt(body, "name") : null,
                    body.has("emoji") ? opt(body, "emoji") : null,
                    body.has("roleDesc") ? opt(body, "roleDesc") : null,
                    body.has("instanceIds") ? longList(body, "instanceIds") : null,
                    body.has("permissions") ? strList(body, "permissions") : null,
                    body.has("reportIntervalMin") && !body.get("reportIntervalMin").isJsonNull()
                            ? body.get("reportIntervalMin").getAsInt() : null);
            return ResponseEntity.ok(employeeJson(e, instanceNames()).toString());
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }
    }

    @DeleteMapping(value = "/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> delete(@PathVariable Long id) {
        try {
            employeeService.deleteEmployee(id);
            return ResponseEntity.ok("{\"ok\":true}");
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    // ===== 需求 =====

    /** 下发需求（自动入队并立即调度） */
    @PostMapping(value = "/{id}/demands", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> dispatch(@PathVariable Long id, @RequestBody String rawBody) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            Long instanceId = body.has("instanceId") && !body.get("instanceId").isJsonNull()
                    ? body.get("instanceId").getAsLong() : null;
            EmployeeDemand d = employeeService.dispatchDemand(id, opt(body, "requirement"), opt(body, "title"), instanceId);
            return ResponseEntity.ok(demandJson(d, instanceNames()).toString());
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }
    }

    @PostMapping(value = "/demands/{did}/cancel", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> cancel(@PathVariable Long did) {
        try {
            employeeService.cancelDemand(did);
            return ResponseEntity.ok("{\"ok\":true}");
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }
    }

    @PostMapping(value = "/demands/{did}/retry", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> retry(@PathVariable Long did) {
        try {
            EmployeeDemand d = employeeService.retryDemand(did);
            return ResponseEntity.ok(demandJson(d, instanceNames()).toString());
        } catch (IllegalArgumentException ex) {
            return badRequest(ex.getMessage());
        }
    }

    // ===== JSON 组装 =====

    private JsonObject employeeJson(DigitalEmployee e, Map<Long, String> instNames) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.getId());
        o.addProperty("name", e.getName());
        o.addProperty("emoji", e.getEmoji());
        o.addProperty("roleDesc", e.getRoleDesc());
        o.addProperty("enabled", e.isEnabled());
        o.addProperty("reportIntervalMin", e.getReportIntervalMin());
        JsonArray perms = new JsonArray();
        for (String p : e.permList()) perms.add(p);
        o.add("permissions", perms);
        JsonArray ids = new JsonArray();
        JsonArray insts = new JsonArray();
        for (Long iid : e.instanceIdList()) {
            ids.add(iid);
            JsonObject io = new JsonObject();
            io.addProperty("id", iid);
            io.addProperty("name", instNames.getOrDefault(iid, "#" + iid + "(已删除)"));
            insts.add(io);
        }
        o.add("instanceIds", ids);
        o.add("instances", insts);
        o.addProperty("createdAt", timeStr(e.getCreatedAt()));
        return o;
    }

    private JsonObject demandJson(EmployeeDemand d, Map<Long, String> instNames) {
        JsonObject o = new JsonObject();
        o.addProperty("id", d.getId());
        o.addProperty("employeeId", d.getEmployeeId());
        o.addProperty("title", d.getTitle());
        o.addProperty("requirement", d.getRequirement());
        o.addProperty("status", d.getStatus());
        if (d.getInstanceId() != null) {
            o.addProperty("instanceId", d.getInstanceId());
            o.addProperty("instanceName", instNames.getOrDefault(d.getInstanceId(), "#" + d.getInstanceId()));
        }
        if (d.getRequestedInstanceId() != null) o.addProperty("requestedInstanceId", d.getRequestedInstanceId());
        o.addProperty("result", d.getResult() != null ? d.getResult() : "");
        o.addProperty("errorMessage", d.getErrorMessage() != null ? d.getErrorMessage() : "");
        o.addProperty("createdAt", timeStr(d.getCreatedAt()));
        o.addProperty("startedAt", timeStr(d.getStartedAt()));
        o.addProperty("finishedAt", timeStr(d.getFinishedAt()));
        o.addProperty("elapsedMs", EmployeeService.elapsedMsOf(d));
        return o;
    }

    private Map<Long, String> instanceNames() {
        Map<Long, String> m = new HashMap<>();
        for (BotInstance i : instanceRepo.findAll()) m.put(i.getId(), i.getName());
        return m;
    }

    private static String timeStr(LocalDateTime t) {
        return t != null ? t.toString() : "";
    }

    private static String opt(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : null;
    }

    private static List<Long> longList(JsonObject o, String key) {
        List<Long> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (var el : o.getAsJsonArray(key)) {
                try { out.add(el.getAsLong()); } catch (Exception ignored) {}
            }
        }
        return out;
    }

    private static List<String> strList(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o.has(key) && o.get(key).isJsonArray()) {
            for (var el : o.getAsJsonArray(key)) {
                try { out.add(el.getAsString()); } catch (Exception ignored) {}
            }
        }
        return out;
    }

    private ResponseEntity<String> badRequest(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg != null ? msg : "请求无效");
        return ResponseEntity.badRequest().body(o.toString());
    }
}
