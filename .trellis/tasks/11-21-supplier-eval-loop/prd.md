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

## MVP-1.5 内置通用 eval 模板（用户 2026-11-22 明确要求，不许再让他手写脚本）
用户原话：「脚本要我自己写，你帮我弄几个通用的吧，我现在还没时间自己编辑要执行的 eval。你可以弄几个 http 实测的，比如测试接口是否返回这种基础的；然后脚本也可以先弄几个简单的，比如运行脚本检查是否真的能读到配置文件。」

### 形态
- **模板库放可读文件，不硬编码进 Java**：`src/main/resources/eval-templates/*.json`（或单个 `eval-templates.json`），每条含 `key / title / kind / targetTemplate / expectTemplate / requiresSandbox / needsVariables / 说明`。用户以后能直接改文件而不动代码。
- **任务级上下文变量**：`eval_task` 增列 `context_json`（`@Lob + length`，遵守 database-guidelines §3）。变量集合最小化：`baseUrl`、`configKey`（如 `SS.DCYiLv.Config`）、`configJsonPath`（本地可解析的配置文件路径）、`collectionPath`、`envPath`、`supplierClassHint`（可选，用于静态类检查）。模板里用 `{{var}}` 占位，试跑时展开。
- **通用脚本落在仓库里**：`scripts/eval/*.js`（node，跨机器可跑、可读可改），**不要**把几十行 JS 塞进 `target` 字段里当内联字符串（不可维护、且 expect/target 会变得不可读）。target 形如 `node scripts/eval/check-config-shape.js "{{configJsonPath}}"`。

### 必备模板（最小集合，宁少而真能跑，不要一堆跑不出的）
| 分组 | 模板 | kind | 判定 | requiresSandbox |
|---|---|---|---|---|
| 配置 | 配置文件能被解析且必需结构齐全（`SupplierID`/`Urls` 七键/`AccountDic` 非空、`ConfirmMode ∈ {INSTANT,ONREQUEST}`、`paymentType` 为数字） | `script` | 退出码 + `stdout=OK` | 否 |
| 配置 | 配置里 `Urls.*` 全部是 https 绝对地址且同前缀一致 | `script` | 退出码 + 断言 | 否 |
| 配置 | 真去读配置中心拿到 `{{configKey}}` 并结构校验 | `script` | 退出码 | **是**（无凭据 → SKIPPED 不计分母） |
| HTTP | 接口基础连通：静态酒店列表/字典类只读端点有返回且状态码 2xx/3xx | `http` | `exit=0` + `json:` 或 `stdout~<状态码>` | **是** |
| HTTP | 查价端点带最小请求体有返回（响应非空、可解析 JSON） | `http` | 退出码 + 断言 | **是** |
| HTTP | Postman 集合真实跑一轮（与实测门禁同源通道） | `http` | 退出码 + `SUPPLIER_EVIDENCE` 断言 | **是** |
| 静态 | 供应商实现类存在且被注册（工厂/映射里能找到，脚本 grep/AST 判定） | `static` | 退出码 | 否 |
| 静态 | 无空 `catch {}` 吞异常、无硬编码密钥明文（脚本判定） | `static` | 退出码 | 否 |
| 构建 | `dotnet build <sln>` 通过 | `build` | 退出码 | 否 —— **但基线命令尚未真实验证**，导入时须在页面标注"未验证" |

### 交互
- 详情页一个「导入通用模板」入口：按分组勾选 → 展开为 eval（变量没填齐的落成 **未落地**，页面直接告诉他缺哪个变量，**不计入分母**）。
- 模板与文案必须写明一条：**eval 里用 curl 的连通性检查，不等于工作台实测门禁认可的 Postman 证据**（`SupplierProbeEvidence.java:61-72` 只认 Postman MCP / `postman collection run`）。不许让用户误以为跑绿 eval 就能免实测。

### 验收（追加）
- AC13′ 新建任务不写一行脚本，点「导入通用模板」选 3 条本地档（配置结构/https 校验/静态类存在），在给定 `configJsonPath` 后**试跑能真拿到 PASS/FAIL**；故意把配置 JSON 的 `Urls` 少给一键 → 必须 FAIL（证明不是恒过）。
- AC14′ 变量未填齐 → 该 eval 为 `未落地` 且 `stats.notLanded` 计入、`executed` 不含它。
- AC15′ `requiresSandbox` 模板在无凭据时记 `SKIPPED` 且**不起进程**（复用 MVP-1 已有语义，不得回归）。
- AC16′ 模板库改文件即生效（新增一条模板不需要改 Java 代码；测试用临时资源目录证明解析器读的是文件）。
- AC17′ 全量 `mvn test` 红数不超过当前基线 `303 跑 / 3 失败 / 1 错误`。

## MVP-1.6 模板拆根目录缺陷（2026-11-22 在用户真任务 id=2 上实测暴露）
把 `repoPath` 收窄到供应商目录后，**两条静态 eval 的需求互相冲突**，实测结果：

```
case 34 无空 catch/硬编码密钥  根=…/src/Business/Supplier/DCYiLv → PASS "OK 扫描 29 个 .cs 文件"
case 41 供应商类被注册          同一根 → FAIL "类 DCYiLvBusiness 只有定义"
```

根因：**注册点（工厂/映射/枚举）在供应商目录之外**，"是否被注册"天然要求扫**仓库根**；而"空 catch/硬编码密钥"要求只扫**供应商目录**，否则全仓库历史债（实测 117 处）会让通过率永远为 0，并诱导实例去乱改与本次交付无关的历史代码。
另实测：`build-dotnet` 模板 target 为 `dotnet build --nologo`，**不带解决方案路径**，在任何 cwd 下都会因缺项目而失败。

**修法**：
1. 变量拆成两个：`repoPath`（仓库根，默认取实例 `claudeWorkingDir`，供 build / class-registered 用）与 `supplierDir`（供应商目录，供 no-empty-catch 等"只该管本次交付"的检查用）。`supplierDir` 留空时**不得瞎猜**：按 `repoPath/src/Business/Supplier/<目录名>` 探测，命中唯一才自动填，命中 0 个或多个 → 该模板落**未落地**并写清原因。
2. `static-class-registered` 改成**双参数**：定义在 `supplierDir` 下查找、引用在 `repoPath` 全仓查找；并把"注册"判据的**代理性质**写进脚本输出与模板说明（类名被引用只是代理，真实注册可能走 `SupplierID`/枚举/配置映射）；对"已跑通的供应商报红"这一假红场景必须有测试覆盖。
3. `build-dotnet` target 补上仓库根的 `*.sln` 解析（`dotnet build "<repoPath 下唯一 .sln>" --nologo`；找不到或有多个 → 未落地并说明），并保持 `verified=false` 的「未真实验证」橙标，直到主会话经用户授权真跑一次。
4. 模板 `needsVariables` 同步更新，前端缺变量标注自动跟随（不硬编码变量名）。

**验收（追加）**
- AC18′ 同一任务下：`no-empty-catch` 根=供应商目录时 PASS，`class-registered` 根=仓库根时不因"注册点在目录外"而假红；两者的 target 分别含各自正确的根参数（精确等值断言）。
- AC19′ `supplierDir` 探测不到唯一目录 → 该模板未落地、`stats.executed` 不含它、notes 写明歧义（0 个或多个候选）。
- AC20′ `build` 模板 target 含真实 `.sln` 路径；无 sln 时未落地而非"跑一条必失败的命令"。
- AC21′ 全量 `mvn test` 红数不超过基线 `330 跑 / 3 失败 / 1 错误 / 1 跳过`。

## MVP-1.7 方向纠正：http 类 eval 必须端到端调**应用代码**，不得绕过适配器直连供应商（用户 2026-11-22 指正）
用户原话：「调用真实接口的时候我看日志你调用的都不是这个供应商的，就是比如我测评的是 DCYiLv，那你就要调用 DCYiLv 代码里的接口，现在是端到端测试，写好代码了调用代码就行了。」

**我此前做错了什么**：`case 42` 用 newman 跑集合**直连 `hotel.toyeebuy.com`**，测的是"供应商活着吗 + Postman 集合自带签名对不对"，**与被测代码无关**；由此得到的 `{"code":"401","message":"未授权"}` 也已重新定性为**集合凭据/签名问题，不是 DCYiLv 代码问题**。这类"绕过适配器的直连"今后不得算作代码质量 eval。

**规则**：
1. `http` 类 eval 的目标必须是**自家应用的入口**（`localhost` / `127.0.0.1` / 内网服务地址），由应用代码去访问供应商、用配置中心读入内存的 `SS.<X>.Config` 签名 —— 这样一条 eval 同时覆盖"配置读到 + 签名对 + 请求映射对 + 响应解析对 + 异常处理对"。入口端口与路由由主会话读 `D:/dida/SupplierV2` 的 Controller/`Program.cs` 得出，**写成 eval 草稿后须先给用户看一眼 target 再执行**（它会真发请求）。
2. 直连供应商端点的模板**降级改名为"供应商可用性探针"**，`countsTowardScore=false`：可跑、可看输出，但**不进代码质量分母**，避免再用"供应商挂了"冒充"代码有问题"。
3. **禁写语义仍然绝对**：端到端路径下也绝不触发下单/取消 —— 应用的下单/取消入口同样在禁词名单内。
4. 已知尖角：`EvalReadOnlyGuard` 对路径分段做 `contains`（`create|cancel|pay|refund|confirm|order`），因此**只读但名字含这些词的应用路由会被误拦**（如 `/api/supplier/order/query`）。宁可拦错不放过是对的，但误拦必须给出**可读原因（命中哪个词、哪一段）**与**一次显式的人工放行**（放行要落进执行记录，便于追责），不得变成用户看不懂的静默红。
5. eval 参数**禁止写死时间**（住期等一律运行时动态生成，如"今天+7 起 1 晚"）—— 现有集合里 `11/20-21` 这种写死日期会随日历腐烂，且会让红/绿失去意义。

## MVP-2 范围与未定项（下一棒，本轮不做）



- **Q1 基线可执行命令到底是什么**：`D:\dida\SupplierV2` 用 `dotnet build` / `dotnet test --filter`？公司有无统一 CI 脚本？现有测试项目在哪、能否脱离内网独立跑（不能的话 `test` 类 eval 的可行性要重估）。
- **Q2 经验写 skill 的自动化程度**：默认「出提案 + 人点写入」；若要全自动需另设保护（仅追加、不改既有段落、可一键回滚）。

## 明确不做
不接公司 CI 系统、不自动 push/merge、不做多供应商并行测评、不做权限模型、不在本期做 eval 版本化对比看板、**不做任何 LLM 主观打分通道**（哪怕用户以后嫌落地麻烦 —— 那条口子一开，n/n 就没意义了）。

## 排序
排在 `11-17-free-node-execution`（编译修复中）与 `11-18-node-artifact-auto-archive`（K1-K8）之后：本任务要新增后端 service/controller 并改 `App.jsx`，与它们同面冲突，必须串行。
