package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.AdHocChat;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 临时需求对话流水仓储（镜像 SupplierJobChatRepository） */
public interface AdHocChatRepository extends JpaRepository<AdHocChat, Long> {
    List<AdHocChat> findByTaskIdOrderByIdAsc(Long taskId);
    void deleteByTaskId(Long taskId);
    /** 重启清扫用：上个进程的对话轮次已随线程消失，PENDING 行必须落终态，否则页面永远转圈 */
    List<AdHocChat> findByStatus(String status);
}
