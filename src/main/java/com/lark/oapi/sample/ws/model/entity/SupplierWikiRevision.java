package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name="supplier_wiki_revision", indexes=@Index(name="idx_wiki_supplier",columnList="kb_code,id"))
public class SupplierWikiRevision {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="kb_code",nullable=false) private String kbCode;
    @Column(length=64,nullable=false) private String sourceDigest;
    private String status="BUILDING";
    @Column(length=1000) private String error="";
    @Column(length=600) private String directory="";
    private Long instanceId;
    private String model="";
    private int sourceCount;
    private long rawChars;
    private int completedSources;
    private long inputTokens=-1;
    private long outputTokens=-1;
    private double costUsd=-1;
    @Lob @Column(length=2147483647) private String manifestJson="{}";
    private LocalDateTime createdAt=LocalDateTime.now();
    private LocalDateTime finishedAt;
    public Long getId(){return id;}
    public String getKbCode(){return kbCode;} public void setKbCode(String v){kbCode=v;}
    public String getSourceDigest(){return sourceDigest;} public void setSourceDigest(String v){sourceDigest=v;}
    public String getStatus(){return status;} public void setStatus(String v){status=v;}
    public String getError(){return error;} public void setError(String v){error=v;}
    public String getDirectory(){return directory;} public void setDirectory(String v){directory=v;}
    public Long getInstanceId(){return instanceId;} public void setInstanceId(Long v){instanceId=v;}
    public String getModel(){return model;} public void setModel(String v){model=v;}
    public int getSourceCount(){return sourceCount;} public void setSourceCount(int v){sourceCount=v;}
    public long getRawChars(){return rawChars;} public void setRawChars(long v){rawChars=v;}
    public int getCompletedSources(){return completedSources;} public void setCompletedSources(int v){completedSources=v;}
    public long getInputTokens(){return inputTokens;} public void setInputTokens(long v){inputTokens=v;}
    public long getOutputTokens(){return outputTokens;} public void setOutputTokens(long v){outputTokens=v;}
    public double getCostUsd(){return costUsd;} public void setCostUsd(double v){costUsd=v;}
    public String getManifestJson(){return manifestJson;} public void setManifestJson(String v){manifestJson=v;}
    public LocalDateTime getCreatedAt(){return createdAt;}
    public LocalDateTime getFinishedAt(){return finishedAt;} public void setFinishedAt(LocalDateTime v){finishedAt=v;}
}
