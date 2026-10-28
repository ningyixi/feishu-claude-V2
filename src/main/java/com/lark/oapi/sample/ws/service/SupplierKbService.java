package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.model.entity.SupplierKbEntry;
import com.lark.oapi.sample.ws.repository.SupplierKbEntryRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 供应商知识库服务（R1/R3/R4 基座）：
 * <ul>
 *   <li>条目 CRUD 与解析状态机（PENDING→PARSING→DONE/FAILED），URL/PDF 异步解析为落盘 Markdown</li>
 *   <li>{@link #syncFromJob} 把存档旧 docs_json 引用清单并入知识库（幂等，按 type+source 去重）</li>
 *   <li>{@link #indexText} 生成注入 agent prompt 的「知识库索引表 + 先查库勿重抓」规则段</li>
 *   <li>重启续跑：启动时把遗留 PENDING/PARSING 条目重新排队</li>
 * </ul>
 * 产物目录：{feishu.kb-dir}/<kbCode>/<entryId>-<slug>.md；元数据在 H2 supplier_kb_entry。
 */
@Service
public class SupplierKbService {

    private static final Logger log = LoggerFactory.getLogger(SupplierKbService.class);

    private final SupplierKbEntryRepository repo;
    private final Path kbRoot;

    public SupplierKbService(SupplierKbEntryRepository repo,
                             @Value("${feishu.kb-dir:D:/feishu-claude-data/supplier-kb}") String kbDir) {
        this.repo = repo;
        this.kbRoot = Paths.get(kbDir);
    }

    // ===== 查询 =====

    public List<SupplierKbEntry> list(String kbCode) {
        return repo.findByKbCodeOrderByCreatedAtAsc(kbCode);
    }

    public SupplierKbEntry require(Long id) {
        return repo.findById(id).orElseThrow(() -> new IllegalArgumentException("知识库条目不存在: " + id));
    }

    /** 存档的知识库归属码：供应商 code，缺省回退 job-<id>（保证无 code 的旧档也有库可挂） */
    public String kbCode(SupplierJob job) {
        String code = job.getSupplierCode();
        return code != null && !code.isBlank() ? code.trim() : "job-" + job.getId();
    }

    // ===== 入库 =====

    /**
     * 新增条目。url/pdf → 排队异步解析；chat/note → 原文直接落盘为 DONE。
     * @param value url=链接, pdf=本地绝对路径, chat/note=文本内容
     */
    public SupplierKbEntry add(String kbCode, String type, String value, String title) {
        if (kbCode == null || kbCode.isBlank()) throw new IllegalArgumentException("kbCode 不能为空");
        if (value == null || value.isBlank()) throw new IllegalArgumentException("内容不能为空");
        type = type == null ? "url" : type.trim().toLowerCase();
        SupplierKbEntry e = new SupplierKbEntry();
        e.setKbCode(kbCode.trim());
        e.setType(type);
        e.setTitle(title != null && !title.isBlank() ? title.trim() : deriveTitle(type, value));
        switch (type) {
            case "url" -> {
                DocParser.assertSafeUrl(value.trim());   // 入库即拦一次，解析时还会逐跳复核
                e.setSource(value.trim());
                e.setStatus("PENDING");
                repo.save(e);
                afterCommit(() -> parseNow(e.getId()));
            }
            case "pdf" -> {
                Path p = Paths.get(value.trim());
                if (!Files.isRegularFile(p)) throw new IllegalArgumentException("PDF 文件不存在: " + value);
                if (!p.getFileName().toString().toLowerCase().endsWith(".pdf")) throw new IllegalArgumentException("仅接受 .pdf 文件");
                try {
                    if (Files.size(p) > DocParser.MAX_PDF_BYTES) throw new IllegalArgumentException("PDF 超过 50MB 上限");
                } catch (IOException ex) { throw new IllegalArgumentException("PDF 不可读: " + ex.getMessage()); }
                e.setSource(value.trim());
                e.setStatus("PENDING");
                repo.save(e);
                afterCommit(() -> parseNow(e.getId()));
            }
            case "chat", "note" -> {
                e.setSource("");   // 文本内容直接进 md，source 留空
                e.setStatus("DONE");
                repo.save(e);
                try {
                    String md = "> " + ("chat".equals(type) ? "聊天记录补充" : "笔记") + "（录入于 "
                            + LocalDateTime.now().toString().substring(0, 19) + "）\n\n" + value.strip() + "\n";
                    writeMd(e, DocParser.chunk(md), meta(o -> o.addProperty("chars", md.length())));
                    e.setStatus("DONE");
                    repo.save(e);
                } catch (IOException ex) {
                    e.setStatus("FAILED");
                    e.setError("写入条目文件失败: " + ex.getMessage());
                    repo.save(e);
                }
            }
            default -> throw new IllegalArgumentException("未知条目类型: " + type);
        }
        log.info("[SupplierKb] 条目入库: id={}, kb={}, type={}, status={}", e.getId(), kbCode, type, e.getStatus());
        return e;
    }

    /** 重新解析（DONE/FAILED 可用；人工纠错后想回到机析结果时用） */
    public void reparse(Long id) {
        SupplierKbEntry e = require(id);
        if (!"url".equals(e.getType()) && !"pdf".equals(e.getType())) throw new IllegalArgumentException("仅 url/pdf 支持重新解析");
        e.setStatus("PENDING");
        e.setError("");
        repo.save(e);
        afterCommit(() -> parseNow(id));
    }

    /** 读解析产物 Markdown（前端预览/编辑，agent 之外的人工通道） */
    public String readMd(Long id) throws IOException {
        SupplierKbEntry e = require(id);
        if (e.getMdPath() == null || e.getMdPath().isBlank()) throw new IllegalArgumentException("该条目还没有解析产物");
        return Files.readString(Paths.get(e.getMdPath()), StandardCharsets.UTF_8);
    }

    /** 人工修正解析产物（纠错后 DONE 保持，meta 标记 editedAt） */
    public void saveMd(Long id, String content) throws IOException {
        SupplierKbEntry e = require(id);
        if (e.getMdPath() == null || e.getMdPath().isBlank()) throw new IllegalArgumentException("该条目还没有解析产物");
        Files.writeString(Paths.get(e.getMdPath()), content, StandardCharsets.UTF_8);
        JsonObject m = parseMeta(e.getMetaJson());
        m.addProperty("editedAt", LocalDateTime.now().toString());
        m.addProperty("chars", content.length());
        e.setMetaJson(m.toString());
        e.setStatus("DONE");
        e.setError("");
        repo.save(e);
        log.info("[SupplierKb] 人工校正产物: id={}, {}字", id, content.length());
    }

    // ===== 存量同步与索引 =====

    /** 幂等：把存档 docs_json 的 [{type:url|path,value}] 引用并入知识库（已存在同 source 则跳过） */
    public void syncFromJob(SupplierJob job) {
        try {
            String kbCode = kbCode(job);
            JsonArray arr = JsonParser.parseString(job.getDocsJson() != null ? job.getDocsJson() : "[]").getAsJsonArray();
            for (JsonElement el : arr) {
                if (!el.isJsonObject()) continue;
                JsonObject o = el.getAsJsonObject();
                String type = o.has("type") ? o.get("type").getAsString() : "url";
                String value = o.has("value") ? o.get("value").getAsString() : "";
                if (value.isBlank()) continue;
                String mapped = "path".equals(type) ? "pdf" : "url";
                if (repo.existsByKbCodeAndTypeAndSource(kbCode, mapped, value.trim())) continue;
                try {
                    add(kbCode, mapped, value, null);
                } catch (Exception ex) {
                    log.warn("[SupplierKb] 存量 docs 同步跳过: {}（{}）", value, ex.getMessage());
                }
            }
        } catch (Exception e) {
            log.warn("[SupplierKb] syncFromJob 失败 job={}: {}", job.getId(), e.getMessage());
        }
    }

    /** 注入 agent prompt 的知识库段：索引表 + 先查库规则（R4） */
    public String indexText(String kbCode) {
        List<SupplierKbEntry> entries = list(kbCode);
        if (entries.isEmpty()) return "（该供应商知识库为空 — 按任务中给出的来源抓取/阅读，读完建议把要点沉淀回知识库）";
        StringBuilder sb = new StringBuilder();
        sb.append("【供应商知识库 · ").append(kbCode).append("】以下条目已统一解析为本地 Markdown（含 PDF 页码/原文行号定位）。\n");
        sb.append("规则：① 优先直接 Read 下列 .md 文件获取内容；② 禁止对状态=已解析 的条目来源再 WebFetch/重复抓取；"
                + "③ 状态=解析失败 的条目在报告中如实标注；④ 发现知识库缺失的新文档/沟通结论，在产物末尾列出「建议入库」清单。\n\n");
        int i = 1;
        for (SupplierKbEntry e : entries) {
            sb.append(i++).append(". ").append(typeIcon(e.getType())).append(' ').append(safeTitle(e));
            switch (e.getStatus()) {
                case "DONE" -> sb.append(" — 已解析，文件: ").append(prettyPath(e.getMdPath()));
                case "FAILED" -> sb.append(" — ✗解析失败: ").append(e.getError());
                case "PARSING", "PENDING" -> sb.append(" — 解析中…");
                default -> sb.append(" — ").append(e.getStatus());
            }
            if (!e.getSource().isBlank()) sb.append("（来源: ").append(e.getSource()).append('）');
            sb.append('\n');
        }
        return sb.toString().stripTrailing();
    }

    // ===== 解析 worker =====

    private void parseNow(Long id) {
        SupplierKbEntry e = repo.findById(id).orElse(null);
        if (e == null) return;
        e.setStatus("PARSING");
        repo.save(e);
        try {
            String md;
            JsonObject meta = new JsonObject();
            if ("url".equals(e.getType())) {
                DocParser.Fetched f = DocParser.fetchUrlToMarkdown(e.getSource());
                md = f.markdown();
                meta.addProperty("finalUrl", f.finalUrl());
                meta.addProperty("title", f.title() == null ? "" : f.title());
                if (f.title() != null && !f.title().isBlank() && (e.getTitle() == null || e.getTitle().isBlank()
                        || e.getTitle().startsWith(e.getType() + ":"))) {
                    e.setTitle(f.title());
                }
            } else if ("pdf".equals(e.getType())) {
                md = DocParser.pdfToMarkdown(Paths.get(e.getSource()));
                meta.addProperty("localPdf", true);
            } else {
                return;   // chat/note 不走机析
            }
            meta.addProperty("chars", md.length());
            meta.addProperty("fetchedAt", LocalDateTime.now().toString());
            writeMd(e, md, meta);
            e.setStatus("DONE");
            e.setError("");
            log.info("[SupplierKb] 解析完成: id={}, {}, {}字", id, e.getType(), md.length());
        } catch (Exception ex) {
            e.setStatus("FAILED");
            String msg = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            e.setError(msg.length() > 400 ? msg.substring(0, 400) : msg);
            log.warn("[SupplierKb] 解析失败: id={}, {}: {}", id, e.getType(), msg);
        }
        repo.save(e);
    }

    /** 产物落盘：{kbRoot}/<kbCode>/<id>-<slug>.md */
    private void writeMd(SupplierKbEntry e, String md, JsonObject meta) throws IOException {
        Path dir = kbRoot.resolve(sanitize(e.getKbCode()));
        Files.createDirectories(dir);
        String slug = e.getId() + "-" + sanitize(e.getTitle()).replaceAll("\\s+", "_");
        Path file = dir.resolve(slug + ".md");
        Files.writeString(file, md, StandardCharsets.UTF_8);
        e.setMdPath(file.toString());
        e.setMetaJson(meta == null ? "{}" : meta.toString());
    }

    /** 重启续跑：进程曾被杀时遗留的 PARSING/PENDING 重新排队 */
    @PostConstruct
    public void resumeStalled() {
        List<SupplierKbEntry> stalled = repo.findByStatusIn(List.of("PARSING", "PENDING"));
        if (stalled.isEmpty()) return;
        for (SupplierKbEntry e : stalled) {
            if ("PARSING".equals(e.getStatus())) { e.setStatus("PENDING"); repo.save(e); }
            afterCommit(() -> parseNow(e.getId()));
        }
        log.info("[SupplierKb] 重启续跑：{} 条滞留条目重新排队解析", stalled.size());
    }

    // ===== 小工具 =====

    /** 事务提交后再起异步解析，避免 worker 线程读不到未提交行 */
    private void afterCommit(Runnable r) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { Thread.ofVirtual().name("kb-parse").start(r); }
            });
        } else {
            Thread.ofVirtual().name("kb-parse").start(r);
        }
    }

    private interface MetaBuilder { void apply(JsonObject o); }

    private static JsonObject meta(MetaBuilder b) {
        JsonObject o = new JsonObject();
        b.apply(o);
        return o;
    }

    private static JsonObject parseMeta(String json) {
        try { return JsonParser.parseString(json == null || json.isBlank() ? "{}" : json).getAsJsonObject(); }
        catch (Exception e) { return new JsonObject(); }
    }

    private static String deriveTitle(String type, String value) {
        return switch (type) {
            case "url" -> value.length() > 80 ? value.substring(0, 80) + "…" : value;
            case "pdf" -> {
                String name = Paths.get(value).getFileName().toString();
                yield name.endsWith(".pdf") ? name.substring(0, name.length() - 4) : name;
            }
            default -> {
                String first = value.strip().lines().findFirst().orElse("记录");
                yield first.length() > 40 ? first.substring(0, 40) + "…" : first;
            }
        };
    }

    private static String typeIcon(String type) {
        return switch (type) {
            case "url" -> "🔗";
            case "pdf" -> "📕";
            case "chat" -> "💬";
            default -> "📝";
        };
    }

    private static String safeTitle(SupplierKbEntry e) {
        return e.getTitle() == null || e.getTitle().isBlank() ? "(未命名条目#" + e.getId() + ")" : e.getTitle();
    }

    private static String prettyPath(String p) {
        return p == null ? "" : p.replace('\\', '/');
    }

    private static String sanitize(String s) {
        return s == null ? "unknown" : s.replaceAll("[^A-Za-z0-9\\u4e00-\\u9fa5._-]", "_");
    }
}
