# 修复：飞书卡片终态更新失败 + 假成功吞掉兜底 → 回复看不见

## 现象（用户 2026-11-24）
「我现在在飞书发送信息给实例，没有回复了」→ 随后他说"看错了"。**但他的感觉有依据**：这是间歇性的，只有**答案带可选项**的那几轮会出问题。

## 现场证据（`D:/feishu-claude-data/run/backend.log`，只读取得）
```
10:48:14.272 [Chat][feishu-DCYiLv] Claude 轮次完成 (449字)        ← 模型确实答完了
10:48:14.943 WARN [Card] 终态 card.update 失败 code=200861
   ErrPath: ROOT -> elements -> [4](tag: action)
   ErrMsg: cards of schema V2 no longer support this capability   ← 病灶一
10:48:15.318 WARN [Card] im.message.patch 失败 code=230099（回退也被同一原因拒）
10:48:15.319 INFO [Card] 终态更新完成: phase=COMPLETED, 449字      ← 病灶二：假成功
```
计数：**`终态 card.update 失败` 4 次、`im.message.patch 失败` 4 次，而 `终态更新完成` 打了 8 次。**

## 根因（两条叠加，缺一不可地造成"看不见"）
1. **`CardBuilder.java:147` 与 `:565` 往卡片里加 `tag:"action"` 元素**，飞书卡片 schema V2 不再支持（`200861 unsupported tag action`）。只在答案里检测到可选项时才走这条路 → 间歇性。
2. **`StreamingCardController.java:414-416` 不看 `patchMessage` 的结果就 `done = true`** → `:420` `messageSent = true` → `:91` 注释里承诺的"什么都没发出去就回退普通 `replyText`"这条保险丝**被假成功骗过、永不触发** → 内容既不进卡片也不发文本；而 `:421` 的 `log.info("终态更新完成")` 打在 `if (done)` 外面，无论真假都报完成。

## 范围
- **C1 干掉不支持的元素**：`CardBuilder` 两处 `tag:"action"` 改为 schema V2 支持的表达（默认方案：**可选项降级成 markdown 正文列表**，如 `**可选：** 1) … 2) …`，不保留交互控件 —— 用户本来就在文本框里回复，按钮不是必需）。若坚持要交互控件，必须先给出 V2 支持该形态的证据（官方文档/成功响应样例），**不许猜**。
- **C2 送达判定必须真实**：`patchMessage` / `closeStreamingAndUpdate` / `sendInteractive` 一律**返回是否真的成功**；`done`、`messageSent` 只能由真实成功置位。任一终态路径失败 → **走普通文本 `replyText`/`im.message.create` 兜底把答案正文发出去**，宁可丑、不可没有。
- **C3 日志如实**：终态失败改 `log.warn/error`，内容含：失败阶段（update/patch/兜底文本）、错误码、卡片元素路径、以及"已回退文本 / 回退也失败"。禁止再出现"什么都没送达却记 完成"。
- **C4 兜底必须被验证过**：兜底路径不能只存在于代码里 —— 必须有测试构造"卡片更新与 patch 全失败"的场景，断言**最终有一条普通文本消息携带完整答案正文被发出**。

## 验收
- AC1 卡片 JSON 组装结果里不再出现 `tag":"action"`（静态断言 + 单测断言），带可选项的长答案也能正常产出终态卡 JSON。
- AC2 mock 三种失败组合（update 失败+patch 成功 / update+patch 全失败 / 全失败且文本兜底成功），断言 `messageSent` 只在真有送达时为 true，且第二种情况下**确实发出文本兜底且正文完整**。
- AC3 `patchMessage` 失败时日志出现 warn/error 且**不再有**「终态更新完成」；成功时才打「完成」。
- AC4 现网验证：主会话部署后由用户或我发一条会带选项的消息，日志出现 0 次 200861，且卡片落到终态。
- AC5 全量 `mvn test` 红数不超过基线 `422 跑 / 3 失败 / 1 错误 / 1 跳过`（4 条为既有 Windows 伪脚本与 temp 环境红）；`node --test`/`npx playwright test`（零环境变量）不得新增失败。

## 明确不做
不改卡片的流式中间帧节流策略与卡片模板结构的其他部分；不动 `Eval*`、知识库、工作流代码；不引入新依赖；不把"降级为纯文本回复"做成全局默认（只在终态失败时兜底，正常情况仍是卡片体验）。

## 排序
P0，排在 `11-23-adhoc-append-sources` 之后立即做（那个已交工并部署）。发布由主会话统一：自验 → 提交 → 打包 → 脱离会话重启。
