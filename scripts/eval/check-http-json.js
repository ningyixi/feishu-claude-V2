#!/usr/bin/env node
'use strict';
// HTTP 端点客观连通检查（11-21-supplier-eval-loop MVP-1.5 内置模板脚本）。
// 用法: node check-http-json.js <METHOD> <URL> [bodyJsonFile]
// 真实发一次请求（node 内置 fetch，无 npm 依赖），断言全部来自响应本身：
//   ① 请求完成（网络层没抛错）② 状态码 < 500 ③ 响应体非空 ④ 响应体可解析为 JSON。
// 通过 → 退出码 0 + stdout「OK status=<码> bytes=<字节>」；否则退出码 1 + 原因与响应摘录。
// 注意：这是基础连通/结构判定，不等价于工作台实测门禁认可的 Postman 证据
//（SupplierProbeEvidence 只认 Postman MCP / postman collection run）。
//
// 【eval 禁止写死时间】（PRD MVP-1.7 规则 5，模板与 target 同规——DCYiLv 集合里写死的
//   11/20-21 就是前车之鉴：今天绿、下周红，且没人知道为什么）：
//   请求体里的住期/日期类字段（checkIn/checkOut/arrival/departure/…，或 <RUNTIME…> 标记、
//   日期字面量值）一律在运行时重写为「今天+7 / 今天+8」再发送，并打印实际使用的日期。
//   模板与 target 里不许出现任何写死日期（EvalTemplatesTest 有单测钉死这条规则）。
const fs = require('fs');

const TIMEOUT_MS = 60000;
const DATE_NAME = /(checkin|check_in|checkout|check_out|arrival|departure|startdate|start_date|enddate|end_date|^date$)/i;
const OUT_NAME = /(out|departure|end)/i;
const DATE_VALUE = /^(\d{4}-\d{1,2}-\d{1,2}|(19|20)\d{6}|\d{1,2}[\/-]\d{1,2}([\/-]\d{2,4})?|<RUNTIME[^>]*>)$/;

function pad(n) { return String(n).padStart(2, '0'); }
function fmtDate(d) { return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
function plusDays(n) { const d = new Date(); d.setDate(d.getDate() + n); return fmtDate(d); }

// 请求体顶层日期字段运行时动态生成：checkOut 类 = 今天+8，其余 = 今天+7（住期恒为未来 1 晚）
function rewriteDates(obj) {
  const rewritten = [];
  if (obj === null || typeof obj !== 'object' || Array.isArray(obj)) return rewritten;
  for (const k of Object.keys(obj)) {
    const v = obj[k];
    if (typeof v !== 'string') continue;
    if (!DATE_NAME.test(k) && !DATE_VALUE.test(v)) continue;
    const out = OUT_NAME.test(k);
    const nv = plusDays(out ? 8 : 7);
    obj[k] = nv;
    rewritten.push(k + '=' + nv + '（今天+' + (out ? 8 : 7) + '，原值「' + v + '」已重写）');
  }
  return rewritten;
}

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
    let parsedBody;
    try {
      parsedBody = JSON.parse(body);
    } catch (e) {
      console.log('FAIL: 请求体文件不是合法 JSON ' + bodyFile + ': ' + e.message);
      process.exit(1);
    }
    // 【eval 禁止写死日期】住期/日期字段一律运行时动态生成后再发送（见文件头注释）
    const rewritten = rewriteDates(parsedBody);
    if (rewritten.length > 0) {
      console.log('实际使用的日期（运行时生成，eval 禁止写死日期）: ' + rewritten.join(' '));
    }
    body = JSON.stringify(parsedBody);
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
