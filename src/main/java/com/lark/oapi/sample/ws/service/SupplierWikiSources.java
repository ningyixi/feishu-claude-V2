package com.lark.oapi.sample.ws.service;

import com.google.gson.*;
import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.repository.SupplierKbPageRepository;
import org.springframework.stereotype.Service;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Current source inventory. Immutable compiler snapshots are separate from editable KB entries. */
@Service
public class SupplierWikiSources {
    public record Source(String id,String title,String type,String stage,String origin,String hash,String content) {}
    public record Snapshot(String kbCode,String digest,List<Source> sources,List<String> warnings,long rawChars) {}
    private final SupplierKbService kb;
    private final SupplierKbPageRepository pages;
    public SupplierWikiSources(SupplierKbService kb,SupplierKbPageRepository pages){this.kb=kb;this.pages=pages;}
    public Snapshot snapshot(SupplierJob job) {
        List<Source> result=new ArrayList<>(); List<String> warnings=new ArrayList<>();
        for(var entry:kb.list(kb.kbCode(job))) {
            if("archive".equals(entry.getContextStage())) continue;
            if(!"DONE".equals(entry.getStatus())) throw new IllegalArgumentException("Wiki 来源未就绪："+entry.getId()+" / "+entry.getTitle());
            result.add(read("e"+entry.getId(),entry.getTitle(),entry.getType(),entry.getContextStage(),entry.getSource(),entry.getMdPath()));
            JsonObject meta=JsonParser.parseString(entry.getMetaJson()).getAsJsonObject();
            if(meta.has("site") && meta.get("site").getAsBoolean()) {
                var children=pages.findByEntryIdOrderByIdAsc(entry.getId());
                for(var page:children) {
                    if("loaded".equals(page.getStatus())) result.add(read("e"+entry.getId()+"-p"+page.getId(),page.getTitle(),"site-page",entry.getContextStage(),page.getUrl(),page.getMdPath()));
                    else warnings.add("站点材料 e"+entry.getId()+" 未载入页面："+page.getUrl()+"（"+page.getStatus()+"）");
                }
                if(children.stream().noneMatch(p->"loaded".equals(p.getStatus()))) throw new IllegalArgumentException("Wiki 站点来源没有已载入的正文页："+entry.getId());
            }
        }
        result.sort(Comparator.comparing(Source::id));
        if(result.isEmpty()) throw new IllegalArgumentException("请先添加并解析供应商材料，再编译 Wiki");
        JsonArray inventory=new JsonArray(); long chars=0;
        for(var source:result) {inventory.add(metadata(source));chars+=source.content.length();}
        JsonObject version=new JsonObject();version.addProperty("schema","supplier-wiki-v1");version.add("sources",inventory);version.add("gaps",new Gson().toJsonTree(warnings));
        return new Snapshot(kb.kbCode(job),SupplierReferenceContext.digest(version.toString()),List.copyOf(result),List.copyOf(warnings),chars);
    }
    public static JsonObject metadata(Source source) {
        JsonObject value=new JsonObject(); value.addProperty("id",source.id);value.addProperty("title",source.title);value.addProperty("type",source.type);
        value.addProperty("stage",source.stage);value.addProperty("origin",source.origin);value.addProperty("hash",source.hash);return value;
    }
    private Source read(String id,String title,String type,String stage,String origin,String file) {
        try {
            Path path=Path.of(file);
            if(Files.size(path)>2_000_000) throw new IllegalArgumentException("单份 Wiki 来源超过读取预算，请按完整章节整理来源："+id);
            String text=Files.readString(path,StandardCharsets.UTF_8);
            if(text.isBlank() || text.length()>500000) throw new IllegalArgumentException("Wiki 来源为空或超过 500000 字符："+id);
            return new Source(id,title,type,stage,origin,SupplierReferenceContext.digest(text),text);
        } catch(java.io.IOException ex){throw new IllegalArgumentException("Wiki 无法读取来源："+id,ex);}
    }
}
