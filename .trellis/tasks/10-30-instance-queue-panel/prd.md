# 实例对话排队可视化：右列实时排队面板 + 消费入对话动线

> 状态：仅创建，未执行。

## Goal

飞书并发发给同一实例的消息会排队（会话轮串行）。当前浏览器"实例 → 💬对话"视图里看不到排队：消息一进来就混进对话流，用户不知道哪条在等、哪条被消费。

目标交互（用户原述）：
1. 底部输入框**左移**，腾出右侧空隙；
2. 右列实时显示**排队中的消息**（最新队头在上）；
3. 某条被消费（真正写进模型本轮）→ 右列队头**消失**，同时左列对话流里出现"我发出了一条消息"；
4. 整体动效/交互做精细（消失动画、进流高亮等）。

## What I already know（代码核查，触点精确）

* **排队现状 = 不可见的阻塞线程**：`TerminalSession.sendMessage` 用 `turnLock`（ReentrantLock，**非公平**）串行化——`tryLock()` 失败即 `listener.onQueued()` 后 `lock()` 阻塞。没有任何可枚举的队列数据结构，这是本任务核心改造点（`TerminalSession.java:318-348` 一带）
* **飞书消息在消费前就落库**：`FeishuBotService` 先 `saveChatMessage(...,"user",...)`（400 行）再调 `chat()`——所以现状左列气泡在"还在排队"时就出现了，与目标 3 冲突，需要"排队/已入会话"状态区分
* **SSE 通道现成**：`StreamingCardController.onQueued`（99 行）与 `BrowserTurnListener.onQueued`（41 行）已存在，A2A 也转发了 onQueued——实时推送可复用现有链路
* **前端**：实例页 → `ChatHistory.jsx`（`/api/instances/{id}/messages` GET，3s 轮询 autoRefresh）
* `TurnListener.onQueued()` 是接口 default 方法，签名无消息标识——要把"是哪条在排队/被消费"传出来，需扩展事件或队列快照 API

## Requirements (草案)

* R1 **会话队列结构**：TerminalSession 维护显式 FIFO 队列条目（id、文本预览、来源[feishu|panel|a2a|supplier-job]、入队时间、关联的 chatMessageId）。入队=tryLock 失败时登记；出队=拿到锁即将写 stdin 时标记 consumed 并广播。注意当前锁非公平，**必须自行 ticket 保序**，否则显示顺序和实际消费顺序不一致
* R2 **队列快照 API**：`GET /api/instances/{id}/queue`（排队中列表 + 当前执行中的一句话摘要）；消费/入队事件经现有 SSE 通道推送增量
* R3 **状态贯通**：FeishuBotService 落库 user 消息时置 `status=QUEUED`（ChatMessage 加列，默认 LIVE 兼容历史行），该条被真正消费时更新为 `LIVE` 并关联 chatMessageId → 供前端过滤与动画锚点。ClaudeCodeService.chat → TerminalSession.sendMessage 需透传 externalId（新增参数或上下文对象）
* R4 **前端交互**：
  * 输入框左移，右列固定宽排队卡片：每行=时间+来源图标+内容摘要；**最新队头在顶**
  * 消费事件 → 队头行高亮收缩消失（CSS transition）→ 左列对话流对应气泡以"刚发出"样式出现（轻微高亮渐隐）
  * 左列不再显示 QUEUED 态消息（仅排队面板可见），避免重复
  * 空队列时右列显示"畅通：没有排队的消息"+ 当前轮已跑时长提示
* R5 **失败/边界**：排队中实例进程死掉/轮次报错 → 队列面板该条标红"已丢弃/异常"，不能静默吞消息；后端重启队列清空（内存态，可接受，面板文案说明）

## Acceptance Criteria (草案)

* [ ] 飞书快速连发 3 条：右列出现 3 条排队（顺序=入队序），左列只见已消费的
* [ ] 逐条消费：队头消失动画 → 左列对应气泡出现且带新消息高亮
* [ ] 面板数据 ≤2s 内与真实队列一致（SSE 推送或轮询兜底）
* [ ] 排队消息不因异常丢失可见性（标红保留或明确提示）
* [ ] 历史消息接口/飞书卡片/员工渠道行为不回退（ChatMessage.status 默认 LIVE）

## Out of Scope（建议，待确认）

* 排队消息的撤回/取消/调整顺序（需要中断阻塞线程，复杂度高，二期）
* 多实例总览页的全局排队墙（本任务只做单实例对话页）

## Open Questions（执行前收敛）

1. 排队面板覆盖范围：**只飞书来源**还是全部来源（工作台对话区/A2A/面板发送同走一个会话锁）？推荐全部（本质是会话级队列），但面板发送的消息左列一直可见、右列短暂重复——可接受？
2. 排队中消息在左列：**完全隐藏**（用户原述）vs 灰色占位气泡？推荐隐藏（与右列动线一致）
3. 是否需要"取消排队"按钮（若 Out of Scope 砍掉就明确不做）？
4. 消费顺序显示：ticket 强制 FIFO（推荐，所见即所得）vs 跟随锁的非公平顺序（显示会跳）？
5. ChatHistory 顶部或右列要不要显示"当前这轮已经跑了多久/正在用什么工具"（数据现成：activityPhase/currentToolName）？顺手增益

## Technical Notes

* 改造点清单：
  - `TerminalSession.java`：QueueEntry 记录 + deque 维护 + consumed 广播回调（listener 已有 onQueued 扩展或新增 `Runnable onDequeued` / 会话级 `QueueListener`）
  - `ClaudeCodeService.chat(...)`：签名加 externalId（或消息上下文对象），`FeishuBotService`(344/400/481)、`SupplierJobService.deliverToInstance/ask`、`E2E/A2A` 调用点适配
  - `ChatMessage` 实体：status 列（默认 LIVE），消费回调里 FeishuBotService/A2A 更新为 LIVE
  - `StreamingCardController`：新增 queue 快照端点 + SSE 事件类型（现有 onQueued 链路扩展）
  - `ChatHistory.jsx`：布局改造（输入左移+右列面板）、SSE 订阅、动画、按 status 过滤
* 内存队列重启即空 = 排队消息会"卡在锁等待线程"随进程死亡一起丢——现状本就如此（sendMessage 阻塞线程是后端内存态），面板文案说明"重启后未消费消息请重发"
* 兼容：ddl-auto:update 加 status 列，旧行 NULL 用包装类型/默认值处理（吸取 10-30 codegen_prep 的 NULL 炸 500 教训——**新列必须 NULL-safe**）
