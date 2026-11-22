# 临时需求：产出配置中心可直接粘贴的供应商配置 JSON

## 用户诉求（2026-11-18 原话）
「新开发一个供应商，还没有配置配置文件的时候，可以实例发信息给我写出 json 配置文件，我复制拿到公司的配置中心。」并提供了真实模板（下方，**凭据已打码**）。

## 模板（配置中心 key 形态：`SS.<供应商标识>.Config`）
```json
{
  "SupplierID": 2226,
  "IsEnableAntErrType": true,
  "Urls": {
    "hotelcodesUrl":  "https://<网关>/static/hotelcodes",
    "hotelinfosUrl":  "https://<网关>/static/hotelinfos",
    "hotelsearchUrl": "https://<网关>/open/avail/hotelsearch",
    "precheckUrl":    "https://<网关>/open/avail/precheck",
    "createUrl":      "https://<网关>/open/booking/create",
    "searchUrl":      "https://<网关>/open/booking/search",
    "cancelUrl":      "https://<网关>/open/booking/cancel"
  },
  "AccountDic": {
    "2996": { "SupplierAccountID": 2996, "AccountName": "instantConfirm",
              "appid": "<APPID>", "secret": "<REDACTED>", "customerCode": "<CUSTOMER_CODE>",
              "paymentType": 1, "ConfirmMode": "INSTANT" },
    "3004": { "SupplierAccountID": 3004, "AccountName": "onRequest",
              "appid": "<APPID>", "secret": "<REDACTED>", "customerCode": "<CUSTOMER_CODE>",
              "paymentType": 1, "ConfirmMode": "ONREQUEST" }
  }
}
```
**结构要点**：顶层 4 个键（`SupplierID` / `IsEnableAntErrType` / `Urls` / `AccountDic`）；`Urls` 的 7 个键名与大小写固定；`AccountDic` 是**以 SupplierAccountID 字符串为 key 的字典**，实际惯例是 INSTANT / ONREQUEST 成对两账号、共用同一组 appid/secret/customerCode 而 `ConfirmMode` 不同（用户样例即如此）。

## 字段来源规则（这是本功能的骨架，不许放宽）
| 字段 | 来源 | 拿不到时 |
|---|---|---|
| `Urls.*` | **本任务已入库的供应商接口文档 / Wiki** | 输出 `【卡点】` 问，**禁止编 URL** |
| 文档里没有的端点（如 `hotelcodesUrl`） | 同前缀推断 | 可填推断值，但必须在代码块外**逐条标注"此条为推断，请确认"** |
| `SupplierID`、`SupplierAccountID` | 配置中心分配，agent **不可能知道** | **必须 `【卡点】` 问，禁止编造数字** |
| `appid` / `secret` / `customerCode` | 凭据 | **绝不生成、绝不猜测、绝不留存**：只输出占位符 `<在此填入 secret>`，并附一句"凭据请从配置中心/密钥库获取，不要粘贴进对话" |
| `paymentType` / `ConfirmMode` / `IsEnableAntErrType` | 文档或既有约定 | 有依据就填并写明依据；无依据 `【卡点】` 问 |

`【卡点】` 协议、待确认卡、飞书主动通知全部**复用 `11-17-adhoc-blocker-notify` 已实现的机制**，本任务不新造第二套。

## 输出形态（AC 核心）
1. **一个** ```json 代码块，结构完整可直接粘贴；**不得拆成多段**，**不得往 JSON 里写注释**（JSON 不支持），说明写在代码块外。
2. 代码块带**「复制」按钮**，整块原文进剪贴板，不依赖鼠标拖选（与"文字可选中复制"是同一诉求的两半，可共用实现）。
3. 同时把这份 JSON **主动发到绑定实例的飞书私聊**（复用 `notifyUser`）—— 用户的场景就是"人不趴工位也能把配置拿走"；推送失败必须在页面可见（沿用既有 `notify_error` 语义）。
4. **不做新按钮、不做新表单、不加字段**：触发方式就是对话框里说"给我这个供应商的配置 JSON"。系统侧只负责把模板骨架与来源规则作为上下文喂进去。

## 模板放哪（用户未指定，这是**我的决定**，可推翻）
仓库内置一份**打码模板**作为权威 schema（`docs/` 或 resources 下均可，按 `.trellis/spec` 惯例选），每轮对话随 wikiHeader 一起注入"模板骨架 + 字段来源规则"；单个任务可在文档来源里粘贴自己的样例配置来**覆盖**内置模板。
**凭据红线**：真实 `secret` 不进仓库、不进日志、不进 DB、不在前端回显。用户若自己在对话里粘了 secret，落库前按规则打码（或原样存但永不回显/不写日志）—— 实现时二选一并说明取舍。

## 明确不做
不接配置中心 API、不自动下发、不做多环境（dev/prod）、不做配置 diff/审核流、不做校验器之外的 schema 校验框架。

## 验收（草，交给实现时细化）
- AC1 在 Wiki 就绪的任务里说"给我 XX 的配置 JSON"，输出**可被 `JSON.parse` 解析**的单块 JSON，且顶层键与 `Urls` 七个键名逐层匹配模板。
- AC2 缺 `SupplierID` 时绝不编数字：出 `【卡点】` + 待确认卡 + 飞书通知。
- AC3 产物中不含任何真实凭据明文（模板打码、生成值只能是占位符）；日志/DB/响应体均无 secret。
- AC4 「复制」按钮复制内容 == 代码块原文（前端测试或 Playwright 断言 clipboard）。
- AC5 飞书私聊能收到这份 JSON；实例未连接时页面显示失败原因。
- AC6 措辞纪律禁词测试通过；全量测试红数不超过基线（当前基线 `Tests run: 266, Failures: 3, Errors: 1`，4 条均为 Windows/temp 环境红）。

## 依赖与排序
依赖 `11-17-adhoc-blocker-notify`（卡点协议 / 待确认卡 / notifyUser 失败可见 / 文字可选中）先落地，本任务在其之上做。两者都改 `AdHocService` 与 `AdHoc.jsx`，**必须串行**，不可并行。
