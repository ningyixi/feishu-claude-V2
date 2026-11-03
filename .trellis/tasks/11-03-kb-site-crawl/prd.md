# 供应商知识库：同站点文档目录递归抓取（BFS ≤200 页）

## Goal
供应商文档常是多页站点。URL 导入增加"导入同站点文档目录"选项：从入口页发现链接、按范围逐页抓取（BFS + 状态清单）。参考 ant_harness crawl.py 规格（用户提供的 11 条）。

## 硬规则（易误解点，用户明示）
- **范围**：目录取入口 URL **第一级路径**。`/docs/api/start` → 允许整个 `/docs/` 下发现页
- **200 页是"发现数"硬上限**：发现第 201 个页面 → 报容量错误；**不**把前 200 页当完整成功
- 累计文本 ≤100 万字符；单次响应 ≤5MB、解码 ≤10 万字符；总超时 30s；≤5 次**同源**重定向（跨源重定向不跟随）；截断=错误不冒充完整
- 入口失败/无有效内容 → 整体失败；已有内容后单页失败 → 标记 failed 继续，出"部分导入"警告
- 安全/范围/容量/截断错误 → 终止整个导入，已 loaded 页面改标 failed"导入中断，已抓取内容未保存"，不留可检索半成品
- 服务重启 → PARSING 中的站点条目转明确 FAILED（不永远"正在导入"），点重解析可重跑
- 新发现的带查询参数链接不入队（入口 URL 可带参）；跳过 pdf/图片/压缩包/css/js/json/xml 等资源链接
- 严格同源（协议+主机+有效端口，子域不同源）；去重忽略 fragment，`/page` 与 `/page.md` 同一逻辑页

## 实现映射（本项目 Java 形态）
- `SiteCrawler`：纯 BFS 逻辑（Fetcher 接口注入，测试可 stub）；limits/范围/去重/状态清单
- `DocParser`：新增 `fetchPage`（md 探测优先→HTML 回退、同源重定向≤5、解码 100k 截断检测、SSRF 逐跳复用 assertSafeUrl）；链接提取 HTML=Jsoup `<a href>` / MD=正则
- `SupplierKbEntry`：type=url + metaJson.site=true → "解析"触发爬虫；新实体 `SupplierKbPage`(entryId,urlKey,title,status,error,mdPath,orderIdx) 逐页增量落库；产物目录 kbRoot/<kbCode>/<entryId>/p<N>.md + 索引 md（entry.mdPath=索引：标题+来源URL+分块行号沿用 chunk()）
- 取消：内存 cancel 集合 + POST /entries/{id}/cancel → 按"整体失败"分级处理
- DNS rebinding：assertSafeUrl 每请求/每重定向复核（https 连接 IP 固定受 JDK HttpClient API 限制，为已知边界，PRD 记录）
- "会话隔离"在本项目=kbCode 隔离（无多租户会话概念）
- 前端 KbPanel：url 类型加复选框"导入同站点文档目录(≤200页)"；站点条目行显示"已发现X·已载入Y·失败Z·未抓取N"+⚠️部分导入徽标+"📄 页面"弹窗（逐页 URL/标题/状态/错误，loaded 页可预览正文）；进度来自 metaJson 增量
- JS 空壳页：提取正文 <80 字符 → 该页 failed"正文不可读取（疑似JS渲染）"，不冒充成功
- 旧数据无页面清单：弹窗显示"历史抓取无逐页记录"

## 验收测试（SiteCrawlerTest，stub Fetcher 离线）
单页模式不跟进链接 / 同域同路径多页成功 / 跨域跨端口越路径资源链接被滤 / 相对链接·fragment·循环·.md 去重 / md 优先 HTML 回退 / 恰好200页成功第201超限报错 / 累计字符超限 / 入口失败整体失败 / 中途失败部分成功保留 / 安全错误终止无半成品 / pending-loaded-failed 状态 / 取消转失败

## Out of Scope
浏览器渲染/登录页/OCR/PDF链接解析/sitemap/全站搜索（规格第十一条明确独立扩展）
