# Journal - codex (Part 1)

> AI development session journal
> Started: 2026-11-02

---



## Session 1: 供应商知识与上下文可靠交付

**Date**: 2026-11-02
**Task**: 供应商知识与上下文可靠交付
**Branch**: `master`

### Summary

完成供应商阶段化文本交付、公司 Skill/spec 正文解析、Postman 实测取证门禁、提示词生成与失效校验、Trellis 任务快照。OrbStack 49 项测试、前端构建、REST smoke 与真实 Newman HTTP 取证脚本验证通过。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `16acb7f` | (see git log) |
| `aca4946` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 2: OrbStack MySQL 与供应商 LLM Wiki 双模式

**Date**: 2026-11-03
**Task**: OrbStack MySQL 与供应商 LLM Wiki 双模式
**Branch**: `master`

### Summary

OrbStack 全栈运行及 MySQL/H2 迁移备份工具已提交。完成 GitHub MCP 源码调研、TEXT/WIKI 固定模式、完整来源快照、带引用编译、过期门禁、阶段必读页直接交付、隔离会话及观测用量。80 项默认测试、真实 MySQL 集成（模型桩）及在线 HTTP smoke 全通过，前端与 Docker 构建通过。旧 H2 文件仍在另一台电脑尚未导入；新容器暂无已认证模型实例，真实材料语义质量 A/B 待用户配置后开展。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `febf141` | (see git log) |
| `f78f3c5` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 3: 供应商 Flowable 持续工作流与人工待办

**Date**: 2026-11-03
**Task**: 供应商 Flowable 持续工作流与人工待办
**Branch**: `master`

### Summary

引入 Flowable 7.2 复用 OrbStack MySQL，持久审批/驳回/补信息/上传材料、独立轮询与通知队列、固定飞书答复命令、暂停恢复取消及中断执行人工恢复。保留 TEXT/WIKI 和 Postman/spec/Skill/Trellis 门禁，自动节点派发前准备 Wiki。修复自定义提示词遗漏驳回意见、并发答复事务快照及跨 MySQL catalog 的流程表发现问题。80 默认测试、5 工作流 MySQL 场景、1 真实 HTTP/后台定时器场景、1 Wiki MySQL 回归及在线 Nginx smoke 全通过。生产容器已构建部署并健康运行；模型和飞书边界用测试桩验证，未发送真实通知。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `2e0f8ca` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 4: 工作空间视觉升级与统一浮层交互

**Date**: 2026-11-04
**Task**: 工作空间视觉升级与统一浮层交互
**Branch**: `master`

### Summary

按用户批准的浮层审查及随后明确授权的视觉重设计，升级工作空间壳层、导航、卡片、表单与反馈；抽取原生对话框/抽屉、锚定确认、动态键盘触摸提示、文档阅读器、通知和草稿保护。E2E 创建与固定版本 Wiki 使用完整页面，补充实体 URL、嵌套编辑保护、Markdown 取消恢复与工作流答复草稿。16 Chromium 场景对编译后的生产前端重复执行，共 32 项通过；桌面和触摸截图已检查；生产容器构建部署及 Nginx 浏览器/真实 API smoke 通过。浏览器 API 为桩，无真实模型/飞书或破坏性操作；后端业务约定未改变。完整文件清单见 docs/界面升级文件清单.md。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `d25b8dd` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete


## Session 5: Windows 主力机迁移交接与 Wiki 存储解释

**Date**: 2026-11-04
**Task**: Windows 主力机迁移交接与 Wiki 存储解释
**Branch**: `master`

### Summary

新增 Windows 无 Docker 的 H2→MySQL 迁移交接与 LLM Wiki 存储说明，交叉核对配置/迁移核心/编译和读取代码，记录主数据选择、备份、原生入口尚缺、文件路径、Flowable 表名大小写、验收及回退。明确 Wiki 使用模型编译、MySQL 和磁盘文件，没有向量中间件；保留语义 A/B 验证边界。更新现有文档入口及数据库规范，8 个本地 Markdown 链接和 Trellis context 检查通过；仅文档变更，未执行实际迁移。

### Main Changes

(Add details)

### Git Commits

| Hash | Message |
|------|---------|
| `ebc0bf4` | (see git log) |

### Testing

- [OK] (Add test results)

### Status

[OK] **Completed**

### Next Steps

- None - task complete
