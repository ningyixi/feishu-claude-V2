#!/usr/bin/env node
'use strict';
// 供应商实现类存在且被注册检查（grep 级确定性规则，无 AST、无 npm 依赖）。
// 用法: node check-class-registered.js <类名> [扫描根目录]
// 扫描根目录默认 = 当前工作目录（eval 执行时 = 绑定实例的工作目录，应指向供应商仓库根）。
// PASS 判定：① 找到类定义（class/interface <类名>）且 ② 至少一个"定义文件之外"的文件引用了该类名
//（工厂/注册映射/ DI 里必然再次出现类名）。纯文本检索——确定、可复现，不做语义理解。
const fs = require('fs');
const path = require('path');

const SKIP_DIRS = new Set(['bin', 'obj', '.git', 'node_modules', '.vs', '.idea', 'packages', 'TestResults']);
const FILE_CAP = 20000;

function collectCsFiles(root, out) {
  let entries;
  try { entries = fs.readdirSync(root, { withFileTypes: true }); } catch (e) { return; }
  for (const ent of entries) {
    if (out.length >= FILE_CAP) return;
    const full = path.join(root, ent.name);
    if (ent.isDirectory()) {
      if (!SKIP_DIRS.has(ent.name)) collectCsFiles(full, out);
    } else if (ent.name.endsWith('.cs')) {
      out.push(full);
    }
  }
}

function main() {
  const name = process.argv[2];
  const root = process.argv[3] || process.cwd();
  if (!name) {
    console.log('FAIL: 缺少参数：类名（用法 node check-class-registered.js <类名> [扫描根目录]）');
    process.exit(1);
  }
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) {
    console.log('FAIL: 类名不合法（只允许字母/数字/下划线）: ' + name);
    process.exit(1);
  }
  if (!fs.existsSync(root)) {
    console.log('FAIL: 扫描根目录不存在: ' + root);
    process.exit(1);
  }
  const files = [];
  collectCsFiles(root, files);
  if (files.length === 0) {
    console.log('FAIL: ' + root + ' 下没扫到任何 .cs 文件——请检查执行工作目录/实例绑定是否指向供应商仓库');
    process.exit(1);
  }
  const defRe = new RegExp('\\b(?:class|interface)\\s+' + name + '\\b');
  const refRe = new RegExp('\\b' + name + '\\b');
  const defFiles = [];
  const refFiles = [];
  for (const f of files) {
    let text;
    try { text = fs.readFileSync(f, 'utf8'); } catch (e) { continue; }
    if (defRe.test(text)) defFiles.push(f);
    else if (refRe.test(text)) refFiles.push(f);
  }
  if (defFiles.length === 0) {
    console.log('FAIL: 未找到类 ' + name + ' 的定义（已扫描 ' + files.length + ' 个 .cs 文件）');
    process.exit(1);
  }
  if (refFiles.length === 0) {
    console.log('FAIL: 类 ' + name + ' 只有定义（' + defFiles.join(', ') + '），'
      + '没有任何其他文件引用它——未注册进工厂/映射');
    process.exit(1);
  }
  console.log('OK 定义: ' + defFiles.join(', ') + '; 另有 ' + refFiles.length
    + ' 个文件引用（如 ' + refFiles[0] + '）');
}

main();
