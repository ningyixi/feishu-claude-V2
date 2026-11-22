#!/usr/bin/env node
'use strict';
// 「本次交付有没有配套测试」确定性检查（11-21-supplier-eval-loop MVP-1.7，grep 级，无 AST、无 npm 依赖）。
// 用法: node check-has-unit-test.js <供应商类名> <扫描根目录> [更多扫描根目录...]
//   典型根：supplierDir（供应商目录）与/或 testProjectPath 所在工程目录——脚本挨个根扫，至少给一个存在的根。
// 判据（全部是文件与符号存在性，零模型判断）：
//   ① 扫描根下存在 .cs 测试文件（含 [Fact] / [Theory] / [Test] / [TestCase] 特性）；
//   ② 其中至少一个文件【引用了供应商类名】（同现即算，子串匹配、大小写敏感——C# 类名大小写敏感）。
// 通过 → 退出码 0 + stdout「OK 找到 N 个引用 <类名> 的测试文件」并逐个列出路径。
// 失败 → 退出码 1 + 可定位原因（哪个根不存在 / 扫了几个文件 / 有没有测试文件 / 是否引用了类名）。
// 防假绿：所有根都不存在、或一个 .cs 都没扫到 → 一律 FAIL（绝不在错误目录里"零文件 = 恒绿"）。
// 注意：本脚本回答的是 review 检查表「本次交付有没有配套测试」这一条——文件+符号存在性是确定性代理判据；
//       测试能否真跑通过由 e2e-supplier-unit-test（dotnet test）回答，两条配套使用。
// 【eval 禁止写死日期】（模板与 target 同规）：本脚本无任何日期参数；将来若需要，一律运行时动态生成。
const fs = require('fs');
const path = require('path');

const SKIP_DIRS = new Set(['bin', 'obj', '.git', 'node_modules', '.vs', '.idea', 'packages', 'TestResults']);
const FILE_CAP = 20000;
// xunit: [Fact] / [Theory]；nunit: [Test] / [TestCase(...)]
const TEST_ATTR_RE = /\[\s*(?:Fact|Theory|Test|TestCase)\s*[\](]/;

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
  const [className, ...roots] = process.argv.slice(2);
  if (!className || roots.length === 0) {
    console.log('FAIL: 用法 node check-has-unit-test.js <供应商类名> <扫描根目录> [更多扫描根目录...]');
    process.exit(1);
  }
  const validRoots = [];
  const badRoots = [];
  for (const r of roots) {
    // 容错：变量未展开成 "{{xxx}}" 原样传进来时视为没给（fail-loud，不静默当成合法根）
    if (r.includes('{{') || !fs.existsSync(r)) badRoots.push(r);
    else validRoots.push(r);
  }
  if (validRoots.length === 0) {
    console.log('FAIL: 没有任何存在的扫描根: ' + badRoots.join('、')
      + ' ——请检查 supplierDir/testProjectPath 上下文变量是否指向真实目录');
    process.exit(1);
  }
  const files = [];
  for (const r of validRoots) collectCsFiles(r, files);
  if (files.length === 0) {
    console.log('FAIL: ' + validRoots.join('、') + ' 下没扫到任何 .cs 文件——请检查扫描根是否指向供应商仓库');
    process.exit(1);
  }
  const testFiles = [];
  const hits = [];
  for (const f of files) {
    let text;
    try { text = fs.readFileSync(f, 'utf8'); } catch (e) { continue; }
    if (!TEST_ATTR_RE.test(text)) continue;
    testFiles.push(f);
    if (text.includes(className)) hits.push(f);
  }
  if (hits.length > 0) {
    console.log('OK 找到 ' + hits.length + ' 个引用 ' + className + ' 的测试文件（扫描根: '
      + validRoots.join('、') + '；共 ' + files.length + ' 个 .cs，其中测试文件 ' + testFiles.length + ' 个）:');
    for (const h of hits.slice(0, 20)) console.log(' - ' + h);
    if (hits.length > 20) console.log(' - …（其余 ' + (hits.length - 20) + ' 个省略）');
    console.log('判据 = [Fact]/[Theory]/[Test] 特性与类名同现（确定性代理判据，非模型判断）；测试能否真跑过由 e2e-supplier-unit-test 回答');
    process.exit(0);
  }
  if (testFiles.length === 0) {
    console.log('FAIL: 扫描根（' + validRoots.join('、') + '）里共扫到 ' + files.length
      + ' 个 .cs 文件，但没有任何带 [Fact]/[Theory]/[Test] 特性的测试文件——本次交付没有配套单元测试。'
      + '下一步：为 ' + className + ' 补 xunit 测试（构造供应商 Business 类、调查价、断言返回结构与价格语义）');
    process.exit(1);
  }
  console.log('FAIL: 扫到 ' + testFiles.length + ' 个测试文件，但没有一个引用 ' + className + '（共扫 '
    + files.length + ' 个 .cs；扫描根: ' + validRoots.join('、') + '）。测试文件示例: '
    + testFiles.slice(0, 5).join('、')
    + ' ——若配套测试在别的工程目录，把该目录追加为本脚本参数');
  process.exit(1);
}

main();
