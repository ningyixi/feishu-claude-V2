#!/usr/bin/env node
'use strict';
// 空 catch / 硬编码密钥明文检查（grep 级确定性规则，无 AST、无 npm 依赖）。
// 用法: node check-no-empty-catch.js [扫描根目录]     （默认 = 当前工作目录）
// 断言：① 没有空 catch 块（catch {} / catch (Exception) { } 之类吞异常写法）；
//       ② 没有把 password/secret/apikey 等直接赋成字符串明面量（占位符/配置读取除外）。
// 通过 → 退出码 0 + stdout「OK」；有违规 → 退出码 1 + 逐条打印 文件:行号。
// 没扫到任何 .cs 文件 → 退出码 1（防止在错误工作目录里"零文件 = 恒绿"的假通过）。
const fs = require('fs');
const path = require('path');

const SKIP_DIRS = new Set(['bin', 'obj', '.git', 'node_modules', '.vs', '.idea', 'packages', 'TestResults']);
const FILE_CAP = 20000;

// catch {} / catch (Xxx) { }——大括号里只有空白
const EMPTY_CATCH_RE = /catch\s*(?:\([^)]*\))?\s*\{\s*\}/g;
// password/secret/apikey/appkey/accesskey/token 直接赋字符串字面量（≥4 字符）
const SECRET_RE = /\b(?:password|passwd|secret|api_?key|app_?key|access_?key|token)\b\s*[:=]\s*"([^"]{4,})"/gi;
// 不算硬编码的值：占位符 / 模板 / 明显配置引用
const SECRET_ALLOWED_VALUE = /^(?:<|\$|\{|%|CHANGE|TODO|REDACTED)/i;
// 不算硬编码的行：从配置/环境变量读取
const SECRET_ALLOWED_LINE = /Configuration|AppSettings|appSettings|GetEnvironmentVariable|GetSection|\bconfig\[/i;

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

function lineOf(text, index) {
  let line = 1;
  for (let i = 0; i < index && i < text.length; i++) if (text[i] === '\n') line++;
  return line;
}

function main() {
  const root = process.argv[2] || process.cwd();
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
  const violations = [];
  for (const f of files) {
    let text;
    try { text = fs.readFileSync(f, 'utf8'); } catch (e) { continue; }
    EMPTY_CATCH_RE.lastIndex = 0;
    let m;
    while ((m = EMPTY_CATCH_RE.exec(text)) !== null) {
      violations.push('空 catch 吞异常 ' + f + ':' + lineOf(text, m.index) + ' → ' + m[0].replace(/\s+/g, ' '));
    }
    const lines = text.split(/\r?\n/);
    for (let i = 0; i < lines.length; i++) {
      const line = lines[i];
      if (SECRET_ALLOWED_LINE.test(line)) continue;
      SECRET_RE.lastIndex = 0;
      let s;
      while ((s = SECRET_RE.exec(line)) !== null) {
        if (SECRET_ALLOWED_VALUE.test(s[1])) continue;
        violations.push('疑似硬编码密钥明文 ' + f + ':' + (i + 1) + ' → ' + line.trim().slice(0, 160));
      }
    }
  }
  if (violations.length > 0) {
    console.log('FAIL: 发现 ' + violations.length + ' 处违规:');
    for (const v of violations.slice(0, 100)) console.log(' - ' + v);
    if (violations.length > 100) console.log(' - …（其余 ' + (violations.length - 100) + ' 处省略）');
    process.exit(1);
  }
  console.log('OK 扫描 ' + files.length + ' 个 .cs 文件，未发现空 catch 或硬编码密钥明文');
}

main();
