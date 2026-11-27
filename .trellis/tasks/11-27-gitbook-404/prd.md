# 临时需求站点抓取防 GitBook 软 404

## Goal

GitBook 类站点用 **HTTP 200 + "Page Not Found" 兜底 markdown**（软 404）骗过了
`.md` 直链优先探测，导致 `https://docs.mews.com/channel-manager-api` 这类
"HTML 路由存在、.md 别名路由不存在"的入口整站只入库 1 页 404 壳。
修复：给 md 直链探测与最终正文加软 404 守卫——探测命中即回退原 URL
（Accept 协商能拿到真实 markdown），最终仍是 404 壳则按单页失败上报，绝不让
假内容冒充"已解析"。

## Background / 根因（已取证，详见 research）

- `DocParser.fetchPage:232-243`：`url+".md"` 探测响应只要非 HTML 就直接采信；
  GitBook 对 `/channel-manager-api.md` 返回 200 软 404 兜底文案 → 入口页被"成功"载入。
- 回退路径本来是对的：`httpGetCrawl` 的 Accept 头 `text/markdown` 优先，实测原 URL
  能协商拿到真实正文；子页面 `.md` 直链也全部正常。**坏只坏在探测结果无校验**。
- 404 壳 2306 字符 > `MIN_BODY_CHARS=80`；壳内 Suggested 链接越出 entryScope →
  BFS 自然止步"载入 1 页"。
- 单页解析 `fetchUrlToMarkdown:118-129` 同构同坑。

## Requirements

- **R1 软 404 判定纯函数**：`DocParser` 新增包内可见静态
  `looksLikeSoftNotFound(String md)`。仅当**两条件同时成立**判 true：
  ① 前 10 行内存在标题行匹配 `(?i)^#{1,3}\s*(page not found|not found|404)\b`；
  ② 全文含 `(?i)does not exist`。禁止用宽泛"包含 404 字样"判定（会误伤讲 404
  的合法文档页）。
- **R2 md 直链探测采信守卫**：`fetchPage` 与 `fetchUrlToMarkdown` 的探测分支改为
  `!looksLikeHtml(text) && !looksLikeSoftNotFound(text)` 才采信；命中软 404 →
  视同"无 md 直链"，走既有回退（原 URL + Accept 协商 / HTML 清洗转换）。
- **R3 最终正文守卫**：两条链路**回退后**拿到的正文（markdown 直读或 HTML 转换结果）
  若仍命中 `looksLikeSoftNotFound` → 抛 `IOException`，文案点破：
  「页面内容形如 404 兜底页（URL 可能不是真实页面路径；GitBook 类站点可查
  llms.txt 索引或补 /.md 确认真实路径）」。站点语义自动成立：入口页抛错 =
  尚无 loaded → `SiteCrawler` 既有规则整体失败（`SiteCrawler:146`）；非入口 =
  单页 failed（`res.partial`）。
- **R4 三入口一致**：守卫放在 `DocParser` 内（站点抓取 `fetchPage` 与单页
  `fetchUrlToMarkdown` 两个消费点），AdHoc / QuickProbe / SupplierKb 三条流水线
  经"唯一权威"惯例自动同修，调用方零改动。

## Acceptance Criteria

- [x] AC1 `looksLikeSoftNotFound` 单测（用本次实测的 Mews 404 壳原文与真实正文
      原文作夹具常量，零网络）：404 壳 → true；带 llms.txt 引言的真实正文 → false；
      标题为 "Handling 404 errors" 之类合法文档页 → false；普通 markdown → false。
      （DocParserSoftNotFoundTest，check 阶段复跑通过）
- [x] AC2 探测分支单测（抽出包内可见的采信判定或直接测纯函数组合）：
      探测文本=软 404 壳 → 不采信；探测文本=真实 md → 采信。
      （包内可见 `adoptMdProbe` + 3 个探测判定测试）
- [x] AC3 现网实测（测试套件之外，会话内执行并留记录）：
      `DocParser.fetchPage("https://docs.mews.com/channel-manager-api", d->true)`
      返回正文含 `# Mews Channel Manager API`（Accept 协商路径），不再是 404 壳。
      （实现会话已实测，见 68233c3 提交说明；check 阶段以同源 UA/Accept 头 curl
      复验三场景，证据表见下方「Check 阶段复核记录」与 research 末节）
- [x] AC4 既有测试零回归：`DocParserTest`、`SiteCrawlerTest`、
      `SiteCrawlHttpFixtureTest`、`AdHocSiteCrawlTest` 等全绿。
      （check 阶段实测：4 类 37 项 + AdHocSiteCrawlTest 12 项 = 49 项全绿）
- [x] AC5 提交仅含本任务文件（工作区另有 11-24-adhoc-manual-stages 的未提交
      WIP，不许混入本 commit）。（`git show 68233c3 --stat` 仅 DocParser.java +
      DocParserSoftNotFoundTest.java 两文件）

## Definition of Done

* 单测新增并通过；`mvn test` 相关类全绿
* 现网 URL 实测证据写入本 PRD 或 journal
* 行为变更点注释引用本任务号
* commit + push（用户惯例：功能完成即 push）

## Technical Approach

- 改动文件：`src/main/java/.../DocParser.java`（+2 静态方法、2 处采信条件、
  2 处最终守卫）；`src/test/java/.../DocParserTest.java`（或新建
  `DocParserSoftNotFoundTest`）。
- 测试基建约束（既有纪律）：SSRF 防线按设计拒绝回环 → fetchPage 端到端只能
  纯函数单测 + 现网人工实测（见 research 末节），**不得**为此开 SSRF 后门。
- 误伤控制：双条件合取；只拦"标题形如 not found 且自述不存在"的兜底文案。

## Decision (ADR-lite)

- **Context**：GitBook 软 404 走 200+markdown，状态码与空壳判定都拦不住。
- **Decision**：内容签名守卫（R1 双条件）置于 DocParser 两个消费点；探测命中→
  回退，回退仍命中→失败。不引入浏览器渲染、不改 SiteCrawler 语义、不动 SSRF 防线。
- **Consequences**：其它平台（Archbee 等）若有不同 404 文案暂不覆盖，签名是
  可扩展点（常量/正则表）；未来可考虑 llms.txt 自动索引发现，本任务不做。

## Out of Scope

- llms.txt / sitemap 自动发现真实入口
- JS 渲染站点支持（维持既有"空壳→FAILED 提示"行为）
- SiteCrawler BFS/范围/预算语义（一行不动）
- 11-24-adhoc-manual-stages 的未提交 WIP（另行收尾）

## Technical Notes

- 证据表与原文样本：`research/gitbook-soft-404.md`
- `httpGet/httpGetCrawl` 对非 200 已抛 IOException（DocParser:412-415），真 404
  路径本就会失败；本次只补 200 软 404。
- GitBook 404 兜底文案自述了 ask= 查询接口与 MCP——站点欢迎 agent 抓取，修好
  守卫后 Mews 整站可正常入库。

## Check 阶段复核记录（2026-11-27，评审对象 68233c3）

### 现网复验（curl，与 httpGetCrawl 逐字相同的 UA + Accept 头）

| 场景 | 请求 | 结果 | 守卫行为 |
|---|---|---|---|
| 探测 | `GET /channel-manager-api.md` | 200 text/markdown 2250B，`# Page Not Found` + `does not exist`（与测试夹具逐字一致） | `adoptMdProbe`=false → 不采信，回退 |
| 回退 | `GET /channel-manager-api` | 200 text/markdown 3424B，llms.txt 引言 + `Mews Channel Manager API`（与夹具一致） | 双条件均不命中 → 正常入库 |
| 死链 | `GET /definitely-not-a-real-page-xyz` | 200 text/markdown 2252B 软 404 壳 | 最终守卫抛 IOException → 单页失败（可读文案指向 llms.txt） |

### 复核发现与修复（check 阶段 1 项代码修复）

- **R1 标题扫描未排除围栏代码块**（DocParser.looksLikeSoftNotFound，中严重度=误伤面）：
  R1 原文是"前 10 行内存在**标题行**"，markdown 语义下围栏代码块内的行不是标题行；
  原实现按裸行扫描，前 10 行内出现 ```` ``` ```` 围栏包裹的 `# Page Not Found` 示例
  （如错误处理指南展示 404 响应样例）且全文含 "does not exist" 时会误判合法页。
  已修：扫描时跳过 ``` / ~~~ 围栏区间（口径与同文件 `setextToAtx` 一致），GitBook
  壳本身首行即裸标题、不在围栏内，检出能力零损失；新增回归测试
  `notFoundSampleInsideCodeFenceIsNotSoftNotFound`（``` 与 ~~~ 双围栏）。
- 其余核查项通过：R2/R3 四处守卫接线齐全且抛 IOException（单页失败级，非
  FatalCrawlError，符合既有分级）；R4 守卫全在 DocParser，SiteCrawler 语义与
  SSRF 防线零改动（68233c3 仅 2 文件）；null/空串/前10行窗口/\r\n/`#{1,3}` 边界
  正确；`toLowerCase()` 无 Locale 与全仓库既有惯例一致；错误文案可读可操作
  （符合 supplier-context §4 精神）。
- 已知边界（按 PRD Out of Scope 不算缺陷）：其它平台 404 文案若不含
  "does not exist"（如仅 "couldn't find"）探测仍会采信——ADR Consequences
  已声明签名是可扩展点。

### 回归验证

`mvn test -Dtest='DocParserSoftNotFoundTest,DocParserTest,SiteCrawlerTest,SiteCrawlHttpFixtureTest'`
= 37 项全绿（含新增围栏测试后 DocParserSoftNotFoundTest 10 项）；
`AdHocSiteCrawlTest` 12 项全绿。合计 49 项，BUILD SUCCESS。
