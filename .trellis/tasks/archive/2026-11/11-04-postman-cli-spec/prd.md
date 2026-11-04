# 实测通道对齐 Postman CLI（双通道）

## 背景
本机实况：**Postman CLI 1.86.0 已装且已登录**（`~/.postman/sessions`），全局 CLAUDE.md 明示走 CLI；插件版 Postman MCP 在该环境注册链路不通（/mcp 恒空）。而工作流的实测证据门禁 `SupplierProbeEvidence.isExecutionTool` **只认 MCP 工具名**，n2/ACTION_SPEC 文案写死"必须经插件"——通道与现实错位。

## 改动
1. `SupplierProbeEvidence.isExecutionTool(name, input)`：新增 Bash 通道——工具=Bash 且 command 含 `postman collection run`/`postman run` 视为合规执行工具；**裸 curl 仍不合规**（MCP 通道判定保留）。
2. `TerminalSession:691` 调用点传入 tool input。
3. ACTION_SPEC 第 6 条：改为"Postman MCP **或** 本机 Postman CLI 任一可用通道"，`SUPPLIER_EVIDENCE` pm.test 要求保留（CLI=newman 内核，cli reporter 同样打印测试名，证据可采）；删"仅插件/降级 newman"表述。
4. n2 种子 prompt 同步双通道；门禁报错文案补"(MCP 或 CLI)"。
5. CLI 用法附在规范里：`postman collection run <file> -e <env> `（勿加 --silent，否则证据行不可见）。

## 验收
- [ ] isExecutionTool：MCP runCollection=true；Bash+postman collection run=true；Bash+curl=false；Read=true? 否
- [ ] 新单测覆盖上述 4 组合；全量无新增失败
- [ ] grep 无残留"必须经 Postman 插件(MCP)"/"降级 newman"
- [ ] 实测节点在仅 CLI 环境下能过证据门禁（用户跑节点实测）

## 不重启承诺
改完 mvn 测试后重启（当前无 RUNNING 则执行）。

## Delivery (2026-11-04)
双通道落地：isExecutionTool(name,input) 接受 MCP 工具与 Bash+`postman collection run`（裸 curl/Read 仍拒）；ACTION_SPEC 第6条与 n2 种子改"CLI 或 MCP 任一可用+勿 --silent"；门禁文案标注双通道；通道单测已加。附带修复：启动清扫孤儿 RUNNING 节点（本次误停事故触发，任何重启后节点不再卡执行中）。n2 已复位 PENDING 可重派。
