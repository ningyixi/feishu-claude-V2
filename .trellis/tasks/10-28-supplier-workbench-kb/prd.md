# 供应商工作台优化：知识库 + 统一补充入口 + 执行规范

## Goal

把供应商接入工作台从"文档引用清单 + agent 每次即席抓取"升级为"一供应商一知识库"：
文档内容一次解析持久复用，任何信息（文档链接、PDF、聊天记录、笔记）可随时从统一入口补充；
同时把接口实测和节点执行动作规范化（Postman 插件优先 + trellis 任务管理纪律）。

## Requirements

* R1 知识库实体（供应商级）：一供应商（supplierCode）一个知识库，跨多个接入工作共享；条目类型 = URL / PDF 本地路径 / 聊天记录文本 / 笔记。创建工作时录入的 docs 自动转为知识库条目（旧 docs_json 兼容只读）。
* R2 解析管线（Java 原生移植参考方案）：URL 与 PDF 入库后异步解析为 Markdown——内容获取 → 文本提取与清洗 → 落盘 + 元数据。校验文件头/大小、页数/超时/重定向/响应大小上限、SSRF 防护；按标题+约 6000 字符分块保留页码/行号定位信息；失败有条目状态（待解析/解析中/成功/失败+原因），不阻塞其他条目。
* R3 统一补充入口（Web 工作台面板）：工作详情页新增「知识库」面板，随时添加任意类型条目、查看解析状态、预览/编辑解析后的 MD（人工纠错）；聊天记录以文本粘贴方式断断续续补充。
* R4 消费改造：agent 节点 prompt 注入「知识库索引表」（标题/类型/状态/文件路径/来源）+ 规则：先查知识库、命中直接 Read 对应 MD、不再重新抓取 URL；新补充条目在下一次节点执行时自动可见。
* R5 接口实测 Postman 规范：实测节点强约束——agent 用 **Postman 插件（MCP，实例均已安装）** 作为接口调用的规范通道：先建/导入 Collection，再经插件发请求，实测产物必含 Collection（v2.1 定义）+ 响应/差异记录；插件不可用时降级 newman CLI 回放（备选，需另装）。
* R6 trellis 运行时任务管理：agent 节点执行映射 trellis 任务——job 对应父任务，节点执行时在工作目录创建子任务（prompt 快照为 PRD），按 trellis 工作流动作规范执行（before-dev 读规范 → implement → check → finish），产物回写工作台 artifact；实例工作目录须是初始化过 trellis 的仓库。
* R7 卡点人机交互（human-in-the-loop）：节点执行中 agent 遇到卡点（缺凭证/文档矛盾/需要决策）时暂停并提示到前端——工作台出现该节点的卡点提醒 + 输入框；用户回答经对话通道注入**正在执行的同一会话**（复用 E2E steering 中途注入机制 + SSE 时间线），agent 收到后继续执行；无回答超时则按"需要玩家提供"清单跳过并标注。卡点检测默认方案：prompt 动作规范约定 agent 输出 `【卡点】{问题}` 标记行，后端流式监听捕获（与 trellis check 阶段天然衔接）。

## Acceptance Criteria

* [ ] 供应商 A 的两个并行工作共享同一知识库，任一处补充条目另一处可见
* [ ] URL/PDF 条目提交后异步解析出 MD；再次执行相关节点时日志验证零重复抓取（agent Read 的是落盘 MD）
* [ ] 解析失败（扫描件/网络失败/超限/SSRF 拦截）条目显示失败原因，其余条目正常可用
* [ ] 详情页可随时补充聊天记录/笔记，下一条节点 prompt 的索引表即包含该条目
* [ ] 实测节点产物包含规范 Collection JSON 且调用经 Postman 插件通道执行（产物模板校验）
* [ ] 节点执行时在实例工作目录生成 trellis 子任务且状态可追踪，产物回填 artifact
* [ ] 节点执行中出现卡点时，工作台实时提示且可输入回答，答案注入原会话后 agent 继续执行（冒烟：故意缺凭证场景）
* [ ] 存量工作（docs_json 老数据）打开不报错，引用自动并入知识库视图

## Definition of Done

* Tests added/updated（解析管线单测：PDF/HTML 样例→MD 快照；SSRF/上限校验测试；知识库 API 集成测试）
* Lint / build green（mvn + vite build）
* Docs/notes updated if behavior changes（工作流模板 prompt 说明同步）
* Rollout: 旧 docs 引用兼容保留；解析失败不影响未启用知识库的存量工作流

## Decision (ADR-lite)

1. **解析引擎 = Java 原生移植**（PDFBox 按位置排序≈layout / Java HttpClient / Jsoup 清洗 / flexmark html2md）。否决 Python 子进程（运维负担）与 agent 代理解析（不确定、烧 token）。OCR、JS 渲染页明确不支持。
2. **消费方式 = 落盘文件 + 索引注入**，agent 按需 Read；否决全文直灌（prompt 容量）与 RAG 检索（大上下文下性价比低，二期再议）。知识库文件目录建议 `D:/feishu-claude-data/supplier-kb/<supplierCode>/`（与 H2 同盘），元数据入 H2 新表 supplier_kb_entry。
3. **入口 = 仅 Web 面板**单渠道；后端 API 从第一天按通用形态设计，飞书聊天 push 与外部 HTTP 推送留二期。
4. **接口实测 = Postman 插件（MCP）优先**（用户实例均已安装），Collection 为必交产物；newman CLI 仅作插件不可用时的降级预案，不进 MVP 必装项。
5. **执行规范 = trellis 运行时映射**（节点 → trellis 子任务 → check → finish），非仅开发侧流程。

## Delivery Status (2026-10-28)

已实现并在线验证（后端 mvn test 9/9 绿，前后端已重启）：
* R1/R3/R4：`supplier_kb_entry` 表 + `SupplierKbService`（异步解析/重启续跑/存量幂等同步）+ `/api/supplier-kb` 四端点 + GameView 知识库面板（KbPanel）
* R2：`DocParser`（URL/PDF→清洗→ATX→6000字符分块含行号定位；SSRF/上限守卫；空正文/整本扫描件→FAILED+建议）
* 验证证据：example.com 解析 DONE 落盘；SSRF 169.254.x 拒绝；apipost JS 站重解析转 FAILED 带人话原因；job1 打开自动并入 2226 库；detail JSON 含 kbCode/pendingQuestion；3000 代理链路 200
* R7 代码链路已接：runNode/answerNode 统一 applyNodeReply 卡点检测（回复含【卡点】→RUNNING+pendingQuestion），前端卡点答复框就绪；**待一次真实节点执行冒烟**（会走实例会话、消耗 token，建议由用户在页面上点一次"让实例执行"验证）
* R5：ACTION_SPEC 已在所有 agent 节点 prompt 末尾强制 Postman 插件协议（Collection v2.1 + 插件执行，newman 降级）；实际 MCP 可用性待真实执行冒烟确认
* R6：四段动作纪律（Before-dev/Implement/Check/Finish）经 ACTION_SPEC 注入生效；"在实例工作目录真实创建 trellis 子任务文件"未做——前提是实例工作目录初始化过 trellis，当前供应商类库目录未必满足，留作二期

遗留/垃圾数据：kbCode=SMOKE-TEST 的 2 条冒烟条目与 D:/feishu-claude-data/supplier-kb/SMOKE-TEST/ 文件可手动清理（后端暂无删除条目 API，二期补）。

## Out of Scope (explicit)

* 扫描件 PDF OCR；动态 JS 渲染网页抓取；向量/语义检索（RAG）
* 飞书聊天 push 入口、外部脚本 HTTP 推送入口（二期）
* 知识库条目权限分级/审批流
* 自动同步供应商官网全站文档（多页爬取仅预留同源范围参数，MVP 单页）

## Technical Notes

* 关键现状文件：
  - `SupplierJob.java`（docs_json @Lob）/ `SupplierJobNode.java`（artifact @Lob）
  - `SupplierJobService.java:240-349`（runNode/renderPrompt/formatDocs/{artifact:nX} 截断 12000）
  - `ClaudeCodeService.java`（TerminalSession 按 instanceId 复用）/ `frontend/src/pages/Supplier.jsx`（211-227 docs 表单）
* 环境约束：Spring Boot 3.4 + Java 21；H2 文件库 `D:/feishu-claude-data/config.mv.db`；前端 React18+Vite（3000 代理 /api→8080）
* **实例 Claude 环境均装有 Postman 插件（MCP）** — R5 的前提；无头子进程模式下 MCP 可用性需在实现前用一次冒烟验证确认
* 项目已初始化 trellis（.trellis/ + create-supplier skill 存在），trellis 运行时映射（R6）可直接利用
* 新增 Maven 依赖候选：pdfbox、flexmark(+html2md extension)、jsoup（或 commonmark）
* 数据模型草案：supplier_kb_entry(id, supplier_code, type[url|pdf|chat|note], source, file_path, md_path, status, error, meta_json, created_at, updated_at)；SupplierJob 与 kb 通过 supplierCode 关联
