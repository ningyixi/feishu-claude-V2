package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * 临时需求任务（11-17-adhoc-requirement-module）——侧边栏「临时需求」模块的独立实体。
 * <p>
 * 与供应商工作台（{@link SupplierJob}）和接口建档（quick 标记的 SupplierJob）<b>完全解耦</b>：
 * 独立表 ad_hoc_task，不写 supplier_job* 任何表、不启动 Flowable、无节点/工件/实测证据。
 * 生命周期 = 两段流水线（来源入库解析 INGEST → 编译 LLM Wiki WIKI）→ READY 对话待命；
 * {@link #error} 非空表示停在 {@link #status} 记录的段上失败（可「重试本段」）。
 * <p>
 * 卡点待确认（11-17-adhoc-blocker-notify）：{@link #pendingQuestion} 非空 = agent 停下来等人拍板，
 * 页面据此渲染常驻「待你确认」卡；{@link #notifiedAt}/{@link #notifyError} 记录同一时刻的飞书私聊
 * 推送结果（推送只是提醒，卡才是权威事实源，失败必须可见）。
 * <p>
 * 底座复用（不算耦合）：知识库条目落在 {@code supplier_kb_entry}（kbCode = "adhoc-&lt;id&gt;" 专属命名空间，
 * 经 SupplierKbService 管线）；Wiki 版本行落在 {@code supplier_wiki_revision}（同 kbCode，经
 * SupplierWikiService.build）；对话走 ClaudeCodeService.chat = 绑定实例主会话（与飞书同 session）。
 */
@Entity
@Table(name = "ad_hoc_task")
public class AdHocTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 任务名（表单必填项，展示用） */
    @Column(nullable = false)
    private String name = "";

    /** 关联文档来源，多行原文（每行一条：http(s) 链接 / .pdf/.html/.htm/.md/.markdown/.txt 本地路径） */
    @Lob
    @Column(length = 2147483647, name = "doc_sources")
    private String docSources = "";

    /** 知识库专属命名空间："adhoc-&lt;id&gt;"——保证不与其他任务共库，删除时可安全整库清理 */
    @Column(name = "kb_code", nullable = false)
    private String kbCode = "";

    @Column(name = "instance_id")
    private Long instanceId;

    /** 流水线状态：INGEST（入库解析）| WIKI（编译中）| READY（对话待命）；配合 error 表达段失败 */
    @Column(nullable = false, length = 20)
    private String status = "INGEST";

    /** 最近一次观察到的 Wiki 版本状态（EMPTY/BUILDING/READY/FAILED），列表页轻量展示用 */
    @Column(name = "wiki_status", length = 20)
    private String wikiStatus = "EMPTY";

    /** 当段失败原因；空串 = 未失败（RUNNING/READY） */
    @Column(length = 4000)
    private String error = "";

    /**
     * 待用户确认的卡点问题原文（11-17-adhoc-blocker-notify 决策 3）；空串 = 当前没有待确认事项。
     * <p>
     * 来源有二：①对话轮次终局文本命中 {@code 【卡点】}（{@link AdHocService#ask}）；
     * ②Wiki 编译产物里检出 {@code 【卡点】}（决策 5，该段不得置 READY）。
     * <b>页面上的待确认卡是权威事实源</b>：用户在对话框回一句即视为答复 → 清空本列、卡片收起、
     * 同一主会话自然续跑（不新建 lane、不 steer、不打断）。
     */
    @Lob
    @Column(length = 2147483647, name = "pending_question")
    private String pendingQuestion = "";

    /** 最近一次卡点飞书私聊推送成功的时间；null = 从未推送成功（配合 {@link #notifyError} 判定） */
    @Column(name = "notified_at")
    private LocalDateTime notifiedAt;

    /**
     * 卡点飞书推送失败原因（决策 4：失败必须可见）；空串 = 无失败。
     * 例："飞书实例未连接"、"该实例尚未绑定飞书私聊用户"、"飞书发送失败 code=…"。
     * 推送失败绝不影响页面待确认卡（卡才是权威事实源），只是卡片上会明示"飞书通知失败：&lt;原因&gt;"。
     */
    @Column(name = "notify_error", length = 1000)
    private String notifyError = "";

    /**
     * 创建时是否勾选「URL 按同站点递归抓取」（11-24-adhoc-site-crawl SC1）：true = INGEST 段把文档来源里的
     * URL 行按 url+site 入库（{@code SupplierKbService.add(..., site=true)} → meta.site=true → 解析走
     * 同站点 BFS 抓取 ≤200 页）。<b>只作用于 URL 行</b>，本地文件/PDF/笔记不受影响。
     * 落在任务行而不是只留在请求里：「重试本段」/重启续跑重走 INGEST 时，尚未入库的 URL 行仍按本标记
     * 建站点条目，不静默降级成单页抓取。
     */
    @Column(name = "site_crawl")
    private Boolean siteCrawl;

    /**
     * 最近一个"因编译产物含【卡点】而被拒绝"的 Wiki 版本行 id（决策 5 的续跑锚点）。
     * <p>
     * 为什么需要它：Wiki 段检出卡点后本段置 FAILED，用户答复 → 点「重试本段」→ 若仍拿同一版 READY 产物
     * 判定，就会永远再次拒绝、续跑变成死路。记下被拒版本后，重试时对这一版触发<b>重新编译</b>
     * （{@code SupplierWikiService.build} 每次都产生新版本行），新一版仍含卡点才再次停下。
     */
    @Column(name = "blocked_revision")
    private Long blockedRevision;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    public AdHocTask() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    /** 空值归一：存量行 NULL 按空串（ddl-auto=update 加列语义） */
    public String getDocSources() { return docSources == null ? "" : docSources; }
    public void setDocSources(String docSources) { this.docSources = docSources; }

    public String getKbCode() { return kbCode; }
    public void setKbCode(String kbCode) { this.kbCode = kbCode; }

    public Long getInstanceId() { return instanceId; }
    public void setInstanceId(Long instanceId) { this.instanceId = instanceId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getWikiStatus() { return wikiStatus == null ? "EMPTY" : wikiStatus; }
    public void setWikiStatus(String wikiStatus) { this.wikiStatus = wikiStatus; }

    public String getError() { return error == null ? "" : error; }
    public void setError(String error) { this.error = error; }

    /** 空值归一：存量行 NULL 按空串（ddl-auto=update 加列语义） */
    public String getPendingQuestion() { return pendingQuestion == null ? "" : pendingQuestion; }
    public void setPendingQuestion(String pendingQuestion) { this.pendingQuestion = pendingQuestion; }

    public LocalDateTime getNotifiedAt() { return notifiedAt; }
    public void setNotifiedAt(LocalDateTime notifiedAt) { this.notifiedAt = notifiedAt; }

    public String getNotifyError() { return notifyError == null ? "" : notifyError; }
    public void setNotifyError(String notifyError) { this.notifyError = notifyError; }

    public Long getBlockedRevision() { return blockedRevision; }
    public void setBlockedRevision(Long blockedRevision) { this.blockedRevision = blockedRevision; }

    /** 空值归一：存量行 NULL 按 false（ddl-auto=update 加列语义）——老任务保持单页抓取（AC2） */
    public boolean isSiteCrawl() { return siteCrawl != null && siteCrawl; }
    public void setSiteCrawl(boolean siteCrawl) { this.siteCrawl = siteCrawl; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
