package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * 数字员工 → 老板的主动上报消息。
 * <p>
 * 触发场景：需求终态（完成汇报/失败/重启中断）、卡住（排队超时/需要老板支持）、定期汇报。
 * 双通道：落库供浏览器聊天框展示（readFlag 驱动红点数字），同时经绑定实例的飞书 app
 * 推送给授权用户（best-effort，见 EmployeeService.postMessage）。
 */
@Entity
@Table(name = "employee_message")
public class EmployeeMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "employee_id")
    private Long employeeId;

    /** 关联需求（可空：定期汇报等不挂具体需求） */
    @Column(name = "demand_id")
    private Long demandId;

    /** REPORT(完成汇报) | SUPPORT(需要支持) | FAILED | INTERRUPTED | STUCK(排队卡住) | PERIODIC(定期汇报) | CHAT(聊天回复) | BOSS(老板发言) */
    private String type = "REPORT";

    /** 消息方向：employee(默认,员工→老板) | boss(老板→员工,聊天框输入)。存量行 NULL 按 employee 归一 */
    private String sender = "employee";

    @Lob
    private String content = "";

    /** 浏览器侧已读标记（红点数字 = 未读数；打开该员工即全部已读） */
    @Column(name = "read_flag")
    private boolean readFlag = false;

    /** 飞书推送是否成功（false = 未推送/推送失败，浏览器侧仍可见） */
    @Column(name = "feishu_pushed")
    private boolean feishuPushed = false;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() { createdAt = LocalDateTime.now(); }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getEmployeeId() { return employeeId; }
    public void setEmployeeId(Long employeeId) { this.employeeId = employeeId; }
    public Long getDemandId() { return demandId; }
    public void setDemandId(Long demandId) { this.demandId = demandId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    /** 空值归一：存量行 sender 为 NULL 时按 employee（员工上报）处理 */
    public String getSender() { return sender != null ? sender : "employee"; }
    public void setSender(String sender) { this.sender = sender; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public boolean isReadFlag() { return readFlag; }
    public void setReadFlag(boolean readFlag) { this.readFlag = readFlag; }
    public boolean isFeishuPushed() { return feishuPushed; }
    public void setFeishuPushed(boolean feishuPushed) { this.feishuPushed = feishuPushed; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
