package com.lark.oapi.sample.ws.model.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 数字员工 — 可被下发需求的虚拟员工。
 * <p>
 * 绑定若干机器人实例（= 员工可操作的范围），需求进队列后按"每实例同时一个需求"
 * 自动并行执行（见 {@link com.lark.oapi.sample.ws.service.EmployeeService}），
 * 需求↔实例的指派关系即前端展示的映射。
 */
@Entity
@Table(name = "digital_employee")
public class DigitalEmployee {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name = "";

    /** 头像 emoji（前端卡片展示） */
    private String emoji = "👤";

    /** 职责描述 — 作为员工人设注入每次需求的 prompt */
    @Lob
    @Column(name = "role_desc")
    private String roleDesc = "";

    /** 绑定的实例 id 列表（CSV）— 员工能操作哪些实例 */
    @Column(name = "instance_ids")
    private String instanceIds = "";

    /**
     * 权限列表（CSV）— 员工被授权在系统里创建哪些对象：
     * e2e=创建E2E测试任务, hook=创建Hook任务, supplier=创建供应商工作。
     * 员工执行需求时经 REST API 创建（prompt 注入契约 + 端点侧强制校验）。
     */
    private String permissions = "";

    private boolean enabled = true;

    /** 定期汇报间隔（分钟；0 = 关闭）。有活跃工作（排队/执行中需求）时才发，避免空闲刷屏 */
    @Column(name = "report_interval_min")
    private int reportIntervalMin = 0;

    /** 上次定期汇报时间 */
    @Column(name = "last_report_at")
    private LocalDateTime lastReportAt;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = LocalDateTime.now(); }

    /** CSV → id 列表（容错解析） */
    public List<Long> instanceIdList() {
        List<Long> out = new ArrayList<>();
        if (instanceIds != null) {
            for (String s : instanceIds.split(",")) {
                try {
                    if (!s.isBlank()) out.add(Long.parseLong(s.trim()));
                } catch (NumberFormatException ignored) {}
            }
        }
        return out;
    }

    /** id 列表 → CSV */
    public void setInstanceIdList(List<Long> ids) {
        StringBuilder sb = new StringBuilder();
        if (ids != null) {
            for (Long l : ids) {
                if (l == null) continue;
                if (sb.length() > 0) sb.append(",");
                sb.append(l);
            }
        }
        this.instanceIds = sb.toString();
    }

    /** CSV → 权限列表 */
    public List<String> permList() {
        List<String> out = new ArrayList<>();
        if (permissions != null) {
            for (String s : permissions.split(",")) {
                if (!s.isBlank()) out.add(s.trim());
            }
        }
        return out;
    }

    /** 权限列表 → CSV（只认合法值 e2e/hook/supplier） */
    public void setPermList(List<String> perms) {
        StringBuilder sb = new StringBuilder();
        if (perms != null) {
            for (String p : perms) {
                if (p == null) continue;
                String v = p.trim().toLowerCase();
                if (!v.equals("e2e") && !v.equals("hook") && !v.equals("supplier")) continue;
                if (sb.length() > 0) sb.append(",");
                sb.append(v);
            }
        }
        this.permissions = sb.toString();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getEmoji() { return emoji; }
    public void setEmoji(String emoji) { this.emoji = emoji; }
    public String getRoleDesc() { return roleDesc; }
    public void setRoleDesc(String roleDesc) { this.roleDesc = roleDesc; }
    public String getInstanceIds() { return instanceIds; }
    public void setInstanceIds(String instanceIds) { this.instanceIds = instanceIds; }
    public String getPermissions() { return permissions; }
    public void setPermissions(String permissions) { this.permissions = permissions; }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public int getReportIntervalMin() { return reportIntervalMin; }
    public void setReportIntervalMin(int reportIntervalMin) { this.reportIntervalMin = reportIntervalMin; }
    public LocalDateTime getLastReportAt() { return lastReportAt; }
    public void setLastReportAt(LocalDateTime lastReportAt) { this.lastReportAt = lastReportAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
