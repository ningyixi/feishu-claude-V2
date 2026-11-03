# 实例会话持久化：后端重启自动 --resume 接回上下文（一实例多会话登记）

> 状态：仅创建，未执行。

## 背景与事故实录

2026-10-29/30 实际发生：用户私聊实例 7 干活 → 期间后端多次重启部署新功能 → 用户在群里 @ 机器人，机器人回答"本次会话从头到尾就是 nihao"——**上下文全丢**。
根因：会话上下文活在"后端 JVM 派生的 CLI 子进程"里；sessions map 是纯内存，JVM 一死全清。现有 `--resume` 机制（TerminalSession.java:286 inheritSessionId、60-62 RESUME_ON_RESTART、185-188 失败降级）只救"**进程死了但后端还活着**"，救不了"**后端重启**"——因为 claudeSessionId 从未落库，新 JVM 无处可查。

## Goal

用户拍板的产品语义：
1. 实例的对话连续性是**会话资产**，不能因后端重新部署而断裂：重启后再对话该实例，应自动接回最近一次 session；
2. **一个实例对应多个逻辑会话**（主会话 + 数字员工需求 lane + 工作台 supplier-job 复用主会话 + A2A…），这些"实例内会话 → claude session_id"的映射要**持久化**。

## Requirements

* R1 新表 `claude_session_registry`：`(id, instance_id, lane_key, claude_session_id, working_dir, model, sandbox_level, last_used_at, created_at)`；lane_key 用内存同键（主会话固定 `"__main__"`，lane 用其 lane 字符串）。唯一约束 (instance_id, lane_key)
* R2 写入时机：CLI init 事件捕获 claudeSessionId 成功时 upsert；进程正常/异常退出路径不依赖（init 后已有值）
* R3 读取时机：`getOrCreate`（主会话）与 `chatInLane` 首次创建 lane 时，内存无活会话 → 查登记表命中则按现有 inherit 模式带 `--resume <id>` 起进程；**cwd 必须与登记时一致**（resume 依赖同项目 hash 目录），不一致视为未命中
* R4 降级安全网：复用现有"resume 启动即退出 → 全新会话"逻辑（TerminalSession.java:185-188），并把新 session_id 更新回登记表（自愈）
* R5 `/new` 语义：删除对应 (instance_id, lane_key) 登记行——重置是真·新会话，不能下次重启又幽灵接回
* R6 配置变更语义：模型/沙箱档变更本来就重建会话——保持"重建即新 session 并覆盖登记"；**工作目录变更**同样覆盖登记（cwd 变了旧 session 接不回是正确行为）
* R7 保留策略：每 instance 保留最近 N 条（建议 10）lane 登记，超出按 last_used_at 淘汰；启动时不做全量扫描（按需查询即可）
* R8 数字员工身份令牌 env 变化导致 lane 重建的场景：重建后覆盖登记（身份 env 不影响 resume 的会话本体）

## Acceptance Criteria

* [ ] 复现实录场景：私聊对话若干轮 → 重启后端 → 再私聊/群 @ 问"我们刚才聊了什么" → 能正确接回（--resume 生效，日志可见"重启并恢复会话上下文"）
* [ ] 员工需求 lane：完成需求后重启后端，追问该需求 → 接回原 lane 上下文而非全新
* [ ] resume 目标被删除/损坏（手动删 ~/.claude/projects 下 jsonl）→ 自动降级全新会话且登记表更新，用户侧只看到回复正常
* [ ] /new 后再对话是新会话；重启后端后也不会接回 /new 前的旧 session
* [ ] 改模型/改目录/改沙箱档 → 新会话正常覆盖登记，无脏接回
* [ ] 同 lane_key 并发首条消息不产生双进程（沿用现有锁）
* [ ] mvn test 新增注册表读写/降级用例；不新增既有失败

## Definition of Done

* 单测：registry upsert/查询、cwd 不匹配视为未命中、/new 清除、resume 失败更新登记（mock TerminalSession 行为或纯 repository 层测试）
* 冒烟实录：真实重启后端 + 飞书对话验证接回（用户可见证据：backend.log 的 resume 行）
* 文档：Hooks/实例页无 UI 变化（纯后端能力），CHANGE 说明写入 PRD 附录

## Technical Notes（触点已核查）

* `TerminalSession.java`：`claudeSessionId` 字段 + init 事件捕获（已有）；`inheritSessionId(String)`(286)；`RESUME_ON_RESTART`(60-62)；start() 组 `--resume`(148-150)；启动即退降级(185-188/600)
* `ClaudeCodeService.java`：`sessions: Map<Long,TerminalSession>`(26)、`getOrCreate`(133 sessionKey=instanceId+dir)、`lanes: Map<String,TerminalSession>`(155)、`chatInLane`(174)、`newSession`(73)——**R2/R3 的读写都收口在这一个 service**：起会话成功 init 后 upsert registry；查 registry 命中 → 构造 TerminalSession 后立即 inheritSessionId(持久化id) 再 start()
* `FeishuBotService` handleCommand 的 /new 分支 → 调 ClaudeCodeService.newSession 时同步删登记（R5）
* 新实体/仓库按项目惯例：entity/model + repository（H2 ddl-auto:update；**新列一律 NULL-safe 包装类型**——10-30 教训）
* claude CLI 的会话 jsonl 本身已落盘（~/.claude/projects/<cwd hash>/），我们要持久化的只是"**该接回哪一个 id**"
* 风险：jsonl 无限增长的会话 resume 后首响应变慢（上下文巨大）——属既有机会成本，不做处理（工作台有卡点协议兜底）；跨实例误接回——查询强制 instance_id+lane_key 双键
* 兼容性：老库首启无登记行=现状行为（全新会话），渐进采纳无迁移成本

## Out of Scope

* 对话消息级持久化（ChatMessage 历史已有，本任务只保"模型侧上下文"连续性）
* resume 失败自动重放历史消息重建上下文（成本高且失真，不做）
* 队列/排队可见性（10-30-instance-queue-panel 已做）
* 前端 UI 展示"已接回上次会话"提示徽标（可选增益，等实测稳定后另议）

## Open Questions（执行前确认）

1. 保留 N=10 lane 合适吗（员工数×需求数峰值）？
2. 群聊是否要与私聊共享主会话（现状共享）——持久化后仍共享，确认符合预期？
3. 要不要在实例状态接口暴露"当前 claudeSessionId + 上次接回时间"方便你自己观察？
