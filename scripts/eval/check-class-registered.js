#!/usr/bin/env node
'use strict';
// 供应商实现类存在且被注册检查（grep 级确定性规则，无 AST、无 npm 依赖）。
// 用法: node check-class-registered.js <类名> [定义根=supplierDir] [引用根=repoPath]
//   定义根: 查找类定义（class/interface）的目录，应 = 供应商目录（如 <repo>/src/Business/Supplier/<供应商>），
//           缺省 = 当前工作目录。
//   引用根: 查找类名引用的目录，应 = 仓库根——注册点（工厂/映射/枚举）天然在供应商目录之外，
//           引用根收窄到供应商目录会把"注册点在目录外"的正经供应商判成假红（MVP-1.6 实测缺陷）。
//           缺省 = 定义根（兼容旧的单根调用形态）。
// PASS 判定：① 定义根下找到类定义 且 ② 引用根下至少一个"定义文件之外"的文件引用该类名。
// ⚠ 代理判据声明：本检查只是「已注册」的代理判据——类名被引用 ≠ 真注册；真实注册可能走
//   SupplierID/枚举/配置映射而不按类名引用。已跑通的供应商在这里报红 → 先人工核实注册方式再调整本 eval。
const fs = require('fs');
const path = require('path');

const SKIP_DIRS = new Set(['bin', 'obj', '.git', 'node_modules', '.vs', '.idea', 'packages', 'TestResults']);
const FILE_CAP = 20000;
const PROXY_NOTE = '（注：类名被引用只是「已注册」的代理判据——真实注册可能走 SupplierID/枚举/配置映射而不按类名引用）';

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
  const defRoot = process.argv[3] || process.cwd();
  const refRoot = process.argv[4] || defRoot;
  if (!name) {
    console.log('FAIL: 缺少参数：类名（用法 node check-class-registered.js <类名> [定义根=supplierDir] [引用根=repoPath]）');
    process.exit(1);
  }
  if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) {
    console.log('FAIL: 类名不合法（只允许字母/数字/下划线）: ' + name);
    process.exit(1);
  }
  if (!fs.existsSync(defRoot)) {
    console.log('FAIL: 定义根目录（supplierDir）不存在: ' + defRoot);
    process.exit(1);
  }
  if (!fs.existsSync(refRoot)) {
    console.log('FAIL: 引用根目录（repoPath）不存在: ' + refRoot);
    process.exit(1);
  }
  if (path.resolve(refRoot) === path.resolve(defRoot)) {
    console.log('WARN: 引用根 = 定义根（' + defRoot + '）——注册点（工厂/映射）通常在供应商目录之外，'
      + '单根扫描可能对正经供应商假红；建议第三参传仓库根');
  }
  const defFilesScanned = [];
  collectCsFiles(defRoot, defFilesScanned);
  if (defFilesScanned.length === 0) {
    console.log('FAIL: 定义根 ' + defRoot + ' 下没扫到任何 .cs 文件——请检查 supplierDir 是否指向正确的供应商目录');
    process.exit(1);
  }
  const refFilesScanned = [];
  collectCsFiles(refRoot, refFilesScanned);
  if (refFilesScanned.length === 0) {
    console.log('FAIL: 引用根 ' + refRoot + ' 下没扫到任何 .cs 文件——请检查 repoPath 是否指向仓库根');
    process.exit(1);
  }
  const defRe = new RegExp('\\b(?:class|interface)\\s+' + name + '\\b');
  const refRe = new RegExp('\\b' + name + '\\b');
  const defFiles = [];
  for (const f of defFilesScanned) {
    let text;
    try { text = fs.readFileSync(f, 'utf8'); } catch (e) { continue; }
    if (defRe.test(text)) defFiles.push(f);
  }
  if (defFiles.length === 0) {
    console.log('FAIL: 供应商目录（' + defRoot + '）下未找到类 ' + name + ' 的定义（已扫描 '
      + defFilesScanned.length + ' 个 .cs 文件）');
    process.exit(1);
  }
  const defSet = new Set(defFiles.map(f => path.resolve(f)));
  const refFiles = [];
  for (const f of refFilesScanned) {
    if (defSet.has(path.resolve(f))) continue;   // 定义文件本身不算引用
    let text;
    try { text = fs.readFileSync(f, 'utf8'); } catch (e) { continue; }
    if (defRe.test(text)) continue;              // 别处的重复定义也不算引用（与旧口径一致）
    if (refRe.test(text)) refFiles.push(f);
  }
  if (refFiles.length === 0) {
    console.log('FAIL: 类 ' + name + ' 只有定义（' + defFiles.join(', ') + '），引用根（' + refRoot
      + '）下没有任何其他文件引用它。' + PROXY_NOTE + '——若确认该类已通过 SupplierID/枚举/配置映射注册，'
      + '本条属假红，请人工核实后调整本 eval。');
    process.exit(1);
  }
  console.log('OK 定义: ' + defFiles.join(', ') + '; 引用根下另有 ' + refFiles.length
    + ' 个文件引用（如 ' + refFiles[0] + '）' + PROXY_NOTE);
}

main();
