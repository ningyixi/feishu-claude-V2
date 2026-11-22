#!/usr/bin/env node
'use strict';
// Urls 协议与前缀一致性检查（11-21-supplier-eval-loop MVP-1.5 内置模板脚本）。
// 用法: node check-urls-https.js <configJsonPath>
// 断言：配置里 Urls.* 全部是 https 绝对地址，且全部同源（协议+主机+端口一致，即"同前缀"）。
// 通过 → 退出码 0 + stdout「OK」；否则退出码 1 + 逐条打印。纯确定性，无模型参与。
const fs = require('fs');

function main() {
  const file = process.argv[2];
  if (!file) {
    console.log('FAIL: 缺少参数：配置文件路径（用法 node check-urls-https.js <configJsonPath>）');
    process.exit(1);
  }
  let cfg;
  try {
    const raw = fs.readFileSync(file, 'utf8');
    if (!raw.trim()) { console.log('FAIL: 配置文件为空: ' + file); process.exit(1); }
    cfg = JSON.parse(raw);
  } catch (e) {
    console.log('FAIL: 配置文件读取/解析失败 ' + file + ': ' + e.message);
    process.exit(1);
  }
  const urls = cfg && typeof cfg === 'object' ? cfg.Urls : null;
  if (urls === null || typeof urls !== 'object' || Array.isArray(urls)) {
    console.log('FAIL: Urls 缺失或不是对象');
    process.exit(1);
  }
  const entries = Object.entries(urls).filter(([, v]) => typeof v === 'string' && v.trim());
  if (entries.length === 0) {
    console.log('FAIL: Urls 没有任何非空字符串条目');
    process.exit(1);
  }
  const problems = [];
  let firstOrigin = null;
  let firstKey = '';
  for (const [k, v] of entries) {
    let u;
    try {
      u = new URL(v);
    } catch (e) {
      problems.push('Urls.' + k + ': 不是合法绝对地址: ' + v);
      continue;
    }
    if (u.protocol !== 'https:') {
      problems.push('Urls.' + k + ': 协议不是 https（当前 ' + u.protocol.replace(':', '') + '）: ' + v);
    }
    if (firstOrigin === null) {
      firstOrigin = u.origin;
      firstKey = k;
    } else if (u.origin !== firstOrigin) {
      problems.push('Urls.' + k + ': 前缀与 ' + firstKey + '（' + firstOrigin + '）不一致: ' + v);
    }
  }
  if (problems.length > 0) {
    console.log('FAIL: Urls 检查发现 ' + problems.length + ' 处问题:');
    for (const p of problems) console.log(' - ' + p);
    process.exit(1);
  }
  console.log('OK 共 ' + entries.length + ' 条 Urls 全部为 https 且同前缀（' + firstOrigin + '）');
}

main();
