#!/usr/bin/env node
/**
 * 错误码一致性校验（代码 ↔ 文档）。
 *
 * 为什么需要：`docs/错误码.md` 曾经只写了 33 个「常用错误码」，而 `backend/**\/src/main` 里
 * 实际有 75 个错误码在抛/在用——有 41 个查不到。文档靠人肉维护必然再次漂移，
 * 所以把这条一致性做成可执行的检查，放进 skeleton-check 流水线。
 *
 * 两个方向：
 *   1. 文档 → 代码（**失败**）：文档里写了代码里不存在的错误码 = 文档过期或有错别字，必须修；
 *   2. 代码 → 文档（**警告**）：代码里有、文档里没写 = 还没补文档。这一条在 #124 合入前
 *      会有 41 条，所以先只警告不阻断；#124 合入后把 --strict 打开即可转为失败。
 *
 * 用法：
 *   node scripts/verify-error-codes.mjs            # 文档→代码 失败，代码→文档 警告
 *   node scripts/verify-error-codes.mjs --strict   # 两个方向都失败
 *   node scripts/verify-error-codes.mjs --list     # 只打印统计
 */

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(new URL(import.meta.url).pathname.replace(/^\/([A-Za-z]:)/, '$1')), '..');
const BACKEND = path.join(ROOT, 'backend');
const DOC = path.join(ROOT, 'docs', '错误码.md');
const CODE_PATTERN = /\b((?:ID|QA|REP|DIS|AUTH|SYS)-[0-9]{3,4})\b/g;

const args = new Set(process.argv.slice(2));
const strict = args.has('--strict');
const listOnly = args.has('--list');

function walk(dir, out = []) {
  if (!fs.existsSync(dir)) return out;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      if (entry.name === 'target' || entry.name === 'node_modules') continue;
      walk(full, out);
    } else if (entry.name.endsWith('.java')) {
      out.push(full);
    }
  }
  return out;
}

/** 只统计 src/main 下的生产代码；src/test 里的构造值（如 QA-2001、DIS-0001）不算。 */
function codesInMainSources() {
  const found = new Map();
  for (const file of walk(path.join(BACKEND))) {
    const normalized = file.replace(/\\/g, '/');
    if (!normalized.includes('/src/main/')) continue;
    const text = fs.readFileSync(file, 'utf8');
    for (const match of text.matchAll(CODE_PATTERN)) {
      const code = match[1];
      if (!found.has(code)) {
        found.set(code, path.relative(ROOT, file).replace(/\\/g, '/'));
      }
    }
  }
  return found;
}

function codesInDoc() {
  if (!fs.existsSync(DOC)) {
    console.error(`找不到文档：${path.relative(ROOT, DOC)}`);
    process.exit(2);
  }
  const found = new Map();
  const lines = fs.readFileSync(DOC, 'utf8').split(/\r?\n/);
  lines.forEach((line, index) => {
    for (const match of line.matchAll(CODE_PATTERN)) {
      const code = match[1];
      if (!found.has(code)) found.set(code, index + 1);
    }
  });
  return found;
}

const inCode = codesInMainSources();
const inDoc = codesInDoc();

const documentedButMissing = [...inDoc.keys()].filter((code) => !inCode.has(code)).sort();
const codedButUndocumented = [...inCode.keys()].filter((code) => !inDoc.has(code)).sort();

console.log(`错误码统计：代码 src/main 中 ${inCode.size} 个，文档中 ${inDoc.size} 个`);
console.log(`  文档有、代码没有：${documentedButMissing.length} 个`);
console.log(`  代码有、文档没有：${codedButUndocumented.length} 个`);

if (listOnly) {
  if (documentedButMissing.length) console.log(`\n文档多出来的：${documentedButMissing.join(', ')}`);
  if (codedButUndocumented.length) console.log(`\n文档缺的：${codedButUndocumented.join(', ')}`);
  process.exit(0);
}

let failed = false;

if (documentedButMissing.length) {
  failed = true;
  console.error('\n[失败] 文档里出现了代码中不存在的错误码（过期或有错别字）：');
  for (const code of documentedButMissing) {
    console.error(`  - ${code}（docs/错误码.md:${inDoc.get(code)}）`);
  }
}

if (codedButUndocumented.length) {
  const label = strict ? '[失败]' : '[警告]';
  const sink = strict ? console.error : console.warn;
  sink(`\n${label} 代码里有、文档里没有的错误码 ${codedButUndocumented.length} 个：`);
  for (const code of codedButUndocumented.slice(0, 20)) {
    sink(`  - ${code}（${inCode.get(code)}）`);
  }
  if (codedButUndocumented.length > 20) {
    sink(`  …… 另有 ${codedButUndocumented.length - 20} 个，用 --list 查看全部`);
  }
  if (strict) failed = true;
  else console.warn('  提示：PR #124 合入后可用 --strict 把这条转为阻断');
}

if (failed) {
  console.error('\n错误码一致性校验未通过。');
  process.exit(1);
}
console.log('\n错误码一致性校验通过。');
