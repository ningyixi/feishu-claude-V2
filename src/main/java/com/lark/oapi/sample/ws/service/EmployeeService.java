package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.model.entity.DigitalEmployee;
import com.lark.oapi.sample.ws.model.entity.EmployeeDemand;
import com.lark.oapi.sample.ws.model.entity.EmployeeMessage;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.DigitalEmployeeRepository;
import com.lark.oapi.sample.ws.repository.EmployeeDemandRepository;
import com.lark.oapi.sample.ws.repository.EmployeeMessageRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数字员工服务 — 员工 CRUD + 需求队列的自动并行调度。
 * <p>
 * 调度模型（用户拍板：自动并行）：
 * <ul>
 *   <li>需求下发即入队（QUEUED），{@link #scheduleQueues} 把最老的需求配对到空闲绑定实例</li>
 *   <li>每员工每实例同一时刻只跑一个需求；绑定 N 个实例 = 最多 N 个需求并行</li>
 *   <li>手动指定实例的需求只等它的实例空闲；自动分派按绑定列表顺序取第一个空闲实例</li>
 *   <li>执行 = 实例常驻会话上的一个 turn（与飞书私聊/E2E/供应商工作台同一条路径，
 *       turnLock 天然串行——跨员工/跨功能共用实例时排队，进度快照展示 queueDepth）</li>
 *   <li>一个需求完成 → finally 链式触发下一次调度</li>
 * </ul>
 * 实时进度：内存 ProgressSnapshot（TurnListener 采集），前端 3s 轮询经 REST 读取；
 * 终态与结果落库，重启后 RUNNING → INTERRUPTED（可重试），QUEUED 自动重新调度。
 * <p>
 * v1 需求不支持 workingDir/model 覆盖（一律用实例配置）：getOrCreate 发现变化会
 * destroy 重建会话——杀活跃轮次 + 丢整个 CLI 上下文，覆盖字段是脚枪。
 */
@Service
public class EmployeeService {

    private static final Logger log = LoggerFactory.getLogger(EmployeeService.class);

    /** 排队超过该分钟数视为"卡住"，主动上报一次 */
    private static final int STUCK_QUEUE_MIN = 30;

    private final DigitalEmployeeRepository employeeRepo;
    private final EmployeeDemandRepository demandRepo;
    private final EmployeeMessageRepository msgRepo;
    private final BotInstanceRepository instanceRepo;
    private final ClaudeCodeService claudeCodeService;
    private final FeishuBotService feishuBotService;

    /** demandId → 实时进度快照（内存态，终态即移除） */
    private final Map<Long, ProgressSnapshot> progress = new ConcurrentHashMap<>();

    public EmployeeService(DigitalEmployeeRepository employeeRepo,
                           EmployeeDemandRepository demandRepo,
                           EmployeeMessageRepository msgRepo,
                           BotInstanceRepository instanceRepo,
                           ClaudeCodeService claudeCodeService,
                           FeishuBotService feishuBotService) {
        this.employeeRepo = employeeRepo;
        this.demandRepo = demandRepo;
        this.msgRepo = msgRepo;
        this.instanceRepo = instanceRepo;
        this.claudeCodeService = claudeCodeService;
        this.feishuBotService = feishuBotService;
    }

    // ===== 员工 CRUD =====

    public List<DigitalEmployee> listEmployees() {
        return employeeRepo.findAll();
    }

    public DigitalEmployee getEmployee(Long id) {
        return employeeRepo.findById(id).orElse(null);
    }

    public DigitalEmployee createEmployee(String name, String emoji, String roleDesc, List<Long> instanceIds,
                                          List<String> permissions, Integer reportIntervalMin,
                                          String workSpec, Boolean autoPr, Long notifyInstanceId) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("请填写员工名称");
        assertInstancesUnbound(instanceIds, null);   // 独占绑定：不得抢其他员工已绑定的实例
        DigitalEmployee e = new DigitalEmployee();
        e.setName(name.trim());
        e.setEmoji(emoji != null && !emoji.isBlank() ? emoji.trim() : "👤");
        e.setRoleDesc(roleDesc != null ? roleDesc.trim() : "");
        e.setInstanceIdList(instanceIds);
        e.setPermList(permissions);
        e.setReportIntervalMin(clampInterval(reportIntervalMin));
        e.setWorkSpec(workSpec != null ? workSpec : "");
        e.setAutoPr(autoPr != null && autoPr);
        e.setNotifyInstanceId(notifyInstanceId != null && notifyInstanceId > 0 ? notifyInstanceId : null);
        employeeRepo.save(e);
        applySandboxOnBind(e.instanceIdList(), List.of());   // 绑定即上锁
        log.info("[员工] 创建数字员工: id={}, name={}, 绑定实例={}, 权限={}, 定期汇报={}min, autoPr={}, spec={}字",
                e.getId(), e.getName(), e.getInstanceIds(), e.getPermissions(), e.getReportIntervalMin(),
                e.isAutoPr(), e.getWorkSpec() != null ? e.getWorkSpec().length() : 0);
        return e;
    }

    public DigitalEmployee updateEmployee(Long id, String name, String emoji, String roleDesc, List<Long> instanceIds,
                                          List<String> permissions, Integer reportIntervalMin,
                                          String workSpec, Boolean autoPr, Long notifyInstanceId) {
        DigitalEmployee e = employeeRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("员工不存在: " + id));
        if (name != null && !name.isBlank()) e.setName(name.trim());
        if (emoji != null && !emoji.isBlank()) e.setEmoji(emoji.trim());
        if (roleDesc != null) e.setRoleDesc(roleDesc.trim());
        if (permissions != null) e.setPermList(permissions);
        if (reportIntervalMin != null) e.setReportIntervalMin(clampInterval(reportIntervalMin));
        if (workSpec != null) e.setWorkSpec(workSpec);
        if (autoPr != null) e.setAutoPr(autoPr);
        if (notifyInstanceId != null) e.setNotifyInstanceId(notifyInstanceId > 0 ? notifyInstanceId : null);   // 0 = 恢复自动选择
        if (instanceIds != null) {
            assertInstancesUnbound(instanceIds, id);   // 独占绑定：排除自己后不得占用其他员工的实例
            List<Long> oldIds = e.instanceIdList();
            e.setInstanceIdList(instanceIds);
            List<Long> added = new ArrayList<>(instanceIds);
            added.removeAll(oldIds);
            List<Long> removed = new ArrayList<>(oldIds);
            removed.removeAll(instanceIds);
            applySandboxOnBind(added, removed);   // 新绑上锁、解绑恢复
            // 绑定变化后：手动指定了已被解绑实例的排队需求直接判死（否则会永远等不到）
            for (EmployeeDemand d : demandRepo.findByEmployeeIdAndStatusInOrderByCreatedAtAsc(id, List.of("QUEUED"))) {
                Long want = d.getRequestedInstanceId();
                if (want != null && !e.instanceIdList().contains(want)) {
                    d.setStatus("FAILED");
                    d.setErrorMessage("指定的实例已被解除绑定");
                    d.setFinishedAt(LocalDateTime.now());
                    demandRepo.save(d);
                }
            }
        }
        employeeRepo.save(e);
        scheduleQueues(id);
        return e;
    }

    @Transactional
    public void deleteEmployee(Long id) {
        DigitalEmployee e = employeeRepo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("员工不存在: " + id));
        // 运行中的需求 best-effort 中止（不再调度新的）
        for (EmployeeDemand d : demandRepo.findByEmployeeIdOrderByCreatedAtAsc(id)) {
            if ("RUNNING".equals(d.getStatus())) {
                d.setStatus("CANCELLED");
                d.setErrorMessage("员工已被删除");
                d.setFinishedAt(LocalDateTime.now());
                demandRepo.save(d);
                abortIfTurnStarted(d);
            }
        }
        applySandboxOnBind(List.of(), e.instanceIdList());   // 删除员工 = 全部解绑，实例恢复全权限
        demandRepo.deleteByEmployeeId(id);
        msgRepo.deleteByEmployeeId(id);
        employeeRepo.delete(e);
        log.info("[员工] 删除数字员工(含需求与上报消息): id={}", id);
    }

    // ===== 权限控制 =====

    /** 权限 key → 中文标签 */
    public static final Map<String, String> PERM_LABELS = Map.of(
            "e2e", "创建 E2E 测试任务",
            "hook", "创建 Hook 任务",
            "supplier", "创建供应商工作");

    /**
     * 创建类端点的权限闸门：员工必须存在且持有该权限，否则抛 IllegalArgumentException（→ 400）。
     * 返回员工实体供调用方回填创建者姓名快照。
     */
    public DigitalEmployee requirePermission(Long empId, String perm) {
        DigitalEmployee emp = empId != null ? employeeRepo.findById(empId).orElse(null) : null;
        if (emp == null) throw new IllegalArgumentException("数字员工不存在: " + empId);
        if (!emp.permList().contains(perm)) {
            throw new IllegalArgumentException("数字员工「" + emp.getName() + "」没有【"
                    + PERM_LABELS.getOrDefault(perm, perm) + "】权限");
        }
        return emp;
    }

    // ===== 实例独占绑定 =====

    /** instanceId → 占用它的员工（未占用返回 null）；excludeEmpId 用于员工编辑时排除自己 */
    private DigitalEmployee occupiedBy(Long instanceId, Long excludeEmpId) {
        if (instanceId == null) return null;
        for (DigitalEmployee e : employeeRepo.findAll()) {
            if (excludeEmpId != null && excludeEmpId.equals(e.getId())) continue;
            if (e.instanceIdList().contains(instanceId)) return e;
        }
        return null;
    }

    /** 员工创建/编辑闸门：绑定的实例不得已被其他员工独占（一个实例只属于一个员工，先解绑再分配） */
    private void assertInstancesUnbound(List<Long> ids, Long selfEmployeeId) {
        if (ids == null) return;
        for (Long iid : ids) {
            DigitalEmployee other = occupiedBy(iid, selfEmployeeId);
            if (other != null) {
                throw new IllegalArgumentException("实例「" + instanceName(iid) + "」已绑定数字员工「"
                        + other.getName() + "」——一个实例只能分配给一个员工，请先在该员工处解绑");
            }
        }
    }

    /**
     * 三大创建端点（E2E 任务/Hook/供应商工作）的实例可用性闸门：
     * <ul>
     *   <li>admin（creatorEmployeeId=null）：不能使用已被员工独占的实例</li>
     *   <li>数字员工：只能使用自己绑定列表内的实例</li>
     * </ul>
     */
    public void assertInstancesUsable(List<Long> ids, Long creatorEmployeeId) {
        if (ids == null || ids.isEmpty()) return;
        DigitalEmployee creator = null;
        if (creatorEmployeeId != null) {
            creator = employeeRepo.findById(creatorEmployeeId)
                    .orElseThrow(() -> new IllegalArgumentException("数字员工不存在: " + creatorEmployeeId));
        }
        for (Long iid : ids) {
            if (iid == null) continue;
            DigitalEmployee occ = occupiedBy(iid, null);
            if (creator == null) {
                if (occ != null) {
                    throw new IllegalArgumentException("实例「" + instanceName(iid) + "」已被数字员工「"
                            + occ.getName() + "」独占绑定，不能再分配给其他人（包括 admin）；请先在该员工处解绑");
                }
            } else if (!creator.instanceIdList().contains(iid)) {
                throw new IllegalArgumentException("实例「" + instanceName(iid) + "」不在数字员工「"
                        + creator.getName() + "」的绑定范围内（员工只能使用自己绑定的实例"
                        + (occ != null && !occ.getId().equals(creator.getId()) ? "，且该实例属于「" + occ.getName() + "」" : "")
                        + "）");
            }
        }
    }

    // ===== 需求下发与调度 =====

    public EmployeeDemand dispatchDemand(Long employeeId, String requirement, String title, Long instanceId) {
        DigitalEmployee emp = employeeRepo.findById(employeeId)
                .orElseThrow(() -> new IllegalArgumentException("员工不存在: " + employeeId));
        if (requirement == null || requirement.isBlank()) throw new IllegalArgumentException("请填写需求内容");
        if (instanceId != null && !emp.instanceIdList().contains(instanceId)) {
            throw new IllegalArgumentException("指定的实例不在该员工的绑定列表里");
        }
        EmployeeDemand d = new EmployeeDemand();
        d.setEmployeeId(employeeId);
        d.setRequirement(requirement.trim());
        String t = title != null ? title.trim() : "";
        d.setTitle(!t.isBlank() ? t
                : requirement.trim().replaceAll("\\s+", " ").substring(0, Math.min(30, requirement.trim().length())));
        d.setRequestedInstanceId(instanceId);
        d.setStatus("QUEUED");
        demandRepo.save(d);
        log.info("[员工] 需求下发: emp={}, demand={}, instance={}", employeeId, d.getId(),
                instanceId != null ? "指定#" + instanceId : "自动分派");
        scheduleQueues(employeeId);
        return d;
    }

    /**
     * 队列调度（synchronized：创建/完成/重试/重启四入口并发触发，以 DB 状态为准重查防双派发）。
     * 配对逻辑：RUNNING 占用的实例 → 排除；QUEUED 按创建序逐个找目标实例
     * （手动指定 = 只等指定实例；自动 = 绑定列表第一个空闲且存在的实例）。
     */
    public synchronized void scheduleQueues(Long employeeId) {
        DigitalEmployee emp = employeeRepo.findById(employeeId).orElse(null);
        if (emp == null) return;
        List<Long> bound = emp.instanceIdList();
        Set<Long> busy = new HashSet<>();
        for (EmployeeDemand d : demandRepo.findByEmployeeIdAndStatusInOrderByCreatedAtAsc(employeeId, List.of("RUNNING"))) {
            if (d.getInstanceId() != null) busy.add(d.getInstanceId());
        }
        for (EmployeeDemand d : demandRepo.findByEmployeeIdAndStatusInOrderByCreatedAtAsc(employeeId, List.of("QUEUED"))) {
            Long want = d.getRequestedInstanceId();
            Long target = null;
            if (want != null) {
                if (!bound.contains(want)) {
                    d.setStatus("FAILED");
                    d.setErrorMessage("指定的实例已被解除绑定");
                    d.setFinishedAt(LocalDateTime.now());
                    demandRepo.save(d);
                    continue;
                }
                if (!busy.contains(want)) target = want;
            } else {
                for (Long iid : bound) {
                    if (!busy.contains(iid) && instanceRepo.existsById(iid)) { target = iid; break; }
                }
            }
            if (target == null) continue;   // 无空闲实例，继续排队
            busy.add(target);
            startDemand(emp, d, target);
        }
    }

    /** 启动一个需求：先落库 RUNNING（防双派发）再起虚拟线程执行 */
    private void startDemand(DigitalEmployee emp, EmployeeDemand d, Long instanceId) {
        BotInstance inst = instanceRepo.findById(instanceId).orElse(null);
        if (inst == null) {
            d.setStatus("FAILED");
            d.setErrorMessage("绑定实例不存在: " + instanceId);
            d.setFinishedAt(LocalDateTime.now());
            demandRepo.save(d);
            return;
        }
        d.setInstanceId(instanceId);
        d.setStatus("RUNNING");
        d.setStartedAt(LocalDateTime.now());
        d.setErrorMessage(null);
        demandRepo.save(d);
        log.info("[员工] 需求开工: emp={}, demand={}, instance={}「{}」", emp.getId(), d.getId(), instanceId, inst.getName());

        String prompt = personaPrompt(emp, d);
        ProgressListener listener = new ProgressListener(d.getId(), instanceId);
        final Long demandId = d.getId();
        final Long empId = emp.getId();
        Thread.ofVirtual().name("emp-demand-" + demandId).start(() -> {
            String dir = firstNonBlank(inst.getClaudeWorkingDir(), inst.getShellWorkingDir(), "");
            try {
                String reply = claudeCodeService.chat(instanceId, "employee-" + empId + "-d" + demandId,
                        prompt, null, dir, inst.getClaudeModel(), listener);
                if (reply != null && (reply.startsWith("❌") || reply.startsWith("⏰"))) {
                    markFailed(demandId, reply);
                } else {
                    markDone(demandId, reply != null ? reply : "");
                }
            } catch (Exception e) {
                log.warn("[员工] 需求执行异常: demand={}, {}", demandId, e.getMessage());
                markFailed(demandId, "执行异常: " + e.getMessage());
            } finally {
                progress.remove(demandId);
                try { scheduleQueues(empId); } catch (Exception e) {
                    log.warn("[员工] 链式调度失败: emp={}, {}", empId, e.getMessage());
                }
            }
        });
    }

    /** 终态落库带守卫：需求可能已被取消/员工被删——非 RUNNING 不覆盖 */
    private void markDone(Long demandId, String result) {
        demandRepo.findById(demandId).ifPresent(d -> {
            if (!"RUNNING".equals(d.getStatus())) return;
            d.setStatus("DONE");
            d.setResult(result);
            d.setFinishedAt(LocalDateTime.now());
            demandRepo.save(d);
            log.info("[员工] 需求完成: demand={}, 结果 {} 字", demandId, result.length());
            notifyDemandFinished(d, result);
        });
    }

    private void markFailed(Long demandId, String error) {
        demandRepo.findById(demandId).ifPresent(d -> {
            if (!"RUNNING".equals(d.getStatus())) return;
            d.setStatus("FAILED");
            d.setErrorMessage(truncate(error, 1000));
            d.setFinishedAt(LocalDateTime.now());
            demandRepo.save(d);
            DigitalEmployee emp = employeeRepo.findById(d.getEmployeeId()).orElse(null);
            if (emp != null) {
                postMessage(emp, "FAILED", "❌ 需求「" + d.getTitle() + "」执行失败：" + d.getErrorMessage()
                        + "\n可到面板查看详情并重试。", d.getId(), d.getInstanceId());
            }
        });
    }

    /** 完成汇报 → REPORT 消息；汇报里「需要老板支持」有实际内容 → 追加 SUPPORT 消息 */
    private void notifyDemandFinished(EmployeeDemand d, String result) {
        DigitalEmployee emp = employeeRepo.findById(d.getEmployeeId()).orElse(null);
        if (emp == null) return;
        long min = d.getStartedAt() != null ? Duration.between(d.getStartedAt(), LocalDateTime.now()).toMinutes() : 0;
        String head = "✅ 需求「" + d.getTitle() + "」已完成（实例 " + instanceName(d.getInstanceId())
                + (min > 0 ? "，耗时 " + min + " 分钟" : "") + "）\n";
        String excerpt = result != null && result.length() > 800
                ? result.substring(0, 800) + "\n…（完整汇报见面板）"
                : (result != null ? result : "");
        postMessage(emp, "REPORT", head + excerpt, d.getId(), d.getInstanceId());
        String support = parseSupportNeed(result);
        if (support != null) {
            postMessage(emp, "SUPPORT", "🙋 需求「" + d.getTitle() + "」员工需要你的支持：\n" + support, d.getId(), d.getInstanceId());
        }
    }

    public void cancelDemand(Long demandId) {
        EmployeeDemand d = demandRepo.findById(demandId)
                .orElseThrow(() -> new IllegalArgumentException("需求不存在: " + demandId));
        switch (d.getStatus()) {
            case "QUEUED" -> {
                d.setStatus("CANCELLED");
                d.setFinishedAt(LocalDateTime.now());
                demandRepo.save(d);
            }
            case "RUNNING" -> {
                d.setStatus("CANCELLED");
                d.setErrorMessage("用户取消（已请求停止）");
                d.setFinishedAt(LocalDateTime.now());
                demandRepo.save(d);
                abortIfTurnStarted(d);
            }
            default -> throw new IllegalArgumentException("当前状态不可取消: " + d.getStatus());
        }
        scheduleQueues(d.getEmployeeId());
    }

    /**
     * best-effort 中止：仅当该需求的 turn 已真正开始才 abortCurrentTurn——
     * abort 中止的是实例会话的"当前轮次"，若需求还在会话队列里排队，
     * 当前轮次属于别的功能（飞书/E2E…），绝不能误杀。
     * 排队中被取消的需求由 {@link ProgressListener#onTurnStart} 在轮次开始瞬间自行中止。
     */
    private void abortIfTurnStarted(EmployeeDemand d) {
        ProgressSnapshot snap = progress.get(d.getId());
        if (snap != null && snap.turnStarted && d.getInstanceId() != null) {
            TerminalSession s = claudeCodeService.getSession(d.getInstanceId());
            if (s != null) s.abortCurrentTurn();
        }
    }

    public EmployeeDemand retryDemand(Long demandId) {
        EmployeeDemand d = demandRepo.findById(demandId)
                .orElseThrow(() -> new IllegalArgumentException("需求不存在: " + demandId));
        if ("RUNNING".equals(d.getStatus()) || "QUEUED".equals(d.getStatus())) {
            throw new IllegalArgumentException("需求正在执行/排队中，无需重试");
        }
        if ("DONE".equals(d.getStatus())) {
            throw new IllegalArgumentException("已完成的需求不能重试（请重新下发）");
        }
        d.setStatus("QUEUED");
        d.setResult(null);
        d.setErrorMessage(null);
        d.setInstanceId(null);          // 实际映射清空，重新分派（手动指定保留）
        d.setStuckReported(false);      // 重新排队 → 卡住提醒资格复位
        d.setStartedAt(null);
        d.setFinishedAt(null);
        demandRepo.save(d);
        scheduleQueues(d.getEmployeeId());
        return d;
    }

    // ===== 重启恢复 =====

    @PostConstruct
    public void reconstruct() {
        try {
            List<EmployeeDemand> running = demandRepo.findByStatus("RUNNING");
            for (EmployeeDemand d : running) {
                d.setStatus("INTERRUPTED");
                d.setErrorMessage("应用重启被中断");
                d.setFinishedAt(LocalDateTime.now());
                demandRepo.save(d);
                DigitalEmployee emp = employeeRepo.findById(d.getEmployeeId()).orElse(null);
                if (emp != null) {
                    postMessage(emp, "INTERRUPTED", "⚠️ 需求「" + d.getTitle()
                            + "」因应用重启被中断，可到面板重试。", d.getId(), d.getInstanceId());
                }
            }
            if (!running.isEmpty()) log.info("[员工] 重启恢复: {} 个进行中需求标记为 INTERRUPTED", running.size());
            Set<Long> emps = new HashSet<>();
            for (EmployeeDemand d : demandRepo.findByStatus("QUEUED")) emps.add(d.getEmployeeId());
            for (Long empId : emps) {
                try { scheduleQueues(empId); } catch (Exception e) {
                    log.warn("[员工] 重启后重新调度失败: emp={}, {}", empId, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("[员工] 重启恢复失败（不影响新功能）", e);
        }
    }

    @PreDestroy
    public void shutdown() {
        progress.clear();     // RUNNING 需求留给下次启动标记 INTERRUPTED
        chatTurns.clear();
    }

    // ===== 主动上报（浏览器聊天框消息 + 飞书推送） =====

    /**
     * 员工主动向老板说话：落库（浏览器红点/聊天框数据源）+ 经绑定实例的飞书 app
     * 推送给授权用户（best-effort，虚拟线程异步，失败不影响浏览器侧）。
     *
     * @param preferInstanceId 优先经该实例推送（通常 = 需求执行实例），不可用再按绑定顺序回退
     */
    public EmployeeMessage postMessage(DigitalEmployee emp, String type, String content, Long demandId, Long preferInstanceId) {
        return postMessage(emp, type, content, demandId, preferInstanceId, "employee");
    }

    /** sender=boss 的消息是老板自己在聊天框说的话：落库即已读（不计红点）、不回推飞书 */
    public EmployeeMessage postMessage(DigitalEmployee emp, String type, String content, Long demandId,
                                       Long preferInstanceId, String sender) {
        EmployeeMessage msg = new EmployeeMessage();
        msg.setEmployeeId(emp.getId());
        msg.setDemandId(demandId);
        msg.setType(type);
        msg.setContent(content);
        msg.setSender(sender != null ? sender : "employee");
        boolean fromBoss = "boss".equals(msg.getSender());
        if (fromBoss) msg.setReadFlag(true);
        msgRepo.save(msg);
        log.info("[员工] 消息: emp={}, type={}, sender={}, demand={}", emp.getName(), type, msg.getSender(), demandId);
        if (!fromBoss) {
            Thread.ofVirtual().name("emp-notify-" + msg.getId()).start(() -> {
                try {
                    if (pushFeishu(emp, content, preferInstanceId)) {
                        msg.setFeishuPushed(true);
                        msgRepo.save(msg);
                    }
                } catch (Exception e) {
                    log.warn("[员工] 飞书推送失败（浏览器侧仍可见）: emp={}, {}", emp.getName(), e.getMessage());
                }
            });
        }
        return msg;
    }

    /** 挑一个"配了授权用户 + 已连接"的飞书通道：老板指定的通知实例最优先，其次需求执行实例，最后按绑定顺序 */
    private boolean pushFeishu(DigitalEmployee emp, String content, Long preferInstanceId) {
        List<Long> order = new ArrayList<>();
        if (emp.getNotifyInstanceId() != null) order.add(emp.getNotifyInstanceId());
        if (preferInstanceId != null && !order.contains(preferInstanceId)) order.add(preferInstanceId);
        for (Long iid : emp.instanceIdList()) if (!order.contains(iid)) order.add(iid);
        for (Long iid : order) {
            BotInstance inst = instanceRepo.findById(iid).orElse(null);
            if (inst == null || inst.getAuthorizedOpenId() == null || inst.getAuthorizedOpenId().isBlank()) continue;
            if (!feishuBotService.isInstanceConnected(iid)) continue;
            feishuBotService.notifyUser(iid, inst.getAuthorizedOpenId(),
                    "👔 数字员工「" + emp.getName() + "」上报：\n\n" + content);
            return true;
        }
        log.info("[员工] 飞书推送跳过（无已连接且配了授权用户的实例）: emp={}", emp.getName());
        return false;
    }

    /** 打开员工聊天框 → 全部已读（红点清零） */
    @Transactional
    public int markMessagesRead(Long employeeId) {
        return msgRepo.markAllRead(employeeId);
    }

    // ===== 老板 ↔ 员工 聊天框对话 =====

    /** 进行中的聊天轮次（employeeId → 状态）；每员工同一时刻只允许一条聊天在处理，防止回复交错 */
    private final Map<Long, ChatTurn> chatTurns = new ConcurrentHashMap<>();

    public static class ChatTurn {
        public final Long instanceId;
        public final Long demandId;   // 追问上下文（可空）
        public final ProgressSnapshot snap = new ProgressSnapshot();

        ChatTurn(Long instanceId, Long demandId) {
            this.instanceId = instanceId;
            this.demandId = demandId;
        }
    }

    /**
     * 老板在聊天框对员工说话 → 员工在其绑定实例上真实执行一轮（带人设、可干活、
     * 与该实例上之前跑过的需求共享 CLI 会话记忆）→ 回复落库进聊天框 + 飞书推送。
     *
     * @param demandId 可空；非空 = 带着该需求的上下文追问（并优先用该需求的执行实例，记忆最对味）
     */
    public EmployeeMessage sendChat(Long empId, String text, Long demandId) {
        DigitalEmployee emp = employeeRepo.findById(empId)
                .orElseThrow(() -> new IllegalArgumentException("员工不存在: " + empId));
        if (text == null || text.isBlank()) throw new IllegalArgumentException("请输入消息内容");
        List<Long> bound = emp.instanceIdList();
        if (bound.isEmpty()) throw new IllegalArgumentException("该员工还没绑定实例，无法接收消息");
        if (chatTurns.containsKey(empId)) throw new IllegalArgumentException("员工正在处理上一条聊天消息，请稍候再发");

        // 实例路由：指定需求的实例 > 最近需求的实例 > 第一个存在的绑定实例
        Long target = null;
        String demandTitle = null;
        if (demandId != null) {
            EmployeeDemand d = demandRepo.findById(demandId).orElse(null);
            if (d != null && empId.equals(d.getEmployeeId())) {
                target = d.getInstanceId();
                demandTitle = d.getTitle();
            }
        }
        if (target == null) {
            List<EmployeeDemand> ds = demandRepo.findByEmployeeIdOrderByCreatedAtAsc(empId);
            for (int i = ds.size() - 1; i >= 0 && target == null; i--) {
                target = ds.get(i).getInstanceId();
            }
        }
        if (target == null || !instanceRepo.existsById(target)) {
            target = bound.stream().filter(instanceRepo::existsById).findFirst().orElse(null);
        }
        if (target == null) throw new IllegalArgumentException("绑定的实例都已不存在，请先编辑员工重新绑定");

        // 老板的话先落库（已读、不推飞书），聊天框立即可见
        EmployeeMessage bossMsg = postMessage(emp, "BOSS", text.trim(), demandId, null, "boss");

        final Long instId = target;
        final String ctxTitle = demandTitle;
        ChatTurn turn = new ChatTurn(instId, demandId);
        chatTurns.put(empId, turn);
        Thread.ofVirtual().name("emp-chat-" + empId).start(() -> {
            try {
                BotInstance inst = instanceRepo.findById(instId).orElseThrow();
                String dir = firstNonBlank(inst.getClaudeWorkingDir(), inst.getShellWorkingDir(), "");
                String reply = claudeCodeService.chat(instId, "employee-" + empId + "-chat",
                        chatPrompt(emp, text.trim(), ctxTitle), null, dir, inst.getClaudeModel(),
                        new ProgressListener(null, instId, turn.snap));
                String content = reply == null || reply.isBlank() ? "（无回复内容）" : reply;
                postMessage(emp, "CHAT", content, demandId, instId);   // 员工回复：落库 + 飞书推送
            } catch (Exception e) {
                log.warn("[员工] 聊天执行失败: emp={}, {}", empId, e.getMessage());
                try {
                    postMessage(emp, "CHAT", "❌ 聊天执行失败: " + e.getMessage(), demandId, instId);
                } catch (Exception ignored) {}
            } finally {
                chatTurns.remove(empId);
            }
        });
        return bossMsg;
    }

    /** 聊天 prompt：人设 + SOP/SPEC + 可选需求上下文 + 对话式回应契约（比需求 prompt 轻，不强制四段汇报） */
    private String chatPrompt(DigitalEmployee emp, String text, String demandTitle) {
        String role = emp.getRoleDesc() != null && !emp.getRoleDesc().isBlank()
                ? "职责：" + emp.getRoleDesc().trim() + "。" : "";
        return "【数字员工聊天】\n"
                + "你是数字员工 " + emp.getEmoji() + "「" + emp.getName() + "」。" + role + "\n"
                + DEFAULT_SOP
                + specSection(emp)
                + (demandTitle != null ? "当前聊天带着需求《" + demandTitle + "》的上下文。\n" : "")
                + "老板在聊天框里对你说：\n" + text + "\n\n"
                + "请以对话方式回应（你的回复会直接显示在老板的聊天框里，支持 markdown，简洁自然，不要冗长套话）。"
                + "追问工作细节时，依据你的会话记忆和当前工作目录的实际情况回答，不确定就实际去查看；"
                + "老板让你干活就直接在当前工作目录里干，干完简要说明做了什么、结果如何。";
    }

    /** 聊天轮次进度（详情页 3s 轮询展示"员工正在回复…"）；无进行中聊天返回 null */
    public JsonObject chatProgressJson(Long employeeId) {
        ChatTurn t = chatTurns.get(employeeId);
        if (t == null) return null;
        JsonObject o = snapshotJson(t.snap, t.instanceId);
        if (o != null) {
            o.addProperty("instanceId", t.instanceId);
            if (t.demandId != null) o.addProperty("demandId", t.demandId);
        }
        return o;
    }

    // ===== Shell 模式：admin 直通命令（不经 Claude，确定性执行） =====

    /** shell 命令超时（秒）——git qpr 之类含 push 的脚本留足余量 */
    private static final int SHELL_TIMEOUT_SEC = 120;
    /** 输出保留上限（超出截尾，防大输出爆库） */
    private static final int SHELL_OUTPUT_CAP = 60_000;
    private static final java.util.regex.Pattern URL_PATTERN =
            java.util.regex.Pattern.compile("https?://[^\\s\"'<>\\[\\]]+");

    /** 进行中的 shell 命令（employeeId → 任务）；每员工同时只允许一条 */
    private final Map<Long, ShellTask> shellTasks = new ConcurrentHashMap<>();

    public static class ShellTask {
        public final String command;
        public final Long instanceId;
        public final long startMs = System.currentTimeMillis();

        ShellTask(String command, Long instanceId) {
            this.command = command;
            this.instanceId = instanceId;
        }
    }

    /**
     * 在员工绑定实例的工作目录直接执行命令（cmd.exe /c，admin 权限直通通道——
     * 沙箱档位约束的是 Claude 模型，不约束老板本人的面板操作）。
     * 交互提问自动答 y（stdin 预灌）；跑完提取输出中的 URL，
     * 结果以 SHELL 消息走员工双通道（聊天框落库 + 飞书推送）。
     *
     * @param instanceId 可空；空 = 最近执行过需求的实例 > 第一个绑定实例
     */
    public void runShell(Long empId, String command, Long instanceId) {
        runShell(empId, command, instanceId, false);
    }

    /**
     * @param autoTriggered true = 系统自动流程触发（如 E2E 完结后自动 git qpr）：
     *                      不记 BOSS 命令气泡（不是老板手发的），结果消息加【自动流程】前缀
     */
    public void runShell(Long empId, String command, Long instanceId, boolean autoTriggered) {
        DigitalEmployee emp = employeeRepo.findById(empId)
                .orElseThrow(() -> new IllegalArgumentException("员工不存在: " + empId));
        if (command == null || command.isBlank()) throw new IllegalArgumentException("请输入要执行的命令");
        if (shellTasks.containsKey(empId)) throw new IllegalArgumentException("上一条命令还在执行中，请稍候");

        Long target = instanceId;
        if (target == null) {
            List<EmployeeDemand> ds = demandRepo.findByEmployeeIdOrderByCreatedAtAsc(empId);
            for (int i = ds.size() - 1; i >= 0 && target == null; i--) target = ds.get(i).getInstanceId();
        }
        if (target == null || !instanceRepo.existsById(target)) {
            target = emp.instanceIdList().stream().filter(instanceRepo::existsById).findFirst().orElse(null);
        }
        if (target == null) throw new IllegalArgumentException("该员工没有可用的绑定实例");

        String cmd = command.trim();
        if (!autoTriggered) {
            // 老板手发命令：先以 BOSS 气泡进聊天框留档（前缀 🖥 $ 区分于对话）
            postMessage(emp, "BOSS", "🖥 $ " + cmd, null, null, "boss");
        }

        final Long instId = target;
        ShellTask task = new ShellTask(cmd, instId);
        shellTasks.put(empId, task);
        Thread.ofVirtual().name("emp-shell-" + empId).start(() -> {
            try {
                String result = execShell(task, instId);
                if (autoTriggered) result = "🤖【自动流程 · E2E 完结触发】\n" + result;
                postMessage(emp, "SHELL", result, null, instId);
            } catch (Exception e) {
                log.warn("[员工] shell 执行异常: emp={}, cmd={}, {}", empId, cmd, e.getMessage());
                try {
                    postMessage(emp, "SHELL", (autoTriggered ? "🤖【自动流程】" : "")
                            + "❌ 命令执行异常: " + e.getMessage() + "\n命令: " + cmd, null, instId);
                } catch (Exception ignored) {}
            } finally {
                shellTasks.remove(empId);
            }
        });
    }

    /** 真正执行：系统 shell（Windows cmd /c，Linux/macOS bash -lc）+ 自动答 y + 超时强杀 + 输出截断 + URL 提取，返回 SHELL 消息正文 */
    private String execShell(ShellTask task, Long instId) throws Exception {
        BotInstance inst = instanceRepo.findById(instId).orElseThrow();
        String dir = firstNonBlank(inst.getClaudeWorkingDir(), inst.getShellWorkingDir(), "");
        // 跨平台 shell：Windows 用 cmd /c；Linux/macOS 用 bash -lc（login shell 保证 nvm 等 PATH 生效）
        String osName = System.getProperty("os.name", "").toLowerCase();
        ProcessBuilder pb = osName.contains("win")
                ? new ProcessBuilder("cmd.exe", "/c", task.command)
                : new ProcessBuilder("bash", "-lc", task.command);
        if (!dir.isBlank()) pb.directory(new java.io.File(dir));
        pb.redirectErrorStream(true);
        long t0 = System.currentTimeMillis();
        Process p = pb.start();

        // 自动应答交互提问：预灌 10 行 y 后关闭 stdin（不读 stdin 的命令无副作用；写失败忽略）
        try (var stdin = p.getOutputStream()) {
            for (int i = 0; i < 10; i++) stdin.write("y\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            stdin.flush();
        } catch (Exception ignored) {}

        // 输出读取放独立虚拟线程（default charset：中文 Windows cmd = GBK，git 输出 ASCII 兼容）
        StringBuilder out = new StringBuilder();
        Thread reader = Thread.ofVirtual().name("emp-shell-read").start(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(
                    p.getInputStream(), java.nio.charset.Charset.defaultCharset()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (out.length() < SHELL_OUTPUT_CAP) out.append(line).append('\n');
                }
            } catch (Exception ignored) {}
        });

        boolean finished = p.waitFor(SHELL_TIMEOUT_SEC, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) p.destroyForcibly();
        reader.join(5_000);
        long elapsed = (System.currentTimeMillis() - t0) / 1000;
        String output = out.toString();
        int exit = finished ? p.exitValue() : -1;

        // URL 提取（去重、去尾部标点）
        java.util.LinkedHashSet<String> urls = new java.util.LinkedHashSet<>();
        java.util.regex.Matcher m = URL_PATTERN.matcher(output);
        while (m.find()) {
            String u = m.group();
            while (!u.isEmpty() && ".,;:!?，。；".indexOf(u.charAt(u.length() - 1)) >= 0) u = u.substring(0, u.length() - 1);
            if (!u.isEmpty()) urls.add(u);
        }

        StringBuilder sb = new StringBuilder();
        if (!finished) {
            sb.append("⏰ 命令超时（").append(SHELL_TIMEOUT_SEC).append("s），已强制终止：").append(task.command).append("\n");
        } else {
            sb.append(exit == 0 ? "✅ 命令执行成功：" : "❌ 命令退出码 " + exit + "：").append(task.command).append("\n");
        }
        sb.append("实例「").append(inst.getName()).append("」· 耗时 ").append(elapsed).append("s");
        if (!urls.isEmpty()) {
            sb.append("\n🔗 链接：\n");
            for (String u : urls) sb.append(u).append("\n");
        }
        String body = output.trim();
        if (!body.isEmpty()) {
            // 头 400 + 尾 1200：URL/结论通常在尾部
            String excerpt = body.length() > 1800
                    ? body.substring(0, 400) + "\n…(中间省略 " + (body.length() - 1600) + " 字)...\n" + body.substring(body.length() - 1200)
                    : body;
            sb.append("\n--- 输出").append(finished ? "" : "（截断）").append(" ---\n").append(excerpt);
        }
        return sb.toString();
    }

    /** shell 执行进度（详情页轮询展示"正在执行命令…"）；无进行中命令返回 null */
    public JsonObject shellProgressJson(Long employeeId) {
        ShellTask t = shellTasks.get(employeeId);
        if (t == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("command", t.command);
        o.addProperty("instanceId", t.instanceId);
        o.addProperty("elapsedMs", System.currentTimeMillis() - t.startMs);
        return o;
    }

    /** 通知巡检（每分钟）：排队卡住检测 + 定期汇报 */
    @Scheduled(fixedDelay = 60_000)
    public void notifyTick() {
        try {
            for (DigitalEmployee emp : employeeRepo.findAll()) {
                try {
                    checkStuck(emp);
                    checkPeriodic(emp);
                } catch (Exception e) {
                    log.warn("[员工] 通知巡检异常: emp={}, {}", emp.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[员工] notifyTick 异常: {}", e.getMessage());
        }
    }

    /** 卡住检测①：QUEUED 超过 {@value #STUCK_QUEUE_MIN} 分钟没开工（实例全忙）→ 上报一次 */
    private void checkStuck(DigitalEmployee emp) {
        for (EmployeeDemand d : demandRepo.findByEmployeeIdAndStatusInOrderByCreatedAtAsc(emp.getId(), List.of("QUEUED"))) {
            if (d.isStuckReported() || d.getCreatedAt() == null) continue;
            long waited = Duration.between(d.getCreatedAt(), LocalDateTime.now()).toMinutes();
            if (waited < STUCK_QUEUE_MIN) continue;
            d.setStuckReported(true);
            demandRepo.save(d);
            postMessage(emp, "STUCK", "⏳ 需求「" + d.getTitle() + "」已排队 " + waited + " 分钟没开工——"
                    + (d.getRequestedInstanceId() != null
                        ? "指定实例「" + instanceName(d.getRequestedInstanceId()) + "」一直忙。"
                        : "绑定的实例都在忙。")
                    + "\n可以：继续等 / 取消后换实例重发 / 腾一个实例出来。", d.getId(), null);
        }
    }

    /** 定期汇报：配置了间隔且有活跃工作（排队/执行中）才发，空闲不打扰 */
    private void checkPeriodic(DigitalEmployee emp) {
        if (emp.getReportIntervalMin() <= 0) return;
        LocalDateTime last = emp.getLastReportAt();
        boolean due = last == null || Duration.between(last, LocalDateTime.now()).toMinutes() >= emp.getReportIntervalMin();
        List<EmployeeDemand> running = demandRepo.findByEmployeeIdAndStatusInOrderByCreatedAtAsc(emp.getId(), List.of("RUNNING"));
        List<EmployeeDemand> queued = demandRepo.findByEmployeeIdAndStatusInOrderByCreatedAtAsc(emp.getId(), List.of("QUEUED"));
        if (running.isEmpty() && queued.isEmpty()) {
            // 空闲期静默对齐基准：开工后从满间隔才开始报，不补发
            if (due) {
                emp.setLastReportAt(LocalDateTime.now());
                employeeRepo.save(emp);
            }
            return;
        }
        if (!due) return;
        emp.setLastReportAt(LocalDateTime.now());
        employeeRepo.save(emp);
        StringBuilder sb = new StringBuilder("📊 定期汇报：\n");
        if (!running.isEmpty()) {
            sb.append("▶ 进行中 ").append(running.size()).append(" 个：\n");
            for (EmployeeDemand d : running) {
                sb.append("  - 「").append(d.getTitle()).append("」");
                if (d.getInstanceId() != null) sb.append(" @").append(instanceName(d.getInstanceId()));
                if (d.getStartedAt() != null) {
                    sb.append("（已 ").append(Duration.between(d.getStartedAt(), LocalDateTime.now()).toMinutes()).append(" 分钟）");
                }
                sb.append("\n");
            }
        }
        if (!queued.isEmpty()) {
            sb.append("⏳ 排队 ").append(queued.size()).append(" 个：");
            for (int i = 0; i < queued.size(); i++) {
                if (i > 0) sb.append("、");
                sb.append("「").append(queued.get(i).getTitle()).append("」");
            }
            sb.append("\n");
        }
        postMessage(emp, "PERIODIC", sb.toString(), null, null);
    }

    /**
     * 卡住检测②：宽容解析完成汇报的「需要老板支持」段落；"无/没有/暂无"等视为不需要，返回 null。
     * LLM 输出格式不完全可控，只做启发式提取，误判可接受（宁多报不漏报）。
     */
    static String parseSupportNeed(String result) {
        if (result == null || result.isBlank()) return null;
        int idx = result.lastIndexOf("需要老板支持");
        if (idx < 0) return null;
        String seg = result.substring(idx + "需要老板支持".length())
                .replaceAll("^[\\s:：)）*#\\-0-9.、]*", "").trim();
        if (seg.isEmpty()) return null;
        String first = seg.split("\\n")[0].trim();
        String norm = first.replaceAll("[\\s。.!！~～*#\\-:：]", "");
        if (norm.isEmpty() || norm.matches("(无|没有|暂无|不需要|不用|没什么|N/?A|NONE)")) return null;
        if (norm.length() <= 6 && (norm.contains("无") || norm.contains("没有"))) return null;
        return seg.length() > 600 ? seg.substring(0, 600) + "…" : seg;
    }

    private String instanceName(Long id) {
        if (id == null) return "(未指派)";
        return instanceRepo.findById(id).map(BotInstance::getName).orElse("#" + id);
    }

    private static int clampInterval(Integer min) {
        if (min == null || min < 0) return 0;
        return Math.min(min, 7 * 24 * 60);   // 上限 7 天
    }

    // ===== 沙箱联动（独占绑定的配套：员工的工作机自动上锁） =====

    /**
     * 绑定/解绑实例时联动沙箱档位：
     * 新绑定 → 全权限(0)的实例自动开严格沙箱(2)；解绑 → 严格沙箱(2)自动回全权限(0)。
     * 只动 0↔2：手动设成宽松档(1)的实例说明 admin 有意为之，绑定/解绑都不覆盖。
     * 档位变更在下次会话使用时由 ClaudeCodeService.getOrCreate 检测并重建终端进程。
     */
    private void applySandboxOnBind(List<Long> newlyBound, List<Long> unbound) {
        for (Long iid : newlyBound) {
            instanceRepo.findById(iid).ifPresent(inst -> {
                if (inst.getSandboxLevel() == SandboxPolicy.LEVEL_FULL) {
                    inst.setSandboxLevel(SandboxPolicy.LEVEL_STRICT);
                    instanceRepo.save(inst);
                    log.info("[员工] 绑定实例自动开启严格沙箱🔒: instance={}「{}」", iid, inst.getName());
                }
            });
        }
        for (Long iid : unbound) {
            instanceRepo.findById(iid).ifPresent(inst -> {
                if (inst.getSandboxLevel() == SandboxPolicy.LEVEL_STRICT) {
                    inst.setSandboxLevel(SandboxPolicy.LEVEL_FULL);
                    instanceRepo.save(inst);
                    log.info("[员工] 解绑实例自动恢复全权限🔓: instance={}「{}」", iid, inst.getName());
                }
            });
        }
    }

    // ===== Prompt =====

    /** 员工人设 + 权限契约 + 默认SOP + 自定义SPEC + 需求全文 + 异步汇报契约（v1 不做暂停提问，缺信息写进"需要老板支持"） */
    private String personaPrompt(DigitalEmployee emp, EmployeeDemand d) {
        String role = emp.getRoleDesc() != null && !emp.getRoleDesc().isBlank()
                ? "职责：" + emp.getRoleDesc().trim() + "。" : "";
        return "【数字员工任务】\n"
                + "你是数字员工 " + emp.getEmoji() + "「" + emp.getName() + "」（员工ID: " + emp.getId() + "）。" + role + "\n"
                + permissionSection(emp)
                + DEFAULT_SOP
                + specSection(emp)
                + "老板给你下发了如下需求，请在当前工作目录内完成：\n\n"
                + "== 需求 ==\n" + d.getRequirement() + "\n\n"
                + "【完成汇报契约】最后请按以下结构汇报：\n"
                + "1. 做了什么（关键动作与产出）\n"
                + "2. 结果（结论/是否成功/关键数据）\n"
                + "3. 遗留问题（没有写\"无\"）\n"
                + "4. 需要老板支持（缺少的凭证/决策/资源等，没有写\"无\"）\n"
                + "注意：这是异步下发的任务，没有人在线回答追问——缺少关键信息时不要空等，"
                + "先完成能做的部分，把缺项明确写进「需要老板支持」。";
    }

    /** 系统内置默认工作 SOP — 注入每次需求/聊天 prompt；与员工自定义 workSpec 冲突时以 SPEC 为准 */
    static final String DEFAULT_SOP =
            "\n【默认工作规范】（系统内置；若与老板给你的自定义 SPEC 冲突，以 SPEC 为准）\n"
            + "1. 链接处理：需求中出现的飞书链接（*.feishu.cn / *.larksuite.com 的文档、表格、多维表格、消息等），"
            + "**禁止直接 curl 读取**（没有登录态，拿不到正文），必须用本机已安装的 lark-cli 相关技能"
            + "（lark-doc / lark-sheet / lark-markdown / lark-im / lark-wiki 等）解析读取；其他公网 URL 可以 curl。\n"
            + "2. 执行边界：所有代码/命令操作只在你绑定实例的工作目录内完成；创建 E2E/Hook/供应商工作等系统操作只能使用你自己绑定的实例（服务端强制校验）。\n"
            + "3. 标准工作流：接到需求 → 完成开发并自验 → 若持有 E2E 权限：通过 API 创建 E2E 测试任务。"
            + "**角色分配原则**：本次编写代码的实例担任 programmer；若你绑定了多个实例，designer（测试设计师）和 executor（执行者）"
            + "应选用与写码实例不同的实例——设计用例和执行测试的不能是写代码的那个（独立性原则，防止给自己的代码放水），且 designer 与 executor 也尽量分开；"
            + "只有绑定实例不够时才让一个实例兼任多角色。创建后 → 启动流水线 → 在群聊里发言说明本次改动了什么、要测什么、怎么判定，"
            + "群聊里老板或成员 @ 你时配合回应 → 然后等待系统把 E2E 结果推送给你（不要阻塞轮询等待）。\n"
            + "4. 提交 PR：统一用老板机器上预置的 git qpr（全局 alias，自动带 reviewer、自动确认、全程无需交互），"
            + "执行完从输出里提取网络链接（Code Review URL）汇报给老板；"
            + "若老板开启了「自动提交 PR」，E2E 完结后系统会自动执行 git qpr 并以你的名义上报链接，你无需自己执行。\n"
            + "5. Git 纪律：提交前用 git status / git diff 确认改动内容，不提交无关文件、不修改 git 全局配置。\n";

    /** 员工自定义工作 SPEC 段（老板手写的规范，优先级最高） */
    private static String specSection(DigitalEmployee emp) {
        if (emp.getWorkSpec() == null || emp.getWorkSpec().isBlank()) return "";
        return "\n【你的工作 SPEC】（老板专门为你制定的工作规范，优先级最高，请逐条严格遵守）\n"
                + emp.getWorkSpec().trim() + "\n";
    }

    /** 权限段：授权了哪些创建类 API（curl 调用契约 + creatorEmployeeId 必带），未授权明确禁止 */
    private String permissionSection(DigitalEmployee emp) {
        List<String> perms = emp.permList();
        if (perms.isEmpty()) {
            return "\n【系统权限】你当前没有任何创建权限（E2E 测试任务/Hook/供应商工作都不能创建）；"
                    + "需求若涉及这些操作，把要创建的内容写进「需要老板支持」。\n\n";
        }
        StringBuilder sb = new StringBuilder("\n【系统权限】你被授权在本系统（http://localhost:8080）用 curl 调 REST API 创建以下对象。"
                + "**请求体 JSON 必须带 \"creatorEmployeeId\": " + emp.getId() + "**（标记创建者是你；服务端会校验，缺失或无权限会被 400 拒绝）：\n");
        if (perms.contains("e2e")) {
            sb.append("- E2E 测试任务（创建→启动→群聊→结果回推 全链路）:\n")
              .append("  ① 创建: POST /api/e2e/tasks，body 示例: {\"members\":[{\"instanceId\":1,\"role\":\"programmer\"},"
                    + "{\"instanceId\":1,\"role\":\"designer\"},{\"instanceId\":1,\"role\":\"executor\"}],\"requirement\":\"被测需求描述\","
                    + "\"autoFix\":true,\"maxRounds\":3,\"commMode\":\"filesystem\",\"creatorEmployeeId\":").append(emp.getId()).append("}"
                    + "（可先 GET /api/instances 查实例；同一实例可兼任多角色；commMode 可选 filesystem/a2a；响应含任务 id）\n")
              .append("  ② 启动流水线: POST /api/e2e/tasks/{id}/start，body {\"creatorEmployeeId\":").append(emp.getId()).append("}（幂等）\n")
              .append("  ③ 群聊发言: POST /api/e2e/tasks/{id}/message，body {\"text\":\"...\",\"to\":\"auto\",\"creatorEmployeeId\":")
              .append(emp.getId()).append("}——to=auto 驱动流水线/回答成员提问，to=programmer|designer|executor 单聊某成员；你的发言会以 👔 数字员工身份显示在群聊里\n")
              .append("  ④ 查状态: GET /api/e2e/tasks/{id}（pipeline.stage=DONE/FAILED 即出结果）；流水线结束后系统也会自动把结果推送到你的上报频道，不必长时间轮询等待\n")
              .append("  注意：只能启动/发言你自己创建的任务；E2E 执行可能耗时较长，创建并启动后即可先汇报\"已创建并启动，任务id=xxx\"，不要阻塞等待结果。\n");
        }
        if (perms.contains("hook")) {
            sb.append("- 创建 Hook 任务: POST /api/hooks，body 示例: {\"name\":\"...\",\"instanceIds\":\"1,2\",\"maintainerInstanceId\":1,"
                    + "\"skillDir\":\"D:\\\\skills\\\\xxx\",\"triggerType\":\"AFTER_CHAT\",\"cronExpr\":\"\",\"enabled\":true,"
                    + "\"creatorEmployeeId\":").append(emp.getId()).append("}"
                    + "（triggerType 可选 AFTER_CHAT/CRON；CRON 时 cronExpr 必填，如 \"0 0 3 * * ?\"）\n");
        }
        if (perms.contains("supplier")) {
            sb.append("- 创建供应商工作: POST /api/supplier-jobs，body 示例: {\"name\":\"...\",\"supplierName\":\"...\",\"supplierCode\":\"...\","
                    + "\"libraryPath\":\"...\",\"docs\":[],\"skills\":[],\"instanceId\":1,\"templateId\":1,"
                    + "\"creatorEmployeeId\":").append(emp.getId()).append("}"
                    + "（可先 GET /api/workflows 查模板、GET /api/instances 查实例）\n");
        }
        sb.append("未被授权的操作一律禁止尝试。创建成功后在汇报里写明创建了什么对象（含返回的 id）。\n\n");
        return sb.toString();
    }

    // ===== 实时进度 =====

    /** 进度快照（内存态）：TurnListener 回调采集，前端轮询读取 */
    public static class ProgressSnapshot {
        public volatile String phase = "排队中";      // 排队中|思考中|工具执行中|生成回复|已结束
        public volatile String currentTool;
        public volatile boolean queued;               // 实例会话忙，本需求在排队
        public volatile boolean turnStarted;          // 本需求的 turn 已真正开始（取消时才允许 abort）
        public volatile long startMs = System.currentTimeMillis();
        public volatile String answerTail = "";
        public volatile String errorTail;
        public final ArrayDeque<String[]> tools = new ArrayDeque<>();   // 最近8条 {name,status,elapsedMs}
        final StringBuilder answer = new StringBuilder();
    }

    /** demandId → 进度 JSON（无快照返回 null） */
    public JsonObject progressJson(Long demandId, Long instanceId) {
        return snapshotJson(progress.get(demandId), instanceId);
    }

    /** 快照序列化（需求进度与聊天进度共用；queueDepth 实时取会话队列长度） */
    private JsonObject snapshotJson(ProgressSnapshot snap, Long instanceId) {
        if (snap == null) return null;
        JsonObject o = new JsonObject();
        o.addProperty("phase", snap.phase);
        o.addProperty("currentTool", snap.currentTool != null ? snap.currentTool : "");
        o.addProperty("queued", snap.queued);
        o.addProperty("turnStarted", snap.turnStarted);
        o.addProperty("elapsedMs", System.currentTimeMillis() - snap.startMs);
        o.addProperty("answerTail", snap.answerTail);
        if (snap.errorTail != null) o.addProperty("errorTail", snap.errorTail);
        TerminalSession s = instanceId != null ? claudeCodeService.getSession(instanceId) : null;
        o.addProperty("queueDepth", s != null ? s.getQueuedCount() : 0);
        JsonArray tools = new JsonArray();
        synchronized (snap.tools) {
            for (String[] t : snap.tools) {
                JsonObject to = new JsonObject();
                to.addProperty("name", t[0]);
                to.addProperty("status", t[1]);
                to.addProperty("elapsedMs", Long.parseLong(t[2]));
                tools.add(to);
            }
        }
        o.add("tools", tools);
        return o;
    }

    /** 轮次监听器 → 快照采集；demandId 非空时在 onTurnStart 检查需求是否已被取消（排队期间取消 → 立即中止自己的轮次） */
    private class ProgressListener implements TurnListener {
        private final Long demandId;      // null = 聊天轮次（无需求取消检查）
        private final Long instanceId;
        private final ProgressSnapshot snap;

        ProgressListener(Long demandId, Long instanceId) {
            this(demandId, instanceId, new ProgressSnapshot());
            progress.put(demandId, snap);
        }

        ProgressListener(Long demandId, Long instanceId, ProgressSnapshot snapshot) {
            this.demandId = demandId;
            this.instanceId = instanceId;
            this.snap = snapshot;
        }

        @Override
        public void onTurnStart() {
            snap.turnStarted = true;
            snap.queued = false;
            snap.phase = "思考中";
            snap.startMs = System.currentTimeMillis();
            if (demandId == null) return;   // 聊天轮次不做需求取消检查
            // 排队期间需求被取消/员工被删 → 轮次开始的瞬间中止（此刻实例的当前轮次就是我们，安全）
            try {
                EmployeeDemand d = demandRepo.findById(demandId).orElse(null);
                if (d == null || !"RUNNING".equals(d.getStatus())) {
                    TerminalSession s = claudeCodeService.getSession(instanceId);
                    if (s != null) s.abortCurrentTurn();
                }
            } catch (Exception ignored) {}
        }

        @Override
        public void onQueued() {
            snap.queued = true;
            snap.phase = "排队中";
        }

        @Override
        public void onThinkingDelta(String delta) {
            if (snap.turnStarted) snap.phase = "思考中";
        }

        @Override
        public void onTextDelta(String delta) {
            snap.phase = "生成回复";
            synchronized (snap.answer) {
                snap.answer.append(delta);
                int len = snap.answer.length();
                snap.answerTail = len > 400 ? snap.answer.substring(len - 400) : snap.answer.toString();
            }
        }

        @Override
        public void onToolStart(ToolStep step) {
            snap.phase = "工具执行中";
            snap.currentTool = step.name;
            addTool(step);
        }

        @Override
        public void onToolEnd(ToolStep step) {
            snap.currentTool = null;
            addTool(step);
        }

        @Override
        public void onTurnComplete(TurnResult result) {
            snap.phase = "已结束";
            snap.currentTool = null;
        }

        @Override
        public void onTurnError(String message, boolean aborted) {
            snap.phase = "已结束";
            snap.currentTool = null;
            snap.errorTail = message;
        }

        private void addTool(ToolStep step) {
            synchronized (snap.tools) {
                snap.tools.addLast(new String[]{
                        step.name != null ? step.name : "",
                        step.status != null ? step.status.name().toLowerCase() : "running",
                        String.valueOf(step.elapsedMs())});
                while (snap.tools.size() > 8) snap.tools.removeFirst();
            }
        }
    }

    // ===== 工具方法 =====

    private static String firstNonBlank(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    /** RUNNING 需求的已耗时（毫秒；非 RUNNING 返回全时长或 null） */
    public static long elapsedMsOf(EmployeeDemand d) {
        if (d.getStartedAt() == null) return 0;
        LocalDateTime end = d.getFinishedAt() != null ? d.getFinishedAt() : LocalDateTime.now();
        return Duration.between(d.getStartedAt(), end).toMillis();
    }
}
