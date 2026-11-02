package com.lark.oapi.sample.ws.service;

import com.google.gson.JsonParser;
import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

/** Resolves company-maintained instructions on the execution host before dispatch. */
@Service
public class SupplierReferenceContext {
    private final SupplierKbService kb;
    public SupplierReferenceContext(SupplierKbService kb) { this.kb = kb; }

    public String load(SupplierJob job, boolean codegen) {
        StringBuilder text = new StringBuilder();
        var skills = JsonParser.parseString(job.getSkillsJson()).getAsJsonArray();
        if (codegen && skills.isEmpty()) throw new IllegalArgumentException("请配置供应商对接 Skill 路径");
        for (var skill : skills) append(text, "对接 Skill", read(job, skill.getAsString(), true));
        if (codegen && job.getSpecDocPath().isBlank()) throw new IllegalArgumentException("请配置公司 spec 文档");
        if (!job.getSpecDocPath().isBlank()) append(text, "公司 spec", read(job, job.getSpecDocPath(), false));
        if (codegen && job.getPromptSkillPath().isBlank()) throw new IllegalArgumentException("请配置公司提示词生成 Skill 路径");
        if (!job.getPromptSkillPath().isBlank()) append(text, "提示词生成 Skill", read(job, job.getPromptSkillPath(), true));
        text.append("\n必须使用目标项目的 Trellis 任务推进：先读取 .trellis/workflow.md，创建或恢复本供应商任务，维护 prd 与 implement/check 上下文，验证后记录进展。缺少 Trellis 或 Skill 引用文件时输出【卡点】，不得自行补造规范。\n");
        if (text.length() > 60000) throw new IllegalArgumentException("Skill/spec 正文超过 60000 字符，请拆分材料");
        return text.toString();
    }

    private void append(StringBuilder text, String role, String content) {
        text.append("\n## ").append(role).append('\n').append(content).append('\n');
    }

    private String read(SupplierJob job, String source, boolean skill) {
        if (source == null || source.isBlank()) throw new IllegalArgumentException("材料路径为空");
        try {
            String content;
            if (source.startsWith("https://") || source.startsWith("http://")) {
                var entry = kb.list(kb.kbCode(job)).stream().filter(e -> source.equals(e.getSource()) && "DONE".equals(e.getStatus()))
                        .findFirst().orElseThrow(() -> new IllegalArgumentException("请先把规范链接解析进供应商知识库：" + source));
                content = kb.readMd(entry.getId());
            } else {
                Path path = Path.of(source);
                if (Files.isDirectory(path) && skill) path = path.resolve("SKILL.md");
                if (!path.isAbsolute()) throw new IllegalArgumentException("请使用执行主机上的绝对路径：" + source);
                if (Files.size(path) > 240000) throw new IllegalArgumentException("规范文件超过读取上限：" + source);
                content = Files.readString(path, StandardCharsets.UTF_8);
            }
            if (content.isBlank()) throw new IllegalArgumentException("规范文件为空：" + source);
            return "来源：" + source + "\nSHA-256：" + digest(content) + "\n" + content;
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("无法读取规范，请确认执行主机上的路径：" + source, ex);
        }
    }

    public static String digest(String content) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
}
