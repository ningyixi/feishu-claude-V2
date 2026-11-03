package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.SupplierWorkflowAttachment;
import com.lark.oapi.sample.ws.repository.*;
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
    private final SupplierWorkflowService workflow;
    private final SupplierWorkflowAttachmentRepository files;
    private final SupplierJobRepository jobs;
    private final SupplierKbService kb;
    private final Path root;
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
            var attachment=new SupplierWorkflowAttachment();attachment.runId=run.id;attachment.taskId=taskId;attachment.filename=name;attachment.hash=hash;attachment.entryId=entry.getId();attachment.path=original.toString();attachment.size=bytes.length;return files.save(attachment);
        }catch(IllegalArgumentException ex){remove(original);throw ex;}catch(Exception ex){remove(original);throw new IllegalArgumentException("文件处理失败："+ex.getMessage(),ex);}
    }
    private void remove(Path path){if(path!=null)try{Files.deleteIfExists(path);}catch(Exception ignored){}}
}
