# Feishu Bot Admin Panel — Design Spec

## Overview

将现有纯 Java 项目重构为 Spring Boot 单体应用，新增 React SPA 前端管理面板。飞书 bot 和 Web 管理后台运行在同一 Spring Boot 进程中。

## Goals

1. Spring Boot 管理应用生命周期（bot 启动/重启/配置热更新）
2. React 前端管理面板：配置管理 + 连通性测试 + 实时日志
3. H2 文件数据库持久化配置
4. 保留现有功能：Shell 终端 + Claude Code CLI 桥接 + 飞书消息处理

## Architecture

```
┌─────────────────────────────────────────────────┐
│              Spring Boot Application             │
│                                                  │
│  ┌──────────────┐  ┌─────────────────────────┐  │
│  │ REST API     │  │ Background Services     │  │
│  │ /api/config  │  │ FeishuBotService (WS)   │  │
│  │ /api/test/*  │  │ ShellService            │  │
│  │ /api/logs    │  │ ClaudeCodeService       │  │
│  └──────┬───────┘  └──────────┬──────────────┘  │
│         │                     │                  │
│  ┌──────┴───────┐  ┌──────────┴──────────────┐  │
│  │ React SPA    │  │ H2 File Database        │  │
│  │ (static/)    │  │ (./data/config.mv.db)   │  │
│  └──────────────┘  └─────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

## Backend

### Dependencies (pom.xml)

- `spring-boot-starter-web` — REST API + 静态资源
- `spring-boot-starter-data-jpa` — JPA + H2
- `spring-boot-starter-validation` — 请求校验
- `h2` — 嵌入式数据库（runtime scope）
- 保留现有依赖：`oapi-sdk`、`okhttp`、`gson`、`logback`

### Package Structure

```
com.lark.oapi.sample.ws
├── Application.java              # @SpringBootApplication 入口
├── config/
│   ├── AppConfig.java            # JPA 实体（单行配置）
│   └── AppConfigRepository.java  # Spring Data JPA Repository
├── controller/
│   ├── ConfigController.java     # GET/PUT /api/config
│   ├── TestController.java       # POST /api/test/feishu, /api/test/claude
│   └── LogController.java       # GET /api/logs (SSE), /api/logs/history
├── service/
│   ├── FeishuBotService.java     # WebSocket 长连接管理
│   ├── ShellService.java         # 本地 Shell（从现有重构）
│   ├── ClaudeCodeService.java    # Claude Code CLI（从现有重构）
│   └── LogService.java           # 日志聚合 + SSE 推送
└── dto/
    ├── AppConfigDTO.java         # 配置传输对象（secret 脱敏）
    ├── TestResultDTO.java        # 测试结果
    └── LogEntry.java             # 日志条目
```

### Data Model — AppConfig (JPA Entity)

单行配置表，固定 id=1：

| 字段 | 类型 | 默认值 | 说明 |
|---|---|---|---|
| `id` | Long | 1 | 主键 |
| `feishuAppId` | String | "" | 飞书 APP ID |
| `feishuAppSecret` | String | "" | 飞书 APP Secret |
| `authorizedOpenId` | String | "" | 授权用户 openId |
| `shellWorkingDir` | String | 用户目录 | Shell 工作目录 |
| `claudeCmdPath` | String | "claude" | claude 命令路径 |

H2 数据库文件：`./data/config`（H2 自动加 `.mv.db` 后缀）

### REST API

#### `GET /api/config`
返回当前配置。`feishuAppSecret` 脱敏显示（仅显示前4位 + `****`）。

#### `PUT /api/config`
保存配置。接收完整配置 JSON，保存到数据库。
- 如果飞书凭证变更，热重启 FeishuBotService
- 如果 shellWorkingDir 变更，重建 ShellService
- 返回更新后的配置

#### `POST /api/test/feishu`
测试飞书连通性：
1. 用当前配置的 appId/appSecret 创建临时 Client
2. 尝试调用一个简单的飞书 API（如获取 bot 信息）
3. 返回 `{ success: boolean, message: string }`

#### `POST /api/test/claude`
测试 Claude Code CLI：
1. 执行 `claude -p "hi" --output-format json --bare`
2. 解析响应，检查 `is_error`
3. 返回 `{ success: boolean, message: string, response?: string }`

#### `GET /api/logs` (SSE)
Server-Sent Events 流，推送实时日志。格式：
```
data: {"timestamp":"...","level":"INFO","logger":"...","message":"..."}
```

#### `GET /api/logs/history`
返回最近 100 条日志（内存环形缓冲区）。

### Service Lifecycle

- `FeishuBotService`：`@EventListener(ApplicationReadyEvent)` 启动 WebSocket
  - `start()` — 建立连接
  - `stop()` — 断开连接
  - `restart()` — 配置变更时调用
- `ShellService`：`@PreDestroy` 销毁 shell 进程
  - `setWorkingDir(path)` — 设置工作目录（重建 shell 进程）
- `ClaudeCodeService`：无状态，每次调用创建新进程

### Log Streaming

自定义 Logback Appender（`SseLogAppender`），将日志事件推送到 `LogService` 的环形缓冲区。`LogService` 通过 `SseEmitter` 推送到前端。

## Frontend — React SPA

### Tech Stack

- React 18 + TypeScript
- Vite 构建
- 纯 CSS（不用 UI 库），手写组件保持轻量
- 构建输出到 `src/main/resources/static/`

### UI Design — DeepSeek Harness 风格

- **深色主题**：背景 #1a1a2e / #16213e，卡片 #0f3460
- **配色**：主色 #e94560（强调），辅色 #53d8fb（信息）
- **布局**：左侧导航栏 + 右侧内容区
- **卡片式内容**：圆角卡片，轻微阴影
- **字体**：系统字体栈，代码用 monospace

### Pages

#### 配置页 `/config`
- 表单字段：APP ID、APP Secret（密码输入框）、授权 openId、Shell 工作目录、Claude 命令路径
- 保存按钮 + 成功/失败 toast
- 目录选择交互：输入框 + 说明文字（浏览器无原生目录选择，用文本输入）

#### 测试页 `/test`
- 两个卡片：飞书连通测试、Claude Code 测试
- 每个卡片：描述 + 测试按钮 + 状态指示（idle/loading/success/error）
- 测试结果展示区

#### 日志页 `/logs`
- 实时滚动日志列表（自动滚动到底部）
- 按级别筛选（ALL/INFO/WARN/ERROR）
- 暂停/恢复按钮
- 每条日志：时间戳 + 级别标签（彩色） + logger 名 + 消息

### Build Integration

Vite 配置 `outDir: '../src/main/resources/static'`。开发时 Vite dev server 代理 API 到 Spring Boot（端口 8080）。

### API Client

简单封装 `fetch`，不用 axios 等库：
```typescript
const api = {
  getConfig: () => fetch('/api/config').then(r => r.json()),
  saveConfig: (config) => fetch('/api/config', { method: 'PUT', body: JSON.stringify(config) }),
  testFeishu: () => fetch('/api/test/feishu', { method: 'POST' }).then(r => r.json()),
  testClaude: () => fetch('/api/test/claude', { method: 'POST' }).then(r => r.json()),
  logsHistory: () => fetch('/api/logs/history').then(r => r.json()),
}
```

SSE 用原生 `EventSource('/api/logs')`。

## Migration Plan

现有代码 → Spring Boot 的映射：

| 现有 | 目标 | 改动 |
|---|---|---|
| `Sample.java` main() | `Application.java` + `FeishuBotService` | 拆分为入口 + 服务 |
| `Sample.java` 消息处理 | `FeishuBotService` | 提取为 @Service |
| `Sample.java` 硬编码配置 | `AppConfig` entity | 从数据库读取 |
| `ShellService.java` | `ShellService.java` (@Service) | 加 workingDir 配置 |
| `ClaudeCodeService.java` | `ClaudeCodeService.java` (@Service) | 加 cmdPath 配置 |
| `ClaudeService.java` | 已删除 | — |
| `replyText()` 等工具方法 | `FeishuBotService` 内部方法 | 移入服务类 |

## Verification

1. `mvn clean package` 编译通过
2. `java -jar target/feishu-claude-v2-1.0-SNAPSHOT.jar` 启动
3. 浏览器访问 `http://localhost:8080` 看到管理面板
4. 配置页填写飞书凭证 → 保存 → 测试连通 → 成功
5. 飞书发消息 → bot 回复（Shell 模式）
6. 日志页看到实时日志
