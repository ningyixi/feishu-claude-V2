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

    public SupplierJobService(SupplierJobRepository jobRepo,
                              SupplierJobNodeRepository nodeRepo,
                              WorkflowTemplateRepository templateRepo,
                              BotInstanceRepository instanceRepo,
                              ClaudeCodeService claudeService,
                              SupplierKbService kbService) {
        this.jobRepo = jobRepo;
        this.nodeRepo = nodeRepo;
        this.templateRepo = templateRepo;
        this.instanceRepo = instanceRepo;
        this.claudeCodeService = claudeService;
        this.kbService = kbService;
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
                "到飞书上对绑定实例下达写代码指令（建议提及使用 create-supplier skill 和本次存档的实测报告），"
                        + "也可以直接在工作台让实例执行检查类任务。代码写完并自查后点完成。", ""));
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
                        message, null, dir, inst.getClaudeModel(), new NoopListener());
                applyNodeReply(n, reply);
            } catch (Exception e) {
                log.error("[SupplierJob] 节点执行异常: job={}, node={}", jobId, nodeId, e);
                n.setNote("执行异常: " + e.getMessage());
                n.setStatus("PENDING");
                nodeRepo.save(n);
            }
        });
    }

    /** 统一处理节点回复：❌→note；【卡点】→挂起待答；正常→artifact 落库回 PENDING（等玩家点完成） */
    private void applyNodeReply(SupplierJobNode n, String reply) {
        if (reply != null && reply.startsWith("❌")) {
            n.setNote("执行失败: " + reply);
            n.setStatus("PENDING");
            log.warn("[SupplierJob] 节点执行失败: job={}, node={}, {}", n.getJobId(), n.getNodeId(), reply);
        } else if (reply != null && reply.contains("【卡点】")) {
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
        nodeRepo.save(n);
    }

    /** 玩家确认节点完成：蓝→绿，推进当前节点 */
    @Transactional
    public void completeNode(Long jobId, String nodeId) {
        SupplierJob job = jobRepo.findById(jobId).orElseThrow(() -> new IllegalArgumentException("工作不存在"));
        SupplierJobNode n = nodeRepo.findByJobIdAndNodeId(jobId, nodeId)
                .orElseThrow(() -> new IllegalArgumentException("节点不存在: " + nodeId));
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

    /** 手动编辑工件（玩家纠正报告） */
    @Transactional
    public void saveArtifact(Long jobId, String nodeId, String artifact) {
        SupplierJobNode n = nodeRepo.findByJobIdAndNodeId(jobId, nodeId)
                .orElseThrow(() -> new IllegalArgumentException("节点不存在"));
        n.setArtifact(artifact != null ? artifact : "");
        nodeRepo.save(n);
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
    static final String ACTION_SPEC = """
            【执行动作规范 · Trellis 纪律（与前文要求冲突时，以本节为准）】
            1. Before-dev：开工前先输出「## Before-dev」，列出将阅读的知识库文件路径与将依据的 skill 要点，禁止跳过直接动手。
            2. Implement：完成主体任务（按前文要求）。引用依据时标注来源（知识库文件/URL+定位）。
            3. Check：输出「## Check」，对照前文任务要求逐条自查（覆盖度/是否臆造/遗漏项），用表格。
            4. Finish：最终产物放「## Finish」段，工作台将把它存为节点工件。
            5. 卡点协议：缺关键信息（凭证/账号/文档矛盾/无法决断的选项）时【不要猜测硬跑】。在输出中单独一行
               「【卡点】<问题是什么；需要玩家提供什么材料或做出什么选择(A/B/C)>」然后结束本轮；
               问题会转给玩家，答复将以「【卡点答复】」消息送达本会话，收到后继续未完成的工作。
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
