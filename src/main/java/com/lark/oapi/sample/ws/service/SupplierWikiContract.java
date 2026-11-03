package com.lark.oapi.sample.ws.service;

import com.google.gson.*;
import java.util.*;
import java.util.regex.Pattern;

/** Mechanical publication gates; semantic truth still requires reviewing source evidence. */
public final class SupplierWikiContract {
    public static final List<String> STAGES=List.of("all","analysis","probe","codegen");
    private static final Pattern ID=Pattern.compile("[a-z0-9][a-z0-9-]{0,63}");
    private static final Pattern LINK=Pattern.compile("\\[[^]\\n]*]\\(([^)\\s]+)\\)");
    public static JsonObject json(String reply) {
        if(reply==null || reply.isBlank() || reply.startsWith("❌")) throw new IllegalArgumentException("Wiki 模型调用失败或无输出");
        String text=reply.strip();
        if(text.startsWith("```json\n") && text.endsWith("```")) text=text.substring(8,text.length()-3).strip();
        else if(text.startsWith("```\n") && text.endsWith("```")) text=text.substring(4,text.length()-3).strip();
        try{return JsonParser.parseString(text).getAsJsonObject();}
        catch(RuntimeException ex){throw new IllegalArgumentException("Wiki 编译器需要完整 JSON 输出，未发布草稿",ex);}
    }
    public static String string(JsonObject object,String key,int max) {
        if(!object.has(key) || !object.get(key).isJsonPrimitive() || !object.get(key).getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Wiki 字段缺失："+key);
        String value=object.get(key).getAsString().strip();
        if(value.isBlank() || value.length()>max) throw new IllegalArgumentException("Wiki 字段为空或过长："+key);
        return value;
    }
    private static JsonArray array(JsonObject object,String key) {
        if(!object.has(key) || !object.get(key).isJsonArray()) throw new IllegalArgumentException("Wiki 数组缺失："+key);
        return object.getAsJsonArray(key);
    }
    private static void quote(String quote,SupplierWikiSources.Source source) {
        if(quote.length()<Math.min(8,source.content().length()) || !source.content().contains(quote)) throw new IllegalArgumentException("Wiki 引文无法在原文核对："+source.id());
    }
    public static JsonObject extraction(JsonObject value,SupplierWikiSources.Source source) {
        string(value,"summary",4000);
        var facts=array(value,"facts");
        if(facts.isEmpty() || facts.size()>1000 || value.toString().length()>50000) throw new IllegalArgumentException("Wiki 来源提取为空或超预算："+source.id());
        for(var fact:facts){var item=fact.getAsJsonObject();string(item,"statement",3000);quote(string(item,"quote",10000),source);}
        return value;
    }
    public static JsonObject publication(JsonObject value,SupplierWikiSources.Snapshot snapshot) {
        var pages=array(value,"pages");
        if(pages.isEmpty() || pages.size()>300) throw new IllegalArgumentException("Wiki 页面数量无效");
        Map<String,JsonObject> byId=new LinkedHashMap<>();
        Map<String,SupplierWikiSources.Source> sources=new HashMap<>();snapshot.sources().forEach(s->sources.put(s.id(),s));
        Set<String> covered=new HashSet<>();
        for(var element:pages){
            var page=element.getAsJsonObject();String id=string(page,"id",64);
            if(!ID.matcher(id).matches() || byId.putIfAbsent(id,page)!=null || id.startsWith("index") || id.equals("schema") || id.equals("log")) throw new IllegalArgumentException("Wiki 页面 ID 无效或重复："+id);
            string(page,"title",160);string(page,"summary",240);string(page,"content",12000);
            if(!List.of("overview","constraints","api","business","concept","source","question").contains(string(page,"kind",32))) throw new IllegalArgumentException("Wiki 页面类型无效");
            var stages=array(page,"stages");if(stages.isEmpty()) throw new IllegalArgumentException("Wiki 页面没有使用阶段");
            for(var stage:stages) if(!STAGES.contains(stage.getAsString())) throw new IllegalArgumentException("Wiki 页面阶段无效");
            var evidence=array(page,"evidence");if(evidence.isEmpty()) throw new IllegalArgumentException("Wiki 页面缺少来源证据："+id);
            for(var citation:evidence){
                var item=citation.getAsJsonObject();String sourceId=string(item,"sourceId",80);
                var source=sources.get(sourceId);if(source==null)throw new IllegalArgumentException("Wiki 引用了未知来源："+sourceId);
                quote(string(item,"quote",10000),source);covered.add(sourceId);
            }
        }
        if(covered.size()!=sources.size())throw new IllegalArgumentException("Wiki 没有覆盖全部来源，拒绝发布");
        for(var page:byId.values()){
            var matcher=LINK.matcher(page.get("content").getAsString());
            while(matcher.find()){
                String link=matcher.group(1);
                if(link.startsWith("https://") || link.startsWith("http://") || link.startsWith("#")) continue;
                if(!link.endsWith(".md") || !byId.containsKey(link.substring(0,link.length()-3))) throw new IllegalArgumentException("Wiki 页面存在断链或越界链接："+link);
            }
        }
        var critical=array(value,"critical"); if(critical.isEmpty())throw new IllegalArgumentException("Wiki 缺少必读约束页");
        for(var id:critical)if(!byId.containsKey(id.getAsString()))throw new IllegalArgumentException("Wiki 必读页不存在");
        if(!value.has("entrypoints") || !value.get("entrypoints").isJsonObject()) throw new IllegalArgumentException("Wiki 缺少阶段入口");
        var entrypoints=value.getAsJsonObject("entrypoints");
        for(var stage:STAGES){
            var entries=array(entrypoints,stage);if(entries.isEmpty())throw new IllegalArgumentException("Wiki 阶段入口为空："+stage);
            for(var id:entries)if(!byId.containsKey(id.getAsString()))throw new IllegalArgumentException("Wiki 阶段入口页不存在："+stage);
            Set<String> selected=new HashSet<>();critical.forEach(id->selected.add(id.getAsString()));entries.forEach(id->selected.add(id.getAsString()));
            int budget=5000+snapshot.warnings().stream().mapToInt(String::length).sum();
            for(String id:selected){var page=byId.get(id);budget+=page.get("content").getAsString().length()+200+page.getAsJsonArray("evidence").size()*200;}
            if(budget>32000)throw new IllegalArgumentException("Wiki 阶段入口超过预算，拒绝发布："+stage);
        }
        return value;
    }
}
