# 工作流顺序不再阻塞节点执行（工作流保留，只拆墙）

## 用户决定（不可再议）
工作流概念与 Flowable 全部**保留**（待办、审批、通知、历史都留着），但目前**不能被它的顺序强制阻塞**：想点哪个节点就跑哪个节点。

## 现状事实（已由代码核实，`文件:行号`）
- `runNode`（`SupplierJobService.java:518-549`）**没有任何顺序校验**：只查 类型∈{agent,agent_check}、本节点非 RUNNING、已绑实例、prompt 长度。前端右列任意节点可点选（`Supplier.jsx:598`）。所以"手动模式"本来就允许乱序。
- 真正拦路的是四道：
  1. **托管闸门**：`SupplierJobController.java:29,154,168,180,193` → `SupplierWorkflowService.assertLegacyWriteAllowed:35`，只要 `supplier_workflow_run` 有记录就抛"该工作由流程引擎管理，请通过流程待办操作"；前端 `Supplier.jsx:345,357,370,377` 同步拦截。现网 job 197（Engine）即 ACTIVE 托管，n2 又是僵尸 RUNNING → **用户手上一切按钮都是死的**。
  2. **`{artifact:nX}` 硬抛**：`SupplierJobService.java:1324-1333` 上游无产物即抛"引用的节点 n1 还没有产物，请先完成上游任务"（内置模板 n2 的 prompt 就引用 `{artifact:n1}`，`:355`）。
  3. **codegen 三连门禁**：`:534,881-907`（开发提示词 digest 不匹配 / 会议文档+KV 不全 / 无 DONE+toolEvidence 的实测节点）→ 抛错。
  4. **`start()` 要求全 PENDING**：`SupplierWorkflowService.java:43` → 乱序跑过的 job 永远无法再启动工作流（二者互斥）。
- Flowable 是**排序器 + 待办/审批账本**，不是执行引擎：引擎 API 只出现在 `SupplierWorkflowService` 一个类里；`plan():106-112` 按节点 DB 状态决定 `finish(INPUT/REVIEW)` 再 `trigger`。手动执行若把节点写成 DONE，`Worker.dispatch:49` 仍可能按 token 位置**再派一次同一节点** —— 这是本任务的核心正确性风险。
- 会红的既有测试：`SupplierWorkflowGateTest`（completeNode 门禁）、`SupplierWorkflowMysqlIT` 5 条（含 `anotherEngineReadsPersistedWaitingTask...`、`concurrentResponsesOnlyAdvanceOnce`）。没有任何测试断言"runNode 拒绝乱序"。

## 范围
- **T1 托管 job 允许手动执行节点**：`run/complete/answerNode/release` 不再被 `assertLegacyWriteAllowed` 挡；前端去掉 managed 拦截，改为**黄条提示**"此工作已由流程托管，手动执行不会推进流程待办"。
- **T2 防重复派发（T1 的前置正确性保障）**：手动跑成 DONE（或已有 artifact）的节点，`plan()` 必须识别为已完成并跳过该 exec_ 位置，**不得**再派 attempt；用现有 `dispatchKey/visit` 机制（`SupplierWorkflowService.java:92`）落，勿另起炉灶。
- **T3 `{artifact:nX}` 缺失改为降级**：渲染为空串 + 在 prompt 里插入一行显式声明"（引用的 nX 尚无产物，本次未携带该上下文）"，同时接口响应/页面提示"上游 nX 无产物"。**不得静默**让模型以为带了。
- **T4 codegen 门禁改为"确认可强制"**：默认仍拦，给一个显式 `force=true` 通道（页面二次确认文案要写清后果），不再无条件抛。
- **T5 `currentNodeId` 语义**（`:708-714` "第一个 PENDING"）：乱序完成后仅作展示，不得被任何逻辑当门禁读；若发现有地方拿它做判断，回报。

## 追加范围（2026-11-18 现场取证，与 T1 同源，一并做掉）

**新事实**：判"托管"的两处判据都是"**run 行存在即挡**"，连取消都解不开——
- 后端 `SupplierWorkflowService.java:35` `assertLegacyWriteAllowed`：`if (runs.findByJobId(jobId).isPresent()) throw`
- 前端 `SupplierWorkflowPanel.jsx:18`：`onManaged?.(data.status !== 'NOT_STARTED')`
→ 用户把流程 CANCEL 之后 `run.status='CANCELLED'` 但**行还在**，两边照样挡。这是用户今天骂的直接原因。

- **T6 判据改为"活着才算托管"**：后端只认 `ACTIVE`/`PAUSED` 的 run（`CANCELLED`/`FAILED` 不再拦手工操作），前端同口径。改完 `SupplierWorkflowGateTest`/相关 IT 的断言要跟着校准，**不许靠放宽断言蒙过**。
- **T7 孤儿流程不再空转**（现场：`run=3` 挂在**已删除的 job 198** 上，`node(run,"input_s1")`（`SupplierWorkflowService.java:62-64`，step=`"s"+seq`）找不到节点必然抛"流程节点快照不匹配"，`SupplierWorkflowWorker` 每 2 秒重试，已刷 **27277** 条 WARN / 15.5 小时）：
  - ① `deleteJob` 级联取消流程（有 run 就 `control(CANCEL)` 语义：删流程实例 + run 置 CANCELLED/删行），杜绝再造孤儿；
  - ② 工作器对**不可恢复错误**（快照不匹配、job 不存在）不得无限期 2 秒重试 —— 置 run 为 FAILED 并停止推进，日志一次一条含 run/job/缺失 step 的上下文。**不引入复杂退避框架**，落"终态即停"即可。
- **T8 quick（接口建档）任务不许拥有工作流**：现场 job 198 `quick=1` 却有 ACTIVE run —— 说明 quick 任务从工作台详情页能启动流程（11-13 的设计是"不启动 Flowable"）。`start()` 对 quick 任务直接拒绝并给明确文案，避免 quick 与引擎双写状态。

## 验收（追加）
- AC6 run 处于 `CANCELLED`/`FAILED` 的 job，手工执行/完成节点**不再**被"该工作由流程引擎管理"挡；`ACTIVE`/`PAUSED` 仍按 T1 的新语义处理。
- AC7 删除带流程的 job 后：Flowable 流程实例被取消、不再有 run 参与推进、日志不再刷"流程节点快照不匹配"。
- AC8 对"job 已不存在的孤儿 run"，工作器一轮之内把 run 打成终态并停嘴（测试断言**只失败一次**，不是每 2 秒无限重试）。
- AC9 quick 任务调用 `start()` 被拒且文案可读。

## 验收（T1-T5）
- AC1 已启动流程的 job（拿现网 job 197 复刻一个测试夹具）能直接点任意 agent 节点执行，不报"请通过流程待办操作"。
- AC2 手动把 n1 跑到 DONE 后，流程待办不会再次派发 n1（T2）；IT 断言这条。
- AC3 n1 无产物时点 n2：执行成功，prompt 内含"未携带 n1 上下文"声明，页面有同样提示；不抛异常。
- AC4 codegen 节点：不带 force 仍拦（原话术），带 force 可跑；两条路径都有测试。
- AC5 `SupplierWorkflowMysqlIT`/`SupplierWorkflowGateTest` 需要因新语义调整断言的，逐条说明改了哪条断言、为什么不是掩盖回归；`mvn test` 全绿（除 `SupplierContextTest` 预存环境性失败）；`npm run build` 通过。

## 明确不做
不删 Flowable、不改 BPMN 建模结构、不动 QuickProbe（接口建档）那条流水线、不做"批量重跑/定时"、不改 Wiki 编译管线。`start()` 全 PENDING 前置（第 4 道墙）**本任务不做**——放宽它需要按节点状态重新放置流程 token，属于建模改动，若 T1-T5 落地后你判断不做它用户仍会被卡，先回报再决定，不要自行扩大范围。
