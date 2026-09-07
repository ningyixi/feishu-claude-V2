# 多实例隔离与 Session 上下文管理：技术方案总结

> 项目：feishu-claude-V2 —— 飞书机器人 × Claude Code CLI（Java 21 / Spring Boot 3.4）
> 姊妹篇：《流式方案重构——从超时猜测到事件驱动》

---

## 一、多实例隔离方案

### 1.1 要解决的问题

一个 Java 进程要同时承载 **N 个互相隔离的飞书机器人**，每个机器人：

- 有自己的一套飞书应用凭证（appId/appSecret）
- 有自己的授权用户（authorizedOpenId）
- 有自己独立的 Claude 工作目录和模型配置
- 有自己独立的对话上下文，互不串扰

典型用法：机器人 A 挂"项目甲"的代码目录用 sonnet，机器人 B 挂"项目乙"的目录用 opus，两者同时在线、各聊各的。

### 1.2 核心设计：一切资源以 instanceId 为键

**数据模型**（`BotInstance` 实体，H2 持久化）：

```
bot_instance
├─ id                  ← 隔离键（所有运行时 Map 都用它索引）
├─ name                ← 展示名（日志前缀 [渠道1]）
├─ feishuAppId/Secret  ← 独立飞书应用凭证
├─ authorizedOpenId    ← 独立授权用户白名单
├─ claudeWorkingDir    ← 独立 Claude 工作目录
├─ claudeModel         ← 独立模型
├─ shellWorkingDir     ← Shell 模式目录
└─ enabled             ← 启停开关
```

**运行时隔离**（三层，每层一个 `Map<Long instanceId, ...>`）：

```
                    ┌────────────────────────────────────────┐
                    │           Spring Boot 单进程            │
                    │                                        │
 Feishu WS 长连接    │  FeishuBotService.connections          │
 (每实例一条)        │  Map<instanceId, InstanceConnection>   │
                    │    ├─ wsClient   ← 独立 WebSocket 连接  │
                    │    ├─ httpClient ← 独立凭证的 API Client│
                    │    └─ instance   ← 配置快照             │
                    │                                        │
 Claude CLI 进程     │  ClaudeCodeService.sessions            │
 (每实例一个常驻)    │  Map<instanceId, TerminalSession>      │
                    │    └─ 独立子进程：独立工作目录/模型/     │
                    │       stdin流/reader线程/轮次锁/看门狗  │
                    │                                        │
 数据落库           │  ChatMessage.instanceId / UserMode     │
 (按实例打标)        │  (openId+workingDir 复合键)            │
                    └────────────────────────────────────────┘
```

逐层拆解：

**① 连接层隔离** —— 每个实例一条独立的飞书 WS 长连接 + 一个独立凭证构建的 HTTP Client：

```java
// FeishuBotService.connectInstance —— 每实例独立建连
Client httpClient = Client.newBuilder(appId, appSecret).build();   // 凭证隔离
com.lark.oapi.ws.Client wsClient = new Builder(appId, appSecret)
        .eventHandler(handler)    // handler 闭包捕获 instanceId，事件天然带归属
        .build();
wsClient.start();
connections.put(instanceId, new InstanceConnection(wsClient, httpClient, instance));
```

事件回调闭包里直接捕获了 `instanceId`，消息一进来就知道归属哪个实例——**路由在源头完成，后续全链路无需再判别**。

**② 计算层隔离** —— 每个实例一个独立的常驻 Claude CLI 子进程，这是隔离最彻底的一层：

```java
// ClaudeCodeService —— instanceId → 独立 TerminalSession
Map<Long, TerminalSession> sessions = new ConcurrentHashMap<>();
```

每个 `TerminalSession` 内部完全自持，实例之间**零共享可变状态**：

| 资源 | 隔离粒度 |
|---|---|
| OS 子进程（PID） | 每实例一个，`ProcessBuilder.directory(实例的claudeWorkingDir)` |
| stdin/stdout NDJSON 流 | 每进程独立 |
| reader / stderr 虚拟线程 | 每进程各一条（线程名带 sessionKey） |
| 轮次串行锁 `turnLock` | 每实例一把——A 实例排队不影响 B 实例 |
| 看门狗 scheduler | 每实例一个 |
| 对话上下文 | 活在各自 CLI 进程内存里（见第二章） |

进程级隔离的好处：**一个实例的 Claude 卡死/崩溃/被重启，其他实例完全无感**。

**③ 数据层隔离** —— 聊天记录按 `instanceId` 打标；用户模式（Shell/Chat）按 `openId + workingDir` 复合键持久化，同一用户在不同实例（不同目录）下模式互不影响，且重启不丢。

### 1.3 生命周期管理

```
应用启动 (AppInitializer @ApplicationReadyEvent)
  └→ 扫描 bot_instance 表 → 逐个 connectInstance(enabled 的实例)

管理面板改配置 / 手动重连
  └→ connectInstance：先 disconnectInstance(旧连接) 再新建
       ├─ 反射关闭旧 WS：autoReconnect=false → disconnect() → executor.shutdownNow()
       │   （SDK 2.5.3 无公开 close API；不关干净的后果是新旧连接并存、事件重复/丢失）
       └─ ClaudeCodeService.getOrCreate 检测配置变更：
            workingDir 或 model 变了 → destroy 旧进程 → 新配置重建
            没变且进程存活 → 直接复用（上下文保留）

实例的 Claude 进程崩溃
  └→ 下一条消息触发 isAlive()==false → 自动 start() 重建（见第二章：上下文代价）
```

**懒加载**：Claude 进程不在连接实例时启动，而是该实例**第一条聊天消息到达时**才创建（`getOrCreate`），不浪费资源。

### 1.4 权限隔离

```java
boolean allowed = ALLOWED_OPEN_IDS.contains(openId)                    // 全局白名单
        || instance.getAuthorizedOpenId().equals(openId);              // 实例级白名单
```

每个实例只响应自己授权的用户；卡片回复用**该实例自己的 httpClient** 发送（`StreamingCardController` 绑定的 Client 来自 `connections.get(instanceId)`），凭证不混用。

### 1.5 诚实的边界：目前隔离不彻底的地方

| 缺口 | 现状 | 影响 |
|---|---|---|
| ShellService 是全局单例 | 实例配置的 `shellWorkingDir` 没传给它，所有实例共用一个全局 shell 目录 | 多实例的 Shell 模式实际不隔离 |
| 同实例多用户共享一个 Claude 进程 | 隔离粒度是"实例"不是"用户" | 同实例两个授权用户的对话上下文互相可见 |
| `/ws/terminal` 网页终端 | 连的是全局裸 shell，不分实例 | 管理面板功能，非飞书链路 |
| 日志流 | LogService/SSE 全局混流，靠 `[实例名]` 前缀区分 | 面板日志页无法按实例过滤 |

改进方向：ShellService 按 instanceId 池化（复制 ClaudeCodeService 的模式）；需要用户级隔离时把 sessions 的键从 `instanceId` 改成 `instanceId:openId`。

---

## 二、Session 对话上下文方案

### 2.1 方案演进史（三个版本）

```
V1  每条消息起一次性进程          claude -p "消息" → 拿 stdout → 进程退出
    ✗ 无上下文：每条消息都是全新会话，Claude 不记得上一句

V1.5 sessionId 持久化 + --resume  捕获 CLI 输出的 session_id 存库（ClaudeSession 表：
                                   openId+workingDir → sessionId），
                                   下一条消息用 claude --resume <id> -p "消息"
    ✓ 有上下文了，但每条消息冷启动一次进程（数秒开销），
      且 -p 一次性模式无法流式

V2  常驻进程「终端池」（当前方案）  每实例一个长驻 claude 进程，
                                   stream-json 双向 NDJSON 通信，
                                   上下文活在进程内存里
    ✓ 零冷启动、天然流式（本次重构的 --include-partial-messages 就建立在此之上）
    ✗ 进程死亡 = 上下文丢失（见 2.4 的恢复方案）
```

V1.5 的 `ClaudeSession` 实体和 Repository 还留在代码库里，但已无任何服务引用（死代码）——它是方案演进的化石。

#### V1.5 复盘：共享 Session 文件引发的"死亡螺旋"（真实事故记录）

V1.5 的上下文完全由**磁盘 session 文件**承载：Claude CLI 把每轮对话写进
`~/.claude/projects/<工作目录哈希>/<session_id>.jsonl`，
`--resume <session_id>` 就是让**一个新进程**读回这个文件恢复上下文。
这意味着：session 文件是全系统的**单写者锁点**，而 V1.5 的架构存在多个进程同时触碰它的可能。

**触发条件**：两个 `claude` 进程同时 `--resume` 同一个 session_id。来源（任一即可）：

- **应用双开**——旧实例忘了杀 / 孤儿 JVM 还在跑 / IDEA 和命令行各起了一份。V1.5 的 `chat()` 虽然加了 `synchronized`，但 **JVM 内的锁跨进程无效**，两个 JVM 各自 spawn 自己的 claude 进程，锁形同虚设；
- **上一条消息的 claude 进程没退干净**（超时被 destroyForcibly 但文件锁未释放、或异常挂起），下一条消息的 resume 又到了。

**然后是三个放大器的连环叠加**（以下代码引自 git `8e743b3` 的 ClaudeCodeService.java）：

```
后来者的 claude 进程等 session 文件锁 → 挂起
  （stdout 不产出、也不退出）
        ↓
放大器① Java 侧管道死锁 —— 超时形同虚设
  byte[] stdoutBytes = process.getInputStream().readAllBytes();  ← 先阻塞读 stdout 到 EOF
  byte[] stderrBytes = process.getErrorStream().readAllBytes();  ← 再读 stderr
  boolean finished = process.waitFor(5, MINUTES);                ← 5 分钟超时写在最后面
  → 子进程挂起时 stdout 永远不到 EOF，readAllBytes 永不返回，
    waitFor(5min) 那行代码永远执行不到 —— "没有超时的超时"
  → 另一形态：stderr 先写满 64KB 管道缓冲区 → 子进程阻塞在写 stderr，
    Java 阻塞在读 stdout，互相等待 = 经典进程管道死锁
        ↓
放大器② chat() 是 synchronized
  → 一个死锁 = ClaudeCodeService 的锁被永久占住
  → 所有用户的所有后续消息排队等锁
  → 表象：整个机器人瘫痪，发什么都没反应
        ↓
放大器③ 重启也修不好
  → 重启 Java 应用不会杀掉它 spawn 的 claude 子进程（孤儿进程存活）
  → 孤儿 claude 仍持有 session 文件锁
  → 新应用起来后第一条 --resume 再次挂起 → "怎么重启都不行"
```

**为什么当时"很麻烦调不好"**：表象是"整体瘫痪 + 重启无效"，根因却藏在用户目录下一个看不见的磁盘文件里（`~/.claude/projects/.../*.jsonl` 的锁）。claude 进程静默等锁、stderr 是空的，Java 日志里什么都看不到；5 分钟超时因为写在 `readAllBytes()` 后面而永远不触发。不 `ps` 出孤儿进程、不知道 CLI 的 session 文件锁机制，几乎无法定位。

**新方案（V2 常驻进程）如何逐一消灭这些死锁因素**：

| V1.5 死锁因素 | V2 的对应解法 |
|---|---|
| 共享磁盘 session 文件（多进程争抢点） | 上下文活在常驻进程内存里，正常链路**不再 --resume**，争抢点整体消失 |
| `synchronized` 跨 JVM 无效 | 每实例单进程 + `turnLock` 轮次串行化；进程内同一时刻只有一轮在飞 |
| `readAllBytes()` 单边阻塞、超时写在读后面 | stdout 由专用虚拟线程阻塞 readLine；stderr 独立虚拟线程**并发排空**；看门狗独立调度（120s 静默 / 30min 硬上限），超时真正可触发 |
| 孤儿子进程存活持锁 | `destroy()`: destroy → waitFor(5s) → destroyForcibly 三段式；看门狗触发的自动重启走同一路径 |
| 一处挂起全局瘫痪 | 每实例独立进程独立锁，挂起只影响本实例，且看门狗自动重启恢复 |

> 注：`--resume` 本身没有错——2.4 规划的崩溃恢复方案还要用它。差别在于新架构里它只发生在**受控时机**（`start()` 重建进程的单线程路径），永远不会出现两个进程并发 resume 同一个文件。
>
> （本节事故链路为基于 V1.5 真实代码的复盘推演；代码证据：`git show 8e743b3:.../ClaudeCodeService.java` —— `synchronized chat()`、`readAllBytes()` 先于 `waitFor(5min)`、DB 查 sessionId 后 `--resume`。）

#### 同一个故事在重构当天又重演了一次：H2 文件锁事故（2026-09-07）

就在本次流式重构的验证过程中，"孤儿进程 + 文件锁"这个 V1.5 的老剧本，换了一个演员又演了一遍：

```
重构验证时，用后台 mvn spring-boot:run 启动了一个测试实例
      ↓
spring-boot:run 会 fork 一个独立的应用 JVM（父进程是 maven，子进程才是应用）
      ↓
验证结束清理时只杀了 maven 父进程
      → fork 出来的应用 JVM 变成孤儿，继续存活
      → 它持有：H2 数据库文件锁（data/config.mv.db）+ 8080 端口
      ↓
开发者手动启动应用 → 启动失败：
   "Database may be already in use: .../config.mv.db"
   "The file is locked: .../config.mv.db [2.3.232/7]"
      ↓
排查：日志只说"文件被锁"，看不到是谁锁的
   → Get-NetTCPConnection 查 8080 端口占用 → Win32_Process 反查命令行
   → 找到孤儿 JVM（spring-boot argfile 特征）→ taskkill 终结 → 恢复正常
```

对照一下，两次事故的结构完全同构：

| | V1.5 session 文件死锁 | H2 文件锁事故 |
|---|---|---|
| 排他文件锁 | `~/.claude/projects/**/*.jsonl`（CLI 的 session 锁） | `data/config.mv.db`（H2 内嵌模式文件锁） |
| 孤儿进程来源 | 重启 Java 应用不会杀掉 spawn 的 claude 子进程 | 杀 maven 父进程不会杀掉 fork 的应用 JVM |
| 表象与根因分离 | "机器人整体瘫痪" ← 磁盘文件锁 | "应用启动失败" ← 看不见的孤儿进程 |
| 日志线索 | 零（claude 静默等锁） | 只报"file is locked"，不报谁锁的 |
| 破局手段 | ps 找孤儿进程 | 端口占用反查 PID → taskkill |

**通用教训**（写进团队的 checklist）：凡是依赖"单进程独占文件锁"的组件（H2 file 模式、CLI session 文件、任何 lockfile），进程管理必须覆盖**完整的派生链**——父进程 → fork 的子进程 → spawn 的孙进程，杀一个不等于杀一窝。清理脚本/流程里要么用进程组（Windows 上 `taskkill /T` 杀进程树），要么按特征（命令行关键字、端口占用）反查全杀。否则锁泄漏一定会在最意想不到的时机重演，而且每一次的表象都和根因隔着一层。



### 2.2 当前方案：常驻进程内的隐式 Session

**核心思想：不自己管理上下文，把上下文完全托管给 CLI 进程。**

```
第一轮:  stdin ← {"type":"user","session_id":"","message":{"role":"user","content":"分析下这个项目"}}
         stdout → {"type":"system","subtype":"init","session_id":"a1b2c3..."}   ← CLI 自己生成 session
                → stream_event/assistant/result ...

第二轮:  stdin ← {"type":"user","session_id":"","message":{..."content":"刚才那个入口类再展开讲讲"}}
         ↑ session_id 恒传空串 = "继续当前会话"，CLI 在同一进程内自动衔接上下文
         stdout → ...（Claude 记得第一轮的所有内容）
```

要点：

1. **进程即 Session**。多轮上下文由 CLI 在进程内存中维护，Java 侧不存、不拼、不重放任何历史——`ChatMessage` 表存的聊天记录仅供管理面板展示，**不参与** Claude 上下文。
2. **session_id 从 `system/init` 事件捕获**，存于 `TerminalSession.claudeSessionId`，当前仅用于 `/sessions` 命令展示（这是 2.4 恢复方案的钩子）。
3. **上下文清空 = 进程重建**。`/new` `/reset` 命令销毁进程再建新的，干净利落；管理面板改 workingDir/model 也触发重建。
4. **轮次串行化保护上下文顺序**（本次流式重构加的）：`turnLock` 保证同一进程内一问一答严格交替——并发消息排队而不是交错写入 stdin，否则 CLI 侧上下文会乱序污染。
5. **进程复用检测**：`getOrCreate` 只在「进程死了」或「配置变了」时重建，其余一律复用，上下文跨消息持续累积。

### 2.3 上下文生命周期全景

```
消息到达 ──→ getOrCreate(instanceId, dir, model)
              │
              ├─ 进程存活且配置未变 ──→ 复用（上下文延续）
              ├─ 配置变更 ──→ destroy + 重建（上下文清空，设计如此）
              └─ 进程已死 ──→ 自动重建（上下文丢失 ⚠）
                     │
              turnLock 排队 → stdin 写 NDJSON → 事件流回调 → result 收尾
                     │
  /new /reset ──→ destroy + 重建（上下文清空，用户意图）
  应用重启 ──→ 所有进程消失（上下文全部丢失 ⚠）
```

### 2.4 已知短板与恢复方案（规划中）

**短板：进程死亡 / 应用重启 = 上下文蒸发。** 常驻进程把上下文锁在了进程内存里，两个 ⚠ 场景用户会明显感知"失忆"。

**恢复方案（CLI 原生支持，改造量小）**：

Claude Code 的 session 本身会落盘（`~/.claude/projects/<目录哈希>/<session_id>.jsonl`），`--resume <session_id>` 可以完整恢复。因此：

```
1. system/init 事件捕获 session_id（✓ 已实现，claudeSessionId 字段）
2. 每次拿到新 session_id → 复活 ClaudeSession 表持久化
   （键：instanceId + workingDir，即 V1.5 的表结构，现成可用）
3. TerminalSession.start() 构建命令时：
   若库里有该实例的 session_id → 追加 --resume <id>
4. 效果：进程崩溃自动重建后无缝续上上下文；应用重启后第一条消息自动恢复
```

配合本次流式重构的事件驱动模型，`--resume` 不影响任何协议行为（init 事件照常、result 照常），属于低风险增量改造。

更进一步的选项（按需）：

- **用户级上下文隔离**：sessions 键改 `instanceId:openId`，每用户独立进程（代价：进程数 × 用户数）
- **上下文观测**：result 事件的 `usage`/`modelUsage` 已带 token 用量，可在卡片 footer 显示上下文水位（openclaw-lark 的 `上下文 x/y (z%)` 即此），提醒用户何时 `/new`

---

## 三、两个方案的关系（一张图收尾)

```
                 instanceId（隔离键）
                        │
     ┌──────────────────┼──────────────────────┐
     │                  │                      │
 连接层              计算层                  数据层
 WS长连接×N      TerminalSession×N       ChatMessage 打标
 HTTP Client×N   （独立CLI子进程）        UserMode 复合键
 凭证隔离             │
               进程 = Session 容器
               上下文托管给 CLI 内存
               turnLock 保证轮次有序
               （规划：session_id 落库
                 + --resume 崩溃恢复）
```

**设计哲学总结**：

1. **隔离键前置**——instanceId 在事件入口（WS handler 闭包）就绑定，全链路透传，不做二次路由。
2. **能托管就不自建**——上下文管理完全交给 CLI 进程，Java 侧零历史拼接；隔离靠 OS 进程边界，比自己搞内存隔离可靠得多。
3. **复用检测 + 懒加载**——进程按需创建、按配置变更重建、其余复用。
4. **演进留痕**——V1.5 的死代码（ClaudeSession 表）恰好是 V2 短板（崩溃恢复）的现成拼图，方案演进时别急着删表。
