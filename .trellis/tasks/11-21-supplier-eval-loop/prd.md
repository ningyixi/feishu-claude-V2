# 侧边栏新功能：供应商代码质量 eval loop

## 用户诉求（2026-11-21）
新增侧边栏模块：创建一个任务（针对某供应商）→ 该任务的**设置可编辑，本质就是维护一组 eval** → 首次由 `review-supplier` skill 生成一批 eval，用户后续补充 → 启动任务 = 对 n 条 eval 执行测试，通过率 `x/n` → **循环改代码直到 `n/n`** → 其中"没通过的"与"改了又通过的"记录要**总结出经验** → 用来**维护两个 skill**：生成供应商代码的 skill（`create-supplier`）与 `review-supplier`（用户在界面上填这两个 skill 的路径）。定位：作为整个供应商交付 loop 的一环。

## 硬约束（用户原话，最高优先级）
> **「eval 是要能执行获得结果的，不能是 agent 主观判断的」**

由此派生三条纪律，违反任一条即算功能失败：
1. **verdict 只来自执行结果**：退出码 / 输出匹配 / 断言比较。**禁止**让 LLM 读代码打分、禁止"看起来没问题就算过"。
2. **LLM 只允许参与一处**：把 `report-and-validation.md` 里「场景 → 关键预期」这类自然语言条目**翻译**成可执行检查（生成脚本/单测/HTTP 断言）。翻译产物必须 ① 展示给用户确认后才入库，② **试跑必须真跑出结果**才算"已落地"。
3. **分母诚实**（防安慰剂 loop）：通过率只统计「已落地为可执行 且 本轮真跑过」的 eval。`未落地` / `跳过（缺沙箱凭据、缺配置）` 一律单列展示，**绝不混进分母**把 `n/n` 变容易。

## eval 数据模型（每条）
`id / title / kind / target(command 或 test filter 或 脚本路径) / expect(退出码 | stdout 正则 | JSONPath 等值) / timeoutSec / requiresSandbox(bool) / enabled / 最近verdict / 最近输出摘要 / 状态轨迹(首跑结果、第几轮转通过、是否曾通过又失败)`

`kind` 取值（全部客观可执行）：
| kind | 判定来源 | 备注 |
|---|---|---|
| `build` | 编译退出码 | 基线门槛 |
| `test` | 单测/集成测试退出码 + 用例计数 | 建议 `--filter` 到供应商相关命名空间 |
| `script` | 仓库内确定性脚本退出码 + stdout 断言 | LLM 翻译自然语言 eval 的主要落点 |
| `http` | Postman CLI 真实调用的响应（状态码/字段值） | 复用 `SUPPLIER_EVIDENCE` 通道与 `11-18-node-artifact-auto-archive` 的 K5 采集 |
| `static` | 确定性规则程序（grep/AST/文件存在性/计数） | **不是 LLM**，是脚本；用于"必须有验价复核""禁止吞异常"这类结构约束 |

## loop 语义
1. 启动 = 跑全部 enabled eval → 通过率 `x/n`（分母按上述纪律）。
2. 未通过集合 + 各自失败输出（截断，保留可定位信息）→ 交给**绑定实例主会话**改代码；**不自动 commit**，只改工作区，每轮记录 diff 摘要（文件/增删行数/关键片段）。
3. 改完**重跑全量**（不是只跑失败的）—— 防止"改好一条弄坏另一条"。
4. 状态轨迹显式区分三态：`一直失败` / `失败→通过`（经验来源）/ `曾通过→又失败`（**regression，必须红标置顶**）。
5. 收敛：`n/n` 且无未处理 regression。护栏：默认最大 **6 轮**；连续两轮失败集合无变化 → **暂停并【卡点】问用户**（复用 `11-17-adhoc-blocker-notify` 的卡点协议 + 待确认卡 + 飞书通知，不新造机制）。
6. 经验沉淀：对「失败→通过」的每条，汇总 {eval 标题、失败输出、该轮 diff 摘要、命中的 review 条目} → 生成**写回两个 skill 的提案**（目标文件默认 `review-supplier/references/history-and-decisions.md` —— 该文件本就为此存在 —— 以及 `create-supplier` 的对应经验文件）。**默认只产出 diff 提案，用户点「写入」才落盘**（跨仓库改 skill 会影响后续所有供应商，必须过人）。

## 与既有实现的关系（复用 / 隔离）
- **判定执行绝不走实例会话**：由后端自己起进程执行命令（`ProcessBuilder` + 超时 + 输出落盘 + 退出码入库）。判定路径必须与"会说话、会自我辩护的东西"物理隔离。
- 改代码走 `ClaudeCodeService.chat` 实例主会话（与飞书同 sessionID 的既有事实不变）；**禁止 `chatInLane` / `steer` / `abortCurrentTurn`**（用户既有硬纪律）。
- 独立表：`eval_task` / `eval_case` / `eval_run` / `eval_case_result`。**不碰** `supplier_job*`、`ad_hoc_*`、QuickProbe（用户「新东西不要耦合」的一贯要求）。
- 侧边栏新入口 + 新页面 `EvalLoop.jsx`；skill 路径输入框复用 `Supplier.jsx:246-258`「关联 Skill（可多个）」的写法。

## 待用户确认（PRD 未定项，别自作主张实现）
- **Q1 基线可执行命令到底是什么**：`D:\dida\SupplierV2` 用 `dotnet build` / `dotnet test --filter`？公司有无统一 CI 脚本？现有测试项目在哪、能否脱离内网独立跑（不能的话 `test` 类 eval 的可行性要重估）。
- **Q2 经验写 skill 的自动化程度**：默认「出提案 + 人点写入」；若要全自动需另设保护（仅追加、不改既有段落、可一键回滚）。

## 明确不做
不接公司 CI 系统、不自动 push/merge、不做多供应商并行测评、不做权限模型、不在本期做 eval 版本化对比看板、**不做任何 LLM 主观打分通道**（哪怕用户以后嫌落地麻烦 —— 那条口子一开，n/n 就没意义了）。

## 排序
排在 `11-17-free-node-execution`（编译修复中）与 `11-18-node-artifact-auto-archive`（K1-K8）之后：本任务要新增后端 service/controller 并改 `App.jsx`，与它们同面冲突，必须串行。
