package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import com.lark.oapi.sample.ws.model.entity.SupplierWorkflowAttachment;
import com.lark.oapi.sample.ws.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.*;

@Service
public class SupplierWorkflowFiles {
    private static final Logger log = LoggerFactory.getLogger(SupplierWorkflowFiles.class);
    private final SupplierWorkflowService workflow;
    private final SupplierWorkflowAttachmentRepository files;
    private final SupplierJobRepository jobs;
    private final SupplierKbService kb;
    private final Path root;

    /** Wiki 自动重编译入口（ObjectProvider 延迟解析，不进构造器签名——测试直接 new） */
    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.beans.factory.ObjectProvider<SupplierWikiService> wikiProvider;

    public SupplierWorkflowFiles(SupplierWorkflowService workflow,SupplierWorkflowAttachmentRepository files,SupplierJobRepository jobs,SupplierKbService kb,@Value("${feishu.kb-dir}") String root){this.workflow=workflow;this.files=files;this.jobs=jobs;this.kb=kb;this.root=Path.of(root).resolve(".workflow-uploads");}
    @Transactional(isolation=org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public SupplierWorkflowAttachment upload(Long jobId,String taskId,MultipartFile file) {
        var run=workflow.activeTaskForUpload(jobId,taskId);
        if(file.isEmpty() || file.getSize()>10L*1024*1024)throw new IllegalArgumentException("文件需为 1 字节到 10MB");
        String name=Optional.ofNullable(file.getOriginalFilename()).orElse("material.txt").replace('\\','/');name=name.substring(name.lastIndexOf('/')+1);
        if(name.isBlank() || name.length()>180)throw new IllegalArgumentException("文件名为空或过长");
        final String cleanName=name;
        String extension=name.contains(".")?name.substring(name.lastIndexOf('.')+1).toLowerCase(Locale.ROOT):"";
        if(!Set.of("pdf","txt","md","markdown","json","csv","yaml","yml","xml","log").contains(extension))throw new IllegalArgumentException("支持 PDF 或 UTF-8 文本（txt/md/json/csv/yaml/xml/log）");
        Path original=null;
        try {
            byte[] bytes=file.getBytes();String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
            var previous=files.findByRunIdAndTaskIdOrderByIdAsc(run.id,taskId).stream().filter(f->hash.equals(f.hash) && f.filename.equals(cleanName)).findFirst();if(previous.isPresent())return previous.get();
            String text;
            if(extension.equals("pdf")){try(var pdf=Loader.loadPDF(bytes)){text=new PDFTextStripper().getText(pdf);}}
            else text=StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            if(text.isBlank())throw new IllegalArgumentException("文件未提取到文本，扫描版 PDF 请先 OCR");
            if(text.length()>500000 || text.indexOf('\0')>=0)throw new IllegalArgumentException("文本含二进制内容或超过 500000 字符，请拆分材料");
            Path directory=root.resolve(run.id.toString()).resolve(taskId);Files.createDirectories(directory);original=directory.resolve(UUID.randomUUID()+"."+extension);Files.write(original,bytes,StandardOpenOption.CREATE_NEW);
            var job=jobs.findById(jobId).orElseThrow();var entry=kb.add(kb.kbCode(job),"note","工作流待办 "+taskId+" 上传文件："+name+"\n原始文件 SHA-256："+hash+"\n\n"+text,name);
            if(!"DONE".equals(entry.getStatus()))throw new IllegalArgumentException("文件知识入库失败："+entry.getError());
            var attachment=new SupplierWorkflowAttachment();attachment.runId=run.id;attachment.taskId=taskId;attachment.filename=name;attachment.hash=hash;attachment.entryId=entry.getId();attachment.path=original.toString();attachment.size=bytes.length;
            var saved=files.save(attachment);
            try{scheduleWikiRecompile(job);}catch(Exception ex){log.warn("[WorkflowFiles] Wiki 自动重编译未触发 job={}: {}",jobId,ex.getMessage());}
            return saved;
        }catch(IllegalArgumentException ex){remove(original);throw ex;}catch(Exception ex){remove(original);throw new IllegalArgumentException("文件处理失败："+ex.getMessage(),ex);}
    }
    /**
     * 上传材料入库弄脏 Wiki 来源后自动重编译（镜像 SupplierJobService.scheduleWikiRecompile / dispatch 的门槛条件）。
     * upload 是事务方法，必须 afterCommit 后再触发——编译异步线程要读到已提交的 KB 条目，
     * 提交前触发会把编译结果和事务状态搅在一起。best-effort：失败只 warn，不影响上传成功。
     */
    private void scheduleWikiRecompile(SupplierJob job) {
        Runnable run = () -> {
            try {
                SupplierWikiService wiki = wikiProvider == null ? null : wikiProvider.getIfAvailable();
                if (wiki == null || !"WIKI".equals(job.getKnowledgeMode())) return;
                String status = String.valueOf(wiki.status(job).get("status"));
                if ("READY".equals(status) || "BUILDING".equals(status)) return;
                wiki.build(job);
                log.info("[WorkflowFiles] 上传材料触发 Wiki 自动重编译: job={}, 原状态={}", job.getId(), status);
            } catch (Exception e) { log.warn("[WorkflowFiles] Wiki 自动重编译未启动 job={}: {}", job.getId(), e.getMessage()); }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive())
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { run.run(); }
                });
        else run.run();
    }
    private void remove(Path path){if(path!=null)try{Files.deleteIfExists(path);}catch(Exception ignored){}}
}
