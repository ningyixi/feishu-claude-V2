# Wiki 派发载荷指针化（wiki-context-pointers）

## 问题（用户实测失败）
派发前置检查失败：`context(job,stage)` 把版本 16 的 **15 篇必读页全文内联**进派发载荷，且每页按证据条数重复贴"证据: eXXX → 绝对路径"行（同页最多重复 14 次），合计突破 32000 字符上限 → 派发被拦。是**交付形态超限，不是知识缺失**：rev16 内容完好、固定版读取通道（pages/sources API + 本地文件）全通。

## 用户的设计判断（采纳）
既然做了 LLM Wiki + 本地落盘 + 固定版本读取 API，派发载荷就应该只给**指路信息**：
- 每页：标题 + summary(≤240) + 文件路径 + 读取 API；
- **critical 页**额外附**硬约束节选**（每页 ≤500 字、合计 ≤4000 字）——签名四头/铁律/冲突口径这类"每次必须注入"的保留最小内联；
- 证据行**按来源去重**（每页一行"证据来源: eA, eB → raw/<id>.md 或 /sources/<id>"）；
- 其余详情一律让 agent 自己 Read/走 API。

## 改动点
`SupplierWikiService.context()`（95-119 行）：
1. 必读页循环不再 `append(page.content)` 全文 → 摘要+路径+API 指引；
2. critical 页附节选（excerptBudget=4000 总额控制）；
3. 证据行 per-page 按 sourceId 去重成一行；
4. 开头话术改为"摘要+节选已内联，全文按路径读取"；
5. 32000 上限保留为安全网（指针化后实际 ~5-8k）。

## 验收
- [ ] rev16（15 必读页）context 长度 < 10000，不再抛"超过 32000"
- [ ] body 含每页 summary+路径+去重证据行；critical 含硬约束节选
- [ ] SupplierWikiTest:61 `contains(QUOTE)` 仍过（critical 节选保留）且 body 更小
- [ ] 派发重跑 job193 n1 通过前置检查（用户侧验证）
- [ ] mvn 全量无新增失败；前后端重启（先确认无 RUNNING）

## Out of Scope
重生成/修订 Wiki（rev16 无需动）；publication 侧 22000/32000 发布预算语义不变。

## Delivery (2026-11-04)
context() 指针化完成：必读页只内联 标题+摘要+全文路径+去重证据行；critical 页附硬约束节选（单页≤500、合计≤4000）；32000 上限保留为安全网。SupplierWikiTest 全过（含 QUOTE 内联断言），全量 86 测仅既有环境性 3F+1E。后端 20:41 重启生效；rev16 无需重编译，直接重派即可。
