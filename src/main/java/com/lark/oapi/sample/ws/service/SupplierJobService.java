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

    public SupplierJobService(SupplierJobRepository jobRepo,
                              SupplierJobNodeRepository nodeRepo,
                              WorkflowTemplateRepository templateRepo,
                              BotInstanceRepository instanceRepo,
                              ClaudeCodeService claudeCodeService) {
        this.jobRepo = jobRepo;
        this.nodeRepo = nodeRepo;
        this.templateRepo = templateRepo;
        this.instanceRepo = instanceRepo;
        this.claudeCodeService = claudeCodeService;
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
        return jobRepo.save(job);
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

    /** 执行 agent/agent_check 节点：发到绑定实例的飞书会话，产物写 artifact（异步） */
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
        BotInstance inst = instanceRepo.findById(job.getInstanceId())
                .orElseThrow(() -> new IllegalArgumentException("绑定实例不存在: " + job.getInstanceId()));

        String prompt = renderPrompt(n.getPrompt(), job, extraInstruction);
        n.setStatus("RUNNING");
        n.setStartedAt(n.getStartedAt() != null ? n.getStartedAt() : LocalDateTime.now());
        n.setNote("");
        nodeRepo.save(n);

        Thread.ofVirtual().name("job-node-" + jobId + "-" + nodeId).start(() -> {
            try {
                String dir = firstNonBlankStr(inst.getClaudeWorkingDir(), inst.getShellWorkingDir(), "");
                String reply = claudeCodeService.chat(inst.getId(), "supplier-job-" + jobId,
                        prompt, null, dir, inst.getClaudeModel(), new NoopListener());
                if (reply != null && reply.startsWith("❌")) {
                    n.setNote("执行失败: " + reply);
                    log.warn("[SupplierJob] 节点执行失败: job={}, node={}, {}", jobId, nodeId, reply);
                } else {
                    n.setArtifact(reply != null ? reply : "");
                    n.setLastRunAt(LocalDateTime.now());
                    log.info("[SupplierJob] 节点执行完成: job={}, node={}, 产物{}字（等待玩家确认）",
                            jobId, nodeId, n.getArtifact().length());
                }
            } catch (Exception e) {
                log.error("[SupplierJob] 节点执行异常: job={}, node={}", jobId, nodeId, e);
                n.setNote("执行异常: " + e.getMessage());
            } finally {
                n.setStatus("PENDING");   // 回到蓝色：执行完也要玩家点"完成"才变绿（游戏规则）
                nodeRepo.save(n);
            }
        });
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

    /**
     * 占位符：{supplierName} {supplierId} {libraryPath} {docs} {skills} {extra} {artifact:nX}
     * 若 prompt 未写 {skills} 且存档配置了 skill，则自动在末尾追加"可用技能"段（内置模板无需修改即生效）。
     */
    String renderPrompt(String template, SupplierJob job, String extra) {
        if (template == null) template = "";
        String docs = formatDocs(job.getDocsJson());
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
        return sb.toString();
    }

    private String formatDocs(String docsJson) {
        try {
            JsonArray arr = JsonParser.parseString(docsJson != null ? docsJson : "[]").getAsJsonArray();
            if (arr.isEmpty()) return "（未录入文档）";
            StringBuilder sb = new StringBuilder();
            int i = 1;
            for (JsonElement el : arr) {
                JsonObject o = el.getAsJsonObject();
                sb.append(i++).append(". [").append(str(o, "type", "url")).append("] ")
                        .append(str(o, "value", "")).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "（文档列表解析失败）";
        }
    }

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
