package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;

/**
 * 站点抓取逐页状态（11-03-kb-site-crawl）：属于某个 url+site 知识库条目，
 * 发现即 pending，成功 loaded，失败 failed；增量持久化支撑前端"已发现X·已载入Y·失败Z·未抓取N"。
 */
@Entity
@Table(name = "supplier_kb_page")
public class SupplierKbPage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entry_id", nullable = false)
    private Long entryId;

    /** 逻辑 URL（发现序内的绝对地址，已去 fragment） */
    @Column(length = 800)
    private String url = "";

    /** 去重键（logicKey） */
    @Column(name = "url_key", length = 800)
    private String urlKey = "";

    @Column(length = 300)
    private String title = "";

    /** pending | loaded | failed */
    @Column(nullable = false)
    private String status = "pending";

    @Column(length = 600)
    private String error = "";

    /** loaded 页面正文 Markdown 的落盘路径 */
    @Column(name = "md_path", length = 600)
    private String mdPath = "";

    @Column(name = "order_idx")
    private Integer orderIdx = 0;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEntryId() { return entryId; }
    public void setEntryId(Long entryId) { this.entryId = entryId; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getUrlKey() { return urlKey; }
    public void setUrlKey(String urlKey) { this.urlKey = urlKey; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getError() { return error; }
    public void setError(String error) { this.error = error; }
    public String getMdPath() { return mdPath; }
    public void setMdPath(String mdPath) { this.mdPath = mdPath; }
    public Integer getOrderIdx() { return orderIdx; }
    public void setOrderIdx(Integer orderIdx) { this.orderIdx = orderIdx; }
}
