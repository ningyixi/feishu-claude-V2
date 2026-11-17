# 不被卡住：实例对话解锁 + 僵尸占用节点可解除

## 已证实根因（2026-11-17，证据=实时后端日志 + 本地 MySQL 3306 现网数据）

**R1 新实例出厂即对话死锁**
`bot_instance.authorized_open_id` 为空时，`FeishuBotService.java:485-490` 权限判定必然失败，任何飞书消息只回「⛔ 无权访问此机器人。」（恰好 11 字 → 日志 `15:29:59.887 发送回复: instanceId=106, 11字`），**从不进 Claude**。全局白名单 `FeishuBotService.java:63-65` 只硬编码一个 openId。死锁点：唯一能写入该列的通道是实例配置界面（`BotInstanceController.java:224`），而"发消息自动绑定"永远跑不到——因为消息先被权限挡回。
现网对照：实例 106 昨天该列为空（被挡），今天人工填入 `ou_0d39a80220ad3abc4b1fa8af1d0682bd` 后对话正常。

**R2 僵尸 RUNNING：答复入口自己消失，且没有第二个出口**
`answerNode`（`SupplierJobService.java:552-570`）答复卡点后清空 `pendingQuestion` 但状态保持 RUNNING；续跑那一轮若未回写，节点永久停在 RUNNING。
现场：job 197 / n2 —— `status=RUNNING`、`pending_question=''`；日志显示 12:22:45「节点卡点待答」、12:23:14 该轮 `[result] 完成 isError=false`（会话其实空闲）。
死锁三角：① 前端卡点区/答复框显示条件是 `RUNNING && pendingQuestion`（`Supplier.jsx:481,525,529`）→ pq 空则入口消失；② `runNode` 拒绝 RUNNING 节点重跑（`SupplierJobService.java:525-527`）；③ 工作台无任何打断入口（`TerminalSession.abortCurrentTurn:277-284` 只被 EmployeeService/A2A 使用）。

**R3 澄清（不需实现）**：卡点期间会话是空闲的，飞书与工作台对话框本就同一条实例主会话（`ClaudeCodeService.java:225-227`，`SupplierSharedSessionTest:94-97` 断言永不使用 lane）。"同一 session 问答"已由架构满足，要做的是别再被 R1/R2 挡住。

## 范围

- **S1 首个私聊者自动绑定**：`authorized_open_id` 为空 **且** `chatType=p2p` 时，自动把发送者 openId 写入该列并放行本轮（落库 + INFO 日志）；已有值时严格匹配、行为不变；**群聊不自动绑定**（空绑定的群消息维持现有拒止）。实例创建返回体与实例列表/详情需能看出"未绑定/已绑定谁"。
- **S2 入口与状态解耦**：节点 `RUNNING` 时卡点区**始终渲染**——有 pq 显示问题原文+答复框；无 pq 显示"执行中（无待答问题）"。
- **S3 解除占用**：新端点 `POST /api/supplier-jobs/{jobId}/nodes/{nodeId}/release`：会话有活跃轮次则 `abortCurrentTurn`，节点 RUNNING→PENDING，`note` 追加留痕（含操作时间）。quick 任务的 phase 记账不得被破坏。
- **S4 僵尸巡检**：定时任务——节点 RUNNING + pq 空 + 该实例会话 `isTurnActive()==false` 连续 ≥N 分钟（默认 10，`-Dsupplier.zombieNodeMinutes` 可覆盖）→ 自动 PENDING + note「已自动解除僵尸占用」。会话活跃时**绝不**降级。

## 验收

- AC1 空绑定实例第一次 p2p 即被 Claude 正常回答且 DB 该列自动写入；随后另一个 openId 私聊仍被拒。
- AC2 空绑定实例的群聊消息不会被自动绑定。
- AC3 job 197/n2 这类 RUNNING+pq 空 节点，页面可见「解除占用」，点后能立刻重新执行该节点。
- AC4 巡检：RUNNING+pq 空+会话空闲→到点自动 PENDING 带 note；会话活跃→不动。
- AC5 S1/S3/S4 有单测；`mvn test`（除预存环境性失败）全绿；`npm run build` 通过。

## S5 排队边界（用户 2026-11-17 补充，硬约束，优先级高于以上任何设计倾向）

两种状态必须严格区分，行为相反：

- **卡点态**（节点 RUNNING 且存在待答问题，含 R2 的"pq 已被清空的僵尸 RUNNING"）：此时 CLI 轮次**已经结束、会话必然空闲**（已核实：`SupplierJobService.java:660-667` 卡点是文本协议，agent 写完【卡点】就结束本轮；现场 job 197 n2 卡点 12:22:45 报出、其轮次 12:23:14 已 `[result] 完成`）。任何入口（飞书 / 工作台对话 / DevChat）说话都必须**立刻进 Claude 当轮回答**，零排队、零等待。
- **非卡点态**（真的有一轮在跑）：保持现状 —— 后来者**继续排队**，一条都不许插。

由此派生的禁止事项（我曾在早期讨论里怀疑要用 `steer` 中途插话来解决，用户这条提醒已把它**明确否决**）：
1. **禁止**引入任何抢占/插话/自动 `abortCurrentTurn`/自动打断活跃轮次来解决"说话没反应"。`TerminalSession.steer()` 在本任务里**不许被调用**（E2E 那边既有用法不动）。
2. **禁止**改 `turnLock` 排队语义、超时、优先级（`TerminalSession.java:377-388`）。排队就是排队。
3. 卡点态唯一的改动是**把挡住"立刻回答"的东西清掉**：R1 权限死锁、以及前端把 RUNNING 一律翻译成"节点正在执行，结束后自动回答"的误导文案（`Supplier.jsx:1174-1183`、`SupplierJobService.java:1195-1208` 的排队提示写入）。卡点态不得再显示任何暗示"要等节点跑完"的字。
4. 需实跑核实并回报：工作台对话链路（`SupplierJobController.java:203-212` → `SupplierJobService.ask:1086-1116`）在**卡点态**是否真有以 `RUNNING` 为条件的发送拦截或延迟。有 → 这就是浏览器侧"没反应"的直接元凶，按 S5 拆掉（仅卡点态放行）；没有 → 照实回报"仅文案误导，无拦截"，不要为了凑改动去动它。

新增验收：
- **AC6 反向断言（防过度修复）**：实例存在**活跃轮次**（`isTurnActive()==true`）时，另一入口发消息必须进入 `pendingQueue` 并显示排队，**不得**被注入、不得被提前执行、不得触发 abort。测试层面直接断言 `TerminalSession.getQueuedCount()`/`queuedSnapshot()` 行为不变。
- **AC7 卡点态直通**：构造"节点 RUNNING + 会话空闲"，飞书/工作台发消息 → 断言该消息进入 `chat()` 并立即开新轮（mock 层面断言未走排队分支、`steer` 从未被调用）。

## 明确不做

不动工作流顺序门禁（→ `11-17-free-node-execution`）；不动 Flowable 建模；不改 KB/Wiki 管线；不把硬编码白名单改成配置化（若实现中认为必须改，先回报再动）；不做实例多绑定人（保持一实例一人）；**不做中途插话/打断**（见 S5）。
