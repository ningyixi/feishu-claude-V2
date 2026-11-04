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
    static void quote(String quote,SupplierWikiSources.Source source) {
        // 归一化后逐字子串优先；再容忍空白/全半角之外的“漏抄转义/标点”按序子序列兜底
        String q=normalize(quote), s=normalize(source.content());
        if(q.length()<Math.min(4,s.length()))                 // 过短无法承载“依据”语义
            throw new IllegalArgumentException("Wiki 引文过短(需≥4字)："+source.id()+"（引文「"+preview(quote)+"」）");
        if(s.contains(q)) return;                             // 逐字命中
        if(q.length()>=6 && isSubsequence(q,s)) return;       // 漏抄 \n 等转义/标点的容错
        throw new IllegalArgumentException("Wiki 引文无法在原文核对："+source.id()+"（逐字与子序列容错均未命中；引文起始「"+preview(quote)+"」）");
    }
    private static boolean isSubsequence(String sub,String full){
        int i=0;
        for(int j=0;j<full.length()&&i<sub.length();j++) if(sub.charAt(i)==full.charAt(j)) i++;
        return i==sub.length();
    }
    /** NFKC 统一全半角/兼容字符，再删除所有空白与零宽字符后比对（两端同规则） */
    static String normalize(String x){
        return java.text.Normalizer.normalize(x, java.text.Normalizer.Form.NFKC).replaceAll("[\\s\\u00A0\\u200B\\u200E\\u200F\\uFEFF]+","");
    }
    private static String preview(String x){ String p=x.strip().replaceAll("\\s+"," "); return p.length()>60?p.substring(0,60)+"…":p; }
    public static JsonObject extraction(JsonObject value,SupplierWikiSources.Source source) {
        string(value,"summary",4000);
        var facts=array(value,"facts");
        if(facts.isEmpty() || facts.size()>1000 || value.toString().length()>50000) throw new IllegalArgumentException("Wiki 来源提取为空或超预算："+source.id());
        for(var fact:facts){var item=fact.getAsJsonObject();string(item,"statement",3000);quote(string(item,"quote",10000),source);}
        return value;
    }
    public static JsonObject publication(JsonObject value,SupplierWikiSources.Snapshot snapshot){ return publication(value,snapshot,false); }
    /** lenient=true：逐页/逐条证据尽力核验，无效页或坏引用丢弃、未覆盖来源记为缺口，只要有可核验页就发布（避免一处瑕疵整版失败） */
    public static JsonObject publication(JsonObject value,SupplierWikiSources.Snapshot snapshot,boolean lenient){
        var pages=array(value,"pages");
        if(pages.isEmpty() || pages.size()>300) throw new IllegalArgumentException("Wiki 页面数量无效");
        Map<String,SupplierWikiSources.Source> sources=new HashMap<>();snapshot.sources().forEach(s->sources.put(s.id(),s));
        Map<String,JsonObject> byId=new LinkedHashMap<>(); JsonArray kept=new JsonArray(); Set<String> covered=new HashSet<>(); List<String> dropped=new ArrayList<>();
        for(var element:pages){
            if(!element.isJsonObject()){ if(!lenient)throw new IllegalArgumentException("Wiki 页面格式无效"); continue; }
            var page=element.getAsJsonObject(); String id;
            try{ id=string(page,"id",64);}catch(RuntimeException e){ if(lenient){dropped.add("(无id)");continue;} throw new IllegalArgumentException("Wiki 页面 ID 无效"); }
            boolean valid=ID.matcher(id).matches() && !id.startsWith("index") && !id.equals("schema") && !id.equals("log") && !byId.containsKey(id);
            try{ string(page,"title",160);string(page,"summary",240);string(page,"content",12000);
                if(!List.of("overview","constraints","api","business","concept","source","question").contains(string(page,"kind",32))) valid=false;
                var stg=array(page,"stages"); if(stg.isEmpty()) valid=false;
                for(var st:stg) if(!STAGES.contains(st.getAsString())) valid=false;
            }catch(RuntimeException e){ valid=false; }
            JsonArray keepEv=new JsonArray();
            if(page.has("evidence")&&page.get("evidence").isJsonArray()) for(var c:page.getAsJsonArray("evidence")){
                try{ var co=c.getAsJsonObject(); String sid=string(co,"sourceId",80); var src=sources.get(sid); if(src==null) throw new IllegalArgumentException("未知来源"); quote(string(co,"quote",10000),src); keepEv.add(c); covered.add(sid);}
                catch(RuntimeException e){ if(!lenient) throw new IllegalArgumentException("Wiki 页面证据不可核验/来源无效："+id); }
            }
            if(keepEv.isEmpty()) valid=false;
            if(!valid){ if(!lenient) throw new IllegalArgumentException("Wiki 页面无效或证据不可核验/重复："+id); dropped.add(id); continue; }
            page.add("evidence",keepEv); byId.put(id,page); kept.add(page);
        }
        if(byId.isEmpty()) throw new IllegalArgumentException("Wiki 没有任何可核验的页面（多数页缺逐字证据），拒绝发布");
        if(!lenient && covered.size()!=sources.size()) throw new IllegalArgumentException("Wiki 没有覆盖全部来源，拒绝发布");
        for(var page:byId.values()){
            var m=LINK.matcher(page.get("content").getAsString());
            if(!lenient){ while(m.find()){String l=m.group(1); if(l.startsWith("http")||l.startsWith("#"))continue; if(!l.endsWith(".md")||!byId.containsKey(l.substring(0,l.length()-3)))throw new IllegalArgumentException("Wiki 页面存在断链或越界链接："+l);} }
            else { StringBuilder sb=new StringBuilder(); int last=0; var r=LINK.matcher(page.get("content").getAsString());
                while(r.find()){ String l=r.group(1); if(l.startsWith("http")||l.startsWith("#")||(l.endsWith(".md")&&byId.containsKey(l.substring(0,l.length()-3))))continue;
                    sb.append(page.get("content").getAsString(),last,r.start()).append(stripLink(r.group(0))); last=r.end(); }
                sb.append(page.get("content").getAsString(),last,page.get("content").getAsString().length()); page.addProperty("content",sb.toString()); }
        }
        List<String> critIds=new ArrayList<>();
        if(value.has("critical")&&value.get("critical").isJsonArray()) for(var e:value.getAsJsonArray("critical")){String s=e.getAsString(); if(byId.containsKey(s)&&!critIds.contains(s))critIds.add(s);}
        if(critIds.isEmpty()) for(var id:byId.keySet()){String k=byId.get(id).get("kind").getAsString(); if(k.equals("constraints")||k.equals("overview"))critIds.add(id);}
        if(critIds.isEmpty()) critIds.add(byId.keySet().iterator().next());
        JsonObject epv=(value.has("entrypoints")&&value.get("entrypoints").isJsonObject())?value.getAsJsonObject("entrypoints"):new JsonObject();
        Map<String,List<String>> epMap=new LinkedHashMap<>();
        for(String stage:STAGES){ List<String> l=new ArrayList<>();
            if(epv.has(stage)&&epv.get(stage).isJsonArray()) for(var e:epv.getAsJsonArray(stage)){String s=e.getAsString(); if(byId.containsKey(s)&&!l.contains(s))l.add(s);}
            if(l.isEmpty()) for(var id:byId.keySet()){ boolean hit=false; for(var st:byId.get(id).getAsJsonArray("stages")){String v=st.getAsString(); if(v.equals(stage)||v.equals("all")){hit=true;break;}} if(hit){l.add(id);break;} }
            if(l.isEmpty()) l.add(byId.keySet().iterator().next()); epMap.put(stage,l);
        }
        if(!lenient){ int base=5000+snapshot.warnings().stream().mapToInt(String::length).sum();
            for(String stage:STAGES){ Set<String> sel=new HashSet<>(critIds); sel.addAll(epMap.get(stage)); int budget=base;
                for(String id:sel){var pg=byId.get(id);budget+=pg.get("content").getAsString().length()+200+pg.getAsJsonArray("evidence").size()*200;}
                if(budget>32000)throw new IllegalArgumentException("Wiki 阶段入口超过预算，拒绝发布："+stage);} }
        value.add("pages",kept);
        JsonArray crit=new JsonArray(); critIds.forEach(crit::add); value.add("critical",crit);
        JsonObject epj=new JsonObject(); for(String stage:STAGES){JsonArray a=new JsonArray(); epMap.get(stage).forEach(a::add); epj.add(stage,a);} value.add("entrypoints",epj);
        if(lenient){ List<String> gaps=new ArrayList<>(); for(var s:sources.values()) if(!covered.contains(s.id())) gaps.add("来源 "+s.id()+" 未被任何页逐字证据覆盖");
            if(!dropped.isEmpty()) gaps.add("丢弃无效页 "+dropped.size()+" 个");
            value.addProperty("lenient",true); if(!gaps.isEmpty()){JsonArray g=new JsonArray(); gaps.forEach(x->g.add(x)); value.add("gaps",g);} }
        return value;
    }
    private static String stripLink(String md){ int b=md.indexOf("]("); return b<0?md:md.substring(0,b)+md.substring(b+2,md.length()-1); }
}
