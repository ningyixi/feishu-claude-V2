# 侧边栏新模块「临时需求」：文档 → LLM Wiki → 对话待命

## 用户原话与意图
侧边栏加一个**新**功能模块叫「临时需求」。创建一个任务，传入该任务关联的文档 → 解析文档 → **直接生成 LLM Wiki**。不要分析工件、不要 Postman 实测、不要接口调用结果文档。Wiki 就绪后，用户在对话框里直接吩咐临时小改（例："某供应商把历史某个接口的字段改了，帮我改一下"）。任务绑定一个实例；**浏览器上跟这个实例说话、和飞书上跟这个实例说话是同一个 session**。

**耦合红线（用户 2026-11-17 明确）：独立的，不要和之前的（11-13「接口建档」QuickProbe）耦合** —— 不共用它的表、Service、Controller、页面、阶段状态机。

## 允许复用的底座（这不算耦合，禁止另拷一份）
- 文档入库与解析：`SupplierKbService` 的来源 add/解析管线（含 URL/站点/PDF/md 与 11-13 新加的本地 HTML；`Jsoup` 剥离 + `Flexmark` 转 MD 那条）。
- Wiki 编译：`SupplierWikiService.build`，直至 READY（版本/来源数/页数状态沿用现成读取）。
- 会话：`ClaudeCodeService.chat(instanceId, openId, ...)` = 实例主会话。飞书侧与浏览器侧天然同一 sessionID/同一队列（`ClaudeCodeService.java:225-227`；`SupplierSharedSessionTest:94-97` 已断言工作台永不使用 lane），本模块**只需要继续用 `chat()`，不要新起 lane**，这一条 AC 就是架构既成事实。

## 范围
- **A1 数据**：新表 `ad_hoc_task`（独立实体）：`id / name / doc_sources(TEXT, 多行来源) / kb_code / instance_id / status / wiki_status / error / created_at / updated_at`。`ddl-auto=update` 自动建表，不碰 `supplier_job*` 任何表。
- **A2 后端**：新 `AdHocController`（`/api/ad-hoc-tasks`：create/list/get/delete/retry-stage/chat）+ 新 `AdHocService`（虚拟线程串行两段：①来源逐条入库解析 ②编译 Wiki 至 READY，然后 status=`READY` 待命）。**无第三段**、无 artifact、无 toolEvidence、无 codegen 门禁。
- **A3 对话**：`POST /api/ad-hoc-tasks/{id}/chat` → `ClaudeCodeService.chat`，工作目录/模型/沙箱取绑定实例的配置（与工作台同源读法）。**关键**：每轮把该任务 Wiki 的可定位入口（wiki 版本 + 页面清单/摘要路径，不是全文塞爆 prompt）注入消息头部，使 agent 知道"这个供应商的接口长什么样、文档在哪"。注入实现要能单测（纯函数拼串），并遵守既有措辞纪律 `WORDING_RULE`（见 `11-10-wording-discipline` 的注入面清单，别漏这一面）。
- **A4 前端**：`App.jsx` 侧边栏新入口「临时需求」+ 新页面 `AdHoc.jsx`：左侧任务列表，右侧=新建表单（任务名 / 关联文档多行 / 绑定实例下拉）+ 两段步骤条（入库 → Wiki）+ 完成后一个对话框（3s 轮询进度，沿用现成 progress/thinking 展示写法）+ Wiki 状态卡（版本/来源数/页数）+ 只读知识库条目列表。
- **A5 失败**：任一段失败显示原因 + 「重试本段」（幂等，依赖现有 KB/Wiki 的来源去重与重建语义）。

## 验收
- AC1 侧边栏出现「临时需求」，路由/页面可用；「接口建档」与供应商工作台**零改动零回归**（两者文件不应出现在本次 diff，除非只是 App.jsx 加一行路由）。
- AC2 建一个含单条 URL 来源的任务：自动推进 入库→Wiki READY，全程无需手动点；不产生任何"分析工件/实测档案"记录。
- AC3 Wiki READY 后在页面对话框发一句针对历史接口的改动要求，能得到**基于该 Wiki 上下文**的回应（测试层面：断言发出的 prompt 含 Wiki 入口段与用户原文）。
- AC4 同一任务，浏览器发一条、飞书私聊同一实例发一条：落在**同一个 TerminalSession**（可用 `ClaudeCodeService.getSession(instanceId)` 断言同实例同对象、`getClaudeSessionId()` 相同），且飞书侧不被权限挡（依赖 `11-17-unblock-instance-chat` 的自动绑定）。
- AC5 删除任务清掉本模块记录；KB/Wiki 历史沿用现有语义，不得误删其他任务共用的库。
- AC6 `AdHocService` 编排单测（顺序推进/失败停在当段/重试幂等/注入函数纯逻辑）；`mvn test` 全绿（除 `SupplierContextTest` 预存环境性失败）；`npm run build` 通过。

## 明确不做
不做批量/定时/权限模型；不做分析工件、Postman 实测、toolEvidence、开发提示词生成、codegen 门禁；不改 Flowable；不改 QuickProbe 与 SupplierJob；不新增文档来源类型（复用现成四种）；不做多实例（一任务一实例）。

## 与其他任务的关系
排在 `11-17-free-node-execution` 之后做（它改的是工作台/Flowable，本任务不依赖其结论，但共用 `SupplierKbService`/`SupplierWikiService`，串行以免文件冲突）。AC4 的飞书半边依赖 `11-17-unblock-instance-chat` 已完成。
