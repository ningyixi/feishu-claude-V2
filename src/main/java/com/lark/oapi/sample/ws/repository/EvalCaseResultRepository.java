package com.lark.oapi.sample.ws.repository;

import com.lark.oapi.sample.ws.model.entity.EvalCaseResult;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvalCaseResultRepository extends JpaRepository<EvalCaseResult, Long> {
    /**
     * 单条 eval 的最近执行记录。
     * <p>
     * 排序契约（显式固定，勿改）：<b>按 id 倒序 = 最新在前</b>。id 单调递增，「第一条」即最近一次。
     * 但注意：轮询「本次试跑」的结果必须用 trial 返回的 resultId 精确取（{@code findById}），
     * 靠「读列表第一条」在并发/时序边界下会读到上一轮的旧结果（MVP-1.6 实测缺陷）。
     */
    List<EvalCaseResult> findTop20ByCaseIdOrderByIdDesc(Long caseId);

    /** 按 verdict 精确查（启动归位悬死的 RUNNING 行用） */
    List<EvalCaseResult> findByVerdict(String verdict);

    void deleteByTaskId(Long taskId);
    void deleteByCaseId(Long caseId);
}
