# GitBook 软 404 防护研究（2026-11-27 会话内实测取证）

## 现象

临时需求「同站点抓取」入口 `https://docs.mews.com/channel-manager-api` 产出的
`D:/feishu-claude-data/supplier-kb/adhoc-9/site-341/p1.md` 是 GitBook 的
"Page Not Found" 兜底文案（2306B），且状态为 loaded、载入 1 页。

## 实测证据（同一 UA `Mozilla/5.0`，2026-11-27）

| 请求 | 状态 | Content-Type | 大小 | 内容 |
|---|---|---|---|---|
| `GET /channel-manager-api`（浏览器 UA） | 200 | text/html | 440242 | **真实正文**（title=`Mews Channel Manager API`，SSR，无 "Page Not Found"） |
| `GET /channel-manager-api.md` | **200** | text/markdown | 2250 | **假 404**：`# Page Not Found … The URL \`channel-manager-api\` does not exist.` |
| `GET /channel-manager-api` + `Accept: text/markdown,…` | 200 | text/markdown | 3424 | **真实正文**（开头 llms.txt 引言，`# Mews Channel Manager API`） |
| `GET /channel-manager-api/readme.md` | 200 | text/markdown | — | **真实正文**（页面在空间内的真实 md 路径是 `readme`） |
| `GET /channel-manager-api/`（尾斜杠） | 302 | text/plain | 15 | Location: `/channel-manager-api` |
| `GET /definitely-not-a-real-page-xyz` | **404** | text/html | 192405 | 真不存在页 → HTML 路由**正确返回 404** |
| `GET /channel-manager-api/guidelines/authentication.md` | 200 | text/markdown | — | 子页面 md 直链**正常** |

结论：**软 404 只发生在 `.md` 后缀路由对"HTML 侧合法别名"（空间根裸路径）不认的场景**。
GitBook 对该情况返回 200 + 兜底 markdown（含 Suggested Pages / ask= 接口自述），
HTML 路由与真 404 路径的状态码都是正确的。

## 我们的消费点（代码证据）

1. `DocParser.fetchPage:232-243`（站点抓取原语，`SupplierKbService.crawlNow:827` 注入为 Fetcher）：
   md 直链探测响应只要 `!looksLikeHtml(text)` 就**直接采信返回**——假 404 由此进入。
   若该步不采信，line 244 回退 `httpGetCrawl(url)` 的 Accept 头本就带
   `text/markdown` 优先（line 269），GitBook 会协商返回真实正文（已实测）。
2. `DocParser.fetchUrlToMarkdown:118-129`（单页解析）：同样的 probe-first 逻辑，同坑。
3. `SiteCrawler:99` `MIN_BODY_CHARS=80`：404 兜底文案 2306 字符，空壳判定拦不住。
4. `SiteCrawler:121` inScope：404 页里的 Suggested 链接全在 `channel-manager-api`
   一级路径之外 → 不入队 → "载入 1 页" 是每一环都"正确工作"的合成结果。

## 检测签名设计依据

兜底文案特征（实测原文）：首个标题为 `# Page Not Found`，正文含
"The URL \`…\` does not exist"。真实页面正文（含 Accept 协商返回的带 llms.txt 引言版）
首标题是页面名，不会命中。合法文档页即使正文提到 404（如讲 404 handler 的文档），
首标题也不会等于 "Page Not Found"——两个条件同时成立才判软 404，误伤面最小。

## 测试约束（仓库既有纪律）

`SiteCrawlHttpFixtureTest` 类注释明确：**SSRF 防线按设计拒绝回环地址，生产
fetchPage 无法穿过本地 HttpServer 夹具；测试禁止访问真实外网域名**。
因此接线验证 = 抽纯判定函数做单测 + 现网 URL 在测试外人工实测。

## 修复后复验（check 阶段，2026-11-27，针对 68233c3）

用与 `httpGetCrawl` 逐字相同的请求头（`User-Agent: Mozilla/5.0 (compatible;
SupplierKbCrawler/1.0)`、`Accept: text/markdown,text/html;q=0.9,text/plain;q=0.8,*/*;q=0.5`）
curl 复验，确认修复依赖的现网行为在守卫落地后依然成立：

| 请求 | 状态 | Content-Type | 大小 | 守卫结论 |
|---|---|---|---|---|
| `GET /channel-manager-api.md` | 200 | text/markdown | 2250B | 软 404 壳（原文与测试夹具逐字一致）→ `adoptMdProbe` 拒绝，回退 |
| `GET /channel-manager-api` | 200 | text/markdown | 3424B | 真实正文（llms.txt 引言 + `Mews Channel Manager API` ×2）→ 放行入库 |
| `GET /definitely-not-a-real-page-xyz` | 200 | text/markdown | 2252B | 死链同样回 200 壳 → 最终守卫抛 IOException = 单页失败 |

三场景与 68233c3 提交说明所载实现会话内 Java 级 `fetchPage` 实测结论一致。
check 阶段另修复一处误伤面：`looksLikeSoftNotFound` 的前 10 行标题扫描跳过
``` / ~~~ 围栏代码块内的行（围栏内不是 markdown 标题行；口径同 `setextToAtx`），
防止"展示 404 响应示例"的合法文档页被误判，详见 PRD「Check 阶段复核记录」。
