package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

/** Creates a real task and immutable context snapshot in the target project's Trellis. */
final class SupplierTrellisContext {
    static String prepare(SupplierJob job, String context) {
        try {
            Path root = Path.of(job.getLibraryPath()).toAbsolutePath();
            if (!Files.isDirectory(root)) root = root.getParent();
            while (root != null && !Files.isRegularFile(root.resolve(".trellis/workflow.md"))) root = root.getParent();
            if (root == null) throw new IllegalArgumentException("类库路径所属项目没有 .trellis/workflow.md，请先初始化 Trellis");
            String relative = ".trellis/tasks/supplier-" + job.getId();
            Path task = root.resolve(relative);
            Files.createDirectories(task);
            String snapshot = "context-" + SupplierReferenceContext.digest(context) + ".md";
            Path file = task.resolve(snapshot);
            if (!Files.exists(file)) Files.writeString(file, context, StandardCharsets.UTF_8);
            Path metadata = task.resolve("task.json");
            if (!Files.exists(metadata)) {
                JsonObject json = new JsonObject();
                json.addProperty("id", "supplier-" + job.getId());
                json.addProperty("name", "supplier-" + job.getId());
                json.addProperty("title", "供应商对接 " + job.getSupplierName());
                json.addProperty("status", "planning");
                json.addProperty("priority", "P2");
                json.addProperty("createdAt", java.time.LocalDate.now().toString());
                json.addProperty("creator", "supplier-workbench");
                json.add("children", new JsonArray());
                json.add("relatedFiles", new JsonArray());
                Files.writeString(metadata, json.toString(), StandardCharsets.UTF_8);
            }
            Path prd = task.resolve("prd.md");
            if (!Files.exists(prd)) Files.writeString(prd, "# 供应商对接 " + job.getSupplierName()
                    + "\n\n按已交付的接口文档、商务约定、实测证据和公司 Skill/spec 完成对接，验证后更新本任务。\n", StandardCharsets.UTF_8);
            JsonObject entry = new JsonObject();
            entry.addProperty("file", relative + "/" + snapshot);
            entry.addProperty("reason", "工作台交付的供应商上下文快照；不可省略");
            for (String action : new String[]{"implement", "check"}) {
                Path jsonl = task.resolve(action + ".jsonl");
                String previous = Files.exists(jsonl) ? Files.readString(jsonl, StandardCharsets.UTF_8) : "";
                StringBuilder curated = new StringBuilder();
                for (String line : previous.split("\\R")) {
                    if (line.isBlank()) continue;
                    var old = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
                    if (old.has("file") && old.get("file").getAsString().startsWith(relative + "/context-")) continue;
                    curated.append(line).append('\n');
                }
                Files.writeString(jsonl, curated + entry.toString() + "\n", StandardCharsets.UTF_8);
            }
            return "\n## 已由工作台创建的 Trellis 任务\n项目：" + root + "\n任务：" + relative
                    + "\n上下文快照：" + file + "\n先运行 python .trellis/scripts/task.py start " + relative + "，在该任务内推进、检查并记录结果。\n";
        } catch (java.io.IOException ex) { throw new IllegalArgumentException("无法创建供应商 Trellis 任务：" + ex.getMessage(), ex); }
    }
}
