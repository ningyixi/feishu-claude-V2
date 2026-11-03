package com.lark.oapi.sample.ws.service;

import com.lark.oapi.sample.ws.model.entity.*;
import com.lark.oapi.sample.ws.repository.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.http.*;
import com.google.gson.JsonParser;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real HTTP and the actual background timer, no manually triggered worker methods. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "spring.profiles.active=mysql","spring.datasource.url=${TEST_MYSQL_URL}","spring.datasource.username=${TEST_MYSQL_USER}",
    "spring.datasource.password=${TEST_MYSQL_PASSWORD}","feishu.kb-dir=${TEST_KB_DIR}","feishu.workflow.poll-ms=250"})
@DirtiesContext
class SupplierWorkflowTimerMysqlIT {
    @Autowired SupplierJobService jobs;
    @Autowired WorkflowTemplateRepository templates;
    @Autowired TestRestTemplate http;
    @Autowired SupplierKbService kb;
    @MockitoBean ClaudeCodeService model;
    @MockitoBean FeishuBotService feishu;
    @Test void httpUploadResponsePauseAndAutomaticCompletion() throws Exception {
        var template=new WorkflowTemplate();template.setName("timer fixture");template.setMode("API");template.setNodesJson("[{\"id\":\"m\",\"type\":\"manual\",\"title\":\"上传业务材料\"}]");templates.save(template);
        var draft=new SupplierJob();draft.setSupplierName("timer fixture");draft.setSupplierCode("timer-fixture");draft.setTemplateId(template.getId());var job=jobs.createJob(draft);
        String base="/api/supplier-jobs/"+job.getId()+"/workflow";
        assertEquals(200,http.postForEntity(base+"/start",Map.of("notifyFeishu",false),String.class).getStatusCode().value());
        String state=http.getForObject(base,String.class),taskId=JsonParser.parseString(state).getAsJsonObject().getAsJsonArray("tasks").get(0).getAsJsonObject().get("id").getAsString();
        var body=new LinkedMultiValueMap<String,Object>();body.add("file",new ByteArrayResource("供应商取消必须携带订单号。".getBytes(java.nio.charset.StandardCharsets.UTF_8)){@Override public String getFilename(){return "business.md";}});
        var headers=new HttpHeaders();headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        var uploaded=http.postForEntity(base+"/tasks/"+taskId+"/files",new HttpEntity<>(body,headers),String.class);assertEquals(200,uploaded.getStatusCode().value(),uploaded.getBody());
        Long sourceId=JsonParser.parseString(uploaded.getBody()).getAsJsonObject().get("entryId").getAsLong();assertTrue(kb.readMd(sourceId).contains("订单号"));
        assertEquals(200,http.postForEntity(base+"/control",Map.of("action","PAUSE"),String.class).getStatusCode().value());
        assertEquals(400,http.postForEntity(base+"/tasks/"+taskId+"/respond",Map.of("action","SUBMIT"),String.class).getStatusCode().value());
        http.postForEntity(base+"/control",Map.of("action","RESUME"),String.class);
        assertEquals(200,http.postForEntity(base+"/tasks/"+taskId+"/respond",Map.of("action","SUBMIT"),String.class).getStatusCode().value());
        long deadline=System.nanoTime()+5_000_000_000L;String status;
        do{status=JsonParser.parseString(http.getForObject(base,String.class)).getAsJsonObject().get("status").getAsString();if("COMPLETED".equals(status))break;Thread.sleep(30);}while(System.nanoTime()<deadline);
        assertEquals("COMPLETED",status);verifyNoInteractions(model,feishu);
    }
}
