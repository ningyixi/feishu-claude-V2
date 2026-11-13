# PRD：smoke-20261113 供应商接口文档分析

## 背景
「接口建档」流水线（SupplierJob #194，quick 探测）派发的文档分析任务。
供应商：smoke-20261113（LLM Wiki 版本 20，来源指纹 306600a2...711b）。

## 输入材料（固定版本 Wiki，本任务已全部 Read）
- 知识地图：`D:\feishu-claude-data\supplier-kb\.wiki\6ae2b0d36c30ca38b9d01db6\revisions\20\wiki\index.md`
- api 页：`yoyo-signature-example.md`（游由签名算例，含 POST /v2/booking/hotels/search 示例）
- constraints 页：`yoyo-api-signature.md`（游由 API 签名关键约束：4 头 / ak-sk / 6 行签名原文 / HMAC-SHA256+Base64 / 空 query 空 body 边界）
- question 页：`yoyo-signature-open-questions.md`（时间戳单位、nonce 防重放、错误码、ak/sk 流程均未决）
- source 页：`yoyo-signature-doc-source.md`（来源：Apifox 托管《接口签名》MD 直链 https://g3iq08vde3.apifox.cn/9086121m0.md，解析于 2026-11-13T12:34:23，sourceId=e306；范围仅签名机制）
- raw 证据：`revisions\20\raw\e306.md`（《接口签名》全文）

## 需求（来自派发动作规范）
1. 逐个阅读文档，产出接口映射报告（markdown 表格）：
   | 业务动作 | 接口(方法+路径) | 关键请求参数 | 关键响应字段 | 备注(鉴权/分页/限频) |
2. 业务动作至少覆盖：同步城市、同步国家、同步酒店(静态信息)、同步房型、同步报价(价格/库存)、查价(RatePlan)、验价(PreBook/CheckRate)、下单(CreateBooking)、查单(GetBooking)、取消(CancelBooking)。
3. 文档里没有的动作明确标注「未找到」；文档不全时如实标注「文档缺失」。
4. 最后给出：鉴权方式、环境(测试/生产)地址、文档可疑或滞后之处。
5. 接口实测协议：真实调用供应商 HTTP 接口须走 Postman CLI（Collection v2.1，命名 <供应商代码>-<yyyyMMdd>，含 SUPPLIER_EVIDENCE Tests 脚本）。
6. 卡点协议：缺关键信息（凭证/环境地址）导致无法真实执行时，以【卡点】提问等待，禁止默默跳过。
7. 输出结构：## Before-dev → Implement → ## Check（自查表）→ ## Finish（最终产物）。

## 验收标准
- [ ] 10 个业务动作逐一有结论（映射到接口 或 标注「未找到」），无臆造接口/字段
- [ ] 鉴权方式依据 constraints 页如实描述
- [ ] 环境地址缺失时如实标注「文档缺失」
- [ ] 文档可疑/滞后之处成段列出
- [ ] 签名算法用官方算例离线核验（本地计算 vs 文档期望值）
- [ ] 实测不可行时按卡点协议提问，不静默跳过

## 进展记录
- 2026-11-13：Wiki 4 页 + raw e306 已全部读取；Postman CLI 1.86.0 可用；知识库仅含《接口签名》一份文档，无接口清单/环境地址/真实凭证 → 实测阶段预计卡点。
- 2026-11-13：签名算法离线核验通过（bodyHash/空 body 边界/最终签名三项与文档算例逐字节一致，见 research/sig-offline-verification.md）。
- 2026-11-13：Postman Collection 两套就绪（供应商正式 + 签名通道自检）；自检集合经 CLI 实跑 postman-echo，2/2 断言通过，沙箱签名脚本复现期望签名 5CDmXPEfEny5efYqpLCTphHC1npnhTcC38qFtji4K9E=。
- 2026-11-13：接口映射报告完成（research/interface-mapping-report.md）：10 个业务动作全部「未找到」正式定义，补充记录唯一算例端点 POST /v2/booking/hotels/search；鉴权方式成文；环境地址标注「文档缺失」；可疑点 7 条。
- 2026-11-13：对供应商的真实 HTTP 实测因缺 base_url + 真实 ak/sk 无法执行 → 已按卡点协议向用户提问（A 补文档+凭证+地址 / B 仅凭证+地址实测唯一端点 / C 冒烟验证接受纯文档分析收口），等待【卡点答复】。
