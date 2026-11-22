# 临时需求：可查看 LLM Wiki 内容

## 用户诉求（2026-11-18 原话）
「在"临时需求"里，我还想加一个功能，就是用户能看一下 llm wiki 的内容，就是"供应商工作台"那里的直接点击看 wiki 的功能，这里也想有。」

## 现状事实（已核实）
- 工作台的查看器是 **`frontend/src/components/SupplierWikiPanel.jsx`**，挂载点 `frontend/src/pages/Supplier.jsx:709`（`<SupplierWikiPanel job={job} showToast={showToast} />`）。
- 后端端点全部**按 jobId 寻址**（`SupplierWikiController.java:11` `@RequestMapping("/api/supplier-jobs/{jobId}")`）：
  `:18 GET /wiki`（状态）、`:19-20 GET|PUT /wiki/schema`、`:21 POST /wiki/build`、`:22 GET /wiki/revisions/{rev}/pages/{id}`（**页正文，即"点开看"**）、`:23 GET /wiki/revisions/{rev}/sources/{id}`（来源原文）。
- 「临时需求」拿不到这些端点：`AdHocService` 只造了一个 **`id=null` 的游离 SupplierJob 适配器**喂 `SupplierWikiService.build`（`11-17-adhoc-requirement-module` 的决策 2，已逐行证实零持久化），而上述端点第一步都是 `job(jobId)` 查库 → 对 adhoc 必然 400。
- AdHoc 页面现在只有 **Wiki 状态卡**（版本/来源数/页数），**没有浏览入口**。

## 设计
- **W1 参数化复用，禁止拷贝**：`SupplierWikiPanel` 改为接受一个访问目标（如 `api` 前缀 / `target` 对象），工作台侧 `<SupplierWikiPanel job={job}>` 的现有调用**行为必须逐字等价**（工作台回归不许破）。**严禁新建一份 `AdHocWikiPanel.jsx`** —— 两份面板以后必然不一致。
- **W2 adhoc 只读端点**：在 `AdHocController` 补一组按任务寻址、内部按 `kbCode`（`adhoc-<id>`）解析的端点，语义与工作台一一对应：
  `GET /api/ad-hoc-tasks/{id}/wiki`、`GET .../wiki/pages/{pageId}`、`GET .../sources/{srcId}`（+ 视 W4 决定是否加 `POST .../wiki/build`）。
  实现优先在 `SupplierWikiService` 侧加**按 kbCode 的只读重载**，而不是让 adhoc 继续伪造 SupplierJob 去过读路径；**不得修改 `SupplierWikiController` 让它同时接受两种 id**（job 语义与 adhoc 语义混起来是长期债）。
- **W3 入口与状态**：AdHoc 的 Wiki 状态卡上加「查看 Wiki」按钮；`READY` 才可点，未就绪时置灰并提示当前阶段（INGEST / Wiki 编译中 / 失败原因），失败态另给「重试本段」。**不许让面板对着 EMPTY/BUILDING 版本空转轮询**（沿用 `notFound` 停表那套纪律）。
- **W4 范围开关（待用户拍，见下）**：只读浏览 vs 连「编译口径 schema」编辑与「重新编译」一起开。

## 验收
- AC1 临时需求任务 READY 后点「查看 Wiki」，能看到页列表并打开任一页的 markdown 正文，与工作台看同一份固定版本**内容一致**。
- AC2 能进一步打开某页的**来源原文**（对应 `sources/{id}`），路径与工作台同构。
- AC3 未 READY（INGEST / BUILDING / FAILED）时按钮置灰且文案说明原因；打开面板不会引发持续轮询或 404 风暴。
- AC4 工作台侧零回归：`SupplierWikiTest`（7）、`SupplierWikiContractTest`（6）、`SupplierWikiAutoRebuildTest`（14）、`QuickProbeServiceTest`（20）全绿；`git diff` 证明 `SupplierWikiPanel` 的改动没有改变工作台渲染结果（能加一条前端快照/静态断言更好）。
- AC5 全量红数不超过基线 `Tests run: 266, Failures: 3, Errors: 1`（4 条 Windows/temp 环境红）；前端构建通过且**不写入 `src/main/resources/static/`**（发布由主会话统一做）。

## 明确不做
不在 adhoc 侧新增 Wiki 编译管线（只调既有 `SupplierWikiService`）；不做 Wiki 页编辑/手写页；不做版本历史对比 UI；不把 `SupplierWikiController` 改成兼容两种 id；不拷面板组件。

## 排序
与其它 AdHoc 面改动**串行**（同改 `AdHoc.jsx` / `AdHocService.java` / `AdHocController.java`）：
`11-17-free-node-execution`（运行中）→ 审并提交 `11-17-adhoc-blocker-notify` → **本任务** 与 `11-18-adhoc-supplier-config` 可合并为一次派工（文件重叠、都不碰工作流侧）。
