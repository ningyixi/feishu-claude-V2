# Research: Postman CLI / newman 落盘结果文件的证据采集契约（K5）

- **Query**: 供应商工作台「接口实测」节点 tool_evidence 恒空 —— Postman CLI/newman 经 Bash 执行、结果落盘形态的真实结构契约、实测命令判定规则、与 MCP 通道证据形状对齐、失败语义、K5 可行性
- **Scope**: mixed（本机真实文件取证 + 本仓库 Java 源码 + 实例会话 transcript）
- **Date**: 2026-11-18
- **取证方式**: 只读。所有字段路径与样例值均来自磁盘真实文件 / `C:\Users\yixi.ning\.claude\projects\D--dida-SupplierV2\*.jsonl` transcript 原文，非推测；推测与未验证项在 §8 单独列出。凭据值已打码。

---

## 0. TL;DR

1. 这台机器上存在 **两种互不兼容的结果文件 schema**：
   - **Schema A（newman 6.2.2 导出）**：顶层 `{collection, environment, globals, run}`，`run.executions[]` 内 `request`/`response` 平级，响应体在 `response.stream`（Buffer 字节数组），**没有 `response.body` 字段**。
   - **Schema B（Postman CLI 1.86.0 `--reporter-json-export` 导出）**：顶层只有 `{run}`，`run.{meta,summary,executions,runError}`；`executions[]` 内是 `requestExecuted`（注意：请求头叫 **`headers`**，复数），成功时 `response` 是 **exec 级平级字段**（不是 `requestExecuted.response`），传输层失败时 **`response` 键整个缺失**。
2. 结果文件路径 **全部是 agent 自选的相对路径**（`.tmp_engine_*_result.json` / `*_run.json` / `*_mtls.json`…无固定规律），**采集器不能靠固定文件名定位**；可靠定位方式是解析命令文本里的 `--reporter-json-export <path>` 参数（本机所有产出 JSON 的调用 100% 带此参数），相对路径按「命令内 `cd "<dir>" &&` 前缀 → 否则实例工作目录」解析。
3. 现有采集链有两处天然断点（详见 §6）：`SupplierProbeEvidence.isExecutionTool` **不认 `newman run`**（今天两次重跑全是 newman）；`progressListener` **拿不到实例工作目录**（无法解析相对路径读文件）。因此「只加一个纯解析函数 + 一处 onToolEnd 分支」**不完全够**，还需既有两点各一处小改（一条正则 + 一个签名传参），但不需要动 TerminalSession 事件结构或 SDK 侧。
4. 失败语义结论：**传输层失败（ECONNRESET 等，零 HTTP 往返）不产生也不应产生证据记录**（两种 schema 里都能确定性识别）；**HTTP 4xx/5xx 但真实往返 = 算证据**（与 `hasHttpResponse` 的 100–599 判定、门禁文案「真实请求/返回」、以及本轮实测中故意设计的 typed-400 探针被实例归档为有效证据的事实一致）。全传输失败的轮次 → 0 条证据 → 门禁仍拦，**不会变成橡皮图章**。

---

## 1. 样本文件清单（真实存在性已核验）

任务书里给的样本路径已经变化：**实例在今天 15:19（07:19Z）自己执行了清理命令**（transcript `8c1f2815-….jsonl` 2026-11-18T07:19:10.932Z 原文：`T=.trellis/tasks/11-17-engine-omni-mapping/research; for f in …; do cp "$f" $T/; done; … rm -f .tmp_engine_*.json .tmp_engine_*.log .tmp_engine_*.js .tmp_engine_*.txt`），把大部分 `.tmp_engine_*` 从 `D:/dida/SupplierV2/` 根目录**移动**到了任务 research 目录，并且 **`.tmp_engine_docs_result.json`（1,344,288 B）不在 cp 清单里，已被 rm 销毁**。`.tmp_engine_evidence.js` / `.tmp_engine_evidence2.js` / `.tmp_engine_evidence_all.txt`（10,891 B）同样已被销毁（全盘 find 无结果）。

现存真实样本（均在 `D:/dida/SupplierV2/.trellis/tasks/11-17-engine-omni-mapping/research/`）：

| 文件 | 大小 | Schema | 产出命令（transcript 核验） | 内容概要 |
|---|---|---|---|---|
| `.tmp_engine_prod_result.json` | 63,027 B | **B**（postman CLI） | `postman collection run Engine-20261117.postman_collection.json -e Engine-20261117-prod.postman_environment.json --reporters cli,json --reporter-json-export .tmp_engine_prod_result.json > .tmp_engine_run_prod.log 2>&1`（2026-11-17T04:11Z，EXIT:1） | 12 exec **全部 ECONNRESET**（prod 无证书） |
| `.tmp_engine_sandbox_result.json` | 61,303 B | **B** | 同上 sandbox 环境（04:10Z，EXIT:1） | 12 exec 全部 ECONNRESET |
| `.tmp_engine_sandbox_mtls.json` | 1,268,019 B | **A**（newman） | `newman run Engine-20261117.postman_collection.json -e Engine-20261117-sandbox.postman_environment.json --folder "01 静态与查价（只读）" --ssl-client-cert .tmp_engine_creds/client.crt --ssl-client-key .tmp_engine_creds/client.key --reporters cli,json --reporter-json-export .tmp_engine_sandbox_mtls.json`（2026-11-18T02:36:50Z） | 7 exec，code=200,200,400,200,500,400,400 |
| `.tmp_engine_mtls2_result.json` | 2,703,418 B | **A** | newman 同形态（04:07:40Z，EXIT:0） | 9 exec，200×5 + 400×4 |
| `.tmp_engine_catalogb.json` | 332,096 B | **A** | newman（04:09:17Z 构造集合后运行，EXIT:0） | 1 exec，200 |
| `.tmp_engine_book_run.json` | 5,768,637 B | **A** | newman（04:18:19Z，EXIT:0） | 8 exec 全 200（真实下单闭环） |
| `.tmp_engine_livebook_run.json` | 5,339,081 B | **A** | newman（06:19:52Z，EXIT:0） | 5 exec 全 200 |
| `cli-run-1.json`（archive：`.trellis/tasks/archive/2026-11/11-05-yoyo-oversea-cli-live-test/research/`） | — | **B（含成功响应，唯一现存样本）** | 11-05 `postman collection run`（YoyoOversea） | 19 exec：200×12, 400×1, 401×1, 500×5 |
| `engine-cli-run-prod.stdout.log` / `engine-cli-run-sandbox.stdout.log` | 72,689 / 73,942 B | CLI stdout 文本 | `> .log 2>&1` 重定向产物 | 见 §3 stdout 形态 |
| `.tmp_engine_{mtls2_run,book_run,livebook,catalogb,run_prod,run_sandbox}.log` | — | newman/CLI stdout 文本 | 同上 | 同上 |

**关于任务书样本的两点更正**：
- `.tmp_engine_sandbox_mtls.json` **不只是 collection 定义**——它是 newman 的 JSON 导出，`collection/environment/globals`（定义+环境快照）与 `run`（**完整运行结果**，7 条 execution 含真实响应）**同在顶层**。它是「定义+结果」合体文件，其中 `run` 部分就是结果。
- `.tmp_engine_docs_result.json`（任务书称 11-17 11:2x 轮）：产出命令核验为 2026-11-17T04:16:14Z（本地 12:16）`postman collection run Engine-docs-probe-20261117.postman_collection.json --reporters cli,json --reporter-json-export .tmp_engine_docs_result.json > .tmp_engine_run_docs.log 2>&1`，EXIT:0。文件与它的 .log **均已被 15:19 的清理删除，无法再取证内容**；其 schema 按同命令形态判定为 Schema B（同批 prod/sandbox_result 实证）。

「47 次调用」：出自实例 Check 工件原文（07:19:33Z，1570 字）「5 轮 47 次真实调用」。磁盘可核验的逐轮 exec 数：1a=12、1b=12、docs=5+1（D5 为运行中途追加，见 transcript 04:17:20Z）、2=7、3=9、3b=1、4=8、5=5。**47 这个合计数无法从现存文件精确重建（docs_result 已删）——标记未证实**；但失败分布可核验：传输层全灭 24 次（1a+1b），HTTP 4xx/5xx 带真实响应 8 次（第 2 轮 400×3+500×1、第 3 轮 400×4），其余为 200。

---

## 2. 结果文件结构契约（可复制字段路径表）

### 2.0 两种 schema 的判别

```
顶层含 "collection" 键            → Schema A（newman 导出）
顶层只有 "run" 且 run.meta 存在    → Schema B（Postman CLI 导出）
```
（本机全部 9 个样本无一例外。另一个旁证：Schema A 的 `User-Agent` 头是 `PostmanRuntime/7.39.1`，Schema B 是 `PostmanRuntime/7.56.2`——runtime 版本不同源。）

### 2.1 Schema A：newman 6.2.2 `-r cli,json --reporter-json-export <file>`

顶层：

| 字段路径 | 类型 | 真实样例（打码） | 缺失可能 | 备注 |
|---|---|---|---|---|
| `collection` | object | `{_: {postman_id:"engine-20261117-omni-http"}, item[], event[], variable[], info{name,...}}` | 不缺失 | 集合定义快照。`collection._` 是 `{postman_id}` 包装；`item[].id`/`_postman_id` 为内部 ID |
| `environment` | object | `{id:"engine-20261117-sandbox", name:"Engine-20261117-sandbox", values:[{key,value,type}]}` | 可为 null（未传 -e 时未证实） | ⚠️ `values[]` 里 **含 token 明文**（本样本 keys: `baseUrl,bestToken,offerToken,offerTotal,quoteToken,bookingId`）——绝不能整体嵌入证据 |
| `globals` | object | `{id, values}` | 同上 | 同上 |
| `run.stats` | object | `{iterations:{total:1,pending:0,failed:0}, items, scripts, prerequests, requests:{total:8,pending:0,failed:0}, tests, assertions:{total:14,pending:0,failed:0}, testScripts, prerequestScripts}` | 不缺失 | 每项都是 `{total,pending,failed}` 三元组。**注意与 Schema B 的 summary 字段名完全不同** |
| `run.timings` | object | `{responseAverage:2001.375, responseMin, responseMax, responseSd, dnsAverage…, firstByte…, started:1794975502232, completed:1794975518479}` | 不缺失 | epoch 毫秒 |
| `run.transfers` | object | `{responseTotal: 286598}` | 不缺失 | 字节数 |
| `run.failures[]` | array | 正常轮 = `[]`（长度 0）；失败样例（archive `newman-run-1.json`，46 条）：`{error:{name:"Error", message:"runtime:extensions~request: request url is empty", stack:"…"}}` | 不缺失（可为空数组） | **断言失败/请求级错误** 汇总。⚠️ HTTP 400/500 但断言 pass 时 failures 为空（sandbox_mtls：400×3+500×1 而 `failures.length=0`） |
| `run.error` | null/object | 本机样本全部 `null` | — | 整轮中断错误（非 null 时 executions 可能不完整；非 null 实样未证实） |

`run.executions[]` 每条（成功/HTTP 失败均有 response；传输层失败形态见 §5）：

| 字段路径 | 类型 | 真实样例（打码） | 缺失可能 | 备注 |
|---|---|---|---|---|
| `.cursor` | object | `{position:0, iteration:0, length:8, cycles:1, empty:false, eof:false, bof:true, cr:false, ref:"78a5851e-…", httpRequestId:"86e37f60-…"}` | 不缺失 | 内部游标，证据可忽略 |
| `.item` | object | `{id, name:"B1-FindBestOffers", request, response, event}` | 不缺失 | **集合定义快照**（`item.request.url` 可能还是 `{{baseUrl}}/…` 字符串；`item.response` 是集合里存的示例响应数组，通常 `[]`）——**不要拿它当实测数据** |
| `.id` | string(uuid) | `"3e139514-…"` | 不缺失 | exec 内部 ID，可忽略 |
| `.request.method` | string | `"POST"` / `"GET"` | 不缺失 | 大写 |
| `.request.url` | object | `{protocol:"https", path:["shop","v1","lodging","best-offers"], host:["partner-api-sandbox","engine","com"], query:[{key,value}], variable:[]}` | 不缺失 | **已解析**（变量已替换）。⚠️ 本机样本里 **没有 `raw` 字段**（`url.raw=undefined`）——拼 URL 需 protocol+host.join('.')+path.join('/')+query |
| `.request.header[]` | array | `[{key:"content-type",value:"application/json"},{key:"User-Agent",value:"PostmanRuntime/7.39.1",system:true},{key:"Postman-Token",value:"bacd…(打码)",system:true}]` | 不缺失 | **单数 `header`**（与 Schema B 的 `headers` 不同！）；`system:true` 为运行时自动头 |
| `.request.body` | object | `{mode:"raw", raw:"{\n  \"criteria\": {…}}", options:{raw:{language:"json"}}}` | **GET 时缺失**（L1 无 body 键） | `raw` 是字符串 |
| `.request.certificate` | object | `{id:"4f23…", name:"client-cert", matches:[{pattern:"<all_urls>"}], key:{src:".tmp_engine_creds/client.key"}, cert:{src:".tmp_engine_creds/client.crt"}, pfx:null}` | 无 mTLS 时缺失 | ⚠️ 暴露**本机凭据文件路径**，证据中建议剔除 |
| `.request.description` | object | `{content:"文档 e313/e309 curl 示例…", type:"text/plain"}` | 可缺失 | — |
| `.request.auth` | — | 本机样本全部 absent | 可缺失 | 有集合级 auth 时形态未证实 |
| `.response.id` | string(uuid) | `"34cd5feb-…"` | 传输失败时整个 `.response` 缺失 | 可忽略 |
| `.response.status` | string | `"OK"` / `"Bad Request"` | 同上 | HTTP 状态文本 |
| `.response.code` | number | `200` / `400` / `500` | 同上 | HTTP 状态码（int） |
| `.response.header[]` | array | `[{key:"Date",value:"Wed, 18 Nov 2026 04:18:23 GMT"},{key:"Content-Type",value:"application/json"},{key:"Content-Length",value:"51767"},…]` | 同上 | **单数 `header`** |
| `.response.stream` | object | `{type:"Buffer", data:[123,34,111,…]}`（UTF-8 字节数组；52KB 响应 → JSON 里 ~181KB） | 同上 | **响应体唯一载体**，解码：`new String(data, UTF_8)` |
| `.response.body` | — | **不存在**（19/19 exec `hasBody=0`） | 恒缺失 | 别找 body 字段 |
| `.response.cookie[]` | array | `[]` | — | — |
| `.response.responseTime` | number | `2303`（毫秒） | — | — |
| `.response.responseSize` | number | `51767`（字节） | — | — |
| `.assertions[]` | array | `[{assertion:"SUPPLIER_EVIDENCE:{\"request\":{…},\"response\":{\"code\":200,\"body\":\"…\"}}", error:null}, {assertion:"EV_PICK1:{…}", error:null}]` | 无测试脚本时可为 `[]` | `assertion` = pm.test 名（字符串）；`error` = null（通过）或 `{name:"AssertionError",…}`（失败）。SUPPLIER_EVIDENCE 标记行本身就是「请求+响应」结构化文本（ACTION_SPEC 1582 行规定的脚本产物），**单条可达 57,046 字符**（book_run B1，因嵌了完整 body） |

传输层失败 exec 的形态（archive `newman-run-1.json` 实证，18/18 exec）：
`{cursor, item, request, id, requestError, testScript}` —— **无 `response` 键**，多 `requestError`；对应 `run.failures[]` 有条目。

`_postman_` 系字段（`collection._.postman_id`、`item.id`、`response.id`、`cursor.ref/httpRequestId`、`certificate.id`）：全部是内部标识，**证据构造可整体忽略**。

### 2.2 Schema B：Postman CLI 1.86.0 `collection run --reporter-json-export <file>`

顶层只有 `run`：

| 字段路径 | 类型 | 真实样例 | 缺失可能 | 备注 |
|---|---|---|---|---|
| `run.meta` | object | `{collectionId:"engine-20261117-omni-http", collectionName:"Engine-20261117", started:1794888674666, completed:1794888690032, duration:15366}`（cli-run-1.json 里 collectionId 是 uuid `a2138005-…`） | 不缺失 | epoch 毫秒 |
| `run.summary.iterations` | object | `{executed:1, errors:0}` | 不缺失 | — |
| `run.summary.executedRequests` | object | 全灭轮 `{executed:12, errors:12}`；成功轮 `{executed:19, errors:0}` | 不缺失 | **`errors` = 传输层失败计数**，是快速判定字段 |
| `run.summary.prerequestScripts` / `.postresponseScripts` | object | `{executed:12, errors:0}` | 不缺失 | — |
| `run.summary.tests` | object | 全灭轮 `{executed:23, failed:12, passed:11, skipped:0}`；成功轮 `{executed:86, failed:0, passed:86, skipped:0}` | 不缺失 | 字段名与 Schema A `stats` 完全不同 |
| `run.summary.timeStats` | object | `{responseAverage:0, responseMin:0, responseMax:0, responseStandardDeviation:0}`（全灭轮全 0） | 不缺失 | — |
| `run.runError` | null/object | 本机样本均 `null` | — | 非 null 实样未证实 |
| `run.executions[]` | array | — | — | 每条 keys：成功 `{iterationCount, requestExecuted, response, tests, errors}`；**传输失败 `{iterationCount, requestExecuted, tests, errors}`——`response` 键整个消失** |

`run.executions[].requestExecuted`：

| 字段路径 | 类型 | 真实样例（打码） | 缺失可能 |
|---|---|---|---|
| `.id` / `.name` | string | `"5a82cf7a-…"` / `"L1-ListProperties"` | 不缺失 |
| `.description` | object | `{content:"文档 e313/e309 curl 示例…", type:"text/plain"}` | 可缺失 |
| `.url` | object | `{protocol:"https", path:["content","v1","property"], host:["partner-api","engine","com"], query:[{key:"request.pageSize",value:"1"}], variable:[]}` | 不缺失（同样**无 raw**） |
| `.headers[]` | array | `[{key:"accept",value:"application/json"},{key:"X-Access-Key",value:"yy21****0696"},{key:"X-Signature",value:"nONG****(打码)"},{key:"User-Agent",value:"PostmanRuntime/7.56.2",system:true}]` | 不缺失；⚠️ **复数 `headers`**；含鉴权头明文 |
| `.method` | string | `"GET"` / `"POST"` | 不缺失 |
| `.body` | object | `{mode:"raw", raw:"{\"keyword\":\"tokyo\"}", options:{raw:{language:"json"}}}` | GET/无体时缺失 |

`run.executions[].response`（**exec 级，平级于 requestExecuted**；仅当发生 HTTP 往返时存在）：

| 字段路径 | 类型 | 真实样例 | 备注 |
|---|---|---|---|
| `.id` | string(uuid) | `"0c19a442-…"` | 可忽略 |
| `._details` | object | `{name:"Internal Server Error", detail:"A generic error message…", code:500, standardName:"Internal Server Error"}` | ⚠️ 内部也带 `code` 键 |
| `.status` | string | `"OK"` / `"Internal Server Error"` | — |
| `.code` | number | `200` / `400` / `401` / `500` | — |
| `.headers[]` | array | `[{key:"Date",value:"Thu, 05 Nov 2026 03:32:30 GMT"},{key:"Content-Type",value:"application/json"},…]` | **复数 `headers`**（Schema A 是单数！） |
| `.stream` | object | `{type:"Buffer", data:[123,34,99,…]}` → 解码 `{"code":0,"msg":"操作成功","data":[…]}` | 响应体唯一载体；cli-run-1 19/19 全有 stream、全无 body 字段 |
| `.cookies[]` | array | `[]` | 复数（Schema A 是 `cookie`） |
| `.responseTime` / `.responseSize` / `.downloadedBytes` | number | `135` / `84` / `84` | 毫秒 / 字节 / 字节 |

`run.executions[].tests[]`（⚠️ Schema B 把 SUPPLIER_EVIDENCE 放这里，不是 assertions）：

| 字段 | 真实样例 | 备注 |
|---|---|---|
| `.name` | `"SUPPLIER_EVIDENCE:{\"request\":{\"name\":\"L1-ListProperties\",\"method\":\"GET\",\"url\":\"https://partner-api.engine.com/content/v1/property?…\",\"body\":\"\"},\"response\":{}}"` | pm.test 名。**传输失败时 `"response":{}`（空对象）** |
| `.error` | 传输失败时 `{name:"AssertionError", index:0, test:"SUPPLIER_EVIDENCE:…", …}`；成功时该键不存在/为 null | 失败判据 |

`run.executions[].errors[]`（传输层错误；成功时为空数组）：
`{errno:-4077, code:"ECONNRESET", syscall:"read", name:"Error", message:"read ECONNRESET", stack:"Error: read ECONNRESET\n at TLSWrap.onStreamRead…", timestamp:1794888676821, stacktrace:[{fileName,lineNumber,functionName,…}], source:"request"}`

`run.executions[].iterationCount`：number，`0` 起。

---

## 3. 「哪些命令算真实实测」——本机实际出现过的调用形态与判定规则

### 3.1 实测命令形态清单（全部来自 transcript 原文）

**会产生 JSON 结果文件的（采集主目标）**：
```bash
# 形态1：postman CLI + JSON 导出（本机 3 次：sandbox/prod/docs）
postman collection run <coll.json> [-e <env.json>] [--timeout-request 60000] \
  [--reporters cli,json] --reporter-json-export <agent自选路径.json> [> <log> 2>&1]
# 形态2：newman + JSON 导出（本机 5 次：mtls/mtls2/catalogb/book/livebook）
newman run <coll.json> -e <env.json> [--folder "<名>"] \
  [--ssl-client-cert <crt> --ssl-client-key <key>] \
  --reporters cli,json --reporter-json-export <agent自选路径.json> [> <log> 2>&1]
```
常见尾随组合（同一条 Bash 命令内）：`; echo "EXIT:$?"`、`; grep -E "…" <log>`、`; node -e "…readFileSync(<同一json>)…"`、`2>&1 | tail -N`、`2>&1 | grep -o "SUPPLIER_EVIDENCE:.*"`。也观察到 `cd "D:/dida/SupplierV2" && …` 前缀（QiYouJi 轮）与无前缀直接相对路径（Engine 轮，会话 cwd 即实例目录）两种。

**不产生 JSON、只有 stdout 文本的**：
```bash
postman collection run <coll> -e <env> --timeout-request 60000 2>&1 | tail -60        # QiYouJi 11-17
postman collection run <coll> … > <路径>.stdout.log 2>&1                              # QiYouJi 归档轮
```

**必须排除的非实测形态（本机真实出现过，含 postman/newman 字样）**：
- `postman --version`、`postman whoami`、`newman -v`、`newman run --help`（⚠️ `newman run --help` 会被朴素的 `\bnewman\s+run\b` 正则误伤）
- `python ./.trellis/scripts/add_session.py --summary "…Postman CLI 6探针…"`（**总结文案里含 postman 字样**，会被子串匹配误伤）
- `cp/mv/du/grep/rm .tmp_engine_*.json …`（搬运结果文件的命令）
- `node -e "…JSON.parse(fs.readFileSync('.tmp_engine_mtls2_result.json'))…"`（读结果文件的提取脚本）

### 3.2 最小可判定条件（从上述形态归纳）

一条 Bash tool_use 判定为「实测执行」当且仅当命令文本（小写化后）**同时**满足：
1. 含 `\bpostman\s+collection\s+run\b` **或** `\bnewman\s+run\b`；
2. 不含 `--help`、`-h`、`--version`、`-v`（紧跟 run 的版本探测形态）；
3. （定位结果文件用）扫描全部 `--reporter-json-export\s+("?)(\S+?)\1` 出现处 → 0..N 个导出路径。**没有导出路径 ≠ 不是实测**（QiYouJi 形态走 stdout/`>.stdout.log`），只是没有结构化文件可解析。

**结果文件路径是 agent 自选的，实证清单**：`.tmp_engine_sandbox_result.json`、`.tmp_engine_prod_result.json`、`.tmp_engine_docs_result.json`、`.tmp_engine_sandbox_mtls.json`、`.tmp_engine_mtls2_result.json`、`.tmp_engine_catalogb.json`、`.tmp_engine_book_run.json`、`.tmp_engine_livebook_run.json`、`qiyouji-cli-run-testing.stdout.log`。**命名无固定规律（_result/_run/_mtls/无后缀都有），采集器绝不能靠文件名模式定位，只能从命令文本解析 `--reporter-json-export` 参数**（次选：解析 `>` / `>>` 重定向目标拿 stdout 日志，再按 `SUPPLIER_EVIDENCE:` 行正则提取——该形态无结构化 JSON 但标记行在文本里，本机 QiYouJi 轮实证存在）。

**相对路径解析规则**（本机两种 cwd 形态都出现过）：命令含 `cd "<dir>" &&` 前缀 → 按 `<dir>` 解析；否则按实例工作目录（`BotInstance.getClaudeWorkingDir()`，`BotInstance.java:91`；`deliverToInstance` 在 `SupplierJobService.java:720` 已算出 `dir`）。

**退出码不可作为判定条件**，实证：
- `postman … > log 2>&1; echo "EXIT:$?"`：Bash 整体退出码 = echo 的 0 → tool_result `is_error=false`，真实 CLI 退出码只存在于 stdout 文本 `EXIT:1`（11-17 04:10/04:11 两轮，12/12 请求 ECONNRESET，CLI 退出 1，**但 ToolStep.status 仍是 SUCCESS**）。
- `postman … | tail -60` / `newman … | grep …`：管道退出码 = 末命令的 0。`newman run --ssl-client-list`（未知参数直接报错）那条 tool_result 也是 `is_error=false`。
- newman 对「HTTP 400/500 但断言 pass」退出 0（mtls2 轮实证：400×4，EXIT:0）。newman 断言失败时退出码本机未直接观测（**未证实**；archive 11-04 轮有 46 failures 的导出文件但命令未带 echo EXIT）。
→ 结论：**真实成败必须从结果文件内容判**（§5），不能从 `step.status`、也不能从命令退出码判。`step.status==SUCCESS` 只该作为「Bash 本身没炸」的粗筛。

**另一个 stdout 陷阱（影响任何依赖 tool_result 文本的方案）**：Bash 输出过大时 harness 会把 tool_result 替换为 `<persisted-output>Output too large (37.2KB). Full output saved to: C:\Users\…\tool-results\bb33zin5a.txt + Preview (first 2KB)`（11-17 04:09 轮实证；23.2KB 的另一轮未被替换，阈值在 23–37KB 之间，精确值**未证实**）。即 stdout 里的 SUPPLIER_EVIDENCE 行**可能被截走**，全文落在实例机器的 tool-results 文件里。落盘 JSON 文件不受此影响——这是文件解析路线优于 stdout 解析路线的实证理由。

---

## 4. 与既有 MCP 通道采集形状对齐

### 4.1 现采集链（逐点核验）

| 位置 | 行为 |
|---|---|
| `TerminalSession.java:709-723`（handleAssistant, tool_use） | 建 `ToolStep(id,name,detail,subAgent)`；仅当 `SupplierProbeEvidence.isExecutionTool(name, input)` 为真才 `step.evidenceInput = SupplierProbeEvidence.capture(obj.get("input"))`（:714-715）。Bash 的 detail 被截到 100 字符（`extractToolDetail`，`TerminalSession.java:885-902`），**完整命令文本只在 evidenceInput 里** |
| `TerminalSession.java:728-756`（handleUser, tool_result） | `if (step.evidenceInput != null) step.evidenceOutput = capture(obj.get("content"))`（:742）——**evidenceInput 为 null 则 output 也不采**；`is_error` → `status=ERROR`（:741,743）；随后同步调 `listener.onToolEnd(step)`（:744） |
| `SupplierProbeEvidence.java:63-72`（isExecutionTool） | MCP：名字含 postman 且含 sendrequest/runcollection/executecollection；Bash：命令匹配 `.*\bpostman\s+collection\s+run\b.*` 或 `.*\bpostman\s+run\b.*`。**`newman run` 两个正则都不匹配** |
| `SupplierProbeEvidence.java:73-78`（capture） | 序列化 JsonElement；`>500000` 字符或空容器 → null |
| `SupplierJobService.java:771-782`（onToolEnd） | `SUCCESS && evidenceInput!=null && evidenceOutput!=null && (hasHttpRequest(in)‖hasHttpRequest(out)) && hasHttpResponse(out)` → 拼「## 实测工具事件 <name> @ <time> + ```json in``` + ```json out```」→ 总量 ≤1,000,000 字符累加进 `n.toolEvidence`（:779） |
| `SupplierJobService.java:677`（runNode） | **每次派发前 `n.setToolEvidence("")` 清空**——证据只算本次派发轮内采到的 |
| 门禁 | `SupplierJobService.java:874-876`（completeNode，probe 节点 toolEvidence 或 artifact 为空即抛）；`:1094-1095`（codegenPrompt 要求存在 DONE 且 toolEvidence 非空的 probe 节点）；`:878-879` 归档时 artifact+「# 实测执行证据」+toolEvidence 一起进 KB |

### 4.2 hasHttpRequest / hasHttpResponse 判定条件（原文摘录，`SupplierProbeEvidence.java`）

```java
// :16-34 hasHttpRequest —— 要点：
if (object.has("method") && object.has("url")) return true;            // :20 对象同时有 method+url 两键即真
for (var entry : object.entrySet()) if (hasHttpRequest(entry.getValue())) return true;   // :21 递归对象值
for (JsonElement item : value.getAsJsonArray()) if (hasHttpRequest(item)) return true;   // :23 递归数组
JsonElement recorded = recordedAssertion(content);                     // :26 字符串先试 SUPPLIER_EVIDENCE:{…} 标记（:10 正则 SUPPLIER_EVIDENCE:(\{[^\r\n]*})）
if (content.startsWith("{") || content.startsWith("[")) { …parseString(content)… }       // :28-30 字符串再试整体 JSON 解析

// :35-60 hasHttpResponse —— 要点：
for (String key : new String[]{"code", "statusCode", "status"}) {      // :41
    if (object.has(key) && object.get(key).isJsonPrimitive()) {
        int status = object.get(key).getAsInt();
        if (status >= 100 && status <= 599 && (object.has("body") || object.has("stream"))) return true;   // :45
    }
}
// 其余与 hasHttpRequest 对称：递归对象/数组、SUPPLIER_EVIDENCE 标记、字符串 JSON 解析（:49-57）
```

注意 `:45`：状态码键命中 **且** 同级有 `body` **或 `stream`** 键才算响应。Schema A/B 的原始 `response` 对象（`code` + `stream`）**天然满足**；但如果构造证据时只留 `code/status/header` 而裁掉 body/stream，就**不满足**。

### 4.3 从 CLI 结果文件构造的 evidence 应当长什么样

MCP 通道下 evidenceInput = MCP 工具入参 JSON（含 method/url → hasHttpRequest 直真），evidenceOutput = MCP 结构化返回（`{"response":{"code":200,"body":"…"}}` 形态，单测 `SupplierProbeEvidenceTest.java:15` 即此形状）。CLI 文件解析要对齐的就是这个形状。对每条**有 HTTP 往返**的 execution 构造：

```json
{
  "source": "postman-cli|newman",
  "collection": "<run.meta.collectionName 或 collection.info.name>",
  "round": {"started": <epoch ms>, "duration": <ms>, "executed": <n>, "withResponse": <n>, "transportErrors": <n>},
  "executions": [
    {
      "name": "<exec.item.name / requestExecuted.name>",
      "request":  {"method": "POST", "url": "<protocol://host.join('.')/path.join('?'+query)>",
                    "headers": {"content-type": "application/json"},          // system:true 的头可裁；鉴权头值打码或保留由实现定
                    "body": "<raw，截断 ≤2000 字符>"},
      "response": {"code": 200, "status": "OK",
                    "headers": {"Content-Type": "application/json"},           // 可选
                    "body": "<stream.data UTF-8 解码后截断 ≤2000 字符>",
                    "responseTime": 2303, "responseSize": 51767}
    }
  ]
}
```

- `request` 有 `method`+`url` → hasHttpRequest 真（`:20`）。
- `response` 有 `code`(100–599 int)+`body` → hasHttpResponse 真（`:45`）。**天然为真，无需依赖 SUPPLIER_EVIDENCE 标记**（标记路线作为 stdout 兜底仍有效：Schema A `assertions[].assertion`、Schema B `tests[].name` 里的 `SUPPLIER_EVIDENCE:{…}` 字符串本身就是 `:10` 正则可解析的形状——但注意单条标记可达 57,046 字符，直接嵌入会撑爆预算，且 SAFE 变体 `SUPPLIER_EVIDENCE_SAFE:` 不会被 `:10` 正则命中（`_SAFE` 挡在冒号前），其 `resp.code:null` 也不会过 `:42` 的 isJsonPrimitive+getAsInt）。
- **绝不能整文件嵌入**：`environment.values` 含 token 明文；`stream.data` 字节数组使 52KB 响应膨胀到 ~181KB JSON；`certificate` 含本机凭据路径；`item`（定义快照）与 `executions[].item.request`（未解析 `{{baseUrl}}`）会重复且失真。
- URL 拼接：两种 schema 的 `url` 都是对象且**均无 `raw` 字段**（本机 28/28 exec 实证），需 `protocol + "://" + host.join(".") + "/" + path.join("/") + "?" + query(k=v&…)`；或直接用 SUPPLIER_EVIDENCE 标记里已解析好的 url 字符串（若有标记）。

### 4.4 预算与截断（1MB / 120k / 400k 三层）

| 预算 | 位置 | 数值 | 对证据的约束 |
|---|---|---|---|
| toolEvidence 累加上限 | `SupplierJobService.java:779` | 1,000,000 字符/节点 | DB 层硬顶，超限静默不追加 |
| **KB 交付上下文** | `SupplierKbService.java:366-386`（contextText） | **全部材料共享 120,000 字符**；单文件 `Files.size > remaining*4` 直接抛「材料超过上下文预算」（:375），`content.length() > remaining` 同抛（:377） | **实际最紧的约束**：probe 工件在 completeNode 时与证据合并归档（:878-879），之后作为 KB 材料交付给后续节点。证据块若上百 KB，会把整个存档的上下文交付挤爆 |
| 派发 prompt 上限 | `SupplierJobService.java:668` | 400,000 字符 | codegenPrompt 聚合所有 probe 的 `artifact+toolEvidence`（:1098）后受此约束 |
| capture() 单事件上限 | `SupplierProbeEvidence.java:76` | 500,000 字符 | 只作用于 tool_result stdout，不作用于文件读取；文件读取需自设上限（本机最大样本 5.8MB；建议跳过 >50MB 文件） |

**每接口截断建议（与既有规范同口径）**：节点 prompt 本身要求「响应 body 截断到 2000 字」（`SupplierJobService.java:450`），实例自己的交付摘要也按「body 截 2000 字符」执行（`evidence-digest-20261118.md` 标题行）。按此口径：每 exec ≈ 2000(body)+~500(request/meta) ≈ 2.5KB；观测到的最大集合 19 接口（cli-run-1）≈ 48KB；Engine 全部 5 轮 30 条 ≈ 75KB —— 均过得了 1MB，但在 120k KB 预算里占比过半，**建议实现时同时给单节点证据总量设一个远小于 120k 的软顶（如 60–80KB），超限先裁 body 至 500 再裁 headers**。headers 截断：`system:true` 的运行时头（User-Agent/Cache-Control/Postman-Token/Accept-Encoding 等）可直接丢弃，实测头（content-type/鉴权/签名）保留键、值按需打码。

---

## 5. 失败 / 部分成功的语义（结论 + 依据）

本机实证的三类「失败」：

| 类别 | 实证样本 | Schema A 形态 | Schema B 形态 | 有无 HTTP 往返 |
|---|---|---|---|---|
| 传输层失败（mTLS 缺失 → ECONNRESET） | 1a/1b 轮 24 exec；archive newman-run-1（46 failures，18 exec 全无 response） | exec **无 `response` 键**，有 `requestError`；`run.failures[]` 非空 | exec **无 `response` 键**；`errors[]` 有 `{code:"ECONNRESET",…}`；`summary.executedRequests.errors=12`；SUPPLIER_EVIDENCE 标记成 `"response":{}` 且 `tests[].error` = AssertionError | **无** |
| HTTP 4xx/5xx（真实响应） | 第2轮 400×3+500×1、第3轮 400×4（**故意设计的 typed-error 探针**）、cli-run-1 400/401/500×5 | `response.code=400/500` + `stream` 有完整错误体（如 `{"code":3,"message":"Expected …property_ids to be >= 1…"}`）；断言 `error:null`（脚本断言 `within(100,599)` 通过）；**`run.failures=[]`** | `response.code=4xx/5xx` + `stream`；`_details.standardName` 有值 | **有** |
| 断言失败（有响应但测试不通过） | prod_result：`tests:{failed:12}`（因传输失败连带）；纯断言失败+有响应的样本本机**未证实** | `assertions[].error={name:"AssertionError",…}`，`run.failures[]` 非空 | `tests[].error` 非空 | 有 |

**结论（给 K5 的判定语义）**：

1. **传输层失败的 execution 不写证据记录**（或写入但绝不含 `code`+`body/stream` 组合）。依据：两种 schema 里都能确定性识别（`response` 键缺失 / `errors[]`/`requestError` 非空）；即使偷懒直接嵌原始 exec JSON，`hasHttpResponse` 也因无 code+body/stream 而为假——但**不要依赖这个兜底**，因为 Schema B 的 `response._details.code` 和标记里 `"response":{}` 的存在说明字段形状有坑，显式过滤最稳。**写了会不会被误认成功**：传输失败的原始 JSON 不含 `code` 于 response 级（键都不存在），不会被 `:41-45` 认成成功；唯一风险点是 `_details.code`（无 body/stream 同级键，`:45` 仍为假）——安全，但证据文本里留着 `_details` 没有意义。
2. **HTTP 4xx/5xx 且有真实响应体 = 算「成功的实测工具记录」**。依据三条：(a) 门禁原文的对照面是「文字报告与自由 curl 不能代替**真实请求/返回**」——判定核心是真实往返，不是业务成功；(b) 现行 `hasHttpResponse` 就是 100–599 全收（`:45`），MCP 通道一直如此，CLI 通道对齐即可，否则同一门禁出现双标；(c) 本轮实测工作流里 typed-400 探针（第 3 轮「写型必败探针」）与 500 供应商缺陷（第 2 轮 L5）恰恰是**最有价值的取证结果**，被实例归档为有效轮次——把它们排除会把门禁变成「只认 2xx」的错东西。
3. **「全失败但有真实往返」（如 12/12 全 400）**：按 2 的口径**过门禁**。这是有意的：probe 节点的交付物是「文档 vs 实际」差异档案，全 4xx 的往返记录仍然是真实档案。**「全传输失败（零往返）」**：0 条证据记录 → `toolEvidence` 保持空 → `:874` 照拦（1a/1b 轮若发生在节点轮内即此结果）——门禁保留牙齿，**不是橡皮图章**。橡皮图章风险的真实边界在别处：agent 可以伪造一个结果文件——但这与 MCP 通道「agent 可以编造 tool_result」是同一信任模型，K5 没有放大它（文件来源命令必须是 isExecutionTool 形态、路径来自本条命令的 --reporter-json-export 参数，读的是**本条命令刚写出的文件**）。
4. 轮级汇总字段建议随证据落一条（供 K6 可解释性直接用，全部可从文件确定性计算）：`executed / withResponse / transportErrors / httpNon2xx / assertionFailed`（Schema A 用 `run.stats.assertions.failed`+逐 exec 扫描；Schema B 用 `summary.executedRequests.errors`+`summary.tests.failed`+逐 exec 扫描）。
5. `run.error`（A）/ `run.runError`（B）非 null = 整轮中断：本机样本全为 null，非 null 时的文件完整性**未证实**——实现时对解析异常/半截 JSON 直接按「0 证据 + 记一条解析失败原因」处理即可，不要半信半疑地部分采纳。

---

## 6. 可行性结论（第 5 点，精确到行）

**结论：可行，但「只加一个纯解析函数 + 一处 onToolEnd 分支」不成立——还差两处既有点的小改（合计 ~3 行级），缺环如下。**

**缺环 1（致命，newman 全灭的直接原因）：`SupplierProbeEvidence.java:63-72` `isExecutionTool` 不认 newman。**
Bash 分支正则只有 `.*\bpostman\s+collection\s+run\b.*` 和 `.*\bpostman\s+run\b.*`（`:69`）。今天两次重跑的命令都是 `newman run …` → `TerminalSession.java:714` 判假 → `evidenceInput` 恒 null → `:742` 连 `evidenceOutput` 都不采 → `SupplierJobService.java:772` 第一个 null 检查短路。签名不变，只需在 `:69` 增加 `|| cmd.matches("(?s).*\\bnewman\\s+run\\b.*")` 并排除 `--help`/`-v`（`newman run --help` 本机真实出现过）。不改这里，onToolEnd 里连完整命令文本都拿不到（`step.detail` 截 100 字符，`TerminalSession.java:901`，观测命令 200–1000+ 字符，`--reporter-json-export` 常在 100 字符之外）。

**缺环 2（致命，文件无法定位）：`SupplierJobService.java:744` `progressListener` 拿不到实例工作目录。**
现签名 `private TurnListener progressListener(SupplierJobNode n, AtomicReference<TurnResult> usage)`；导出路径观测全为**相对路径**，必须相对实例 cwd 解析。`dir` 就在唯一调用点的作用域里（`deliverToInstance`：`:720` 算出 `dir = firstNonBlankStr(inst.getClaudeWorkingDir(), inst.getShellWorkingDir(), "")`，`:723` 调用 `progressListener(n, usage)`）→ 签名加第三参 `String dir`、调用点透传即可（另有单参重载 `:742`，同步补默认值）。命令带 `cd "<dir>" &&` 前缀时优先用前缀目录（QiYouJi 轮实证该形态）。

**缺环 3（本体，新增件）：结果文件内容从不出现在任何工具事件里——唯一通路是 onToolEnd 时按解析出的路径读盘。**
- 新增纯解析函数（建议挂 `SupplierProbeEvidence`，与既有谓词同类，且有现成单测锚点 `src/test/java/com/lark/oapi/sample/ws/service/SupplierProbeEvidenceTest.java`）：`命令文本 → {tool, exportPaths[], cdDir?}` 与 `结果文件 JSON → §4.3 形状的证据 JsonArray（含 §4.4 截断、§5 失败过滤、schema A/B 判别）`。
- `SupplierJobService.java:771-782` onToolEnd 加分支：`step.status==SUCCESS && "bash".equalsIgnoreCase(step.name) && evidenceInput!=null` → 解析命令 → 有 exportPaths → 逐个读文件（大小上限自设）→ 构造证据块（沿用 `:776-777` 的「## 实测工具事件」文本格式与 `:779` 的 1MB 预算）→ **与既有 stdout-marker 判定互斥去重**（`postman collection run` 同时命中新旧两路：文件解析成功则跳过 `:772-775` 老判定，失败再回落老判定，避免同一 step 写两块）。
- 线程事实：onToolEnd 在 TerminalSession 事件读线程上同步执行（`TerminalSession.java:744` 调用点），读 ≤6MB 文件是毫秒~几十毫秒级阻塞；如嫌重可在分支内丢虚拟线程，但要注意 `:778` 的 `synchronized(n)` 累加与 `applyNodeReply` 落库的时序（轮次结束前必须完成，否则证据赶不上 `nodeRepo.save(n)`）。
- 时序安全：`--reporter-json-export` 文件在 CLI 进程退出前写完（实证：同一条 Bash 命令内 `; echo EXIT:$?; node -e "readFileSync(同一文件)"` 立即解析成功），onToolEnd 晚于 tool_result，读得到完整文件。传输层全灭轮也会写文件（prod/sandbox_result.json 即 EXIT:1 轮的产物）——文件存在性与成败无关，成败按 §5 从内容判。

**不需要动的**：TerminalSession 事件结构/ToolStep 字段（evidenceInput/evidenceOutput 已够用）、ClaudeCodeService、SDK 协议侧、`:874` 门禁本身（K5 修好后该门禁对真实往返轮次自然放行；AC7′ 的「n2 完成与 n4 codegen 两处门禁免 force」随 `:874` 与 `:1094-1095` 同源于 toolEvidence 非空而一并满足）。

**顺带核验的事实（影响验收口径）**：`runNode` 每次派发清空 toolEvidence（`SupplierJobService.java:677`），所以「重跑一次即 tool_evidence>0」的前提是**该次派发轮内**有可解析的实测命令；实例若像 1570 字那轮一样只做归档搬运（cp/mv/rm + 写摘要，无任何 run 命令），证据仍为 0——这是正确行为（本轮确实没实测），K6 的可解释输出应能区分「0 个实测形态命令」与「有命令但解析失败」。

---

## 7. 与任务书背景的两点事实校正

1. 任务书称根因是「Bash 的 tool_result 是文本 blob，没有 evidenceInput/evidenceOutput 结构化字段」——**只对了一半**：`isExecutionTool` 命中时 Bash 的 input/stdout **会被**存进 evidenceInput/evidenceOutput（`TerminalSession.java:714-715, :742`），且 stdout 里的 `SUPPLIER_EVIDENCE:{…}` 标记文本**本来就能**被 `recordedAssertion` 路径判真（单测 `SupplierProbeEvidenceTest.java:18-20` 覆盖此形状；11-17 QiYouJi 轮 stdout 实证含完整标记）。今天采集为 0 的精确原因是三选三全踩：**① 命令是 `newman run`（isExecutionTool 不认，两字段根本没采）；② 即便认，stdout 被 `> .log 2>&1` 重定向/`| grep`/persisted-output 截断，标记不在 tool_result 里；③ 1570 字那轮压根没有实测命令（纯归档搬运）**。结构化 JSON 只存在于 `--reporter-json-export` 落盘文件里，而现采集链不读盘——这才是需要 K5 补的能力边界。
2. `.tmp_engine_sandbox_mtls.json` 不是「collection 定义（不是结果）」——它是 newman 导出，**定义与结果合体**，其 `run.executions[]` 含 7 条真实响应（200×3/400×3/500×1），本身就是合格的结果文件样本。

## 8. 未证实清单

- `.tmp_engine_docs_result.json` / `.tmp_engine_evidence*.js/.txt` / `.tmp_engine_run_docs.log` 的内容（已被实例 11-18 15:19 清理命令删除；仅命令与大小可从 transcript/任务书核验）。
- 「47 次调用」的精确构成（自报数，磁盘不可重建）。
- newman 断言失败时的进程退出码（本机未观测到带 echo EXIT 的断言失败轮）。
- Schema A `run.error` / Schema B `run.runError` 非 null 时的文件形态（本机样本全 null）。
- Schema A 未传 `-e` 时 `environment/globals` 的取值（null 还是缺键）。
- persisted-output 的精确触发阈值（观测边界：23.2KB 未触发、37.2KB 触发）。
- `request.auth` 非空时的导出形态（本机样本全部 absent）。
- 集合定义里带 saved response（`item.response` 非空数组）时 Schema A 的形态。

## 附：本次调研产物（均在本目录，只读脚本，未触碰任何产品代码/样本文件）

- `parse_structure.js` / `parse_detail.js` / `parse_schemab.js` / `parse_schemab2.js` — 结果文件结构 dump（node，只读）
- `parse_transcripts.js` / `scan_tail.js` — transcript 命令形态提取（node，只读）
