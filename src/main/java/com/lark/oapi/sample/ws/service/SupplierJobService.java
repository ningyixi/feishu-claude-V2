package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.model.entity.BotInstance;
import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.model.entity.SupplierJobNode;
import com.lark.oapi.sample.ws.model.entity.WorkflowTemplate;
import com.lark.oapi.sample.ws.repository.BotInstanceRepository;
import com.lark.oapi.sample.ws.repository.SupplierJobNodeRepository;
import com.lark.oapi.sample.ws.repository.SupplierJobRepository;
import com.lark.oapi.sample.ws.repository.WorkflowTemplateRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 供应商接入工作台服务 — "游戏向导"的后端。
 * <p>
 * 一个工作(SupplierJob) = 一局游戏存档：创建时选定模式（工作流模板）并把模板节点
 * <b>快照</b>成 SupplierJobNode（此后改模板不影响已有存档）。玩家沿节点推进：
 * <ul>
 *   <li>manual/reminder 节点：玩家线下做事，回来点"完成"（蓝→绿）</li>
 *   <li>agent/agent_check 节点：渲染 prompt（占位符注入存档信息+上游工件）→
 *       发到<b>绑定实例的飞书常驻会话</b>执行（复用 ClaudeCodeService 终端池，上下文连续、可中途换实例）→
 *       产物存 artifact 供浏览器展示/编辑/纠正重跑 → 玩家确认后点"完成"</li>
 * </ul>
 * currentNodeId 始终指向第一个未完成节点，前端据此做"游戏提醒"。
 */
@Service
public class SupplierJobService {

    private static final Logger log = LoggerFactory.getLogger(SupplierJobService.class);

    private final SupplierJobRepository jobRepo;
    private final SupplierJobNodeRepository nodeRepo;
    private final WorkflowTemplateRepository templateRepo;
    private final BotInstanceRepository instanceRepo;
    private final ClaudeCodeService claudeCodeService;
    private final SupplierKbService kbService;
    private final com.lark.oapi.sample.ws.repository.SupplierJobChatRepository chatRepo;

    public SupplierJobService(SupplierJobRepository jobRepo,
                              SupplierJobNodeRepository nodeRepo,
                              WorkflowTemplateRepository templateRepo,
                              BotInstanceRepository instanceRepo,
                              ClaudeCodeService claudeService,
                              SupplierKbService kbService,
                              com.lark.oapi.sample.ws.repository.SupplierJobChatRepository chatRepo) {
        this.jobRepo = jobRepo;
        this.nodeRepo = nodeRepo;
        this.templateRepo = templateRepo;
        this.instanceRepo = instanceRepo;
        this.claudeCodeService = claudeService;
        this.kbService = kbService;
        this.chatRepo = chatRepo;
    }

    // ===== 内置模板种子 =====

    @PostConstruct
    public void seedTemplates() {
        if (!templateRepo.existsByBuiltinTrueAndMode("API")) {
            WorkflowTemplate t = new WorkflowTemplate();
            t.setName("新接 API 供应商（内置路径）");
            t.setMode("API");
            t.setBuiltin(true);
            t.setDescription("从接口文档分析到上线的标准路径，可在工作流编辑器中修改或复制后自定义");
            t.setNodesJson(apiTemplateNodes());
            templateRepo.save(t);
            log.info("[SupplierJob] 已种入内置 API 模式工作流模板");
        }
        if (!templateRepo.existsByBuiltinTrueAndMode("CM")) {
            WorkflowTemplate t = new WorkflowTemplate();
            t.setName("新接 CM 供应商（占位）");
            t.setMode("CM");
            t.setBuiltin(true);
            t.setDescription("CM 模式流程尚未设计，请在工作流编辑器中补充节点");
            t.setNodesJson("[{\"id\":\"n1\",\"title\":\"CM 接入流程待设计\",\"type\":\"reminder\","
                    + "\"description\":\"CM 模式的默认工作流还没开发，先到工作流编辑器里为这个模板设计节点，再开始游戏。\",\"prompt\":\"\"}]");
            templateRepo.save(t);
            log.info("[SupplierJob] 已种入 CM 模式占位模板");
        }
    }

    /** 旧数据回填（10-29-codegen-prep）：标题含"写代码"的节点补上门禁标记（模板+存档快照） */
    @PostConstruct
    public void backfillCodegenPrep() {
        try {
            for (WorkflowTemplate t : templateRepo.findAll()) {
                JsonArray arr = parseNodes(t.getNodesJson());
                boolean changed = false;
                for (JsonElement el : arr) {
                    if (el.isJsonObject() && isCodegenTitle(str(el.getAsJsonObject(), "title", ""))
                            && !el.getAsJsonObject().has("codegenPrep")) {
                        el.getAsJsonObject().addProperty("codegenPrep", true);
                        changed = true;
                    }
                }
                if (changed) { t.setNodesJson(arr.toString()); templateRepo.save(t); }
            }
            for (SupplierJobNode n : nodeRepo.findAll()) {
                if (!n.isCodegenPrep() && isCodegenTitle(n.getTitle())) {
                    n.setCodegenPrep(true);
                    nodeRepo.save(n);
                }
            }
        } catch (Exception e) {
            log.warn("[SupplierJob] codegenPrep 回填失败（不影响启动）: {}", e.getMessage());
        }
    }

    /** 兼容"让实例写代码/让实例写供应商代码"等标题写法 */
    static boolean isCodegenTitle(String title) {
        return title != null && title.contains("代码") && title.contains("写");
    }

    private String apiTemplateNodes() {
        JsonArray nodes = new JsonArray();
        nodes.add(node("n1", "agent", "拉取接口文档 → 接口映射报告",
                "让绑定实例阅读你录入的全部接口文档（链接会抓取、本地路径会读取），产出「业务动作 → 供应商接口」映射报告："
                        + "定时同步城市/国家/酒店/报价、查价、验价、下单、查单、取消各调哪个接口、关键参数是什么。"
                        + "报告出来后可以人工编辑纠正，或填纠正意见让实例重跑。",
                "【供应商接入 · 接口文档分析】\n"
                        + "供应商: {supplierName}（ID: {supplierId}），类库路径: {libraryPath}\n"
                        + "接口文档清单:\n{docs}\n\n"
                        + "请逐个阅读文档（url 用 WebFetch 抓取，本地路径直接 Read；文档不全时如实标注\"文档缺失\"），产出接口映射报告（markdown 表格）：\n"
                        + "| 业务动作 | 接口(方法+路径) | 关键请求参数 | 关键响应字段 | 备注(鉴权/分页/限频) |\n"
                        + "业务动作至少覆盖：同步城市、同步国家、同步酒店(静态信息)、同步房型、同步报价(价格/库存)、查价(RatePlan)、验价(PreBook/CheckRate)、下单(CreateBooking)、查单(GetBooking)、取消(CancelBooking)。\n"
                        + "文档里没有的动作明确标注\"未找到\"。最后给出：鉴权方式、环境(测试/生产)地址、文档可疑或滞后之处。\n"
                        + "{extra}"));
        nodes.add(node("n2", "agent", "接口实测 → 响应示例存档",
                "文档常有滞后，以真实响应为准。让实例基于上一步的映射报告生成查询条件，真实调用供应商各接口，"
                        + "把请求/响应示例存档（显示在下方工件区），作为写代码的依据。",
                "【供应商接入 · 接口实测】\n"
                        + "供应商: {supplierName}（ID: {supplierId}）\n"
                        + "上一步的接口映射报告:\n{artifact:n1}\n\n"
                        + "请对映射报告中的每个接口：\n"
                        + "1. 生成合理的查询条件（小数据量、真实可执行；需要凭证时先从文档/配置中找，找不到就列出\"需要玩家提供\"清单并跳过该接口）\n"
                        + "2. 用 curl 真实调用（注意鉴权头/签名），记录：请求(方法+URL+关键头+body) 与 响应(状态码+body 截断到 2000 字)\n"
                        + "3. 对比响应与文档描述的差异，重点标注文档滞后之处（字段名/类型/枚举值不一致等）\n"
                        + "输出 markdown 实测报告，按接口分节。无法调用的接口明确标注原因。\n"
                        + "{extra}"));
        nodes.add(node("n3", "manual", "确认基础信息与前置申请",
                "核对存档信息：供应商 ID/枚举值是否已申请（NuGet 包 Suppliers 枚举）、测试账号/凭证是否到位、类库路径是否正确。"
                        + "缺什么先去申请，齐了再点完成。", ""));
        nodes.add(node("n4", "manual", "让实例写供应商代码",
                "先在「写代码准备」提交三方会议文档（飞书云文档链接）与当前配置 key/value，"
                        + "再点「生成提示词」复制发给绑定实例（自动聚合知识库/实测档案/skill/spec/会议文档/配置）。代码写完并自查后点完成。", ""));
        nodes.get(3).getAsJsonObject().addProperty("codegenPrep", true);   // n4 写代码前置门禁
        nodes.add(node("n5", "agent_check", "代码依赖扫描 → 配置提醒清单",
                "让实例扫描生成的供应商代码，找出所有需要外部配置的依赖（redis、配置中心开关、账号参数、NuGet 枚举、防火墙/白名单等），"
                        + "产出「上线前必须配置的清单」。",
                "【供应商接入 · 代码依赖检查】\n"
                        + "供应商: {supplierName}，类库路径: {libraryPath}\n\n"
                        + "请扫描该类库目录下的代码（含引用的公共组件调用），列出所有需要代码之外配置的依赖项，输出 markdown 清单：\n"
                        + "| 依赖项 | 代码证据(文件:行为) | 需要配置的位置 | 建议配置内容 |\n"
                        + "重点检查：redis 使用（缓存 key/开关）、配置中心参数（ParamManager/Account 读取的每个配置项）、Suppliers 枚举与 BusinessMiddleman case、"
                        + "HTTP 代理/防火墙白名单、定时任务注册、日志/告警接入。\n"
                        + "{extra}"));
        nodes.add(node("n6", "reminder", "配置中心 / 环境配置",
                "按上一步的清单逐项去配置中心、NuGet、网络白名单等完成配置。全部配完再点完成。", ""));
        nodes.add(node("n7", "manual", "联调验证",
                "用测试账号跑通完整链路：静态同步 → 报价 → 查价 → 验价 → 下单 → 查单 → 取消。"
                        + "验证数据落库与映射正确性。通过后点完成。", ""));
        nodes.add(node("n8", "reminder", "上线准备与归档",
                "预发/生产环境配置核对、监控告警确认、把本次接入踩坑经验反馈给 Skill Hook 沉淀（如果有对应 hook 会自动处理）。"
                        + "全部就绪后点完成，本局游戏通关 🎉", ""));
        return nodes.toString();
    }

    private JsonObject node(String id, String type, String title, String desc, String prompt) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        o.addProperty("type", type);
        o.addProperty("title", title);
        o.addProperty("description", desc);
        o.addProperty("prompt", prompt);
        return o;
    }

    // ===== 工作（存档）CRUD =====

    @Transactional
    public SupplierJob createJob(SupplierJob draft) {
        WorkflowTemplate tpl = templateRepo.findById(draft.getTemplateId())
                .orElseThrow(() -> new IllegalArgumentException("工作流模板不存在: " + draft.getTemplateId()));

        SupplierJob job = new SupplierJob();
        job.setName(draft.getName() != null && !draft.getName().isBlank()
                ? draft.getName() : "接入 " + draft.getSupplierName());
        job.setMode(tpl.getMode());
        job.setTemplateId(tpl.getId());
        job.setSupplierCode(nullSafe(draft.getSupplierCode()));
        job.setSupplierName(nullSafe(draft.getSupplierName()));
        job.setLibraryPath(nullSafe(draft.getLibraryPath()));
        job.setDocsJson(draft.getDocsJson() != null ? draft.getDocsJson() : "[]");
        job.setSkillsJson(draft.getSkillsJson() != null ? draft.getSkillsJson() : "[]");
        job.setInstanceId(draft.getInstanceId());
        job.setStatus("ACTIVE");
        // 创建者标签（数字员工或 admin）——由控制器校验权限后填入 draft
        job.setCreatedByEmployeeId(draft.getCreatedByEmployeeId());
        job.setCreatedByEmployeeName(draft.getCreatedByEmployeeName());
        jobRepo.save(job);

        // 模板节点 → 存档节点快照
        JsonArray nodes = parseNodes(tpl.getNodesJson());
        int seq = 0;
        String firstNodeId = null;
        for (JsonElement el : nodes) {
            if (!el.isJsonObject()) continue;
            JsonObject o = el.getAsJsonObject();
            SupplierJobNode n = new SupplierJobNode();
            n.setJobId(job.getId());
            n.setNodeId(str(o, "id", "n" + (seq + 1)));
            n.setSeq(seq);
            n.setTitle(str(o, "title", "(未命名节点)"));
            n.setType(str(o, "type", "manual"));
            n.setDescription(str(o, "description", ""));
            n.setPrompt(str(o, "prompt", ""));
            n.setCodegenPrep(o.has("codegenPrep") && o.get("codegenPrep").getAsBoolean());
            n.setStatus("PENDING");
            nodeRepo.save(n);
            if (firstNodeId == null) firstNodeId = n.getNodeId();
            seq++;
        }
        job.setCurrentNodeId(firstNodeId);
        jobRepo.save(job);
        kbService.syncFromJob(job);   // 旧 docs 引用并入知识库（R1，幂等）
        log.info("[SupplierJob] 存档已创建: id={}, name={}, mode={}, 节点{}个, 实例={}",
                job.getId(), job.getName(), job.getMode(), seq, job.getInstanceId());
        return job;
    }

    @Transactional
    public SupplierJob updateJob(Long id, SupplierJob patch) {
        SupplierJob job = jobRepo.findById(id).orElseThrow(() -> new IllegalArgumentException("工作不存在: " + id));
        if (patch.getName() != null) job.setName(patch.getName());
        if (patch.getSupplierCode() != null) job.setSupplierCode(patch.getSupplierCode());
        if (patch.getSupplierName() != null) job.setSupplierName(patch.getSupplierName());
        if (patch.getLibraryPath() != null) job.setLibraryPath(patch.getLibraryPath());
        if (patch.getDocsJson() != null) job.setDocsJson(patch.getDocsJson());
        if (patch.getSkillsJson() != null) job.setSkillsJson(patch.getSkillsJson());
        if (patch.getInstanceId() != null && !patch.getInstanceId().equals(job.getInstanceId())) {
            log.info("[SupplierJob] 存档 {} 切换执行实例: {} -> {}", id, job.getInstanceId(), patch.getInstanceId());
            job.setInstanceId(patch.getInstanceId());
        }
        SupplierJob saved = jobRepo.save(job);
        if (patch.getDocsJson() != null) kbService.syncFromJob(saved);   // 文档变更同步知识库（幂等）
        return saved;
    }

    @Transactional
    public void deleteJob(Long id) {
        nodeRepo.deleteByJobId(id);
        jobRepo.deleteById(id);
    }

    public List<SupplierJob> listJobs() {
        return jobRepo.findAllByOrderByUpdatedAtDesc();
    }

    public Optional<SupplierJob> getJob(Long id) {
        return jobRepo.findById(id);
    }

    public List<SupplierJobNode> getNodes(Long jobId) {
        return nodeRepo.findByJobIdOrderBySeqAsc(jobId);
    }

    // ===== 节点操作 =====

    /**
     * 执行 agent/agent_check 节点：发到绑定实例的常驻会话，产物写 artifact（异步）。
     * 回复含【卡点】→ 保持 RUNNING 且 pendingQuestion 挂起待玩家答复（R7），
     * 答复经 {@link #answerNode} 注入同一会话续跑。
     */
    public void runNode(Long jobId, String nodeId, String extraInstruction) {
        SupplierJob job = jobRepo.findById(jobId).orElseThrow(() -> new IllegalArgumentException("工作不存在"));
        SupplierJobNode n = nodeRepo.findByJobIdAndNodeId(jobId, nodeId)
                .orElseThrow(() -> new IllegalArgumentException("节点不存在: " + nodeId));
        if (!"agent".equals(n.getType()) && !"agent_check".equals(n.getType())) {
            throw new IllegalArgumentException("该节点类型(" + n.getType() + ")不支持实例执行");
        }
        if ("RUNNING".equals(n.getStatus())) {
            throw new IllegalArgumentException("节点正在执行中，请等待完成");
        }
        if (job.getInstanceId() == null) {
            throw new IllegalArgumentException("存档还没绑定执行实例，请先在顶部选择实例");
        }
        kbService.syncFromJob(job);   // 幂等：确保存量文档在知识库中有索引（执行时先查库勿重抓）

        String prompt = renderPrompt(n.getPrompt(), job, extraInstruction);
        n.setStatus("RUNNING");
        n.setStartedAt(n.getStartedAt() != null ? n.getStartedAt() : LocalDateTime.now());
        n.setNote("");
        n.setPendingQuestion("");
        nodeRepo.save(n);

        deliverToInstance(job, n, "job-node-" + jobId + "-" + nodeId, prompt);
    }

    /** R7 卡点答复：把玩家回答注入执行该节点的原会话，agent 续跑（回复再卡点则继续挂起） */
    public void answerNode(Long jobId, String nodeId, String answer) {
        SupplierJob job = jobRepo.findById(jobId).orElseThrow(() -> new IllegalArgumentException("工作不存在"));
        SupplierJobNode n = nodeRepo.findByJobIdAndNodeId(jobId, nodeId)
                .orElseThrow(() -> new IllegalArgumentException("节点不存在: " + nodeId));
        if (!"RUNNING".equals(n.getStatus()) || n.getPendingQuestion().isBlank()) {
            throw new IllegalArgumentException("该节点当前没有待答复的卡点");
        }
        if (answer == null || answer.isBlank()) throw new IllegalArgumentException("答复内容不能为空");
        if (job.getInstanceId() == null) throw new IllegalArgumentException("存档未绑定执行实例");
        n.setPendingQuestion("");
        nodeRepo.save(n);
        deliverToInstance(job, n, "job-answer-" + jobId + "-" + nodeId,
                "【卡点答复】玩家答复如下，优先遵循它继续未完成的工作（不要重新开始已完成的部分），完成后按动作规范输出：\n\n"
                        + answer.strip());
    }

    /** 发消息到实例会话（虚拟线程异步）并统一处理回复 */
    private void deliverToInstance(SupplierJob job, SupplierJobNode n, String threadName, String message) {
        Long jobId = job.getId();
        String nodeId = n.getNodeId();
        BotInstance inst = instanceRepo.findById(job.getInstanceId())
                .orElseThrow(() -> new IllegalArgumentException("绑定实例不存在: " + job.getInstanceId()));
        Thread.ofVirtual().name(threadName).start(() -> {
            try {
                String dir = firstNonBlankStr(inst.getClaudeWorkingDir(), inst.getShellWorkingDir(), "");
                String reply = claudeCodeService.chat(inst.getId(), "supplier-job-" + jobId,
                        message, null, dir, inst.getClaudeModel(), progressListener(n));
                applyNodeReply(n, reply);
            } catch (Exception e) {
                log.error("[SupplierJob] 节点执行异常: job={}, node={}", jobId, nodeId, e);
                n.setNote("执行异常: " + e.getMessage());
                n.setStatus("PENDING");
                n.setProgress("");
                nodeRepo.save(n);
            }
        });
    }

    /** 执行进度监听：工具事件写 progress；思考增量节流(≥1.5s)写 thinking 尾部片段，前端轮询即见"在做什么+在想什么" */
    private TurnListener progressListener(SupplierJobNode n) {
        return new TurnListener() {
            private final StringBuilder think = new StringBuilder();
            private long lastThinkWrite;

            @Override public void onTurnStart() { }
            @Override public void onThinkingDelta(String delta) {
                if (delta == null || delta.isEmpty()) return;
                think.append(delta);
                if (think.length() > 3000) think.delete(0, think.length() - 3000);
                long now = System.currentTimeMillis();
                if (now - lastThinkWrite < 1500) return;
                lastThinkWrite = now;
                String tail = think.length() > 500 ? think.substring(think.length() - 500) : think.toString();
                patchLive(n, f -> f.setThinking(tail.strip()));
            }
            @Override public void onTextDelta(String delta) { }
            @Override public void onToolStart(ToolStep step) {
                think.setLength(0);   // 新工具动作开新段落，思考缓冲重置
                String raw = "🔧 " + step.name
                        + (step.detail == null || step.detail.isBlank() ? "" : " · " + step.detail.strip());
                final String text = raw.length() > 560 ? raw.substring(0, 560) + "…" : raw;
                patchLive(n, f -> f.setProgress(text));
            }
            @Override public void onToolEnd(ToolStep step) { }
            @Override public void onTurnComplete(TurnResult result) { }
            @Override public void onTurnError(String message, boolean aborted) { }
        };
    }

    /** 执行中的实时字段写入：仅 RUNNING 态更新，失败绝不影响执行主链 */
    private void patchLive(SupplierJobNode n, java.util.function.Consumer<SupplierJobNode> mut) {
        try {
            nodeRepo.findByJobIdAndNodeId(n.getJobId(), n.getNodeId()).ifPresent(f -> {
                if ("RUNNING".equals(f.getStatus())) {
                    mut.accept(f);
                    nodeRepo.save(f);
                }
            });
        } catch (Exception ignore) { }
    }

    /** 统一处理节点回复：❌→note；行首【卡点】→挂起待答；正常→artifact 落库回 PENDING（等玩家点完成） */
    private void applyNodeReply(SupplierJobNode n, String reply) {
        if (reply != null && reply.startsWith("❌")) {
            n.setNote("执行失败: " + reply);
            n.setStatus("PENDING");
            log.warn("[SupplierJob] 节点执行失败: job={}, node={}, {}", n.getJobId(), n.getNodeId(), reply);
        } else if (reply != null && STANDBLOCK.matcher(reply).find()) {
            n.setPendingQuestion(reply);
            // 状态保持 RUNNING：等待玩家在工作台答复后注入续跑（R7）
            log.info("[SupplierJob] 节点卡点待答: job={}, node={}", n.getJobId(), n.getNodeId());
        } else {
            n.setArtifact(reply != null ? reply : "");
            n.setPendingQuestion("");
            n.setLastRunAt(LocalDateTime.now());
            n.setStatus("PENDING");
            log.info("[SupplierJob] 节点执行完成: job={}, node={}, 产物{}字（等待玩家确认）",
                    n.getJobId(), n.getNodeId(), n.getArtifact().length());
        }
        if (!"RUNNING".equals(n.getStatus())) { n.setProgress(""); n.setThinking(""); }   // 只有卡点挂起态保留最后进度
        nodeRepo.save(n);
    }

    /** 玩家确认节点完成：蓝→绿，推进当前节点。带写代码门禁的节点必须先交齐会议文档+KV配置 */
    @Transactional
    public void completeNode(Long jobId, String nodeId) {
        SupplierJob job = jobRepo.findById(jobId).orElseThrow(() -> new IllegalArgumentException("工作不存在"));
        SupplierJobNode n = nodeRepo.findByJobIdAndNodeId(jobId, nodeId)
                .orElseThrow(() -> new IllegalArgumentException("节点不存在: " + nodeId));
        if (n.isCodegenPrep() && !prepComplete(job.getMeetingDocUrl(), job.getConfigJson())) {
            throw new IllegalArgumentException("【写代码准备】未完成：需先提交三方会议文档（飞书云文档链接），并补充至少一组完整的配置 key/value");
        }
        n.setStatus("DONE");
        n.setDoneAt(LocalDateTime.now());
        nodeRepo.save(n);

        // 推进当前节点 = 第一个 PENDING
        nodeRepo.findFirstByJobIdAndStatusOrderBySeqAsc(jobId, "PENDING").ifPresentOrElse(
                next -> job.setCurrentNodeId(next.getNodeId()),
                () -> {
                    job.setCurrentNodeId(null);
                    job.setStatus("COMPLETED");
                    log.info("[SupplierJob] 🎉 存档通关: id={}, name={}", jobId, job.getName());
                });
        jobRepo.save(job);
    }

    // ===== 写代码前置准备（10-29-codegen-prep） =====

    /** 三方会议文档必须是飞书云文档 https 链接（feishu.cn / larksuite.com 及其子域） */
    static boolean isFeishuDocUrl(String url) {
        try {
            java.net.URI u = new java.net.URI(url == null ? "" : url.trim());
            String host = u.getHost();
            return "https".equalsIgnoreCase(u.getScheme()) && host != null
                    && (host.equals("feishu.cn") || host.endsWith(".feishu.cn")
                        || host.equals("larksuite.com") || host.endsWith(".larksuite.com"));
        } catch (Exception e) {
            return false;
        }
    }

    /** 门禁达成判定：会议文档链接非空 且 至少一组 key/value 都完整的配置 */
    static boolean prepComplete(String meetingDocUrl, String configJson) {
        if (meetingDocUrl == null || meetingDocUrl.isBlank()) return false;
        try {
            JsonArray arr = JsonParser.parseString(configJson == null || configJson.isBlank() ? "[]" : configJson).getAsJsonArray();
            for (JsonElement el : arr) {
                if (el.isJsonObject()) {
                    JsonObject o = el.getAsJsonObject();
                    String k = str(o, "key", "").trim();
                    String v = str(o, "value", "").trim();
                    if (!k.isEmpty() && !v.isEmpty()) return true;
                }
            }
        } catch (Exception ignore) { }
        return false;
    }

    public boolean prepReady(SupplierJob job) { return prepComplete(job.getMeetingDocUrl(), job.getConfigJson()); }

    /** 保存写代码准备信息（部分更新：null 字段不动；configs 传数组 JSON 字符串或 null） */
    @Transactional
    public SupplierJob savePrep(Long jobId, String meetingDocUrl, String specDocPath, String configsJson) {
        SupplierJob job = jobRepo.findById(jobId).orElseThrow(() -> new IllegalArgumentException("工作不存在"));
        if (meetingDocUrl != null) {
            String m = meetingDocUrl.trim();
            if (!m.isEmpty() && !isFeishuDocUrl(m)) {
                throw new IllegalArgumentException("三方会议文档必须是飞书云文档链接（https，域名 feishu.cn / larksuite.com）");
            }
            job.setMeetingDocUrl(m);
        }
        if (specDocPath != null) job.setSpecDocPath(specDocPath.trim());
        if (configsJson != null) {
            JsonArray arr;
            try {
                arr = JsonParser.parseString(configsJson).getAsJsonArray();
            } catch (Exception e) {
                throw new IllegalArgumentException("配置格式非法（需 key/value 数组）");
            }
            for (JsonElement el : arr) {
                JsonObject o = el.isJsonObject() ? el.getAsJsonObject() : new JsonObject();
                String k = str(o, "key", "").trim();
                String v = str(o, "value", "").trim();
                if (!k.isEmpty() != !v.isEmpty()) throw new IllegalArgumentException("配置项 key 和 value 需成对填写: " + (k.isEmpty() ? v : k));
            }
            job.setConfigJson(arr.toString());
        }
        SupplierJob saved = jobRepo.save(job);
        log.info("[SupplierJob] 写代码准备已保存: job={}, 会议文档={}, 配置{}组, spec={}",
                jobId, saved.getMeetingDocUrl().isBlank() ? "无" : "有",
                JsonParser.parseString(saved.getConfigJson()).getAsJsonArray().size(),
                saved.getSpecDocPath().isBlank() ? "无" : "有");
        return saved;
    }

    /** 生成发给实例的写代码提示词（聚合六类参考资料，用户一键复制粘贴到飞书） */
    public String codegenPrompt(Long jobId) {
        SupplierJob job = jobRepo.findById(jobId).orElseThrow(() -> new IllegalArgumentException("工作不存在"));
        String kbIndex = kbService.indexText(kbService.kbCode(job));
        String skills = formatSkills(job.getSkillsJson());
        String n2 = nodeRepo.findByJobIdAndNodeId(jobId, "n2").map(SupplierJobNode::getArtifact).orElse("");
        if (n2.isBlank()) {   // 自定义模板里实测节点可能不叫 n2：找带工件的 agent 节点里标题含"实测/档案"的
            n2 = nodeRepo.findByJobIdOrderBySeqAsc(jobId).stream()
                    .filter(x -> x.getArtifact() != null && !x.getArtifact().isBlank()
                            && (x.getType().startsWith("agent")) && isCodegenTitle(x.getTitle()) == false
                            && (x.getTitle().contains("实测") || x.getTitle().contains("档案") || x.getTitle().contains("Postman") || x.getTitle().contains("Collection")))
                    .findFirst().map(SupplierJobNode::getArtifact).orElse("");
        }
        return buildCodegenPrompt(job.getSupplierName(), job.getSupplierCode(), job.getLibraryPath(),
                kbIndex, skills, job.getMeetingDocUrl(), job.getSpecDocPath(), job.getConfigJson(), n2);
    }

    /** 纯函数便于单测：提示词组装 */
    static String buildCodegenPrompt(String supplierName, String supplierCode, String libraryPath,
                                     String kbIndex, String skills, String meetingDocUrl,
                                     String specDocPath, String configsJson, String n2Artifact) {
        StringBuilder p = new StringBuilder();
        p.append("【供应商接入 · 写代码】\n")
         .append("请为供应商 ").append(supplierName).append("（ID: ").append(supplierCode)
         .append("）编写供应商对接代码，类库目录: ").append(libraryPath).append("。\n\n")
         .append("== 参考资料（动手前务必先读） ==\n")
         .append("1) 接口文档知识库（已解析为本地 Markdown，先 Read 这些文件，不要重新抓取）：\n")
         .append(kbIndex).append("\n\n")
         .append("2) 接口实测档案（Postman Collection 与逐接口\"文档 vs 实际\"差异，来自工作台实测节点工件）：\n");
        if (n2Artifact != null && !n2Artifact.isBlank()) {
            String a = n2Artifact.length() > 3000 ? n2Artifact.substring(0, 3000) + "\n…（截断，完整内容见工作台该节点工件）" : n2Artifact;
            p.append(a).append("\n");
        } else {
            p.append("（尚无实测档案——若接口尚未实测，先在工作台完成实测节点再写代码）\n");
        }
        p.append("\n3) 三方会议文档（本次实现的重要参考，飞书云文档）：")
         .append(meetingDocUrl == null || meetingDocUrl.isBlank() ? "（未提交）" : meetingDocUrl).append("\n")
         .append("4) spec 文档：").append(specDocPath == null || specDocPath.isBlank() ? "（未提供）" : specDocPath).append("\n")
         .append("5) 当前配置 key/value（实现中按这些键名读取配置）：\n");
        StringBuilder kv = new StringBuilder();
        try {
            for (JsonElement el : JsonParser.parseString(configsJson == null ? "[]" : configsJson).getAsJsonArray()) {
                if (el.isJsonObject()) {
                    JsonObject o = el.getAsJsonObject();
                    kv.append("   - ").append(str(o, "key", "")).append(" = ").append(str(o, "value", "")).append('\n');
                }
            }
        } catch (Exception ignore) { }
        p.append(kv.length() == 0 ? "   （无）\n" : kv);
        p.append("\n6) Skill（写码流程遵循）：\n").append(skills.isBlank() ? "（存档未配置 skill）\n" : skills + "\n");
        p.append("\n== 注意事项 ==\n")
         .append("- 如果实现用到 redis（缓存 key/开关），记得去配置中心完成配置。\n")
         .append("- 覆盖度自查：同步城市/国家/酒店/房型/报价、查价、验价、下单、查单、取消各接口映射齐全，鉴权方式与实测档案一致。\n")
         .append("- 按 create-supplier skill 的流程与项目规范落码，完成后自查并汇报。\n");
        return p.toString();
    }

    /** 手动编辑工件（玩家纠正报告） */
    @Transactional
    public void saveArtifact(Long jobId, String nodeId, String artifact) {
        SupplierJobNode n = nodeRepo.findByJobIdAndNodeId(jobId, nodeId)
                .orElseThrow(() -> new IllegalArgumentException("节点不存在"));
        n.setArtifact(artifact != null ? artifact : "");
        nodeRepo.save(n);
    }

    // ===== 对话区（玩家随时问执行实例"现在什么情况"） =====

    /**
     * 玩家提问：落库 user 行 + 预占 agent PENDING 行，随后以独立轮次发到实例会话。
     * 会话串行：若节点执行中，TerminalSession 自动排队，当前执行段结束后被回答（不丢消息）。
     */
    public void ask(Long jobId, String question) {
        SupplierJob job = jobRepo.findById(jobId).orElseThrow(() -> new IllegalArgumentException("工作不存在"));
        if (question == null || question.isBlank()) throw new IllegalArgumentException("问题不能为空");
        if (job.getInstanceId() == null) throw new IllegalArgumentException("存档未绑定执行实例，无法提问");
        BotInstance inst = instanceRepo.findById(job.getInstanceId())
                .orElseThrow(() -> new IllegalArgumentException("绑定实例不存在"));
        com.lark.oapi.sample.ws.model.entity.SupplierJobChat u = new com.lark.oapi.sample.ws.model.entity.SupplierJobChat();
        u.setJobId(jobId); u.setRole("user"); u.setText(question.strip());
        chatRepo.save(u);
        com.lark.oapi.sample.ws.model.entity.SupplierJobChat a = new com.lark.oapi.sample.ws.model.entity.SupplierJobChat();
        a.setJobId(jobId); a.setRole("agent"); a.setStatus("PENDING"); a.setText("");
        chatRepo.save(a);
        Thread.ofVirtual().name("job-chat-" + jobId).start(() -> {
            try {
                String dir = firstNonBlankStr(inst.getClaudeWorkingDir(), inst.getShellWorkingDir(), "");
                String reply = claudeCodeService.chat(inst.getId(), "supplier-job-" + jobId,
                        "【玩家提问】关于本次供应商接入任务（" + job.getName() + "），请就你当前掌握的情况直接、简短回答；"
                                + "不要因此重跑或中断任何任务，只回答：\n\n" + question.strip(),
                        null, dir, inst.getClaudeModel(), new NoopListener());
                boolean bad = reply == null || reply.startsWith("❌");
                a.setText(bad ? (reply == null ? "（无回复）" : reply) : reply);
                a.setStatus(bad ? "FAILED" : "DONE");
            } catch (Exception e) {
                a.setText("提问投递失败: " + e.getMessage());
                a.setStatus("FAILED");
            }
            chatRepo.save(a);
        });
    }

    public List<com.lark.oapi.sample.ws.model.entity.SupplierJobChat> chatHistory(Long jobId) {
        return chatRepo.findByJobIdOrderByIdAsc(jobId);
    }

    // ===== prompt 渲染 =====

    /** 存档知识库归属码（控制器 detail JSON 用，前端按它拉知识库面板） */
    public String getKbCode(SupplierJob job) { return kbService.kbCode(job); }

    /** 幂等把存量 docs_json 引用并入知识库（详情打开/执行前都会调，失败静默不影响主流程） */
    public void ensureKbSync(SupplierJob job) { kbService.syncFromJob(job); }

    /**
     * 占位符：{supplierName} {supplierId} {libraryPath} {docs}=知识库索引 {skills} {extra} {artifact:nX}
     * 末尾统一追加 ACTION_SPEC（动作规范，与前文冲突以规范为准）。
     * 若 prompt 未写 {skills} 且存档配置了 skill，则自动在末尾追加"可用技能"段（内置模板无需修改即生效）。
     */
    String renderPrompt(String template, SupplierJob job, String extra) {
        if (template == null) template = "";
        String docs = kbService.indexText(kbService.kbCode(job));
        String skills = formatSkills(job.getSkillsJson());
        String out = template
                .replace("{supplierName}", nullSafe(job.getSupplierName()))
                .replace("{supplierId}", nullSafe(job.getSupplierCode()))
                .replace("{libraryPath}", nullSafe(job.getLibraryPath()))
                .replace("{docs}", docs)
                .replace("{skills}", skills)
                .replace("{extra}", extra != null && !extra.isBlank()
                        ? "\n\n== 玩家的补充/纠正指示（优先遵循） ==\n" + extra : "");
        if (!template.contains("{skills}") && !skills.isBlank()) {
            out = out + "\n\n【本工作可用的 Skill】\n开工前先阅读下列 skill 目录中的 SKILL.md（及其引用的文件），任务范围内严格遵循 skill 的指导：\n" + skills;
        }
        // {artifact:nX} 引用其他节点工件
        Matcher m = Pattern.compile("\\{artifact:([\\w-]+)}").matcher(out);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String ref = m.group(1);
            String artifact = nodeRepo.findByJobIdAndNodeId(job.getId(), ref)
                    .map(SupplierJobNode::getArtifact).orElse("（引用的节点 " + ref + " 还没有产物）");
            if (artifact.length() > 12000) artifact = artifact.substring(0, 12000) + "\n...(截断)";
            m.appendReplacement(sb, Matcher.quoteReplacement(artifact));
        }
        m.appendTail(sb);
        return sb + "\n\n" + ACTION_SPEC;
    }

    /** R5/R6/R7 统一动作规范：Trellis 四段纪律 + 卡点协议 + Postman 实测协议（追加在所有 agent prompt 末尾） */
    static final java.util.regex.Pattern STANDBLOCK = java.util.regex.Pattern.compile("(?m)^\\s*【卡点】");
    static final String ACTION_SPEC = """
            【执行动作规范 · Trellis 纪律（与前文要求冲突时，以本节为准）】
            1. Before-dev：开工前先输出「## Before-dev」，列出将阅读的知识库文件路径与将依据的 skill 要点，禁止跳过直接动手。
            2. Implement：完成主体任务（按前文要求）。引用依据时标注来源（知识库文件/URL+定位）。
            3. Check：输出「## Check」，对照前文任务要求逐条自查（覆盖度/是否臆造/遗漏项），用表格。
            4. Finish：最终产物放「## Finish」段，工作台将把它存为节点工件。
            5. 卡点协议（最高优先级）：只要因缺少关键信息（接口凭证/账号、环境地址、互相矛盾的选项）导致无法真实执行，
               必须提问等待，禁止默默「列清单并跳过」草草收尾——即使上文或前文任务要求这么说过，也以本节为准。
               做法：在输出中另起一行、以【卡点】开头写清需要玩家提供的材料和具体问法（可给 A/B/C 选项），然后结束本轮；
               问题会转给玩家，答复将以「【卡点答复】」消息送达本会话，收到后继续未完成的工作。
               注意：【卡点】必须是此刻真正在向玩家提出的问题；转述规则、举例说明时不要使用该标记行。
            6. 接口实测协议（凡真实调用供应商 HTTP 接口）：必须经 Postman 插件(MCP) 执行——
               a) 先把待测接口整理成 Postman Collection（v2.1 JSON，命名 <供应商代码>-<yyyyMMdd>，含环境变量/鉴权配置）；
               b) 经插件发送请求、采集真实响应；c) 工件中附 Collection 定义与逐接口"文档 vs 实际"差异对比。
               仅当 Postman 插件确认不可用时才降级 newman CLI 回放；自由 curl 不算合规实测产物。""";

    /** skill 目录列表 → prompt 段落（空配置返回空串） */
    private String formatSkills(String skillsJson) {
        try {
            JsonArray arr = JsonParser.parseString(skillsJson != null ? skillsJson : "[]").getAsJsonArray();
            if (arr.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (JsonElement el : arr) {
                String path = el.isJsonPrimitive() ? el.getAsString()
                        : (el.isJsonObject() && el.getAsJsonObject().has("value")
                            ? el.getAsJsonObject().get("value").getAsString() : "");
                if (!path.isBlank()) sb.append(i++).append(". ").append(path.trim()).append("\n");
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    private static JsonArray parseNodes(String nodesJson) {
        try {
            return JsonParser.parseString(nodesJson != null ? nodesJson : "[]").getAsJsonArray();
        } catch (Exception e) {
            return new JsonArray();
        }
    }

    private static String str(JsonObject o, String key, String def) {
        return o.has(key) && !o.get(key).isJsonNull() ? o.get(key).getAsString() : def;
    }

    private static String nullSafe(String s) { return s != null ? s : ""; }

    private static String firstNonBlankStr(String... vals) {
        for (String v : vals) if (v != null && !v.isBlank()) return v;
        return "";
    }

    /** 节点执行不需要 UI 流式（前端轮询节点状态），收结果即可 */
    private static class NoopListener implements TurnListener {
        public void onTurnStart() {}
        public void onThinkingDelta(String delta) {}
        public void onTextDelta(String delta) {}
        public void onToolStart(ToolStep step) {}
        public void onToolEnd(ToolStep step) {}
        public void onTurnComplete(TurnResult result) {}
        public void onTurnError(String message, boolean aborted) {}
    }
}
