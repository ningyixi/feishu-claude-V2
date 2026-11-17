package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.AdHocTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 临时需求任务仓储（11-17-adhoc-requirement-module）——独立于 supplier_job* 任何仓储 */
public interface AdHocTaskRepository extends JpaRepository<AdHocTask, Long> {
    List<AdHocTask> findAllByOrderByIdDesc();
}
