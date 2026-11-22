#!/usr/bin/env node
'use strict';
// 配置结构检查（11-21-supplier-eval-loop MVP-1.5 内置模板脚本）。
// 用法: node check-config-shape.js <configJsonPath>
// 判定纯确定性：全部断言通过 → 退出码 0 + stdout「OK」；任何缺失/不符 → 退出码 1 + 逐条打印。
// 不调用任何模型。断言内容：SupplierID 数字、Urls 七键齐全、AccountDic 非空且每账号
// ConfirmMode ∈ {INSTANT,ONREQUEST}、paymentType 数字。
const fs = require('fs');
const { validateConfigShape } = require('./config-shape-core.js');

function main() {
  const file = process.argv[2];
  if (!file) {
    console.log('FAIL: 缺少参数：配置文件路径（用法 node check-config-shape.js <configJsonPath>）');
    process.exit(1);
  }
  let raw;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } catch (e) {
    console.log('FAIL: 配置文件读不到 ' + file + ': ' + e.message);
    process.exit(1);
  }
  if (!raw.trim()) {
    console.log('FAIL: 配置文件为空: ' + file);
    process.exit(1);
  }
  let cfg;
  try {
    cfg = JSON.parse(raw);
  } catch (e) {
    console.log('FAIL: 配置文件不是合法 JSON: ' + e.message);
    process.exit(1);
  }
  const problems = validateConfigShape(cfg);
  if (problems.length > 0) {
    console.log('FAIL: 配置结构检查发现 ' + problems.length + ' 处问题:');
    for (const p of problems) console.log(' - ' + p);
    process.exit(1);
  }
  console.log('OK');
}

main();
