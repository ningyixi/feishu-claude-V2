# smoke-20261113（游由 YOYO）接口映射报告

- 供应商：smoke-20261113（SupplierJob #194）；LLM Wiki 版本 20；来源指纹 `306600a2a12c60b61548bdc977776dcfbb0b76d5969953a558ef6680297d711b`
- 依据材料（全部 Read 完整正文）：Wiki 4 页（api/constraints/question/source）+ raw 证据 `e306.md`
- 唯一登记来源：《接口签名》（Apifox 托管 MD：https://g3iq08vde3.apifox.cn/9086121m0.md ，解析于 2026-11-13T12:34:23）
- **文档范围声明**：该来源仅定义签名机制；除一个酒店搜索签名算例外，不含任何接口的字段枚举、响应结构、错误码（source 页明确归纳，raw e306 全文核实无 truncation）。

## 一、接口映射表

| 业务动作 | 接口(方法+路径) | 关键请求参数 | 关键响应字段 | 备注(鉴权/分页/限频) |
|---|---|---|---|---|
| 同步城市 | **未找到** | — | — | 知识库无城市接口任何记载 |
| 同步国家 | **未找到** | — | — | 同上 |
| 同步酒店(静态信息) | **未找到** | — | — | 仅有"酒店搜索"签名算例端点（见下方补充行），非静态信息同步接口定义 |
| 同步房型 | **未找到** | — | — | 知识库无记载 |
| 同步报价(价格/库存) | **未找到** | — | — | 知识库无记载 |
| 查价(RatePlan) | **未找到** | — | — | 无法确认下方搜索端点是否返回 RatePlan 级数据——文档缺失 |
| 验价(PreBook/CheckRate) | **未找到** | — | — | 知识库无记载 |
| 下单(CreateBooking) | **未找到** | — | — | 知识库无记载 |
| 查单(GetBooking) | **未找到** | — | — | 知识库无记载 |
| 取消(CancelBooking) | **未找到** | — | — | 知识库无记载 |
| （补充）酒店搜索——文档中唯一出现的端点，仅作为签名算例 | `POST /v2/booking/hotels/search?pageSize=20&pageNum=1` | query：`pageSize`、`pageNum`；body：`regionId`、`keyword`（均仅算例值 `{"regionId":"123","keyword":"tokyo"}`，无字段枚举/类型/必填说明） | **文档缺失**（无任何响应结构定义） | 鉴权：4 头签名（见下节）；分页：`pageSize`/`pageNum`（据算例推断）；限频：文档未提及 |

> 10 个要求的业务动作在当前知识库中**全部未找到正式接口定义**；补充行仅为签名算例中顺带出现的端点，不构成可用的接口规格。

## 二、鉴权方式（依据 constraints 页 yoyo-api-signature.md，证据 e306）

- 所有请求必须携带 4 个头：`X-Access-Key`（ak）、`X-Timestamp`、`X-Nonce`、`X-Signature`。
- ak/sk 均须联系游由创建（无自助申请入口）；sk 仅客户端本地保存，严禁入请求。
- 签名原文 6 行按序 `\n` 拼接（末行后无换行）：`YOYO-HMAC-SHA256` / accessKey / timestamp / nonce / raw_query_string（`?` 后原始字符串，不含 `?`）/ `sha256_hex(raw_body)`（小写 hex，即 bodyHash）。
- `signature = BASE64(HMAC_SHA256(sk, signingString))`。
- 边界：无 querystring → 空字符串参与签名；无 body → bodyHash 固定 `e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`。
- **算法已双重核验可复现**：① 本地 Python 重算 bodyHash/空 body 边界值/最终签名，与文档算例逐字节一致；② Postman CLI 沙箱 pre-request 脚本经 postman-echo 回显验签，复现期望签名 `5CDmXPEfEny5efYqpLCTphHC1npnhTcC38qFtji4K9E=`（2/2 断言通过）。

## 三、环境(测试/生产)地址

**文档缺失**。Wiki 全部 4 页与 raw e306 中无任何 host/base URL；唯一 URL 为 Apifox 文档托管地址（非接口地址）。算例仅有路径 `/v2/booking/hotels/search`，无域名与端口。

## 四、文档可疑或滞后之处

1. **覆盖面严重不足**：仅《接口签名》一份文档，10 个核心业务动作零接口定义——无法支撑接入开发，疑似只登记了供应商文档空间的一页（Apifox 空间通常有完整目录，建议核对是否漏登记其余页面）。
2. **无环境地址**：测试/生产 host 完全缺失，实测无法进行。
3. **无真实凭证**：ak/sk 仅示例值（ak_test_123/sk_test_456），获取流程为"联系游由"，无自助入口、配额与轮换说明（question 页）。
4. **签名语义未决**（question 页）：`X-Timestamp` 单位/时钟偏差/有效期未定义（13 位示例形似毫秒级 Unix 时间戳，仅为推测）；`X-Nonce` 唯一性规则（长度/字符集/查重窗口）未说明；验签失败/过期/重放的错误码与响应结构未给出。
5. **`/v2/booking/hotels/search` 真实性存疑**：仅出现在签名算例中，无正式定义，无法确认该端点在实际环境存在、版本前缀（v2）是否现行。
6. **分页/限频/错误码全局缺失**：除算例中的 pageSize/pageNum 外无任何约定。
7. CLI 提示 `No authorization data found`（Postman 云发布用），不影响本地 collection run——自检与后续实测均可正常执行。

## 五、实测状态与工件

- Postman CLI 1.86.0 可用；**签名通道自检已跑通**（selfcheck 集合 → postman-echo，SUPPLIER_EVIDENCE 证据行 + SIGNATURE_SELFCHECK 断言全过，2026-11-13）。
- 供应商正式 Collection 已备好（含签名 pre-request 脚本与 SUPPLIER_EVIDENCE Tests 脚本），**因缺 base_url + 真实 ak/sk 无法对供应商发起真实调用**——已按卡点协议向用户提问。
- 工件路径（任务 `.trellis/tasks/11-13-smoke-20261113-doc-analysis/`）：
  - `postman/smoke-20261113-20261113.postman_collection.json` + `.env.json`（供应商正式集合，待凭证）
  - `postman/smoke-20261113-20261113-signature-selfcheck.postman_collection.json` + `.env.json`（通道自检，已执行）
  - `research/sig-offline-verification.md`（离线核验记录）
  - `research/interface-mapping-report.md`（本报告）
