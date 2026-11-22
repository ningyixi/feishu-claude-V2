#!/usr/bin/env node
'use strict';
// HTTP 端点客观连通检查（11-21-supplier-eval-loop MVP-1.5 内置模板脚本）。
// 用法: node check-http-json.js <METHOD> <URL> [bodyJsonFile]
// 真实发一次请求（node 内置 fetch，无 npm 依赖），断言全部来自响应本身：
//   ① 请求完成（网络层没抛错）② 状态码 < 500 ③ 响应体非空 ④ 响应体可解析为 JSON。
// 通过 → 退出码 0 + stdout「OK status=<码> bytes=<字节>」；否则退出码 1 + 原因与响应摘录。
// 注意：这是基础连通/结构判定，不等价于工作台实测门禁认可的 Postman 证据
//（SupplierProbeEvidence 只认 Postman MCP / postman collection run）。
const fs = require('fs');

const TIMEOUT_MS = 60000;

async function main() {
  const [method, url, bodyFile] = process.argv.slice(2);
  if (!method || !url) {
    console.log('FAIL: 用法 node check-http-json.js <METHOD> <URL> [bodyJsonFile]');
    process.exit(1);
  }
  let body;
  if (bodyFile) {
    try {
      body = fs.readFileSync(bodyFile, 'utf8');
    } catch (e) {
      console.log('FAIL: 请求体文件读不到 ' + bodyFile + ': ' + e.message);
      process.exit(1);
    }
    try {
      JSON.parse(body);
    } catch (e) {
      console.log('FAIL: 请求体文件不是合法 JSON ' + bodyFile + ': ' + e.message);
      process.exit(1);
    }
  }
  let res;
  try {
    res = await fetch(url, {
      method: method.toUpperCase(),
      headers: body ? { 'Content-Type': 'application/json' } : undefined,
      body,
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
  } catch (e) {
    console.log('FAIL: 请求未完成（' + method.toUpperCase() + ' ' + url + '）: ' + e.message);
    process.exit(1);
  }
  const text = await res.text();
  const problems = [];
  if (res.status >= 500) problems.push('状态码 ' + res.status + '（5xx 服务端错误）');
  if (!text.trim()) problems.push('响应体为空');
  else {
    try { JSON.parse(text); } catch (e) { problems.push('响应体不是合法 JSON: ' + e.message); }
  }
  if (problems.length > 0) {
    console.log('FAIL: ' + problems.join('; '));
    console.log('响应摘录（前 500 字符）: ' + text.slice(0, 500));
    process.exit(1);
  }
  console.log('OK status=' + res.status + ' bytes=' + Buffer.byteLength(text));
}

main().catch(e => { console.log('FAIL: 脚本异常: ' + e.message); process.exit(1); });
