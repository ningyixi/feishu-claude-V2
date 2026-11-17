package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.model.entity.AdHocChat;
import com.lark.oapi.sample.ws.model.entity.AdHocTask;
import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.model.entity.SupplierKbEntry;
import com.lark.oapi.sample.ws.model.entity.SupplierWikiRevision;
import com.lark.oapi.sample.ws.repository.AdHocChatRepository;
import com.lark.oapi.sample.ws.repository.AdHocTaskRepository;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.SupplierWikiRevisionRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 临时需求编排（11-17-adhoc-requirement-module）：侧边栏「临时需求」模块的两段流水线 + 对话待命。
 * <p>
 * 创建一个任务 → 传入关联文档（多行来源）→ 虚拟线程串行推进两段：
 * <ol>
 *   <li><b>INGEST 入库解析</b>：来源逐条进知识库并解析至 DONE（复用 {@link SupplierKbService}
 *       add/reparse 管线，含 URL/PDF/本地 HTML/md；按 type+source 去重 → 重试幂等）。</li>
 *   <li><b>WIKI 编译</b>：复用 {@link SupplierWikiService#build} 编译 LLM Wiki 直至 READY。</li>
 * </ol>
 * 然后 status=READY 待命——用户在页面对话框直接吩咐临时小改，每轮经
 * {@link ClaudeCodeService#chat} 打到<b>绑定实例主会话</b>（与飞书私聊天然同 sessionID/同队列，
 * 本服务绝不使用 lane），消息头部注入该任务 Wiki 的可定位入口（版本+页面清单+路径，不塞全文）。
 * <p>
 * <b>解耦红线</b>：任务本体是独立实体 {@link AdHocTask}（表 ad_hoc_task），不写 supplier_job* 任何表、
 * 不依赖 QuickProbe/SupplierJobService 的编排与 phase 记账；对 {@link SupplierWikiService} 的复用通过
 * 一个<b>从不落库的 transient SupplierJob 适配器</b>（{@link #wikiJob}）完成——该服务现有签名以 job 为
 * 上下文载体，本模块无 job 行，也不为此改它一行。Wiki 状态读取走 {@link SupplierWikiRevisionRepository}
 * 轻量单行读（对齐 {@code feishuPointer} 的"热路径不做指纹重算"纪律）。
 * <p>
 * 段状态落库：status=当前段（INGEST/WIKI）+ error（空=推进中，非空=停在该段失败，可「重试本段」）；
 * READY 为终态。等待原语（轮询间隔/超时/睡眠器/流水线启动器）可注入，便于单测快进。
 * 重启恢复：编排线程不跨进程存活——启动时把未 READY 且无错误标记的任务置"重启中断，请重试本段"。
 */
@Service
public class AdHocService {

    private static final Logger log = LoggerFactory.getLogger(AdHocService.class);

    /** 两段流水线的段标识（顺序即执行顺序；READY 为终态=对话待命） */
    public static final String STAGE_INGEST = "INGEST";
    public static final String STAGE_WIKI = "WIKI";
    public static final String STATUS_READY = "READY";
    static final List<String> STAGES = List.of(STAGE_INGEST, STAGE_WIKI);

    /** 文档来源清单上限：单任务定位（非批量工具），超限直接拒绝 */
    static final int MAX_SOURCE_LINES = 50;
    /** 单条来源行长度上限（URL/本地路径都远小于此值） */
    static final int MAX_SOURCE_LINE_CHARS = 2000;
    /** Wiki 页面清单注入上限（A3：注入入口不注入全文；超出部分指向 index.md） */
    static final int MAX_HEADER_PAGES = 60;
    /** 注入头部总长上限（防御性截断，页面摘要再长也吃不爆上下文） */
    static final int MAX_HEADER_CHARS = 8000;

    private final AdHocTaskRepository taskRepo;
    private final AdHocChatRepository chatRepo;
    private final BotInstanceRepository instanceRepo;
    private final SupplierKbService kbService;
    private final SupplierWikiService wikiService;
    private final SupplierWikiRevisionRepository wikiRevisions;
    private final ClaudeCodeService claudeCodeService;

    /** 编排中的任务（防重复启动/重复重试；进程内即可——重启后由 recoverInterrupted 兜底） */
    private final Set<Long> orchestrating = ConcurrentHashMap.newKeySet();

    /** 轮询间隔（毫秒）——测试可注入 */
    long pollIntervalMs = 3000;
    /** 单段等待超时（毫秒）：60 分钟对齐实例轮次硬超时——测试可注入 */
    long stepTimeoutMs = 60 * 60 * 1000L;

    /** 睡眠器（抽出来便于单测快进） */
    interface Sleeper { void sleep(long millis) throws InterruptedException; }
    Sleeper sleeper = Thread::sleep;

    /** 异步任务启动器（流水线 / 对话轮次）：生产 = 具名虚拟线程；测试注入同步执行便于确定性断言 */
    java.util.function.BiConsumer<String, Runnable> starter =
            (name, task) -> Thread.ofVirtual().name(name).start(task);

    public AdHocService(AdHocTaskRepository taskRepo,
                        AdHocChatRepository chatRepo,
                        BotInstanceRepository instanceRepo,
                        SupplierKbService kbService,
                        SupplierWikiService wikiService,
                        SupplierWikiRevisionRepository wikiRevisions,
                        ClaudeCodeService claudeCodeService) {
        this.taskRepo = taskRepo;
        this.chatRepo = chatRepo;
        this.instanceRepo = instanceRepo;
        this.kbService = kbService;
        this.wikiService = wikiService;
        this.wikiRevisions = wikiRevisions;
        this.claudeCodeService = claudeCodeService;
    }

    // ===== 启动恢复 =====

    /** 重启清扫：编排线程已随上个进程消失——未 READY 且无错误标记的任务置"中断，请重试本段" */
    @PostConstruct
    public void recoverInterrupted() {
        try {
            for (AdHocTask t : taskRepo.findAll()) {
                if (STATUS_READY.equals(t.getStatus()) || !t.getError().isBlank()) continue;
                t.setError("服务重启中断了流水线，请重试本段");
                taskRepo.save(t);
                log.warn("[AdHoc] 任务 {} 因重启中断，段={}，等待用户重试", t.getId(), t.getStatus());
            }
        } catch (Exception e) {
            log.warn("[AdHoc] 重启恢复扫描失败（不影响启动）: {}", e.getMessage());
        }
        // 对话轮次同理：PENDING 的 agent 行永远等不到回写（轮次线程已随进程消失）→ 落终态，前端停止转圈
        try {
            for (AdHocChat c : chatRepo.findByStatus("PENDING")) {
                c.setStatus("FAILED");
                c.setText("服务重启中断了本轮对话，请重新发送");
                chatRepo.save(c);
                log.warn("[AdHoc] 对话行 {} 因重启中断，已置 FAILED", c.getId());
            }
        } catch (Exception e) {
            log.warn("[AdHoc] 对话中断清扫失败（不影响启动）: {}", e.getMessage());
        }
    }

    // ===== 创建 =====

    /**
     * 创建临时需求任务并立即启动两段流水线。
     *
     * @param name       任务名（留空自动生成"临时需求-&lt;时间戳&gt;"兜底）
     * @param instanceId 绑定实例（Wiki 编译与后续对话都在这台实例的主会话上）
     * @param docSources 关联文档多行原文（每行一条：http(s) 链接 / .pdf/.html/.htm/.md/.markdown/.txt 本地路径）
     */
    public AdHocTask create(String name, Long instanceId, String docSources) {
        if (instanceId == null) throw new IllegalArgumentException("请绑定执行实例");
        BotInstance inst = instanceRepo.findById(instanceId)
                .orElseThrow(() -> new IllegalArgumentException("执行实例不存在: " + instanceId));
        if (!inst.isEnabled()) throw new IllegalArgumentException("执行实例已停用，请选择可用实例");

        List<String> lines = sourceLines(docSources);
        if (lines.isEmpty()) throw new IllegalArgumentException("请至少填写 1 条关联文档来源");
        if (lines.size() > MAX_SOURCE_LINES) {
            throw new IllegalArgumentException("关联文档过多（单次 ≤" + MAX_SOURCE_LINES + " 条），请精简后重试");
        }
        // 来源行预检：类型可识别 + 本地文件存在（URL 安全校验留给入库管线的 assertSafeUrl）
        for (String value : lines) {
            if (value.length() > MAX_SOURCE_LINE_CHARS) {
                throw new IllegalArgumentException("单条文档来源过长（≤" + MAX_SOURCE_LINE_CHARS + " 字符）");
            }
            String type = detectType(value);
            if (!"url".equals(type) && !Files.isRegularFile(Paths.get(value))) {
                throw new IllegalArgumentException("文档来源文件不存在: " + value);
            }
        }

        AdHocTask t = new AdHocTask();
        t.setName(name == null || name.isBlank()
                ? "临时需求-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                : name.strip());
        t.setDocSources(String.join("\n", lines));
        t.setInstanceId(instanceId);
        t.setStatus(STAGE_INGEST);
        t.setWikiStatus("EMPTY");
        t.setError("");
        taskRepo.save(t);
        t.setKbCode("adhoc-" + t.getId());   // 专属命名空间：不与其他任务共库，删除可整库清理
        taskRepo.save(t);
        log.info("[AdHoc] 临时需求任务已创建: id={}, kb={}, 来源{}条, 实例={}", t.getId(), t.getKbCode(), lines.size(), instanceId);
        startPipeline(t.getId(), STAGE_INGEST);
        return t;
    }

    /** 多行来源原文 → 规范化行清单（去空行/去首尾空白/去重，保持顺序） */
    static List<String> sourceLines(String docSources) {
        Set<String> lines = new LinkedHashSet<>();
        if (docSources != null) {
            for (String raw : docSources.split("\n")) {
                String v = raw == null ? "" : raw.strip();
                if (!v.isEmpty()) lines.add(v);
            }
        }
        return List.copyOf(lines);
    }

    /** 来源行类型识别（与 KB 入库管线的映射一致）：http(s) → url；扩展名 → pdf / html / md；其余拒绝 */
    static String detectType(String line) {
        String v = line == null ? "" : line.strip();
        String low = v.toLowerCase();
        if (low.startsWith("http://") || low.startsWith("https://")) return "url";
        if (low.endsWith(".pdf")) return "pdf";
        if (low.endsWith(".html") || low.endsWith(".htm")) return "html";
        if (low.endsWith(".md") || low.endsWith(".markdown") || low.endsWith(".txt")) return "md";
        throw new IllegalArgumentException("无法识别的文档来源（支持 http(s) 链接 / .pdf/.html/.htm/.md/.markdown/.txt 本地路径）: " + v);
    }

    // ===== 重试 / 删除 =====

    /** 重试本段（幂等：入库按 type+source 去重、Wiki READY 即跳过）——从 status 记录的失败段续跑 */
    public void retryStage(Long taskId) {
        AdHocTask t = require(taskId);
        if (orchestrating.contains(taskId)) throw new IllegalArgumentException("流水线正在推进中，请等待当前段结束");
        if (t.getError().isBlank()) throw new IllegalArgumentException("任务未处于失败/中断状态，无需重试");
        String stage = STAGES.contains(t.getStatus()) ? t.getStatus() : STAGE_INGEST;
        t.setError("");
        taskRepo.save(t);
        startPipeline(taskId, stage);
    }

    /**
     * 删除任务：清掉本模块全部记录（任务行 + 对话流水 + 专属命名空间下的 KB 条目及其产物文件，
     * kbService.delete 自带墓碑/站点页行清理）。KB 命名空间 "adhoc-&lt;id&gt;" 为本任务专属，
     * 整库清理不会误删其他任务共用的库（AC5）；Wiki 版本行/目录是不可变历史，沿用现有语义保留。
     */
    @Transactional
    public void delete(Long taskId) {
        AdHocTask t = require(taskId);
        orchestrating.remove(taskId);   // 编排线程后续读写落空自然终止
        chatRepo.deleteByTaskId(taskId);
        for (SupplierKbEntry e : kbService.list(t.getKbCode())) {
            try {
                kbService.delete(e.getId());
            } catch (RuntimeException ex) {
                log.warn("[AdHoc] KB 条目 {} 清理失败（继续删除任务）: {}", e.getId(), ex.getMessage());
            }
        }
        taskRepo.delete(t);
        log.info("[AdHoc] 任务已删除: id={}, kb={}", taskId, t.getKbCode());
    }

    public AdHocTask require(Long taskId) {
        return taskRepo.findById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("临时需求任务不存在: " + taskId));
    }

    private void startPipeline(Long taskId, String fromStage) {
        // 原子占位：add 返回 false = 该任务已有流水线在跑（并发重试竞态）——拒绝而不是双跑
        if (!orchestrating.add(taskId)) {
            throw new IllegalArgumentException("流水线正在推进中，请等待当前段结束");
        }
        try {
            starter.accept("ad-hoc-pipeline-" + taskId, () -> {
                try {
                    runPipeline(taskId, fromStage);
                } finally {
                    orchestrating.remove(taskId);
                }
            });
        } catch (RuntimeException ex) {
            orchestrating.remove(taskId);
            // 连编排线程都起不来：把段落成 FAILED，否则页面停在"推进中"永远转圈
            markFailed(taskId, "流水线启动失败：" + ex.getMessage());
            throw ex;
        }
    }

    // ===== 流水线（同步执行体；生产由 startPipeline 放到虚拟线程） =====

    void runPipeline(Long taskId, String fromStage) {
        AdHocTask task = taskRepo.findById(taskId).orElse(null);
        if (task == null) return;
        try {
            for (int i = STAGES.indexOf(fromStage); i < STAGES.size(); i++) {
                String stage = STAGES.get(i);
                setStage(taskId, stage);
                log.info("[AdHoc] 任务 {} 进入段 {}", taskId, stage);
                if (STAGE_INGEST.equals(stage)) runIngestStage(task);
                else if (STAGE_WIKI.equals(stage)) runWikiStage(task);
                else throw new IllegalStateException("未知段: " + stage);
            }
            taskRepo.findById(taskId).ifPresent(t -> {
                t.setStatus(STATUS_READY);
                t.setWikiStatus("READY");
                t.setError("");
                taskRepo.save(t);
            });
            log.info("[AdHoc] 任务 {} Wiki 就绪，进入对话待命", taskId);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            markFailed(taskId, "流水线等待被中断（服务可能正在重启），请重试本段");
        } catch (Exception e) {
            markFailed(taskId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            log.warn("[AdHoc] 任务 {} 流水线失败于段 {}: {}", taskId, currentStatus(taskId), e.getMessage());
        } catch (Throwable t) {
            // Error（OOM/StackOverflow 等）也要落 FAILED：任何出口都不得把任务留在"推进中"永远转圈
            markFailed(taskId, "流水线异常终止: " + t);
            log.error("[AdHoc] 任务 {} 流水线异常终止于段 {}", taskId, currentStatus(taskId), t);
        }
    }

    /** ① 入库解析：来源逐条进知识库（幂等）→ 触发解析 → 等全部材料 DONE */
    private void runIngestStage(AdHocTask task) throws InterruptedException {
        String kbCode = task.getKbCode();
        List<String> lines = sourceLines(task.getDocSources());
        if (lines.isEmpty()) throw new IllegalStateException("任务没有配置文档来源");

        // 1) 逐条入库（已存在同 type+source 则跳过——重试幂等，不产生重复条目）
        Set<String> existing = new HashSet<>();
        for (SupplierKbEntry e : kbService.list(kbCode)) {
            existing.add(e.getType() + "\u0000" + (e.getSource() == null ? "" : e.getSource()));
        }
        for (String value : lines) {
            String type = detectType(value);
            if (existing.contains(type + "\u0000" + value)) continue;
            kbService.add(kbCode, type, value, null);
        }

        // 2) 触发解析（RAW=刚入库原文引用 / FAILED=上次解析失败；url/pdf/html 走异步解析，md 入库即 DONE）
        for (SupplierKbEntry e : kbService.list(kbCode)) {
            if (("RAW".equals(e.getStatus()) || "FAILED".equals(e.getStatus()))
                    && List.of("url", "pdf", "html", "md").contains(e.getType())) {
                kbService.reparse(e.getId());
            }
        }

        // 3) 等待全部材料离开过渡态（DONE=就绪；FAILED=段失败并给出原因）
        waitUntil(task.getId(), () -> {
            List<SupplierKbEntry> entries = kbService.list(kbCode);
            List<String> failed = new ArrayList<>();
            List<String> busy = new ArrayList<>();
            for (SupplierKbEntry e : entries) {
                switch (e.getStatus() == null ? "" : e.getStatus()) {
                    case "DONE" -> { }
                    case "FAILED" -> failed.add(title(e) + "（" + e.getError() + "）");
                    default -> busy.add(title(e) + "[" + e.getStatus() + "]");
                }
            }
            if (!failed.isEmpty()) return "文档解析失败：" + String.join("；", failed) + "——修复后重试本段";
            if (!busy.isEmpty()) return null;   // 继续等
            return entries.isEmpty() ? "知识库没有可解析的材料" : "";
        }, "文档解析等待超时（" + (stepTimeoutMs / 60000) + " 分钟）——未完成材料请重试本段");
    }

    /**
     * ② 编译 Wiki 至 READY：EMPTY → build；BUILDING → 等待；FAILED → 本次调用内自动重建一次
     * （对齐 QuickProbe/Wiki 惯例——否则重启把 BUILDING 置 FAILED 后「重试本段」会永远撞旧 FAILED 行），
     * 重建仍 FAILED 才置段失败；READY → 完成。状态读取走版本行轻量单读，不做来源指纹重算。
     */
    private void runWikiStage(AdHocTask task) throws InterruptedException {
        final boolean[] rebuilt = { false };
        waitUntil(task.getId(), () -> {
            SupplierWikiRevision latest = wikiRevisions.findFirstByKbCodeOrderByIdDesc(task.getKbCode()).orElse(null);
            String status = latest == null ? "EMPTY" : (latest.getStatus() == null ? "EMPTY" : latest.getStatus());
            updateWikiStatus(task.getId(), status);
            switch (status) {
                case "READY":
                    return "";
                case "BUILDING":
                    return null;
                case "FAILED":
                    if (rebuilt[0]) return "Wiki 编译失败：" + (latest.getError() == null || latest.getError().isBlank() ? "未知原因" : latest.getError());
                    rebuilt[0] = true;
                    return triggerWikiBuild(task);
                default:   // EMPTY
                    return triggerWikiBuild(task);
            }
        }, "Wiki 编译等待超时（" + (stepTimeoutMs / 60000) + " 分钟），请重试本段");
    }

    /** 触发一次 Wiki 编译：null=已启动（或已有并发编译），转入等待；非 null=启动失败，作为段失败原因抛出 */
    private String triggerWikiBuild(AdHocTask task) {
        try {
            wikiService.build(wikiJob(task));
            return null;
        } catch (IllegalArgumentException ex) {
            String msg = ex.getMessage() == null ? "" : ex.getMessage();
            if (msg.contains("正在编译")) return null;   // 并发编译——等待即可
            return "Wiki 编译启动失败：" + msg;
        }
    }

    /**
     * transient SupplierJob 适配器（<b>从不落库</b>）：{@link SupplierWikiService} 现有签名以 job 为上下文
     * 载体（实际只读 knowledgeMode/instanceId/supplierCode/supplierName/docsJson 五个字段，编译与版本行
     * 全部按 kbCode 归属），本模块用一次性对象满足签名，避免改它一行、也避免往 supplier_job 表塞行。
     */
    SupplierJob wikiJob(AdHocTask task) {
        SupplierJob adapter = new SupplierJob();
        adapter.setSupplierCode(task.getKbCode());
        adapter.setSupplierName(task.getName());
        adapter.setKnowledgeMode("WIKI");
        adapter.setInstanceId(task.getInstanceId());
        adapter.setDocsJson("[]");   // 来源已由 INGEST 段入库，编译前无需再同步
        return adapter;
    }

    // ===== 等待原语（sleeper/超时可注入，单测快进） =====

    /** 等待探针：null=继续等；空串=成功；其余=段失败原因 */
    private interface WaitProbe { String check(); }

    /**
     * 轮询等待，每轮先确认任务还存在——用户删除运行中任务后，流水线在下一个轮询点（≤pollInterval）
     * 自行终止，不再触发后续 Wiki 编译等实例侧开销。
     */
    private void waitUntil(Long taskId, WaitProbe probe, String timeoutHint) throws InterruptedException {
        long elapsed = 0;
        while (true) {
            if (taskRepo.findById(taskId).isEmpty()) throw new IllegalStateException("任务已删除，流水线终止");
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

    private String currentStatus(Long taskId) {
        return taskRepo.findById(taskId).map(AdHocTask::getStatus).orElse("");
    }

    private void setStage(Long taskId, String stage) {
        taskRepo.findById(taskId).ifPresent(t -> {
            t.setStatus(stage);
            t.setError("");
            taskRepo.save(t);
        });
    }

    private void updateWikiStatus(Long taskId, String wikiStatus) {
        taskRepo.findById(taskId).ifPresent(t -> {
            if (!wikiStatus.equals(t.getWikiStatus())) {
                t.setWikiStatus(wikiStatus);
                taskRepo.save(t);
            }
        });
    }

    private void markFailed(Long taskId, String message) {
        try {
            taskRepo.findById(taskId).ifPresent(t -> {
                String m = message == null || message.isBlank() ? "未知错误" : message;
                t.setError(m.length() > 3900 ? m.substring(0, 3900) + "…" : m);
                taskRepo.save(t);
            });
        } catch (Exception e) {
            log.warn("[AdHoc] 任务 {} 失败状态落库失败: {}", taskId, e.getMessage());
        }
    }

    /** 纯函数便于单测：由 status+error 推导某段的展示状态（DONE/RUNNING/FAILED/PENDING） */
    static String stepStatus(String status, String error, String stageKey) {
        if (STATUS_READY.equals(status)) return "DONE";
        int cur = STAGES.indexOf(status == null ? "" : status);
        int idx = STAGES.indexOf(stageKey);
        if (cur < 0 || idx < 0) return "PENDING";
        if (idx < cur) return "DONE";
        if (idx > cur) return "PENDING";
        return error == null || error.isBlank() ? "RUNNING" : "FAILED";
    }

    // ===== 对话待命（A3）：Wiki 入口注入 + 实例主会话 =====

    /** Wiki 页面清单项（注入用摘要，不含正文） */
    public record PageBrief(String id, String title, String summary) {}

    /** 轻量 Wiki 快照：一行版本记录 + manifest 页面清单（零文件 IO，热路径每轮对话可读） */
    public record WikiSnapshot(String status, Long revision, int sourceCount, String directory,
                               String phase, String error, List<PageBrief> pages) {
        static final WikiSnapshot EMPTY = new WikiSnapshot("EMPTY", null, 0, "", "", "", List.of());
    }

    WikiSnapshot wikiSnapshot(String kbCode) {
        SupplierWikiRevision latest = wikiRevisions.findFirstByKbCodeOrderByIdDesc(kbCode).orElse(null);
        if (latest == null) return WikiSnapshot.EMPTY;
        String status = latest.getStatus() == null ? "EMPTY" : latest.getStatus();
        return new WikiSnapshot(status, latest.getId(), latest.getSourceCount(),
                latest.getDirectory() == null ? "" : latest.getDirectory(),
                latest.getPhase() == null ? "" : latest.getPhase(),
                latest.getError() == null ? "" : latest.getError(),
                "READY".equals(status) ? parsePageBriefs(latest.getManifestJson()) : List.of());
    }

    /** 纯函数便于单测：manifestJson → 页面清单摘要（id/title/summary）；解析失败按空清单（注入降级为无清单） */
    static List<PageBrief> parsePageBriefs(String manifestJson) {
        List<PageBrief> briefs = new ArrayList<>();
        try {
            JsonObject manifest = JsonParser.parseString(manifestJson == null || manifestJson.isBlank() ? "{}" : manifestJson).getAsJsonObject();
            if (!manifest.has("pages") || !manifest.get("pages").isJsonArray()) return briefs;
            for (var el : manifest.getAsJsonArray("pages")) {
                if (!el.isJsonObject()) continue;
                JsonObject p = el.getAsJsonObject();
                briefs.add(new PageBrief(text(p, "id"), text(p, "title"), text(p, "summary")));
            }
        } catch (RuntimeException ignore) {
            // manifest 异常不阻断对话：降级为无页面清单
        }
        return briefs;
    }

    /**
     * 纯函数便于单测（A3 注入面）：拼「任务 LLM Wiki 可定位入口」头部——版本 + 索引/页面/原文路径 +
     * 页面清单（id/标题/摘要，≤{@value #MAX_HEADER_PAGES} 页，总长 ≤{@value #MAX_HEADER_CHARS} 字符）。
     * <b>只注入入口不注入全文</b>：几十页正文会吃光上下文，agent 按路径 Read 即可。
     * 措辞遵守 WORDING_RULE 纪律（本段自身无禁词；纪律句由 {@link #buildChatPrompt} 尾部统一拼接）。
     * <p>
     * Wiki 未就绪（任务仍在 INGEST/WIKI 段，或版本行 EMPTY/BUILDING/FAILED）时<b>不注入入口段</b>，
     * 只给一行"尚未就绪（当前阶段 X）+ 需要文档请给路径"——对话本身任何阶段都允许（见 {@link #ask}）。
     */
    static String wikiHeaderBlock(String taskName, String taskStatus, WikiSnapshot wiki) {
        StringBuilder sb = new StringBuilder();
        sb.append("【临时需求任务】").append(taskName == null || taskName.isBlank() ? "(未命名)" : taskName).append('\n');
        String status = wiki == null ? "EMPTY" : wiki.status();
        if (wiki != null && "READY".equals(status)) {
            sb.append("【任务 LLM Wiki】版本 #").append(wiki.revision()).append("（").append(wiki.sourceCount()).append(" 个来源）已就绪。\n");
            String dir = wiki.directory();
            sb.append("索引先读：").append(dir).append("/wiki/index.md；页面正文 ").append(dir).append("/wiki/<页面ID>.md；原文 ").append(dir).append("/raw/<来源ID>.md（均允许 Read）。\n");
            if (!wiki.pages().isEmpty()) {
                sb.append("页面清单（按需 Read，禁止凭记忆猜测）：\n");
                int shown = 0;
                for (PageBrief p : wiki.pages()) {
                    if (shown >= MAX_HEADER_PAGES) {
                        sb.append("- ……其余 ").append(wiki.pages().size() - shown).append(" 页见 index.md\n");
                        break;
                    }
                    String summary = p.summary() == null ? "" : p.summary().strip();
                    if (summary.length() > 160) summary = summary.substring(0, 160) + "…";
                    sb.append("- ").append(p.id()).append('｜').append(p.title()).append(summary.isEmpty() ? "" : "：" + summary).append('\n');
                    shown++;
                }
            }
            sb.append("规则：回答本任务接口/字段/流程问题前先读相关页面并引用页面 ID；Wiki 未覆盖的内容明说，不得猜测；只做用户本轮吩咐的事，不要重跑或中断其他任务。");
        } else {
            // 未就绪（任务仍在 INGEST/WIKI 段，或版本行 EMPTY/BUILDING/FAILED）：不注入入口段，
            // 只给一行阶段说明 + 指路——对话本身不设阶段门禁（见 ask），别让用户在编译期"什么都不能说"
            sb.append("该任务 Wiki 尚未就绪（当前阶段：").append(stageHint(taskStatus, wiki))
                    .append("），如需引用文档请直接给路径；本轮不要声称读过 Wiki，也不要猜测其内容。");
        }
        String header = sb.toString();
        if (header.length() > MAX_HEADER_CHARS) header = header.substring(0, MAX_HEADER_CHARS) + "\n……（清单超长已截断，完整页面索引见 index.md）";
        return header;
    }

    /** 纯函数便于单测：Wiki 未就绪时的"当前阶段"说明（任务段 + 版本状态/编译阶段/失败原因） */
    static String stageHint(String taskStatus, WikiSnapshot wiki) {
        String stage = STAGE_INGEST.equals(taskStatus) ? "入库解析中"
                : STAGE_WIKI.equals(taskStatus) ? "编译 Wiki 中"
                : STATUS_READY.equals(taskStatus) ? "对话待命"
                : taskStatus == null || taskStatus.isBlank() ? "未知" : taskStatus;
        String ws = wiki == null ? "EMPTY" : wiki.status();
        if ("BUILDING".equals(ws)) {
            return stage + " · Wiki 正在编译" + (wiki.phase().isBlank() ? "" : "（" + wiki.phase() + "）");
        }
        if ("FAILED".equals(ws)) {
            return stage + " · Wiki 上次编译失败：" + (wiki.error().isBlank() ? "未知原因" : wiki.error());
        }
        return stage + " · Wiki 尚未编译";
    }

    /**
     * 纯函数便于单测（A3 注入面）：每轮对话消息 = Wiki 入口头部 + 用户原文 + 措辞纪律。
     * 对话轮不走 ACTION_SPEC，须自带 {@link SupplierJobService#WORDING_RULE}（对齐 buildAskMessage 的
     * 注入面纪律，见 SupplierPromptWordingTest——本注入面不得遗漏）。
     */
    static String buildChatPrompt(String wikiHeader, String question) {
        return wikiHeader + "\n\n【用户吩咐】\n" + question.strip() + "\n" + SupplierJobService.WORDING_RULE;
    }

    /**
     * 用户在页面对话框吩咐一句：落库 user 行 + 预占 agent PENDING 行，随后经
     * {@link ClaudeCodeService#chat} 发到<b>绑定实例主会话</b>——与飞书私聊同一 TerminalSession/
     * 同一 sessionID/同一公平队列（架构既成事实，本服务不使用 lane、不 steer、不打断）：
     * 实例空闲立即回答；真有一轮在跑则照旧排队，忙完即答。
     * <p>
     * <b>任何任务阶段都可对话</b>（设计变更 2026-11-17）：Wiki 编译要几分钟、失败还会停在 WIKI 段，
     * 这期间把对话框门禁掉正是"被卡住"。未就绪时不注入 Wiki 入口段，改注入一行阶段说明
     * （见 {@link #wikiHeaderBlock}）；Wiki 编译走的是 lane 会话，与主会话对话互不阻塞。
     */
    public void ask(Long taskId, String question) {
        AdHocTask t = require(taskId);
        if (question == null || question.isBlank()) throw new IllegalArgumentException("内容不能为空");
        if (t.getInstanceId() == null) throw new IllegalArgumentException("任务未绑定执行实例，无法对话");
        BotInstance inst = instanceRepo.findById(t.getInstanceId())
                .orElseThrow(() -> new IllegalArgumentException("绑定实例不存在"));
        if (!inst.isEnabled()) throw new IllegalArgumentException("绑定实例已停用，无法对话");

        String prompt = buildChatPrompt(
                wikiHeaderBlock(t.getName(), t.getStatus(), wikiSnapshot(t.getKbCode())), question);

        AdHocChat u = new AdHocChat();
        u.setTaskId(taskId); u.setRole("user"); u.setText(question.strip());
        chatRepo.save(u);
        AdHocChat a = new AdHocChat();
        a.setTaskId(taskId); a.setRole("agent"); a.setStatus("PENDING"); a.setText("");
        chatRepo.save(a);

        // 目录/模型与飞书私聊同源同归一（null→""）：两侧传给 chat() 的配置必须逐字相同，
        // 否则 ClaudeCodeService.getOrCreate 会判定"配置变更"而重建实例主会话（=打断飞书那边的上下文）
        String dir = firstNonBlank(inst.getClaudeWorkingDir(), inst.getShellWorkingDir(), "");
        String model = inst.getClaudeModel() == null ? "" : inst.getClaudeModel();
        try {
            starter.accept("ad-hoc-chat-" + taskId, () -> {
                try {
                    String reply = claudeCodeService.chat(inst.getId(), "ad-hoc-task-" + taskId, prompt,
                            dir, model, chatProgressListener(a, inst.getId()));
                    boolean bad = reply == null || reply.startsWith("❌");
                    a.setText(bad ? (reply == null ? "（无回复）" : reply) : reply);
                    a.setStatus(bad ? "FAILED" : "DONE");
                } catch (Exception e) {
                    a.setText("对话投递失败: " + e.getMessage());
                    a.setStatus("FAILED");
                }
                saveQuietly(a);
            });
        } catch (RuntimeException ex) {
            // 投递线程都起不来：立刻把预占行落终态，否则前端永远转圈
            a.setText("对话投递失败: " + ex.getMessage());
            a.setStatus("FAILED");
            saveQuietly(a);
            throw ex;
        }
    }

    /** 对话行落库失败不得冒泡到 reader 线程/调用方（进度与终态回写尽力而为） */
    private void saveQuietly(AdHocChat row) {
        try {
            chatRepo.save(row);
        } catch (RuntimeException e) {
            log.warn("[AdHoc] 对话行 {} 落库失败: {}", row.getId(), e.getMessage());
        }
    }

    public List<AdHocChat> chatHistory(Long taskId) {
        return chatRepo.findByTaskIdOrderByIdAsc(taskId);
    }

    /**
     * 对话实时进度回写（沿用工作台对话区写法）：轮次阶段摘要节流（≥2 秒一次 save）写进 agent PENDING 行
     * 的 text 字段，前端轮询即可见"在做什么"；轮次结束后由 {@link #ask} 用最终回复覆盖。
     * 回调发生在 TerminalSession reader 线程上，写库失败静默——绝不影响对话主链。
     */
    private TurnListener chatProgressListener(AdHocChat a, Long instanceId) {
        return new TurnListener() {
            private final long sentAt = System.currentTimeMillis();
            private volatile long startAt = -1;      // <0 = 尚未开始执行（还在排队）
            private volatile String phase = "QUEUE";
            private volatile String toolName;
            private volatile String toolDetail;
            private volatile int queuedAhead = -1;
            private volatile long lastWrite;

            private void write(boolean force) {
                if (!"PENDING".equals(a.getStatus())) return;   // 已落终态（DONE/FAILED），进度不再覆盖
                long now = System.currentTimeMillis();
                if (!force && now - lastWrite < 2000) return;
                lastWrite = now;
                long elapsed = startAt >= 0 ? now - startAt : now - sentAt;
                String text = SupplierJobService.chatProgressText(phase, toolName, toolDetail, elapsed, queuedAhead);
                try {
                    if (!text.equals(a.getText())) { a.setText(text); chatRepo.save(a); }
                } catch (Exception ignore) { }
            }

            @Override public void onQueued() {
                TerminalSession ts = claudeCodeService.getSession(instanceId);
                // onQueued 时本条已登记 pendingQueue：快照条数 = 自己 + 前面排队的 ≈ 前面还有 N 条
                if (ts != null) queuedAhead = ts.queuedSnapshot().size();
                Thread.ofVirtual().name("ad-hoc-chat-queue-hint").start(() -> {
                    try { Thread.sleep(5000); } catch (InterruptedException e) { return; }
                    if (startAt < 0) write(true);   // 从发送到开始执行等待超过 5 秒才显示排队提示
                });
            }
            @Override public void onTurnStart() {
                startAt = System.currentTimeMillis();
                phase = "THINKING";
                write(true);
            }
            @Override public void onThinkingDelta(String delta) {
                if (startAt < 0) return;
                phase = "THINKING";
                write(false);
            }
            @Override public void onTextDelta(String delta) {
                if (startAt < 0) return;
                phase = "GENERATING";
                write(false);
            }
            @Override public void onToolStart(ToolStep step) {
                phase = "TOOL";
                toolName = step.name;
                toolDetail = step.detail;
                write(true);   // 工具切换是关键节点，跳过节流立即刷新
            }
            @Override public void onToolEnd(ToolStep step) {
                phase = "THINKING";
                toolName = null;
                toolDetail = null;
                write(false);
            }
            @Override public void onTurnComplete(TurnResult result) { }
            @Override public void onTurnError(String message, boolean aborted) { }
        };
    }

    // ===== 查询（控制器 JSON 数据源） =====

    public boolean isRunning(Long taskId) {
        return orchestrating.contains(taskId);
    }

    /** 列表行 JSON（轻量：不读 Wiki 版本行） */
    public JsonArray listJson() {
        JsonArray arr = new JsonArray();
        for (AdHocTask t : taskRepo.findAllByOrderByIdDesc()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", t.getId());
            o.addProperty("name", t.getName());
            o.addProperty("kbCode", t.getKbCode());
            if (t.getInstanceId() != null) o.addProperty("instanceId", t.getInstanceId());
            o.addProperty("status", t.getStatus());
            o.addProperty("wikiStatus", t.getWikiStatus());
            o.addProperty("error", t.getError());
            o.addProperty("running", orchestrating.contains(t.getId()));
            o.addProperty("createdAt", String.valueOf(t.getCreatedAt()));
            o.addProperty("updatedAt", String.valueOf(t.getUpdatedAt()));
            arr.add(o);
        }
        return arr;
    }

    /** 详情 JSON：任务 + 两段状态 + Wiki 状态卡（轻量单行读）+ 知识库条目（前端 3s 轮询的数据源） */
    public JsonObject detail(Long taskId) {
        AdHocTask t = require(taskId);
        JsonObject o = new JsonObject();
        o.addProperty("id", t.getId());
        o.addProperty("name", t.getName());
        o.addProperty("kbCode", t.getKbCode());
        if (t.getInstanceId() != null) o.addProperty("instanceId", t.getInstanceId());
        instanceRepo.findById(t.getInstanceId() == null ? -1L : t.getInstanceId())
                .ifPresent(inst -> o.addProperty("instanceName", inst.getName()));
        o.addProperty("status", t.getStatus());
        o.addProperty("wikiStatus", t.getWikiStatus());
        o.addProperty("error", t.getError());
        o.addProperty("running", orchestrating.contains(taskId));
        o.addProperty("docSources", t.getDocSources());
        o.addProperty("createdAt", String.valueOf(t.getCreatedAt()));
        o.addProperty("updatedAt", String.valueOf(t.getUpdatedAt()));

        JsonArray steps = new JsonArray();
        for (String stage : STAGES) {
            JsonObject s = new JsonObject();
            s.addProperty("key", stage);
            s.addProperty("status", stepStatus(t.getStatus(), t.getError(), stage));
            steps.add(s);
        }
        o.add("steps", steps);

        WikiSnapshot wiki = wikiSnapshot(t.getKbCode());
        JsonObject w = new JsonObject();
        w.addProperty("status", wiki.status());
        if (wiki.revision() != null) w.addProperty("revision", wiki.revision());
        w.addProperty("sourceCount", wiki.sourceCount());
        w.addProperty("pageCount", wiki.pages().size());
        w.addProperty("phase", wiki.phase());
        w.addProperty("error", wiki.error());
        o.add("wiki", w);

        JsonArray entries = new JsonArray();
        for (SupplierKbEntry e : kbService.list(t.getKbCode())) {
            JsonObject eo = new JsonObject();
            eo.addProperty("id", e.getId());
            eo.addProperty("title", e.getTitle());
            eo.addProperty("type", e.getType());
            eo.addProperty("status", e.getStatus());
            eo.addProperty("source", e.getSource());
            eo.addProperty("error", e.getError());
            entries.add(eo);
        }
        o.add("kbEntries", entries);
        return o;
    }

    // ===== 小工具 =====

    private static String title(SupplierKbEntry e) {
        return e.getTitle() == null || e.getTitle().isBlank() ? "(未命名条目#" + e.getId() + ")" : e.getTitle();
    }

    private static String text(JsonObject o, String key) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : "";
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }
}
