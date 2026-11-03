package com.lark.oapi.sample.ws.controller;

import com.lark.oapi.sample.ws.model.entity.*;
import com.lark.oapi.sample.ws.repository.SupplierJobRepository;
import com.lark.oapi.sample.ws.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/supplier-jobs/{jobId}")
public class SupplierWikiController {
    private final SupplierJobRepository jobs;
    private final SupplierWikiService wiki;
    private final SupplierKnowledgeService knowledge;
    public SupplierWikiController(SupplierJobRepository jobs,SupplierWikiService wiki,SupplierKnowledgeService knowledge){this.jobs=jobs;this.wiki=wiki;this.knowledge=knowledge;}
    private SupplierJob job(Long id){return jobs.findById(id).orElseThrow(()->new IllegalArgumentException("工作不存在"));}
    @GetMapping("/wiki") public ResponseEntity<?> status(@PathVariable Long jobId){try{return ResponseEntity.ok(wiki.status(job(jobId)));}catch(IllegalArgumentException ex){return bad(ex);}}
    @GetMapping("/wiki/schema") public ResponseEntity<?> schema(@PathVariable Long jobId){try{return ResponseEntity.ok(wiki.schema(job(jobId)));}catch(IllegalArgumentException ex){return bad(ex);}}
    @PutMapping("/wiki/schema") public ResponseEntity<?> schema(@PathVariable Long jobId,@RequestBody Map<String,String> body){try{if(body.get("instructions")==null)throw new IllegalArgumentException("需要 instructions 文本");wiki.saveInstructions(job(jobId),body.get("instructions"));return ResponseEntity.ok(Map.of("ok",true));}catch(IllegalArgumentException ex){return bad(ex);}}
    @PostMapping("/wiki/build") public ResponseEntity<?> build(@PathVariable Long jobId){try{var row=wiki.build(job(jobId));return ResponseEntity.accepted().body(Map.of("revision",row.getId(),"status",row.getStatus()));}catch(IllegalArgumentException ex){return bad(ex);}}
    @GetMapping("/wiki/revisions/{revision}/pages/{id}") public ResponseEntity<?> page(@PathVariable Long jobId,@PathVariable Long revision,@PathVariable String id){return read(jobId,revision,id,false);}
    @GetMapping("/wiki/revisions/{revision}/sources/{id}") public ResponseEntity<?> source(@PathVariable Long jobId,@PathVariable Long revision,@PathVariable String id){return read(jobId,revision,id,true);}
    private ResponseEntity<?> read(Long jobId,Long revision,String id,boolean raw){try{return ResponseEntity.ok(Map.of("content",wiki.read(job(jobId),revision,id,raw),"revision",revision,"id",id));}catch(IllegalArgumentException ex){return bad(ex);}}
    @GetMapping("/knowledge-runs") public List<SupplierKnowledgeRun> runs(@PathVariable Long jobId){job(jobId);return knowledge.runs(jobId);}
    private ResponseEntity<?> bad(IllegalArgumentException ex){return ResponseEntity.badRequest().body(Map.of("error",ex.getMessage()==null?"Wiki 请求无效":ex.getMessage()));}
}
