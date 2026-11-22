# 工作台节点工件自动入库 + 空壳工件拦截（接口实测成果不再丢）

## 现场证据（2026-11-18，逐条实测取得）
job 197「接入直连 Engine」/ n2「接口实测 → 响应示例存档」：
```
10:31:47 [job-node-197-n2] 发送消息 (openId=supplier-job-197-WIKI-106)
10:37:02 [result] 完成: isError=false, 1字, 314624ms, tools=15
10:37:02 [SupplierJob] 节点执行完成: job=197, node=n2, 产物1字（等待用户确认）
```
- DB：`n2 status=PENDING`、`artifact` 长度 **1**（内容 `'M'`）、`tool_evidence` 长度 **0**；`supplier_kb_entry` 中 `kb_code='2164'` **没有任何 `node:n2` 的行**（对照组：n1 有 —— id=315、`source='job:197/node:n1'`、9578 字）。
- 磁盘：agent 的真实成果落在**被测工程根目录的未跟踪临时文件**：`D:/dida/SupplierV2/.tmp_engine_creds/{client.crt,client.key,keycheck.js}`、`.tmp_engine_sandbox_mtls.json`、`.tmp_engine_evidence_all.txt`、`.tmp_engine_evidence2.js`、`.tmp_engine_docs_result.json`。
- `supplier-kb/2164/.deliveries/68db….md`（10:31）**不是工件**，是 `SupplierKbService.snapshotDelivery()`（`:355-364`）写的**上下文投喂快照**（SHA-256 命名，设计上不进 KB 列表）。

## 根因（三条互相独立，都要治）
1. **归档时机不对称**：`QuickProbeService.java:413-414` 在流水线里就 `archiveArtifact`（注释写明与 completeNode 同规则、按 source 幂等）；工作台节点必须用户点「完成」→ `SupplierJobService.java:820` 才归档。用户的合理预期是"跑完就在库里"。
2. **空壳工件无拦截**：result 只有 1 字仍按"执行完成"记账。**若只做第 1 条，`'M'` 会被自动灌进知识库并喂给 Wiki —— 比现在更糟。** 所以第 2 条与第 1 条是同一件事的两半。
3. **成果与工件断开**：agent 把证据写进工作目录，系统只认 result 文本 + `toolEvidence`；本例 15 次工具调用却 `tool_evidence=0`，"最后一米"结构性缺失。

## 范围
- **K1 跑完即归档**：agent 节点拿到 result 且通过 K2 校验后立即 `kbService.archiveArtifact(...)`，**复用 `SupplierJobService.java:1179` 已有的 `artifact + toolEvidence` 组装配方**（别新写一套），source 仍为 `job:X/node:Y`；`completeNode`（`:820`）保留原调用，二次归档必须幂等（同 source 覆盖，不产生第二条条目）。
- **K2 空壳工件判定（纯函数，必测）**：不通过则**不入库**、节点**不得**显示"执行完成"，页面红字写明「本轮跑了 X 分 Y 秒、N 次工具，最终回复仅 Z 字，未入库」并给「重跑本节点」。阈值可配（`supplier.minArtifactChars`，默认 200）。判定至少覆盖：字符数、是否含 markdown 结构/表格/JSON、是否整段只是一个文件路径。
- **K3 落盘文件纳入工件**：从本轮工具流水提取 `Write`/`Edit`/`MultiEdit` 的目标路径（采集点在 `progressListener`，`SupplierJobService.java:602-657` 一带），在工件尾部追加「本轮落盘文件」清单（路径 + 字节数，**不搬文件内容**）；并给实测/分析节点的 prompt 注入面（`ACTION_SPEC`）加一条硬要求：**最终回复必须自带完整结论正文，不得只给文件路径或一句结论**。
- **K4 `.deliveries` 语义不动**：投喂快照保持现状，不进 KB 列表（`SupplierJobService.java:138` 的"严禁扩大"注释继续有效）。

## 追加根因（2026-11-18 二次取证，K5）
用户撞到的红字「缺少成功的 Postman 实测工具记录…」出自 `SupplierJobService.java:874`：
`if (!force && isProbeNode(n) && (n.getToolEvidence().isBlank() || n.getArtifact().isBlank()))`，
其中 `isProbeNode`（`:916-918`）= `contextStage=="probe"` **或标题含「实测」/「Postman」** —— n2 标题「接口实测 → 响应示例存档」命中，故必然被拦。它与知识库条目、Wiki 状态**完全无关**。

**为什么 `tools=15` 却 `tool_evidence=0`**：采集条件在 `:771-775` —— 工具事件必须 `SUCCESS` 且 `evidenceInput`/`evidenceOutput` 同时非空，且能解析出 http request（`:773` `SupplierProbeEvidence.hasHttpRequest`）与 http response（`:774` `hasHttpResponse`）才入账。本轮 agent 用 Bash 跑 Postman CLI 后**把结果写进 `.tmp_engine_*.json` 文件、再用自己的 `keycheck.js`/`evidence*.js` 读文件**，请求/返回从未以工具返回形态出现 → **一条都没采上**。加上 `runNode` 在派发前 `:677 n.setToolEvidence("")` 清空旧证据，于是"干了活但证据为零"。
- **K5 采集面补齐**：识别"经 Postman CLI/newman 通过 Bash 执行、结果落盘"的实测形态（至少：Bash 命令含 `postman`/`newman` 且退出码 0 且产出结果文件 → 从结果文件解析 executions 的 request/response 入库）；并在实测节点 prompt 里写死"每个接口必须经 Postman MCP 或 CLI 真实发起，**请求与响应必须出现在工具调用返回中**，不得只把结果写进文件"。
- **K6 门禁可解释**：门禁拒绝时，页面/回复必须同时告知**本轮采到的工具事件数、其中被判为实测证据的条数、以及不合规的具体原因**（现在只有一句"请运行实测节点"，用户完全无法自证为什么不算）。
- 注意：`completeNode` 尾部的 KB 归档发生在**门禁校验之后**（`:1262` 注释明确"同一事务内唯一一次"）。K1 改为跑完即归档时必须重审这个次序——不能把未过门禁的空壳工件提前灌进知识库。

## K7「用公司 Skill 生成提示词」按钮没有 force 出口（2026-11-18 现场，用户被彻底堵死）
现场：用户在 n4 备齐材料点该按钮，弹「请先完成带真实 Postman 工具证据（MCP 或 CLI）的实测节点」，**且没有任何强制/继续的选项**。

代码事实（不是猜测）：
- 前端按钮 `frontend/src/pages/Supplier.jsx:835` → `POST /api/supplier-jobs/{id}/codegen-prompt/generate`（调用点在卡片组件内，约 `:746`）。
- 后端 `SupplierJobController.java:296-302`：`jobService.generateCodegenPrompt(id)` **既无 `force` 入参，也不返回 `forceable` 标记**，异常一律 `err(...)`。
- 服务层其实已具备 force 能力：`SupplierJobService.java:1088 codegenPrompt(jobId, force)`、`:658` runNode 内部就用 `generatedDevelopmentPrompt(job, codegenPrompt(jobId, true), true)`。
- 即 `11-17-free-node-execution` 的 T4 只把 force 接到了 run / complete（`Supplier.jsx:354/397` 的 `data.forceable` 二次确认），**漏了这条按钮路径**，属于 T4 自身范围未做完，不是新问题。

**改法（最小）**：① 控制器 `generateCodegenPrompt` 接受 body `{"force":bool}` 并透传到服务层 force 重载；捕获 `CodegenGateException` 时返回带 `forceable:true` 的错误（复用既有 `errForceable`）；② 前端该按钮复用 `:354` 同一段二次确认逻辑；③ `runCheck`（🧪 自动检查要点，`:753`）若同样读 codegen 门禁，一并核对。

**验收（追加）**：
- **AC8′** n4 材料齐备但实测证据为空时，点「用公司 Skill 生成提示词」→ 弹**可确认的强制对话框**（不是无出口的错误 toast）；确认后成功产出提示词文本并可「一键复制」。
- **AC9′** 不带 force 的默认路径仍严格拦（保持原话术），并有一条测试钉住"默认不放行"。

## K8 证据通道纪律进系统提示词（用户 2026-11-18 明确要求："写在对应接口的系统提示词之类的地方，不用后续又出现这种问题，很消耗时间"）
**当前缺陷**：采集判据只存在于代码里（`SupplierProbeEvidence.java:61-72`），发出去的节点提示词**从不告诉实例该用哪条通道**。结果每次都要用户手粘一段话术，且换人换任务就复发。

**改法**：
1. 新增 **probe 专属附加段**（不是塞进通用 `ACTION_SPEC`，避免污染 n1/n9 等无关节点的 prompt）：由 `isProbeNode(n)`（`SupplierJobService.java:916-918`）判定，在实测类节点的 prompt 尾部拼接，内容至少包含：
   - 必须用官方 Postman CLI 经 Bash 执行 `postman collection run <collection.json> -e <env.json>`，或 Postman MCP 的执行类工具；**禁止 newman**；
   - collection 的 tests 必须逐接口打印 `SUPPLIER_EVIDENCE:{"method":…,"url":…,"code":…,"body":…}`；
   - **禁止 `> log` / `| tail` 等重定向或管道截断**（会把证据行截走），单次输出控制在 30KB 内，接口多则分批多轮；
   - 最终回复必须是完整报告正文，不得只给文件路径。
   文案须过措辞纪律（`WORDING_RULE` 禁词测试，见 `11-10-wording-discipline` 的注入面清单，本段要**加进那张清单**）。
2. **单一事实源（关键，防再次腐烂）**：提示词里承诺的命令形态**必须与识别表同源**。做法二选一并在回报中说明：① 该段文案由 `SupplierProbeEvidence` 的识别常量派生（命令名/参数模板共用同一处定义）；② 保留字面文案，但**加一条测试**断言"提示文本里出现的每一条被测命令形态，`isExecutionTool` 都必须认"（反向也要：识别表新增通道时测试提醒补文案）。**不接受只改文案不改测试** —— 那正是今天"代码改了、提示词没跟上、用户背锅"的复现路径。
3. 同时把这条纪律写进 `.trellis/spec/backend/`（与 K5/K6 一并）：**"实测证据通道 = 识别表正则的唯一事实源，新增通道必须同批改文案与测试"**。

**验收（追加）**
- **AC10′** 实测类节点的 prompt 文本中**无需用户输入任何指示**即包含上述四条纪律（测试断言渲染后的 prompt 含 `postman collection run`、含 `SUPPLIER_EVIDENCE:`、含"禁止 newman/重定向"关键语）。
- **AC11′** 同源测试存在且会红：临时把识别表加一条新命令而文案不更新 → 测试失败（证明它不是摆设）。
- **AC12′** 非实测节点（n1 分析、n9 复盘）的 prompt **不含**该段，避免无谓 token 与指令串味；QuickProbe 的 n1/n2 快照同样受益（n2 是实测类）。

## K5 契约（已定稿，实现者直接照做，不要再自行摸索）
权威文件：`.trellis/tasks/11-18-node-artifact-auto-archive/research/postman-cli-evidence-contract.md`（含逐字段契约表、真实样例值、可复跑取证脚本 `parse_*.js`/`scan_tail.js`）。

**今天全灭的直接原因（不是"采集面不全"，是识别表漏项）**：`SupplierProbeEvidence.java:63-72` 的 `isExecutionTool` 只匹配 `postman (collection )?run`，**不认 `newman run`**；实例的命令全是 newman → `TerminalSession.java:714-715` 永不填 `evidenceInput` → `SupplierJobService.java:772` 首个 null 检查短路 → `tool_evidence` 恒 0。

**三处改动（缺一处都不算修好）**：
1. `SupplierProbeEvidence.java:69`：识别加 `newman run`，排除 `--help`/`-v`/`--version`。
2. `SupplierJobService.java:744` `progressListener` 签名补 `String dir` 透传（唯一调用点 `:720`/`:723` 作用域内已有）；相对导出路径按「命令内 `cd "<dir>" &&` 前缀 → 否则实例 `getClaudeWorkingDir()`」解析。
3. 新增纯解析函数：**只靠解析命令里的 `--reporter-json-export <path>` 定位结果文件**（本机产出 JSON 的调用 100% 带该参数；文件名由 agent 自选，**禁止硬编码 `_result/_run` 之类后缀**）；双 schema 分支（newman 导出 vs Postman CLI 1.86 导出：`header[]` vs `headers[]`、响应体在 `stream` 字节数组、`_details.code` 内嵌）；沿用 `:779` 的 1MB 预算与既有事件文本格式，并与老的 stdout-marker 判定**互斥去重**（防同一调用记两遍）。

**三条已实证的坑（写死，别靠猜）**：
- **退出码不可信**：`> log 2>&1; echo EXIT:$?` 与 `| tail` 会把 Bash 退出码洗成 0（实证：12/12 全 ECONNRESET 的一轮 `is_error=false`）。成败一律**从结果文件内容判**。
- **stdout 不可信**：输出 >~30KB 时 harness 替换为存根 + 2KB 预览，`SUPPLIER_EVIDENCE` 标记行会被截走 → 必须读盘。
- **读盘安全**：`onToolEnd` 在 TerminalSession 事件线程**同步**执行（`TerminalSession.java:744`），文件读取必须设大小上限；导出文件在 CLI 退出前写完（同命令内 `; node -e readFileSync` 立即可读，实证），时序安全。

**失败语义（定稿）**：HTTP 4xx/5xx 且带真实响应体 → **算**有效实测记录（最有价值的取证）；传输层零往返（`requestError` / `errors[]=ECONNRESET` / 无 `response` 键）→ **不记录**，全灭轮 `tool_evidence` 保持空、门禁照拦。**不得做成橡皮图章。**

**预算口径**：真正的紧箍不是 1MB，是 `SupplierKbService.java:366-386` 的 **120,000 字符共享上下文预算**（证据随 artifact 归档进 KB 后会被拼进后续 prompt）。按既有"body 截 2000 字"口径，单节点证据**软顶 60–80KB**。

## K5 定性升级（2026-11-18 15:19 二次重跑实测，A 方案证伪）
用户按补充指示**重跑了两次 n2**，结果决定性：

```
14:17→14:22  节点执行完成: job=197, node=n2, 产物 2432 字
15:15→15:19  节点执行完成: job=197, node=n2, 产物 1570 字   ← 工件质量没问题（有 Check 表格、真实调用记录）
n2 现状：status=PENDING, artifact=1570 字, tool_evidence=0
新进程日志中「实测工具事件」出现次数：0
```

**结论：这不是话术能解决的，是采集器的能力边界。** `:771-775` 的采集条件要求工具事件自带 `evidenceInput`/`evidenceOutput`（结构化 JSON，能解析出 http request + response）；agent 经 **Bash 调 Postman CLI** 时，Bash 的 tool_result 是一段文本 blob，**天然不带这两个字段** → 无论 prompt 多严格都采不到 0→1。
因此 K5 必须落到代码：**Bash 类工具事件的采集分支** —— 命令含 `postman`/`newman` 且退出码 0 → 定位其结果文件（如 `.tmp_engine_docs_result.json`）→ 解析 `run.executions[]` 的 request/response 生成 `实测工具事件` 记录（受既有 1MB 预算约束）。不得靠"劝 agent 改用 MCP"当解决方案。

**连带后果（必须一起处理）**：n2 的 `tool_evidence=0` 时，用户点「强制标记完成」可以跳过 `:874`，但走到 codegen 阶段仍会被 `:897-899`（要求"存在 DONE **且有 toolEvidence** 的实测节点"）**再拦一次**，需要再 force 一次。用户已明确表达期望："执行完了自动会有一个结果新增进知识库，然后可以往下走" —— 即 **K5 修好后不该再有第二次 force**。验收须加一条：
- **AC7′** K5 生效后，n2 正常重跑一次即 `tool_evidence>0`，**完成 n2 与进入 n4 两处门禁都无需 force**。

## 验收
- AC1 重跑 n2 产出正常报告后：`supplier_kb_entry` 出现 `source='job:197/node:n2'` 的行、KB 目录出现 `NNN-*.md`，**全程不点「完成」**。
- AC2 之后再点「完成」不产生第二条条目（按 source 幂等覆盖）。
- AC3 复现空壳场景：极短 result → 不入库 + 页面红字含耗时与工具次数 + 可重跑；重跑正常后能入库。
- AC4 工件尾部出现「本轮落盘文件」清单（含 `.tmp_engine_evidence_all.txt` 这类路径与大小）；`ACTION_SPEC` 新增文案过措辞禁词测试。
- AC5 quick 侧零回退：`QuickProbeServiceTest`（20）、`AdHoc*`、`SupplierSharedSessionTest`、`SupplierNodeReleaseTest` 全绿。
- AC6 全量测试红数不超过基线 `Tests run: 266, Failures: 3, Errors: 1`（4 条为 Windows/temp 环境红）；`npm run build` 通过。

## 明确不做
不把 agent 写到工作目录的文件**内容**搬进知识库（只列清单——否则等于把用户源码抄进 KB，且与 `.deliveries` 的"严禁扩大"纪律冲突）；不做临时文件清理器（另议：`.tmp_engine_creds/client.key` 明文私钥未被 `.gitignore` 覆盖，是独立风险项）；不改 Flowable；不改 `.deliveries`；不动工作流顺序墙（那是 `11-17-free-node-execution`）。

## 排序（硬约束）
与 `11-17-free-node-execution` **同改 `SupplierJobService.java`** → 必须串行，排其后；否则两个 agent 互相覆盖。
