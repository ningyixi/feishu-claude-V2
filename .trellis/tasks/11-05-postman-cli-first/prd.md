# 实测协议话术 CLI 优先

## 背景
11-04 双通道后，agent 执行时仍按文序先检查 MCP(必不存在)→绕弯再落 CLI。本机 MCP 注册链路确认不通，话术应现实优先。

## 改动(仅文案，两处于 SupplierJobService)
1. ACTION_SPEC 第6条: "走 Postman 合规通道——首选本机 Postman CLI(postman collection run,已装已登录,勿加--silent)；仅当工具清单真实存在 Postman MCP 时可用 MCP 替代。自由 curl 不算合规" — Collection/SUPPLIER_EVIDENCE 要求不变。
2. n2 种子 prompt: "用 Postman CLI(postman collection run) 真实调用（MCP 存在时可替代；均不可用输出【卡点】）"。

## 验收
- [ ] 两处文案生效(编译通过 javac 临时目录)；grep 无"必须经 Postman 插件"残留
- [ ] 不改门禁/证据逻辑(11-04 已双通道)；下次重启生效(本任务不重启,用户有任务在跑)

## Status (2026-11-05)
两处话术已改+附带修掉残留"插件事件"措辞; javac 临时目录验证通过(未跑mvn/未重启,用户任务在跑)。下次后端重启生效。
