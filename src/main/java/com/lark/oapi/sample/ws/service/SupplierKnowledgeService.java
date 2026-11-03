package com.lark.oapi.sample.ws.service;

import com.google.gson.Gson;
import com.lark.oapi.sample.ws.model.entity.*;
import com.lark.oapi.sample.ws.repository.SupplierKnowledgeRunRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

/** Explicit strategy selection, never silently fall back from Wiki to raw text. */
@Service
public class SupplierKnowledgeService {
    public record Pack(String mode,String body,String digest,Long revision,long rawChars,List<String> pages) {
        public String rendered(){return "【知识表示："+mode+"；来源指纹："+digest+(revision==null?"":"；Wiki 版本："+revision)+"】\n"+body;}
    }
    private final SupplierKbService kb;
    private final SupplierWikiService wiki;
    private final SupplierWikiSources sources;
    private final SupplierKnowledgeRunRepository runs;
    public SupplierKnowledgeService(SupplierKbService kb,SupplierWikiService wiki,SupplierWikiSources sources,SupplierKnowledgeRunRepository runs){this.kb=kb;this.wiki=wiki;this.sources=sources;this.runs=runs;}
    @PostConstruct public void recoverInterrupted(){for(var run:runs.findByStatus("DISPATCHED")){run.setStatus("INTERRUPTED");run.setFinishedAt(LocalDateTime.now());runs.save(run);}}
    public Pack context(SupplierJob job,String stage) {
        if("WIKI".equals(job.getKnowledgeMode())){var pack=wiki.context(job,stage);return new Pack("WIKI",pack.body(),pack.digest(),pack.revision(),pack.rawChars(),pack.pages());}
        String body="all".equals(stage)?kb.contextText(kb.kbCode(job)):kb.contextText(kb.kbCode(job),stage);
        return textPack(job,body);
    }
    public Pack chatContext(SupplierJob job,String stage) {
        return "WIKI".equals(job.getKnowledgeMode())?context(job,stage):textPack(job,kb.indexText(kb.kbCode(job)));
    }
    private Pack textPack(SupplierJob job,String body) {
        // Metrics must not impose Wiki-wide source readiness rules on the TEXT control arm.
        try{var snapshot=sources.snapshot(job);return new Pack("TEXT",body,snapshot.digest(),null,snapshot.rawChars(),List.of());}
        catch(IllegalArgumentException ex){return new Pack("TEXT",body,"",null,-1,List.of());}
    }
    public Long record(SupplierJob job,String nodeId,String stage,Pack pack,String prompt){
        var row=new SupplierKnowledgeRun();row.setJobId(job.getId());row.setNodeId(nodeId);row.setMode(pack.mode());row.setStage(stage);row.setSourceDigest(pack.digest());
        row.setWikiRevision(pack.revision());row.setRawChars(pack.rawChars());row.setKnowledgeChars(pack.body().length());row.setPromptChars(prompt.length());
        row.setRequestDigest(SupplierReferenceContext.digest(prompt));row.setPagesJson(new Gson().toJson(pack.pages()));runs.save(row);return row.getId();
    }
    public void finish(Long id,TurnResult result,String reply){
        if(id==null)return;var row=runs.findById(id).orElseThrow();
        row.setStatus(reply==null || reply.startsWith("❌") || (result!=null && result.isError)?"FAILED":SupplierJobService.STANDBLOCK.matcher(reply).find()?"WAITING":"COMPLETED");
        if(result!=null){row.setInputTokens(result.inputTokens);row.setOutputTokens(result.outputTokens);row.setCacheReadTokens(result.cacheReadTokens);row.setCacheWriteTokens(result.cacheWriteTokens);
            row.setCostUsd(result.costUsd);row.setModel(result.model);row.setDurationMs(result.durationMs);}
        row.setFinishedAt(LocalDateTime.now());runs.save(row);
    }
    public List<SupplierKnowledgeRun> runs(Long jobId){return runs.findTop200ByJobIdOrderByIdDesc(jobId);}
}
