package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name="supplier_knowledge_run",indexes=@Index(name="idx_knowledge_job",columnList="job_id,id"))
public class SupplierKnowledgeRun {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="job_id") private Long jobId;
    private String nodeId;
    private String mode;
    private String stage;
    @Column(length=64) private String sourceDigest;
    private Long wikiRevision;
    private long rawChars;
    private int knowledgeChars;
    private int promptChars;
    @Column(length=64) private String requestDigest;
    @Lob @Column(length=2147483647) private String pagesJson="[]";
    private String status="DISPATCHED";
    private long inputTokens=-1;
    private long outputTokens=-1;
    private long cacheReadTokens=-1;
    private long cacheWriteTokens=-1;
    private double costUsd=-1;
    private String model="";
    private long durationMs=-1;
    private LocalDateTime createdAt=LocalDateTime.now();
    private LocalDateTime finishedAt;
    public Long getId(){return id;}
    public Long getJobId(){return jobId;}public void setJobId(Long v){jobId=v;}
    public String getNodeId(){return nodeId;}public void setNodeId(String v){nodeId=v;}
    public String getMode(){return mode;}public void setMode(String v){mode=v;}
    public String getStage(){return stage;}public void setStage(String v){stage=v;}
    public String getSourceDigest(){return sourceDigest;}public void setSourceDigest(String v){sourceDigest=v;}
    public Long getWikiRevision(){return wikiRevision;}public void setWikiRevision(Long v){wikiRevision=v;}
    public long getRawChars(){return rawChars;}public void setRawChars(long v){rawChars=v;}
    public int getKnowledgeChars(){return knowledgeChars;}public void setKnowledgeChars(int v){knowledgeChars=v;}
    public int getPromptChars(){return promptChars;}public void setPromptChars(int v){promptChars=v;}
    public String getRequestDigest(){return requestDigest;}public void setRequestDigest(String v){requestDigest=v;}
    public String getPagesJson(){return pagesJson;}public void setPagesJson(String v){pagesJson=v;}
    public String getStatus(){return status;}public void setStatus(String v){status=v;}
    public long getInputTokens(){return inputTokens;}public void setInputTokens(long v){inputTokens=v;}
    public long getOutputTokens(){return outputTokens;}public void setOutputTokens(long v){outputTokens=v;}
    public long getCacheReadTokens(){return cacheReadTokens;}public void setCacheReadTokens(long v){cacheReadTokens=v;}
    public long getCacheWriteTokens(){return cacheWriteTokens;}public void setCacheWriteTokens(long v){cacheWriteTokens=v;}
    public double getCostUsd(){return costUsd;}public void setCostUsd(double v){costUsd=v;}
    public String getModel(){return model;}public void setModel(String v){model=v;}
    public long getDurationMs(){return durationMs;}public void setDurationMs(long v){durationMs=v;}
    public LocalDateTime getCreatedAt(){return createdAt;}
    public LocalDateTime getFinishedAt(){return finishedAt;}public void setFinishedAt(LocalDateTime v){finishedAt=v;}
}
