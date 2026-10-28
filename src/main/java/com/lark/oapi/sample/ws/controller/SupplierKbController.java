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
 * 条目列表 / 新增（url|pdf|chat|note 随时补充）/ 重解析 / 产物 MD 读与人工校正。
 */
@RestController
@RequestMapping("/api/supplier-kb")
public class SupplierKbController {

    private static final Logger log = LoggerFactory.getLogger(SupplierKbController.class);

    private final SupplierKbService kbService;

    public SupplierKbController(SupplierKbService kbService) {
        this.kbService = kbService;
    }

    /** 列条目（Jackson 直出实体，无大字段：正文在 md 文件里） */
    @GetMapping("/{kbCode}/entries")
    public List<SupplierKbEntry> entries(@PathVariable String kbCode) {
        return kbService.list(kbCode);
    }

    /** 新增条目：{"type":"url|pdf|chat|note","value":"链接/路径/文本","title":"可选"} */
    @PostMapping(value = "/{kbCode}/entries", produces = "application/json;charset=UTF-8")
    public ResponseEntity<String> add(@PathVariable String kbCode, @RequestBody String rawBody) {
        try {
            JsonObject body = JsonParser.parseString(rawBody).getAsJsonObject();
            String type = body.has("type") && !body.get("type").isJsonNull() ? body.get("type").getAsString() : "url";
            String value = body.has("value") && !body.get("value").isJsonNull() ? body.get("value").getAsString() : "";
            String title = body.has("title") && !body.get("title").isJsonNull() ? body.get("title").getAsString() : "";
            SupplierKbEntry e = kbService.add(kbCode, type, value, title);
            return ResponseEntity.ok("{\"ok\":true,\"id\":" + e.getId() + ",\"status\":\"" + e.getStatus() + "\"}");
        } catch (IllegalArgumentException e) {
            return err(e.getMessage());
        } catch (Exception e) {
            log.error("[SupplierKb] 新增条目失败 kb={}", kbCode, e);
            return err("新增失败: " + e.getMessage());
        }
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
