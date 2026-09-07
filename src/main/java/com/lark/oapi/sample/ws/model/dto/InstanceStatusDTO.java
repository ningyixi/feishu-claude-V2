package com.lark.oapi.sample.ws.model.dto;

/**
 * 实例运行状态 — 由真实运行时状态推导（连接/轮次阶段/排队/空闲时长），
 * 前端轮询 /api/instances/status 展示趣味徽章。
 */
public class InstanceStatusDTO {

    private Long instanceId;
    /** 状态码：DISABLED/DISCONNECTED/CRASHED/WORKING/GENERATING/THINKING/DONE/IDLE/FISHING/SLEEPING */
    private String code;
    private String emoji;
    private String label;
    /** 趣味详情文案（含当前工具名/耗时/排队数等实时信息） */
    private String detail;
    /** 是否忙碌（轮次进行中，前端显示脉冲动画） */
    private boolean busy;

    public InstanceStatusDTO() {}

    public InstanceStatusDTO(Long instanceId, String code, String emoji, String label, String detail, boolean busy) {
        this.instanceId = instanceId;
        this.code = code;
        this.emoji = emoji;
        this.label = label;
        this.detail = detail;
        this.busy = busy;
    }

    public Long getInstanceId() { return instanceId; }
    public void setInstanceId(Long instanceId) { this.instanceId = instanceId; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getEmoji() { return emoji; }
    public void setEmoji(String emoji) { this.emoji = emoji; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }
    public boolean isBusy() { return busy; }
    public void setBusy(boolean busy) { this.busy = busy; }
}
