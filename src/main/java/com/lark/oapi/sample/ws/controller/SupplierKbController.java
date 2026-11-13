package com.lark.oapi.sample.ws.controller;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.model.entity.SupplierKbEntry;
import com.lark.oapi.sample.ws.service.SupplierKbService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 供应商知识库 REST API（R3 统一补充入口的后端）：
 * 条目列表 / 新增（url|pdf|html|md|chat|note 随时补充）/ 重解析 / 产物 MD 读与人工校正。
 */
@RestController
@RequestMapping("/api/supplier-kb")
public class SupplierKbController {

    private static final Logger log = LoggerFactory.getLogger(SupplierKbController.class);

    private final SupplierKbService kbService;
    private final com.lark.oapi.sample.ws.repository.SupplierKbPageRepository entryPageRepo;

    public SupplierKbController(SupplierKbService kbService,
                                com.lark.oapi.sample.ws.repository.SupplierKbPageRepository entryPageRepo) {
        this.kbService = kbService;
        this.entryPageRepo = entryPageRepo;
    }

    /** 列条目（Jackson 直出实体，无大字段：正文在 md 文件里） */
    @GetMapping("/{kbCode}/entries")
    public List<SupplierKbEntry> entries(@PathVariable String kbCode) {
        return kbService.list(kbCode);
    }

    /** 新增条目：{"type":"url|pdf|html|md|chat|note","value":"链接/路径/文本","title":"可选","site":false} */
    @PostMapping(value = "/{kbCode}/entries", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> add(@PathVariable String kbCode, @RequestBody String rawBody) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            String type = body.has("type") && !body.get("type").isJsonNull() ? body.get("type").getAsString() : "url";
            String value = body.has("value") && !body.get("value").isJsonNull() ? body.get("value").getAsString() : "";
            String title = body.has("title") && !body.get("title").isJsonNull() ? body.get("title").getAsString() : "";
            boolean site = body.has("site") && !body.get("site").isJsonNull() && body.get("site").getAsBoolean();
            SupplierKbEntry e = kbService.add(kbCode, type, value, title, site);
            return ResponseEntity.ok("{\"ok\":true,\"id\":" + e.getId() + ",\"status\":\"" + e.getStatus() + "\"}");
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (Exception e) {
            log.error("[SupplierKb] 新增条目失败 kb={}", kbCode, e);
            return err("新增失败: " + e.getMessage());
        }
    }

    /** 站点抓取页面清单（逐页状态/标题/错误，pending|loaded|failed） */
    @GetMapping("/{kbCode}/entries/{id}/pages")
    public java.util.List<com.lark.oapi.sample.ws.model.entity.SupplierKbPage> entryPages(@PathVariable String kbCode, @PathVariable Long id) {
        return entryPageRepo.findByEntryIdOrderByIdAsc(id);
    }

    /** 取消站点抓取 */
    @PostMapping(value = "/entries/{id}/cancel", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> cancel(@PathVariable Long id) {
        kbService.cancelCrawl(id);
        return ResponseEntity.ok("{\"ok\":true}");
    }

    /** 单页正文预览（站点抓取 loaded 页） */
    @GetMapping(value = "/pages/{pageId}/md", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> pageMd(@PathVariable Long pageId) {
        try {
            var page = entryPageRepo.findById(pageId)
                    .orElseThrow(() -> new IllegalArgumentException("页面不存在: " + pageId));
            if (page.getMdPath() == null || page.getMdPath().isBlank()) throw new IllegalArgumentException("该页没有已保存正文");
            JsonObject o = new JsonObject();
            o.addProperty("content", java.nio.file.Files.readString(java.nio.file.Paths.get(page.getMdPath())));
            return ResponseEntity.ok(o.toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (Exception e) {
            return err("读取失败: " + e.getMessage());
        }
    }

    @PutMapping(value = "/entries/{id}/stage", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> stage(@PathVariable Long id, @RequestBody String body) {
        try {
            JsonObject request = JsonParser.parseString(body).getAsJsonObject();
            kbService.setContextStage(id, request.has("stage") && !request.get("stage").isJsonNull() ? request.get("stage").getAsString() : "");
            return ResponseEntity.ok("{\"ok\":true}");
        } catch (IllegalArgumentException ex) { return err(ex.getMessage()); }
    }

    /** 重新解析（FAILED 重试 / DONE 回到机析结果） */
    @PostMapping(value = "/entries/{id}/reparse", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> reparse(@PathVariable Long id) {
        try {
            kbService.reparse(id);
            return ResponseEntity.ok("{\"ok\":true}");
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        }
    }

    /** 删除条目（元数据+产物文件；自动同步过的来源记墓碑不再带回） */
    @DeleteMapping(value = "/entries/{id}", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> delete(@PathVariable Long id) {
        try {
            kbService.delete(id);
            return ResponseEntity.ok("{\"ok\":true}");
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (Exception e) {
            log.error("[SupplierKb] 删除失败 id={}", id, e);
            return err("删除失败: " + e.getMessage());
        }
    }

    /** 读条目解析产物 */
    @GetMapping(value = "/entries/{id}/md", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> getMd(@PathVariable Long id) {
        try {
            JsonObject o = new JsonObject();
            o.addProperty("content", kbService.readMd(id));
            return ResponseEntity.ok(o.toString());
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (Exception e) {
            return err("读取失败: " + e.getMessage());
        }
    }

    /** 人工校正解析产物 */
    @PutMapping(value = "/entries/{id}/md", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> saveMd(@PathVariable Long id, @RequestBody String rawBody) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            kbService.saveMd(id, body.has("content") ? body.get("content").getAsString() : "");
            return ResponseEntity.ok("{\"ok\":true}");
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (Exception e) {
            return err("保存失败: " + e.getMessage());
        }
    }

    private ResponseEntity<String> err(String msg) {
        JsonObject o = new JsonObject();
        o.addProperty("error", msg != null ? msg : "请求无效");
        return ResponseEntity.badRequest().body(o.toString());
    }
}
