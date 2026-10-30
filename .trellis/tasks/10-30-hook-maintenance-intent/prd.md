# Hook 模块：skill 维护意图字段（每个 hook 声明维护什么业务）

> 状态：仅创建，未执行。

## Goal

现在 skill_hook 只描述"监控谁、维护哪个 skill 目录"，不描述**维护什么**。同一实例的对话对多个 skill 都有价值，但没有意图声明，维护者 LLM 只能泛泛提炼，不同 hook 的行为无法区分。用户方案：新建 hook 时设置"维护意图"，下游各环节按意图差异化。

典型场景（用户原例）：
* hook A：维护**业务开发细节**（接口踩坑、字段约定、流程实现）
* hook B：维护 **review 要点**（代码评审中反复出现的问题与检查项）
* hook C：维护**怎么写好提示词**（对话中验证过的 prompt 模式）

## Requirements

* R1 数据模型：SkillHook 新增 `intent` 字段（必填文本，建议 ≤200 字）；旧 hook 回填默认值"通用经验沉淀"（不炸存量）
* R2 判定差异化：`buildJudgePrompt` 注入意图——本轮对话与该 skill 的**维护意图无关**时判 false，避免不同 hook 把同一批对话重复沉淀成同质内容
* R3 维护差异化：`buildMaintainPrompt` 注入意图——只提炼与该意图相关的经验，并按意图选择落笔的文件/章节（如意图是 review 要点 → 沉淀成检查清单格式）
* R4 前端：新建/编辑 hook 表单增加"维护意图"必填项（带 3 个示例 placeholder 提示常见类型）；hook 列表卡片显示意图徽章（截断+悬浮全文）
* R5 意图可见性：生成的维护产物上下文里保留意图声明（维护者改 skill 文件时知道这个目录的定位），建议写入/更新 skill 目录 SKILL.md 头部"本 skill 维护意图"段（幂等：已存在则不重复写）

## Acceptance Criteria

* [ ] 新建 hook 不填意图会被表单拦截；后端 create 校验非空
* [ ] 两个监控同实例、意图不同的 hook：judge 阶段各自过滤明显不相关对话（构造对话样例验证）
* [ ] maintain 产物只沉淀与意图相关的经验（快照断言 prompt 含意图文本）
* [ ] 列表/编辑页可见意图；旧 hook 显示默认意图且可修改
* [ ] mvn test 无新增失败；Hooks.jsx vite build 通过

## Definition of Done

* 单测：judge/maintain prompt 组装含意图断言；旧 hook 回填逻辑
* SKILL.md 意图段幂等写入测试
* 行为变化同步 Hooks.jsx 页内说明文案

## Out of Scope

* 一个 hook 多意图（意图列表）
* 意图之间的冲突仲裁/去重（两 hook 意图重叠时不特别处理，各自运行）
* 按意图自动推荐 skill 目录结构

## Open Questions（执行前收敛）

1. 意图形态：**单一自由文本**（推荐，简单直接）vs 预设枚举+自定义补充？枚举便于将来按类型统计，文本更灵活
2. judge 过滤强度：与意图"完全无关→不沉淀"还是"擦边也允许并标注置信度"？（推荐前者，宁缺毋滥）
3. SKILL.md 头部意图段（R5）要不要？好处是维护者改文件时知道定位；风险是 hook 自动改用户手写的 SKILL.md 结构（可做成开关）
4. 意图变更时是否触发一次"按新意图重整 skill 目录"的维护？（MVP 建议不做，只影响后续沉淀）

## Technical Notes

* 触点（已核查）：
  - `model/entity/SkillHook.java`（name/instanceIds/maintainerInstanceId/skillDir/triggerType/cronExpr/enabled/...）——加 intent 列，ddl-auto:update 自动建
  - `service/SkillHookService.java`：`buildJudgePrompt`(≈329 行)、`buildMaintainPrompt`(≈341 行)、judge 调用 109-110、maintain 调用 207-208
  - `controller/SkillHookController.java`（create 校验）+ `frontend/src/pages/Hooks.jsx`（新建/编辑表单、列表卡片）
  - 维护者会话 = maintainerSession(hook) 常驻实例会话，意图注入 prompt 即可，无需新进程
* 旧数据：`intent IS NULL` 读出时给默认文案；回填脚本可放 seed/@PostConstruct（参考 SupplierJobService.seedTemplates 模式）
* 风险：judge prompt 变长（每 hook 每次对话都要跑 judge）——意图文本 ≤200 字可控；token 成本略增，可接受
