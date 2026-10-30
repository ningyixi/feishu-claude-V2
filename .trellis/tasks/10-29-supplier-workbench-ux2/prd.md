# 供应商工作台优化(二期): 思考进度可见 + 写代码前置准备流程

> 状态：仅创建，未执行。执行前先收敛下方 Open Questions。

## Goal

1. 节点执行时不仅看到"用什么工具"，还能看到**当前思考内容**，玩家据此估算进行到哪、还要多久；
2. 把"让实例写代码"这一步从口头约定升级为**结构化前置流程**：三方会议文档（飞书云文档链接）为硬门禁 → 补充配置 key/value → redis 配置提醒 → 一键生成可复制的写代码提示词（聚合知识库解析产物、Postman 实测文档、skill、spec 文档、会议文档、KV 配置的位置与内容）。

## Requirements

### R1 思考流进度（子任务 10-29-think-progress）
* 复用 TurnListener.onThinkingDelta（TerminalSession 已在解析 thinking_delta 事件，数字员工流式卡片在用）；progressListener 增加节流写入（≥1.5s 且取尾部片段）到节点新字段 `thinking`
* 前端节点卡片在执行条下方显示"💭 正在思考：…"（可折叠/滚动，轮次结束清空）；卡点挂起时同样保留最后思考片段
* 与现有 progress（工具动作）双行并存：工具行=在做什么，思考行=在想什么

### R2 写代码前置准备流程（子任务 10-29-codegen-prep）
* R2.1 **提示词生成器**：在"让实例写代码"节点提供「生成提示词」按钮，产出可一键复制的完整提示词，内容聚合：
  - 供应商信息（名称/ID/类库路径）
  - 知识库解析产物位置（DONE 条目的 md 文件路径列表，来自 supplier_kb_entry）
  - Postman 接口实测文档位置（n2 工件/Collection 存档）
  - 使用的 skill 目录（skillsJson）
  - spec 文档位置（⚠️ 指代待确认，见 Open Questions #1）
  - 三方会议文档（飞书云文档链接）
  - 玩家补充的配置 key/value 清单
* R2.2 **会议文档硬门禁**：必须先提交"三方会议文档"的飞书云文档链接（校验域名 feishu.cn/larksuite.com，路径 docs|docx|wiki），否则不能进入/完成写代码节点——提交入口在面板，存储后自动带入 R2.1 提示词
* R2.3 **KV 配置必填**：提交会议文档后，必须再补充一组当前配置的 key/value（动态行，键值都必填；敏感值显示策略见 Open Questions #4），同样自动带入提示词
* R2.4 **redis 提醒**：KV 补充完成后给出固定提示："如果用到 redis，记得去配置中心配置"（可考虑 KV 键名含 redis 时高亮，MVP 恒显示）

### 门禁实现倾向（待确认）
* 后端强校验：completeNode 对带"写代码准备"标记的节点校验 会议文档+KV 已提交，未达则 400；前端节点卡片显示清单（☐ 会议文档 ☐ KV配置 → 📝 生成提示词）
* 节点识别：工作流模板节点增加属性 `requiresCodegenPrep`（编辑器可勾选），内置 API 模板的 n4 默认勾选——避免按标题文字猜

## Acceptance Criteria

* [ ] n2 执行中，节点卡片能看到滚动更新的思考内容（与工具行动作一致不互斥）
* [ ] 未提交会议文档链接时，写代码节点无法点"完成"（后端 400 + 前端清单提示）
* [ ] 非飞书域名链接被拒；提交后写进存档并出现在提示词里
* [ ] 会议文档提交后才出现 KV 表单；KV 未填齐同样拦截
* [ ] KV 提交后界面出现 redis 配置中心提醒
* [ ] 「生成提示词」输出包含全部六类信息且可一键复制，复制到飞书发给实例可直接开工

## Definition of Done

* 后端单测：门禁校验、提示词组装（快照断言）；思考节流不拖垮 reader 线程（压测注释即可）
* mvn test 无新增失败；vite build 通过；行为变化更新模板编辑器说明与 PRD 附录
* 前后端重启冒烟

## Out of Scope

* 飞书云文档内容抓取进知识库（可二期接 lark-doc，目前只收链接）
* 思考流的 SSE 实时推送（沿用 4s 轮询；要更低延迟另立项）
* 配置中心自动写入（只提醒不代配）

## Open Questions（执行前必须收敛）

1. **"spec 文档"具体指什么？** 供应商类库工程内的规范文档（那路径规则/让用户填）？create-supplier skill 里的 spec？还是别的固定位置？
2. **"Postman 调用的情况文档"的位置**：等于 n2 工件（artifact 里内嵌的 Collection）就够，还是实例会把 Collection 落成文件（路径写进工件）？提示词里指向哪个？
3. 门禁强度：后端强拦截（推荐）vs 仅前端提示？对**已存在的旧存档**（n4 无标记）要不要回填标记？
4. KV 敏感值（密钥/token）：明文存储+明文进提示词（实例本来就要用），前端打码显示即可？还是要加密存储？
5. 思考流展示形态：全文滚动 tail（推荐 500 字尾）还是折叠摘要？要不要控制写入频率默认值（1.5s）？

## Technical Notes

* 现状触点（一期末已就位的基础）：
  - `TurnListener.onThinkingDelta`（TerminalSession stream-json 解析，数字员工流式卡片在用）
  - `SupplierJobNode.progress`（一期新增的工具行动作字段）与 `progressListener`——本任务在其上扩展
  - `SupplierJobService.renderPrompt/completeNode`、`SupplierKbService.list(kbCode)`（DONE 条目路径清单）
  - `WorkflowTemplate.nodesJson` 节点属性扩展 + `frontend/src/pages/Supplier.jsx` WorkflowEditor 节点弹窗
  - n2 工件含 Postman Collection（ACTION_SPEC 第 6 条要求）；skills 在 `SupplierJob.skillsJson`
* 新增存储建议：`SupplierJob.meetingDocUrl`（600）+ `SupplierJob.configJson`（@Lob，KV 数组 JSON）；或独立表 supplier_codegen_prep——执行时二选一
* 前端交互顺序（同一卡片内）：☐会议文档 → ☐KV 配置 → 📣redis 提示 → 📝生成提示词（+复制）→ 用户粘贴到飞书
