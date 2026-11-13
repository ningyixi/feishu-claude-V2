# 供应商接口快速建档（工具定位：只分析，不开发）

## 产品定位（用户 2026-11-13 明确）

这是一个**独立工具**，不是一个迷你工作：输入一堆供应商接口文档，直接产出"接口分析 + Postman 实测档案 + LLM Wiki"。只考虑分析，**完全不考虑后续开发**——不出现类库路径/代码位置等任何开发相关字段与文案；页面与话术按"用完即走的分析工具"设计。

## 背景与问题

供应商工作台目前一切能力都由"工作流"驱动：想跑"读文档 → agent 分析 → Postman CLI 实测 → 结果入库 → 编译 LLM Wiki"这一段，也必须先新建一整个工作（选模板、建存档、走节点），哪怕只想用这一小段。目标：把这条链抽成侧边栏独立工具，五分钟开箱即用，不走工作流。

## 目标（MVP）

侧边栏新增「接口建档」工具页：
1. **极简表单**（就这四样，多一个都不要）：
   - 供应商标识（必填，一行文本即可，如"游由海外/shyytrip"——内部作 kbCode 命名空间；留空可由后端自动生成 `quick-<时间戳>` 兜底，实现二选一，回报说明）
   - 文档来源多行（必填 ≥1，复用现有知识库来源类型：URL/站点/PDF/md 路径等）
   - 绑定执行实例（复用实例下拉与独占闸门）
   - 任务名（选填，默认=供应商标识）
   - **禁止出现**：类库路径、供应商数字 ID、API/CM 模式选择、任何"后续开发"话术。
2. **一键启动**后自动串行执行四段流水线（虚拟线程后台推进，页面 3s 轮询进度）：
   - ① 入库：文档来源逐条进知识库并解析（复用 SupplierKbService 现有 add/解析管线，含站点抓取）
   - ② 分析：agent 执行"文档分析→接口映射报告"（复用内置模板 n1 同款 prompt 与上下文打包；prompt 占位符中空缺的开发类字段渲染为空即可，不得报错）
   - ③ 实测：agent 执行"接口实测→真实调用存档"（复用 n2 同款 prompt、Postman 实测协议、toolEvidence 证据采集机制）
   - ④ 归档编译：把 ②③ 工件+实测证据归档入库（archiveArtifact），然后编译 LLM Wiki（SupplierWikiService.build），直至 READY
3. **进度 UI**：四段步骤条（入库/分析/实测/建档完成），当前段显示实时进度（复用 node 的 progress/thinking 展示）；每段失败显示原因 + 「从本段重试」按钮；全部完成后显示结果卡：分析摘要、实测档案入口、Wiki 状态（版本/来源数/页数）、知识库条目列表（复用 KbPanel 只读查看）。
4. 完成后的引导以**查看本次分析产物**为主；"在工作台打开"仅作次要链接（服务高级用户想续用，但文案不得暗示"接下来开发"）。

## 关键设计决策（已定，实现不必再议）

- **内部复用 SupplierJob**：快速建档任务本体就是一个打了 `quick` 标记的 SupplierJob（实体加 boolean 列 quick，默认 false；ddl-auto=update 自动加列），knowledgeMode 固定 WIKI，节点快照只有两个 agent 节点（照抄内置模板 n1/n2 的 prompt/description），**不启动 Flowable 流程**。工作台工作列表 API 默认过滤 quick 任务（避免与正式工作混淆），但 /supplier?job=<id> 详情页照常可开。
- **流水线编排**放在新服务 `QuickProbeService`：直接调用 SupplierKbService/SupplierJobService(runNode)/SupplierWikiService 等现有公开方法推进，**不绕过** runNode 的上下文打包与证据采集；等待轮次完成用轮询节点状态（RUNNING→PENDING/DONE）或 knowledge-runs，实现选简单可靠者。
- 快速任务**不受写代码门禁/会议文档/Flowable 待办影响**（那些节点不存在）。
- 删除沿用现有 deleteJob 语义（级联清 KB/Wiki 历史照常）。

## 验收标准

- AC1 侧边栏出现「快速建档」入口，路由/页面可用，旧功能零回归（工作台/流程/Hook 页不动）。
- AC2 新建快速任务后四段自动串行推进；每段状态实时可见；agent 段能看到实例真实工具动作进度。
- AC3 完成后：知识库含全部来源+分析工件+实测档案（source 标记同规则）；Wiki revision 状态 READY；"在工作台打开"链接可达。
- AC4 任一段失败：页面显示原因，「重试本段」可续跑（幂等，不产生重复条目——依赖现有 archive/source 幂等）。
- AC5 快速任务不出现在工作台工作列表；实例状态/会话机制照常（同一实例并发限制与现有一致）。
- AC6 单测：QuickProbeService 编排状态机（mock 依赖：顺序推进/失败停在当段/重试幂等/quick 过滤）；SupplierJobRepository 查询含/不含 quick 的清单行为。
- AC7 `mvn test`（Supplier* 全量 + 新增测试）除预存 SupplierContextTest 环境性失败外全绿；`npm run build` 通过。

## 明确不做（防扩大）

- 不做批量任务、不做定时、不加权限模型、不动 Flowable/待办/通知、不新增文档来源类型、不改 Wiki 编译管线。

## 挂载点

- 后端：新 Controller `QuickProbeController`（/api/quick-probes：list/create/get/delete/retry-step），实体 SupplierJob +quick 列，listJobs 过滤。
- 前端：App.jsx 侧边栏路由 + 新页面 `QuickProbe.jsx`（表单+步骤条+轮询），复用 Supplier.jsx 中实例选择器/Wiki 状态展示的既有写法。
- 服务：`QuickProbeService`（编排，虚拟线程）。

## 部署

实现+测试全绿后：打包 fat jar → 复制到 D:/feishu-claude-data/run/app.jar → 重启后端 → 冒烟（创建一条含单 URL 来源的快速任务，观察推进到分析段即可判活，Wiki 编译可后台继续）→ commit + push。用户已授权本任务重启服务。
