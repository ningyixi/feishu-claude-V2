# 供应商流程完善（三期）：Logger配置/Review节点/提示词自检/开发期对话/输入框升级

## Requirements（用户 2026-11-03 口述）

* R1 n8（上线准备归档）文本补充："还要在 SS.AliyunLoggerConfig 配置上补充配置"——内置模板+存量 ACTIVE 存档回填
* R2 新增 **n9 review 节点**：agent 类型；用户在执行输入框填公司 review skill（目录/名称）→ 点执行 → 实例用该 skill review 本供应商代码，指出问题（产物进 artifact）
* R3 n4 提示词**自动检查**：生成提示词后跑规则检查（纯静态，不调模型），检查项含：trellis 任务管理、使用 skill、spec 文档、知识库索引地址、三方会议文档、配置 KV、redis 提醒、实测/Postman 档案——表格呈现 √/×；「按结果修正」按钮：把缺失项的标准段落**针对性补写**进当前提示词（含用户手工编辑后的文本）
* R4 新增 **n4.5 开发期对话节点**（排在 n4 与 n5 之间，devChat 属性）：
  * 用户随时输入"原始话"（开发中和供应商沟通得的特殊业务处理、要调整的点）
  * 点生成 → 系统基于【知识库索引+本节点历史对话+skill 清单+供应商信息】把原始话**重写为可直接发给飞书实例的提示词**
  * 前端展示重写结果可复制；对话记录存在节点聊天框，**无限轮**
  * 与执行节点同一实例会话（保持开发上下文连续）
* R5 输入框升级：节点"补充指示"与"卡点答复"由单行 input → textarea（3 行，可拖大）；Enter=提交、Shift+Enter=换行；n4.5 对话输入同样

## 迁移策略
* 内置 API 模板与**存量 ACTIVE 存档**自动回填 n9/n45（幂等：按 nodeId 判存），n8 描述按关键字判重追加
* n45 seq 插在 n4 之后（后续节点 seq 顺延）；模板编辑器可再改

## Acceptance Criteria
* [ ] 旧存档打开即见新 n4.5/n9 节点，n8 文案含 AliyunLoggerConfig；重进不重复插入
* [ ] n4 生成提示词→检查表 8 项 √/× 正确；删掉提示词里 trellis 句→检查×→点修正→段落回来
* [ ] n4.5 连续对话 3 轮，历史持久化显示，每条重写提示词可复制
* [ ] n9 输入 skill 路径执行，prompt 含该 skill 且按 agent 节点产物链走
* [ ] instruction/卡点输入框可多行（Shift+Enter），不破坏 Enter 提交
* [ ] check/fix 纯函数单测；mvn 无新增失败

## Out of Scope
* 检查规则的 LLM 语义判断（用关键词，白盒可测）；节点级权限；n9 自动挂到 n4（保持手工推进节奏）

## Technical Notes
* 触点：SupplierJobService(apiTemplateNodes 种子/快照/askNode/check/fix/@PostConstruct 迁移)、SupplierJobController(3 新端点+detail devChat)、SupplierJobNode.devChat(Boolean!)、新实体 SupplierNodeChat+repo、Supplier.jsx(CodegenPrepCard 检查表/NodeChatPanel/textarea×2)
