#!/usr/bin/env node
'use strict';
// 配置中心真读检查（11-21-supplier-eval-loop MVP-1.5 内置模板脚本）。
// 【协议未对真实配置中心验证过】——本脚本按最常见的"HTTP GET 取配置值"形态实现，
// 若公司配置中心不是这个协议，直接改这个文件（判定仍必须落在退出码 + 输出上）。
// 用法: node fetch-config-center.js <configKey>
// 依赖环境变量：
//   CONFIG_CENTER_URL   必填。取值端点；含 {key} 占位则替换为 configKey，否则追加 ?key=<configKey>
//   CONFIG_CENTER_TOKEN 可选。作为 Authorization: Bearer 头发送
// 断言：HTTP 2xx、响应可解析 JSON、从中取出配置对象（兼容 value/data 包一层）后跑结构校验
//（与 check-config-shape.js 同一套断言）。通过 → 退出码 0 + stdout「OK」。
const { validateConfigShape } = require('./config-shape-core.js');

const TIMEOUT_MS = 30000;

function looksLikeSupplierConfig(o) {
  return o && typeof o === 'object' && !Array.isArray(o)
    && ('SupplierID' in o || 'Urls' in o || 'AccountDic' in o);
}

function pickConfig(parsed) {
  // 配置中心常见回包：{value:"<json字符串>"} / {data:{...}} / {content:"..."} / 直接就是配置。
  // 逐个候选尝试（字符串先 JSON.parse），优先返回"像供应商配置"的那个；都不像则返回本体让校验逐条报错。
  const candidates = [parsed && parsed.value, parsed && parsed.data, parsed && parsed.content, parsed];
  let firstObject = null;
  for (const c of candidates) {
    if (c == null) continue;
    let obj = c;
    if (typeof c === 'string') {
      try { obj = JSON.parse(c); } catch (e) { continue; }
    }
    if (obj && typeof obj === 'object') {
      if (looksLikeSupplierConfig(obj)) return obj;
      if (firstObject === null) firstObject = obj;
    }
  }
  return firstObject !== null ? firstObject : parsed;
}

async function main() {
  const key = process.argv[2];
  if (!key) {
    console.log('FAIL: 缺少参数 configKey（用法 node fetch-config-center.js <configKey>）');
    process.exit(1);
  }
  const base = process.env.CONFIG_CENTER_URL;
  if (!base || !base.trim()) {
    console.log('FAIL: 环境变量 CONFIG_CENTER_URL 未设置，无法访问配置中心（本模板需沙箱凭据，未配置时应记「跳过」）');
    process.exit(1);
  }
  const url = base.includes('{key}')
    ? base.split('{key}').join(encodeURIComponent(key))
    : base.replace(/\?$/, '') + (base.includes('?') ? '&' : '?') + 'key=' + encodeURIComponent(key);
  const headers = {};
  if (process.env.CONFIG_CENTER_TOKEN) headers['Authorization'] = 'Bearer ' + process.env.CONFIG_CENTER_TOKEN;
  let res;
  try {
    res = await fetch(url, { headers, signal: AbortSignal.timeout(TIMEOUT_MS) });
  } catch (e) {
    console.log('FAIL: 配置中心请求未完成（' + url + '）: ' + e.message);
    process.exit(1);
  }
  const text = await res.text();
  if (res.status < 200 || res.status >= 300) {
    console.log('FAIL: 配置中心返回状态码 ' + res.status + '；响应摘录: ' + text.slice(0, 300));
    process.exit(1);
  }
  let parsed;
  try { parsed = JSON.parse(text); } catch (e) {
    console.log('FAIL: 配置中心响应不是合法 JSON: ' + e.message + '；摘录: ' + text.slice(0, 300));
    process.exit(1);
  }
  const cfg = pickConfig(parsed);
  const problems = validateConfigShape(cfg);
  if (problems.length > 0) {
    console.log('FAIL: 配置中心取到 ' + key + '，但结构校验发现 ' + problems.length + ' 处问题:');
    for (const p of problems) console.log(' - ' + p);
    process.exit(1);
  }
  console.log('OK 配置中心 ' + key + ' 取到且结构校验通过');
}

main().catch(e => { console.log('FAIL: 脚本异常: ' + e.message); process.exit(1); });
