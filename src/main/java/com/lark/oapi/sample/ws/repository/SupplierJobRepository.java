package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.SupplierJob;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierJobRepository extends JpaRepository<SupplierJob, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select j from SupplierJob j where j.id = :id")
    java.util.Optional<SupplierJob> lockById(@org.springframework.data.repository.query.Param("id") Long id);
    List<SupplierJob> findAllByOrderByUpdatedAtDesc();

    /** 工作台工作列表（11-13）：不含快速建档任务——quick 任务走 /api/quick-probes 专用入口 */
    List<SupplierJob> findAllByQuickFalseOrderByUpdatedAtDesc();

    /** 快速建档任务清单（工具页列表 + 启动恢复扫描共用） */
    List<SupplierJob> findAllByQuickTrueOrderByUpdatedAtDesc();

    /**
     * 飞书指针注入（②a）：实例绑定的、仍值得指向知识底座的工作。
     * status 取值见 {@link SupplierJob#getStatus()}（ACTIVE | COMPLETED）——通关存档的 Wiki/知识库
     * 依然有效可查，两种状态都要；用单条 StatusIn 查询一次取回并按 updatedAt 新→旧排序，
     * 比拆两个查询再内存合并更简单可靠（排序交给数据库，无去重问题）。
     */
    List<SupplierJob> findByInstanceIdAndStatusInOrderByUpdatedAtDesc(Long instanceId, java.util.Collection<String> statuses);
}
