# 供应商代码质量测评（eval loop）

适用范围：`EvalRunner` / `EvalReadOnlyGuard` / `EvalLoopService` / `EvalTemplates` / `scripts/eval/*` / `frontend/src/pages/EvalLoop.jsx`。
这些规则每条都来自一次真实返工，**不是风格偏好**；违反其中任何一条，eval 就从"信号"退化成"噪音"。

## 1. 判定只来自执行结果（最高优先）
PASS/FAIL 只允许由**退出码 + 输出断言**（`exit=<n>` / `stdout=<子串>` / `stdout~<正则>` / `json:<点路径>=<值>`）产生。

**禁止**让模型读代码后打分，禁止"看起来没问题就算过"。LLM 唯一允许参与的地方是把 review skill 的自然语言条目**翻译**成可执行检查，且翻译产物必须经用户确认、并**真跑出结果**才算已落地。

> Bad: `expect: "让模型判断这段实现是否符合规范"` —— 通过率会随模型心情漂移，且永远无法复现。
> Good: `expect: "exit=0\nstdout=OK"` + 一个确定性脚本。

## 2. 分母诚实
`stats` 的分母只含**已落地、本轮真跑过、且计入评分**的条目。以下三类必须单列、绝不混入分母：

- `未落地`（target 为空：缺变量/有歧义/被闸拒落地）
- `跳过`（缺沙箱凭据；**不得起进程**）
- `探针`（`countsTowardScore=false`：只回答"外部服务活着吗"，不证明交付代码正确）

> 反例（真实事故）：直连供应商网关的 http eval 全挂在 `401 未授权` 上，如果计入分母，就会把"Postman 集合凭据过期"冒充成"供应商代码有 bug"，并且让 `n/n` 永远不可能达成。

## 3. 端到端：必须走自家应用，不许绕过适配器
测评某供应商就得触发**该供应商在自己代码里的路径**，由应用去读配置中心（`SS.<X>.Config`，运行时载入内存，**磁盘上没有这个文件**）、自己签名、自己解析响应。

**禁止**用 Postman/newman/curl 直连供应商网关来充当代码质量 eval —— 那测的是"供应商可用性 + 集合自带签名"。此类目标一律降级为探针（见第 2 条）。

## 4. 只读安全闸（`EvalReadOnlyGuard`）
- 判定对象是"**会不会产生写副作用**"，不是"这是谁的域名"。
- 禁止表（`create/cancel/pay/refund/confirm/order` 等提交语义）**优先级高于一切允许规则**；命中即同步 `FAIL`（计入分母、不是 SKIPPED），且**进程必须没起**（`exitCode=null`、`elapsedMs=0`、无输出文件）。
- 允许表是**数据**（回环/内网 host、只读端点类别）。新增目标类别=加数据条目，**不改判定结构**。
- 拦错要能解释：给出命中的 URL、路径段、禁词；若确需放行，走**显式人工放行并落进执行记录**，不许静默。

> Bad: 闸"通过"后进程实际发出了 `booking/create` —— 在供应商沙箱里下成真实订单。
> Bad: 命中禁词后静默 SKIPPED —— 安全闸静默失效等于没有闸。

## 5. 扫描根必须按语义拆开
- `supplierDir`（供应商自己的目录）：只该管本次交付的检查（空 catch、硬编码密钥）。
- `repoPath`（仓库根）：注册性/装配性检查（定义在供应商目录、引用在仓库根）与 `dotnet build`。

自动探测**只在唯一命中时**生效；0 个或多个候选一律落"未落地"并写明歧义，**绝不回退成扫仓库根或扫进程 cwd**（那会让"117 处历史债"淹死本次交付，或让 eval 在后端自己的目录里空跑）。

> 真实事故：模板未传扫描根 → 脚本退化成扫后端进程目录；另一例是共用一个根导致"已注册的供应商"永远报未注册。

## 6. eval 参数禁止写死时间
住期、日期、"昨天/今天"一律运行时动态生成（如今天+7），并在输出里打印实际取值。写死日期会让 eval 随日历腐烂——今天绿、下周红，且没人知道为什么。

## 7. 模板与脚本外置，判定内核稳定
模板是 `src/main/resources/eval-templates.json`（**Java 里不许硬编码模板清单**，有测试断言"改文件即生效"）；确定性检查脚本在 `scripts/eval/*.js`（纯 node、零 npm 依赖、用户可读改）。`EvalRunner.judge/execute` 是判定内核，扩展能力时加旁路纯函数，**不要往里塞业务**。

## 8. 执行记录与轮询契约
- 每次试跑**先落一行 RUNNING**、同步返回 `{taskId, caseId, resultId, verdict}`；轮询方必须**按 `resultId` 取本次结果**，禁止"读列表第一条"（曾因此把上一轮 10677ms 的旧结果当成本轮）。
- `results` 列表按 id 倒序（最新在前）是显式契约，写在 repository 注释里。
- 失败时该次的 reason + 输出必须直接可见（不藏进二级交互）；输出走落盘文件分页读取，**路径只认库记录，绝不接受调用方传入路径**（否则是任意文件读取）。
- 输出落盘/回显前必须脱敏 `secret`/`apikey`/`authorization` 等凭据值 —— eval 的设计初衷是把输出全文给用户看，与凭据天然冲突。

## 9. 运行与排查
后端**必须脱离调用者会话**运行，否则会话/任务被回收会连带杀掉服务（2026-11-22 实际发生过，且日志与进程同时消失、死因无从追查）：

```
启动  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/run-backend-detached.ps1
日志  tail -f /d/feishu-claude-data/run/backend.log      # 上一份为 backend.log.prev
停止  taskkill //PID $(cat /d/feishu-claude-data/run/backend.pid) //F
```

eval 排查顺序：页面执行记录 → `reason`（含命中禁词/缺失变量/歧义候选）→ `outputFile` 全文 → 模板文件里该条的 `description`。
