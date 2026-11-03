package com.lark.oapi.sample.ws.controller;

import com.lark.oapi.sample.ws.service.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import java.util.Map;

@RestController
@RequestMapping("/api/supplier-jobs/{jobId}/workflow")
public class SupplierWorkflowController {
    private final SupplierWorkflowService workflow;
    private final SupplierWorkflowFiles files;
    public SupplierWorkflowController(SupplierWorkflowService workflow,SupplierWorkflowFiles files){this.workflow=workflow;this.files=files;}
    @GetMapping public Map<String,Object> state(@PathVariable Long jobId){return workflow.state(jobId);}
    @PostMapping("/start") public Object start(@PathVariable Long jobId,@RequestBody Map<String,Object> body){return workflow.start(jobId,(String)body.get("recipient"),!Boolean.FALSE.equals(body.get("notifyFeishu")));}
    @PostMapping("/control") public Object control(@PathVariable Long jobId,@RequestBody Map<String,String> body){workflow.control(jobId,body.getOrDefault("action",""));return Map.of("ok",true);}
    @PostMapping("/tasks/{taskId}/respond") public Object respond(@PathVariable Long jobId,@PathVariable String taskId,@RequestBody Map<String,String> body){workflow.respond(jobId,taskId,body.getOrDefault("action",""),body.getOrDefault("answer",""));return Map.of("ok",true);}
    @PostMapping("/tasks/{taskId}/files") public Object upload(@PathVariable Long jobId,@PathVariable String taskId,@RequestParam("file") MultipartFile file){return files.upload(jobId,taskId,file);}
    @PostMapping("/notices/{noticeId}/retry") public Object retry(@PathVariable Long jobId,@PathVariable Long noticeId){workflow.retryNotice(jobId,noticeId);return Map.of("ok",true);}
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> bad(IllegalArgumentException ex){return ResponseEntity.badRequest().body(Map.of("error",ex.getMessage()==null?"无效工作流请求":ex.getMessage()));}
}
