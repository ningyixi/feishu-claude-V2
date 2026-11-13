package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.model.entity.SupplierJobNode;
import com.lark.oapi.sample.ws.model.entity.SupplierKbEntry;
import com.lark.oapi.sample.ws.model.entity.WorkflowTemplate;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.SupplierJobNodeRepository;
import com.lark.oapi.sample.ws.repository.SupplierJobRepository;
import com.lark.oapi.sample.ws.repository.WorkflowTemplateRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 供应商接口快速建档（11-13-standalone-doc-probe）：独立工具页的四段流水线编排。
 * <p>
 * 任务本体 = 打了 {@code quick} 标记的 {@link SupplierJob}（knowledgeMode 固定 WIKI，
 * 节点快照只有内置模板 n1「文档分析」/ n2「接口实测」两个 agent 节点，<b>不启动 Flowable 流程</b>）。
 * 一键启动后在虚拟线程上串行推进四段：
 * <ol>
 *   <li><b>KB 入库</b>：文档来源逐条进知识库并解析（复用 {@link SupplierKbService} add/reparse 管线，
 *       含站点抓取），全部材料就绪后完成<b>首次 Wiki 编译</b>——后续 agent 段走 WIKI 上下文打包
 *       （{@code wiki.context → ready()}），Wiki 不 READY 会被阻断，故初编译必须落在本段尾部
 *       （镜像 SupplierWorkflowWorker.dispatch 的"派发前自动编译/等待"门槛）。</li>
 *   <li><b>ANALYSIS 分析</b>：{@link SupplierJobService#runNode} 执行 n1（不绕过其上下文打包/证据采集），
 *       轮询节点状态等待轮次离开 RUNNING。</li>
 *   <li><b>PROBE 实测</b>：同上执行 n2（Postman 实测协议 + toolEvidence 随 progressListener 采集）。
 *       无实测证据<b>不算失败</b>（供应商可能未接通），照常归档继续。</li>
 *   <li><b>WIKI 归档编译</b>：n1/n2 工件+实测证据经 {@link SupplierKbService#archiveArtifact}
 *       归档入库（source "job:X/node:Y" 幂等），节点置 DONE，再编译 Wiki 直至 READY。</li>
 * </ol>
 * <b>段状态落库</b>：复用 SupplierJob 新增的 quick_phase（KB/ANALYSIS/PROBE/WIKI/DONE）+
 * quick_error 两列（而非借用 currentNodeId 约定）——不干扰工作台对 currentNodeId 的既有语义
 * （"第一个未完成节点"、前端"带我去"定位），失败原因也有了持久化位置。
 * 当前段 error 为空 = RUNNING，非空 = FAILED（可从该段重试）。
 * <p>
 * <b>等待机制</b>：runNode 是异步投递（deliverToInstance 虚拟线程），本服务轮询 repo 等节点状态
 * 离开 RUNNING（超时 60 分钟对齐实例硬超时，超时置段 FAILED）；agent 输出【卡点】时节点保持
 * RUNNING 挂起待答，用户可在 /supplier?job=&lt;id&gt; 工作台详情页答复续跑，等待循环自然覆盖该场景。
 * 轮询间隔/超时/睡眠器均可注入，便于单测。
 * <p>
 * <b>重启恢复</b>：编排线程不跨进程存活——启动时把未 DONE 且无错误标记的快速任务置
 * quickError="服务重启中断…"，用户「从本段重试」续跑（镜像 recoverInterrupted 精神，不做断点续跑）。
 */
@Service
public class QuickProbeService {

    private static final Logger log = LoggerFactory.getLogger(QuickProbeService.class);

    /** 四段流水线的段标识（顺序即执行顺序；DONE 为终态） */
    public static final String PHASE_KB = "KB";
    public static final String PHASE_ANALYSIS = "ANALYSIS";
    public static final String PHASE_PROBE = "PROBE";
    public static final String PHASE_WIKI = "WIKI";
    public static final String PHASE_DONE = "DONE";
    static final List<String> PHASES = List.of(PHASE_KB, PHASE_ANALYSIS, PHASE_PROBE, PHASE_WIKI);

    /** 节点快照 id（与内置 API 模板的分析/实测节点一致） */
    static final String NODE_ANALYSIS = "n1";
    static final String NODE_PROBE = "n2";

    /** 文档来源清单上限：单任务建档定位（非批量工具），超限直接拒绝 */
    static final int MAX_SOURCE_LINES = 50;
    /** 单条来源行长度上限（URL/本地路径都远小于此值） */
    static final int MAX_SOURCE_LINE_CHARS = 2000;

    private final SupplierJobRepository jobRepo;
    private final SupplierJobNodeRepository nodeRepo;
    private final WorkflowTemplateRepository templateRepo;
    private final BotInstanceRepository instanceRepo;
    private final SupplierKbService kbService;
    private final SupplierJobService jobService;
    private final SupplierWikiService wikiService;

    /** 编排中的任务（防重复启动/重复重试；进程内即可——重启后由 recoverInterrupted 兜底） */
    private final Set<Long> orchestrating = ConcurrentHashMap.newKeySet();

    /** 轮询间隔（毫秒）——测试可注入 */
    long pollIntervalMs = 3000;
    /** 单段等待超时（毫秒）：60 分钟对齐实例轮次硬超时——测试可注入 */
    long stepTimeoutMs = 60 * 60 * 1000L;

    /** 睡眠器（抽出来便于单测快进） */
    interface Sleeper { void sleep(long millis) throws InterruptedException; }
    Sleeper sleeper = Thread::sleep;

    /** 流水线启动器（生产 = 虚拟线程；测试注入同步执行） */
    java.util.function.Consumer<Runnable> starter =
            task -> Thread.ofVirtual().name("quick-probe").start(task);

    public QuickProbeService(SupplierJobRepository jobRepo,
                             SupplierJobNodeRepository nodeRepo,
                             WorkflowTemplateRepository templateRepo,
                             BotInstanceRepository instanceRepo,
                             SupplierKbService kbService,
                             SupplierJobService jobService,
                             SupplierWikiService wikiService) {
        this.jobRepo = jobRepo;
        this.nodeRepo = nodeRepo;
        this.templateRepo = templateRepo;
        this.instanceRepo = instanceRepo;
        this.kbService = kbService;
        this.jobService = jobService;
        this.wikiService = wikiService;
    }

    // ===== 启动恢复 =====

    /** 重启清扫：编排线程已随上个进程消失——未 DONE 且无错误标记的快速任务置"中断，请重试本段" */
    @PostConstruct
    public void recoverInterrupted() {
        try {
            for (SupplierJob j : jobRepo.findAllByQuickTrueOrderByUpdatedAtDesc()) {
                if (PHASE_DONE.equals(j.getQuickPhase()) || !j.getQuickError().isBlank()) continue;
                if (j.getQuickPhase().isBlank()) j.setQuickPhase(PHASE_KB);   // 建了还没起跑就重启
                j.setQuickError("服务重启中断了流水线，请从本段重试");
                jobRepo.save(j);
                log.warn("[QuickProbe] 快速任务 {} 因重启中断，段={}，等待用户重试", j.getId(), j.getQuickPhase());
            }
        } catch (Exception e) {
            log.warn("[QuickProbe] 重启恢复扫描失败（不影响启动）: {}", e.getMessage());
        }
    }

    // ===== 创建 =====

    /**
     * 创建快速建档任务并立即启动流水线。
     *
     * @param identifier 供应商标识（kbCode 命名空间；留空自动生成 quick-&lt;时间戳&gt; 兜底）
     * @param taskName   任务名（选填，默认 = 标识）
     * @param sources    文档来源行（≥1；http(s) 链接 / .pdf / .md/.markdown/.txt 本地路径）
     * @param siteCrawl  URL 来源是否按同站点目录递归抓取
     */
    public SupplierJob create(String identifier, String taskName, Long instanceId,
                              List<String> sources, boolean siteCrawl,
                              Long creatorEmployeeId, String creatorEmployeeName) {
        if (instanceId == null) throw new IllegalArgumentException("请绑定执行实例");
        BotInstance inst = instanceRepo.findById(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("执行实例不存在: " + instanceId));
        if (!inst.isEnabled()) throw new IllegalArgumentException("执行实例已停用，请选择可用实例");
        if (sources == null || sources.isEmpty()) throw new IllegalArgumentException("文档来源至少 1 条");
        // 输入上限（工具定位：单任务建档，不做批量）——防止超大清单把串行解析/站点抓取拖成无底洞
        if (sources.size() > MAX_SOURCE_LINES) {
            throw new IllegalArgumentException("文档来源过多（单次 ≤" + MAX_SOURCE_LINES + " 条），请精简后重试");
        }
        for (String raw : sources) {
            if (raw != null && raw.length() > MAX_SOURCE_LINE_CHARS) {
                throw new IllegalArgumentException("单条文档来源过长（≤" + MAX_SOURCE_LINE_CHARS + " 字符）");
            }
        }

        String code = identifier == null ? "" : identifier.strip();
        if (code.isBlank()) {
            code = "quick-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        }
        if (code.length() > 100) throw new IllegalArgumentException("供应商标识过长（≤100 字符）");

        // 来源行预检：类型可识别 + 本地文件存在（URL 安全校验留给入库管线的 assertSafeUrl）
        JsonArray docs = new JsonArray();
        Set<String> lines = new LinkedHashSet<>();
        for (String raw : sources) {
            String value = raw == null ? "" : raw.strip();
            if (value.isEmpty() || !lines.add(value)) continue;   // 去空去重
            String type = detectType(value);
            if (!"url".equals(type) && !Files.isRegularFile(Paths.get(value))) {
                throw new IllegalArgumentException("文档来源文件不存在: " + value);
            }
            JsonObject d = new JsonObject();
            d.addProperty("type", "url".equals(type) ? "url" : "path");
            d.addProperty("value", value);
            if ("url".equals(type) && siteCrawl) d.addProperty("site", true);
            docs.add(d);
        }
        if (docs.isEmpty()) throw new IllegalArgumentException("文档来源至少 1 条");

        WorkflowTemplate tpl = builtinApiTemplate();
        JsonObject n1 = templateNode(tpl, NODE_ANALYSIS);
        JsonObject n2 = templateNode(tpl, NODE_PROBE);

        SupplierJob job = new SupplierJob();
        job.setName(taskName == null || taskName.isBlank() ? code : taskName.strip());
        job.setMode("API");
        job.setKnowledgeMode("WIKI");
        job.setQuick(true);
        job.setTemplateId(tpl.getId());
        job.setSupplierCode(code);
        job.setSupplierName(code);
        job.setInstanceId(instanceId);
        job.setDocsJson(docs.toString());
        job.setStatus("ACTIVE");
        job.setQuickPhase(PHASE_KB);
        job.setCreatedByEmployeeId(creatorEmployeeId);
        job.setCreatedByEmployeeName(creatorEmployeeName);
        jobRepo.save(job);

        int seq = 0;
        for (JsonObject def : List.of(n1, n2)) {
            SupplierJobNode n = new SupplierJobNode();
            n.setJobId(job.getId());
            n.setNodeId(str(def, "id", "n" + (seq + 1)));
            n.setSeq(seq++);
            n.setType("agent");
            n.setTitle(str(def, "title", "(未命名节点)"));
            n.setDescription(str(def, "description", ""));
            n.setPrompt(str(def, "prompt", ""));
            n.setContextStage("auto");
            n.setStatus("PENDING");
            nodeRepo.save(n);
        }
        log.info("[QuickProbe] 快速建档任务已创建: id={}, 标识={}, 来源{}条, 实例={}",
                job.getId(), code, docs.size(), instanceId);
        startPipeline(job.getId(), PHASE_KB);
        return job;
    }

    /** 来源行类型识别（与 syncFromJob 的映射一致）：http(s) → url；扩展名 → pdf / md；其余拒绝 */
    static String detectType(String line) {
        String v = line == null ? "" : line.strip();
        String low = v.toLowerCase();
        if (low.startsWith("http://") || low.startsWith("https://")) return "url";
        if (low.endsWith(".pdf")) return "pdf";
        if (low.endsWith(".md") || low.endsWith(".markdown") || low.endsWith(".txt")) return "md";
        throw new IllegalArgumentException("无法识别的文档来源（支持 http(s) 链接 / .pdf / .md/.markdown/.txt 本地路径）: " + v);
    }

    /** 从内置 API 模板取节点定义（照抄 n1/n2 的 prompt/description，模板被编辑过则跟随最新） */
    private WorkflowTemplate builtinApiTemplate() {
        return templateRepo.findByMode("API").stream().filter(WorkflowTemplate::isBuiltin).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("未找到内置 API 工作流模板，无法创建快速建档任务"));
    }

    private JsonObject templateNode(WorkflowTemplate tpl, String nodeId) {
        for (JsonElement el : JsonParser.parseString(tpl.getNodesJson() == null ? "[]" : tpl.getNodesJson()).getAsJsonArray()) {
            if (el.isJsonObject() && nodeId.equals(str(el.getAsJsonObject(), "id", ""))) return el.getAsJsonObject();
        }
        throw new IllegalArgumentException("内置 API 模板缺少 " + nodeId + " 节点（分析/实测），请先在工作流模板中恢复");
    }

    // ===== 重试 / 删除 =====

    /** 从指定段重试（幂等：入库按 type+source 去重、归档按 source 幂等、Wiki READY 且指纹一致则跳过） */
    public void retry(Long jobId, String step) {
        SupplierJob job = requireQuick(jobId);
        String phase = step == null ? "" : step.strip().toUpperCase();
        if (!PHASES.contains(phase)) throw new IllegalArgumentException("未知的重试段: " + step);
        if (orchestrating.contains(jobId)) throw new IllegalArgumentException("流水线正在推进中，请等待当前段结束");
        if (job.getQuickError().isBlank()) throw new IllegalArgumentException("任务未处于失败/中断状态，无需重试");
        job.setQuickPhase(phase);
        job.setQuickError("");
        jobRepo.save(job);
        startPipeline(jobId, phase);
    }

    /** 删除沿用现有 deleteJob 语义（节点/对话级联清；KB 材料按现状保留在库中） */
    public void delete(Long jobId) {
        requireQuick(jobId);
        orchestrating.remove(jobId);   // 编排线程后续读写落空自然终止
        jobService.deleteJob(jobId);
    }

    public SupplierJob requireQuick(Long jobId) {
        SupplierJob job = jobRepo.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("快速建档任务不存在: " + jobId));
        if (!job.isQuick()) throw new IllegalArgumentException("该工作是工作台正式工作，不是快速建档任务: " + jobId);
        return job;
    }

    private void startPipeline(Long jobId, String fromPhase) {
        // 原子占位：add 返回 false = 该任务已有流水线在跑（并发重试竞态）——拒绝而不是双跑
        if (!orchestrating.add(jobId)) {
            throw new IllegalArgumentException("流水线正在推进中，请等待当前段结束");
        }
        try {
            starter.accept(() -> {
                try {
                    runPipeline(jobId, fromPhase);
                } finally {
                    orchestrating.remove(jobId);
                }
            });
        } catch (RuntimeException ex) {
            orchestrating.remove(jobId);
            throw ex;
        }
    }

    // ===== 流水线（同步执行体；生产由 startPipeline 放到虚拟线程） =====

    void runPipeline(Long jobId, String fromPhase) {
        SupplierJob job = jobRepo.findById(jobId).orElse(null);
        if (job == null || !job.isQuick()) return;
        try {
            for (int i = PHASES.indexOf(fromPhase); i < PHASES.size(); i++) {
                String phase = PHASES.get(i);
                setPhase(jobId, phase);
                log.info("[QuickProbe] 任务 {} 进入段 {}", jobId, phase);
                switch (phase) {
                    case PHASE_KB -> runKbStage(job);
                    case PHASE_ANALYSIS -> runAgentStage(job, NODE_ANALYSIS, "文档分析");
                    case PHASE_PROBE -> runAgentStage(job, NODE_PROBE, "接口实测");
                    case PHASE_WIKI -> runArchiveStage(job);
                    default -> throw new IllegalStateException("未知段: " + phase);
                }
            }
            jobRepo.findById(jobId).ifPresent(j -> {
                j.setQuickPhase(PHASE_DONE);
                j.setQuickError("");
                j.setStatus("COMPLETED");
                j.setCurrentNodeId(null);
                jobRepo.save(j);
            });
            log.info("[QuickProbe] 任务 {} 四段全部完成，Wiki 已就绪", jobId);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            markFailed(jobId, "流水线等待被中断（服务可能正在重启），请从本段重试");
        } catch (Exception e) {
            markFailed(jobId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.warn("[QuickProbe] 任务 {} 流水线失败于段 {}: {}", jobId, currentPhase(jobId), e.getMessage());
        }
    }

    /** ① 入库：来源逐条进知识库（幂等）→ 解析本任务条目 → 等全部材料就绪 → 首次 Wiki 编译至 READY */
    private void runKbStage(SupplierJob job) throws InterruptedException {
        String kbCode = kbService.kbCode(job);
        List<SourceRef> refs = parseDocs(job.getDocsJson());
        if (refs.isEmpty()) throw new IllegalStateException("任务没有配置文档来源");

        // 1) 逐条入库（已存在同 type+source 则跳过——重试幂等，不产生重复条目）
        Set<String> owned = new HashSet<>();
        for (SourceRef r : refs) owned.add(r.type + "\u0000" + r.value);
        for (SupplierKbEntry e : kbService.list(kbCode)) {
            owned.remove(e.getType() + "\u0000" + (e.getSource() == null ? "" : e.getSource()));
        }
        for (SourceRef r : refs) {
            if (!owned.contains(r.type + "\u0000" + r.value)) continue;
            kbService.add(kbCode, r.type, r.value, null, r.site);
        }

        // 2) 触发本任务条目的解析（RAW=刚入库原文引用 / FAILED=上次解析失败；url/pdf 走异步解析或站点抓取，md 入库即 DONE）
        for (SupplierKbEntry e : kbService.list(kbCode)) {
            boolean mine = refs.stream().anyMatch(r -> r.type.equals(e.getType()) && r.value.equals(e.getSource()));
            if (mine && ("RAW".equals(e.getStatus()) || "FAILED".equals(e.getStatus()))
                    && List.of("url", "pdf", "md").contains(e.getType())) {
                kbService.reparse(e.getId());
            }
        }

        // 3) 等待全部非 archive 材料离开过渡态（DONE=就绪；FAILED=段失败并给出原因）
        waitUntil(job.getId(), () -> {
            List<SupplierKbEntry> entries = kbService.list(kbCode).stream()
                    .filter(e -> !"archive".equals(e.getContextStage())).toList();
            List<String> failed = new ArrayList<>();
            List<String> busy = new ArrayList<>();
            for (SupplierKbEntry e : entries) {
                switch (e.getStatus() == null ? "" : e.getStatus()) {
                    case "DONE" -> { }
                    case "FAILED" -> failed.add(title(e) + "（" + e.getError() + "）");
                    default -> busy.add(title(e) + "[" + e.getStatus() + "]");
                }
            }
            if (!failed.isEmpty()) return "文档解析失败：" + String.join("；", failed) + "——修复后从本段重试";
            if (!busy.isEmpty()) return null;   // 继续等
            return entries.isEmpty() ? "知识库没有可解析的材料" : "";
        }, "文档解析等待超时（" + (stepTimeoutMs / 60000) + " 分钟）——未完成材料请到知识库面板处理后重试本段");

        // 4) 首次 Wiki 编译至 READY（WIKI 上下文打包的前置；READY 且指纹一致时为幂等空操作）
        ensureWikiReady(job);
    }

    /** ②/③ agent 段：runNode 派发（复用其上下文打包+进度/证据采集），轮询等待轮次离开 RUNNING */
    private void runAgentStage(SupplierJob job, String nodeId, String label) throws InterruptedException {
        SupplierJob fresh = jobRepo.findById(job.getId())
                .orElseThrow(() -> new IllegalStateException("任务已不存在: " + job.getId()));
        jobService.runNode(fresh.getId(), nodeId, "");
        waitUntil(job.getId(), () -> {
            SupplierJobNode n = nodeRepo.findByJobIdAndNodeId(job.getId(), nodeId).orElse(null);
            if (n == null) return "节点不存在: " + nodeId;
            if ("RUNNING".equals(n.getStatus())) return null;   // 含【卡点】挂起待答——工作台答复续跑后自然离开
            if (!n.getNote().isBlank()) return label + "失败：" + n.getNote();
            if (n.getArtifact() == null || n.getArtifact().isBlank()) return label + "结束但未产出工件，请重试本段";
            return "";
        }, label + "等待超时（" + (stepTimeoutMs / 60000) + " 分钟），请重试本段");
    }

    /** ④ 归档编译：n1/n2 工件+实测证据归档入库（source 幂等）→ 节点置 DONE → Wiki 编译至 READY */
    private void runArchiveStage(SupplierJob job) throws InterruptedException {
        SupplierJob fresh = jobRepo.findById(job.getId())
                .orElseThrow(() -> new IllegalStateException("任务已不存在: " + job.getId()));
        for (String nodeId : List.of(NODE_ANALYSIS, NODE_PROBE)) {
            SupplierJobNode n = nodeRepo.findByJobIdAndNodeId(fresh.getId(), nodeId).orElse(null);
            if (n == null) continue;
            // 与 completeNode 相同的归档内容规则；archiveArtifact 按 source "job:X/node:Y" 幂等
            kbService.archiveArtifact(fresh, nodeId, n.getTitle(),
                    n.getArtifact() + (n.getToolEvidence().isBlank() ? "" : "\n\n# 实测执行证据\n" + n.getToolEvidence()));
            if (!"DONE".equals(n.getStatus())) {
                n.setStatus("DONE");
                n.setDoneAt(LocalDateTime.now());
                nodeRepo.save(n);
            }
        }
        ensureWikiReady(fresh);
    }

    /**
     * 编译并等待 Wiki 至 READY：EMPTY/STALE → build（"正在编译"并发异常捕获忽略，转入等待）；
     * BUILDING → 等待；FAILED → 本次调用内自动重建一次（对齐 scheduleWikiRecompile 的
     * "非 READY/BUILDING 即重建"惯例——否则重启把 BUILDING 置 FAILED 后，「从本段重试」
     * 会永远撞上旧 FAILED 版本无法续跑），重建后仍 FAILED 才置段失败；READY → 完成
     * （指纹一致时整段为幂等空操作）。
     */
    private void ensureWikiReady(SupplierJob job) throws InterruptedException {
        final boolean[] rebuilt = { false };
        waitUntil(job.getId(), () -> {
            Map<String, Object> st = wikiService.status(job);
            String status = String.valueOf(st.get("status"));
            switch (status) {
                case "READY":
                    return "";
                case "BUILDING":
                    return null;
                case "FAILED":
                    if (rebuilt[0]) return "Wiki 编译失败：" + st.get("error");
                    rebuilt[0] = true;
                    return triggerWikiBuild(job);
                default:   // EMPTY / STALE
                    return triggerWikiBuild(job);
            }
        }, "Wiki 编译等待超时（" + (stepTimeoutMs / 60000) + " 分钟），请重试本段");
    }

    /** 触发一次 Wiki 编译：null=已启动（或已有并发编译），转入等待；非 null=启动失败，作为段失败原因抛出 */
    private String triggerWikiBuild(SupplierJob job) {
        try {
            wikiService.build(job);
            return null;
        } catch (IllegalArgumentException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            if (msg.contains("正在编译")) return null;   // 并发编译（自动重编译等）——等待即可
            return "Wiki 编译启动失败：" + msg;
        }
    }

    // ===== 等待原语（sleeper/超时可注入，单测快进） =====

    /** 等待探针：null=继续等；空串=成功；其余=段失败原因 */
    private interface WaitProbe { String check(); }

    /**
     * 轮询等待，每轮先确认任务还存在——用户删除运行中任务后（deleteJob 不取消在飞轮次），
     * 流水线在下一个轮询点（≤pollInterval）自行终止，不再触发后续 runNode/Wiki 编译等实例侧开销。
     */
    private void waitUntil(Long jobId, WaitProbe probe, String timeoutHint) throws InterruptedException {
        long elapsed = 0;
        while (true) {
            if (jobRepo.findById(jobId).isEmpty()) throw new IllegalStateException("任务已删除，流水线终止");
            String result = probe.check();
            if (result != null) {
                if (result.isBlank()) return;
                throw new IllegalStateException(result);
            }
            if (elapsed >= stepTimeoutMs) throw new IllegalStateException(timeoutHint);
            sleeper.sleep(pollIntervalMs);
            elapsed += pollIntervalMs;
        }
    }

    // ===== 段状态读写 =====

    private String currentPhase(Long jobId) {
        return jobRepo.findById(jobId).map(SupplierJob::getQuickPhase).orElse("");
    }

    private void setPhase(Long jobId, String phase) {
        jobRepo.findById(jobId).ifPresent(j -> {
            j.setQuickPhase(phase);
            j.setQuickError("");
            jobRepo.save(j);
        });
    }

    private void markFailed(Long jobId, String message) {
        try {
            jobRepo.findById(jobId).ifPresent(j -> {
                String m = message == null || message.isBlank() ? "未知错误" : message;
                j.setQuickError(m.length() > 4000 ? m.substring(0, 4000) + "…" : m);
                jobRepo.save(j);
            });
        } catch (Exception e) {
            log.warn("[QuickProbe] 任务 {} 失败状态落库失败: {}", jobId, e.getMessage());
        }
    }

    /** 纯函数便于单测：由 phase+error 推导某段的展示状态（DONE/RUNNING/FAILED/PENDING） */
    static String stepStatus(String phase, String error, String stepKey) {
        if (PHASE_DONE.equals(phase)) return "DONE";
        int cur = PHASES.indexOf(phase == null ? "" : phase);
        int idx = PHASES.indexOf(stepKey);
        if (cur < 0 || idx < 0) return "PENDING";
        if (idx < cur) return "DONE";
        if (idx > cur) return "PENDING";
        return error == null || error.isBlank() ? "RUNNING" : "FAILED";
    }

    // ===== 查询（控制器 JSON 数据源） =====

    public List<SupplierJob> list() {
        return jobRepo.findAllByQuickTrueOrderByUpdatedAtDesc();
    }

    public boolean isRunning(Long jobId) {
        return orchestrating.contains(jobId);
    }

    /** 列表行 JSON（轻量：不触发 Wiki 指纹重算） */
    public JsonArray listJson() {
        JsonArray arr = new JsonArray();
        for (SupplierJob j : list()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", j.getId());
            o.addProperty("name", j.getName());
            o.addProperty("supplierCode", j.getSupplierCode());
            if (j.getInstanceId() != null) o.addProperty("instanceId", j.getInstanceId());
            o.addProperty("phase", j.getQuickPhase());
            o.addProperty("error", j.getQuickError());
            o.addProperty("running", orchestrating.contains(j.getId()));
            o.addProperty("status", j.getStatus());
            o.addProperty("createdAt", String.valueOf(j.getCreatedAt()));
            o.addProperty("updatedAt", String.valueOf(j.getUpdatedAt()));
            arr.add(o);
        }
        return arr;
    }

    /** 详情 JSON：任务 + 四段状态 + 节点实时进度 + Wiki 状态 + 知识库条目（前端 3s 轮询的唯一数据源） */
    public JsonObject detail(Long jobId) {
        SupplierJob job = requireQuick(jobId);
        JsonObject o = new JsonObject();
        o.addProperty("id", job.getId());
        o.addProperty("name", job.getName());
        o.addProperty("supplierCode", job.getSupplierCode());
        if (job.getInstanceId() != null) o.addProperty("instanceId", job.getInstanceId());
        o.addProperty("status", job.getStatus());
        o.addProperty("phase", job.getQuickPhase());
        o.addProperty("error", job.getQuickError());
        o.addProperty("running", orchestrating.contains(jobId));
        o.addProperty("kbCode", kbService.kbCode(job));
        o.addProperty("createdAt", String.valueOf(job.getCreatedAt()));
        o.addProperty("updatedAt", String.valueOf(job.getUpdatedAt()));

        JsonArray steps = new JsonArray();
        for (String phase : PHASES) {
            JsonObject s = new JsonObject();
            s.addProperty("key", phase);
            s.addProperty("status", stepStatus(job.getQuickPhase(), job.getQuickError(), phase));
            steps.add(s);
        }
        o.add("steps", steps);

        JsonArray nodes = new JsonArray();
        for (SupplierJobNode n : jobService.getNodes(jobId)) {
            JsonObject no = new JsonObject();
            no.addProperty("nodeId", n.getNodeId());
            no.addProperty("title", n.getTitle());
            no.addProperty("status", n.getStatus());
            no.addProperty("progress", n.getProgress());
            no.addProperty("thinking", n.getThinking());
            no.addProperty("pendingQuestion", n.getPendingQuestion());
            no.addProperty("note", n.getNote());
            no.addProperty("artifact", n.getArtifact());
            no.addProperty("hasEvidence", !n.getToolEvidence().isBlank());
            no.addProperty("lastRunAt", String.valueOf(n.getLastRunAt()));
            no.addProperty("doneAt", String.valueOf(n.getDoneAt()));
            nodes.add(no);
        }
        o.add("nodes", nodes);

        try {
            o.add("wiki", new com.google.gson.Gson().toJsonTree(wikiService.status(job)));
        } catch (Exception e) {
            JsonObject w = new JsonObject();
            w.addProperty("status", "ERROR");
            w.addProperty("error", e.getMessage());
            o.add("wiki", w);
        }

        JsonArray entries = new JsonArray();
        for (SupplierKbEntry e : kbService.list(kbService.kbCode(job))) {
            JsonObject eo = new JsonObject();
            eo.addProperty("id", e.getId());
            eo.addProperty("title", e.getTitle());
            eo.addProperty("type", e.getType());
            eo.addProperty("status", e.getStatus());
            eo.addProperty("source", e.getSource());
            eo.addProperty("error", e.getError());
            eo.addProperty("contextStage", e.getContextStage());
            entries.add(eo);
        }
        o.add("kbEntries", entries);
        return o;
    }

    // ===== 小工具 =====

    /** docsJson 行 → 来源引用（type 重新识别；site 标记来自创建时的抓取选项） */
    record SourceRef(String type, String value, boolean site) {}

    static List<SourceRef> parseDocs(String docsJson) {
        List<SourceRef> refs = new ArrayList<>();
        try {
            for (JsonElement el : JsonParser.parseString(docsJson == null || docsJson.isBlank() ? "[]" : docsJson).getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                JsonObject d = el.getAsJsonObject();
                String value = str(d, "value", "").strip();
                if (value.isEmpty()) continue;
                boolean site = d.has("site") && d.get("site").getAsBoolean();
                refs.add(new SourceRef(detectType(value), value, site));
            }
        } catch (Exception e) {
            throw new IllegalStateException("文档来源清单解析失败: " + e.getMessage());
        }
        return refs;
    }

    private static String title(SupplierKbEntry e) {
        return e.getTitle() == null || e.getTitle().isBlank() ? "(未命名条目#" + e.getId() + ")" : e.getTitle();
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }
}
