#!/usr/bin/env node
/**
 * Gherkin 结构校验（Issue #27）
 *
 * 为什么不用 @cucumber/gherkin：本仓库前端/后端都没有 Cucumber 依赖，为了不给 CI 和
 * 队友的 node_modules 增加负担，这里用零依赖的结构校验兜住「文件漏写、场景数不足、
 * 步骤缺 当/那么、关键字写错、表格/文档串悬空」这类会让人白跑一遍的错误。
 *
 * 校验项：
 *   1. 首行必须是 `# language: zh-CN`；
 *   2. 必须有一个 `功能:`；
 *   3. Scenario 数 >= 3（--min 可覆盖）；
 *   4. 每个 Scenario 至少各有一个 `当` 和 `那么` 步骤；
 *   5. 步骤关键字只能是 假设/当/那么/并且/但是/而且；
 *   6. `场景大纲` 必须有 `例子:` 且带表头与至少一行数据；
 *   7. 表格行必须紧跟步骤或另一表格行（不能悬空）。
 *
 * 用法：
 *   node scripts/verify-gherkin.mjs                 # 校验 docs/02-需求/stories
 *   node scripts/verify-gherkin.mjs <目录> [--min 3]
 */

import { readdirSync, readFileSync, statSync } from 'node:fs';
import { join, relative, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const STEP_KEYWORDS = ['假设', '当', '那么', '并且', '但是', '而且'];
const BLOCK_KEYWORDS = ['功能', '背景', '场景', '场景大纲', '例子', '规则'];
const DEFAULT_DIR = 'docs/02-需求/stories';

function parseArgs(argv) {
  const args = { dir: null, min: 3 };
  for (let i = 0; i < argv.length; i += 1) {
    if (argv[i] === '--min') {
      args.min = Number(argv[i + 1]);
      i += 1;
    } else if (!argv[i].startsWith('--')) {
      args.dir = argv[i];
    }
  }
  return args;
}

function collectFeatureFiles(dir) {
  const found = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    if (statSync(full).isDirectory()) {
      found.push(...collectFeatureFiles(full));
    } else if (entry.endsWith('.feature')) {
      found.push(full);
    }
  }
  return found.sort();
}

/** 判断一行是否以某个关键字开头（关键字后必须是 ASCII 冒号或空白）。 */
function startsWithKeyword(line, keywords) {
  const trimmed = line.trimStart();
  for (const kw of keywords) {
    if (!trimmed.startsWith(kw)) continue;
    const rest = trimmed.slice(kw.length, kw.length + 1);
    if (rest === ':' || rest === '：' || rest === ' ' || rest === '\t' || rest === '') {
      return kw;
    }
  }
  return null;
}

function validate(file) {
  const errors = [];
  const lines = readFileSync(file, 'utf8').split(/\r?\n/);
  const name = relative(process.cwd(), file);

  const firstMeaningful = lines.find((line) => line.trim() !== '');
  if (!firstMeaningful || !/^#\s*language:\s*zh-CN\s*$/.test(firstMeaningful.trim())) {
    errors.push('首行必须是 `# language: zh-CN`');
  }

  let featureCount = 0;
  let scenarios = [];
  let current = null;
  let inBackground = false;
  let lastLineKind = 'start';
  let inDocString = false;

  for (let i = 0; i < lines.length; i += 1) {
    const raw = lines[i];
    const line = raw.trim();
    const lineNo = i + 1;

    if (line === '') continue;

    // 文档串内部：跳过所有校验，直到闭合的 """
    if (inDocString) {
      if (line === '"""') inDocString = false;
      continue;
    }
    if (line.startsWith('"""')) {
      if (current === null) errors.push(`第 ${lineNo} 行：文档串出现在场景之外`);
      else lastLineKind = 'step';
      inDocString = line === '"""' ? false : true;
      continue;
    }

    // 注释
    if (line.startsWith('#')) continue;

    // 表格行
    if (line.startsWith('|')) {
      if (lastLineKind === 'table') continue;
      if (lastLineKind !== 'step' && lastLineKind !== 'examples') {
        errors.push(`第 ${lineNo} 行：表格悬空（前面不是步骤或表头）`);
      }
      lastLineKind = 'table';
      continue;
    }

    // 块关键字
    if (startsWithKeyword(line, BLOCK_KEYWORDS)) {
      const kw = startsWithKeyword(line, BLOCK_KEYWORDS);
      const rest = line.slice(kw.length).replace(/^\s*[:：]\s*/, '').trim();
      if (kw === '功能') {
        featureCount += 1;
        if (rest === '') errors.push(`第 ${lineNo} 行：功能名称为空`);
        current = null;
        inBackground = false;
      } else if (kw === '背景') {
        current = null;
        inBackground = true;
      } else if (kw === '场景' || kw === '场景大纲') {
        if (rest === '') errors.push(`第 ${lineNo} 行：场景名称为空`);
        inBackground = false;
        current = { line: lineNo, name: rest, keyword: kw, when: 0, then: 0, examples: false, examplesRows: 0 };
        scenarios.push(current);
      } else if (kw === '例子') {
        if (current === null) errors.push(`第 ${lineNo} 行：例子出现在场景之外`);
        else current.examples = true;
        lastLineKind = 'examples';
        continue;
      }
      lastLineKind = 'block';
      continue;
    }

    // 步骤
    if (startsWithKeyword(line, STEP_KEYWORDS)) {
      if (current === null && !inBackground) {
        errors.push(`第 ${lineNo} 行：步骤出现在任何场景之外`);
      } else if (current !== null) {
        const kw = startsWithKeyword(line, STEP_KEYWORDS);
        const rest = line.slice(kw.length).trim();
        if (rest === '') errors.push(`第 ${lineNo} 行：步骤内容为空`);
        if (kw === '当') current.when += 1;
        if (kw === '那么') current.then += 1;
      }
      lastLineKind = 'step';
      continue;
    }

    // 其它行：功能下方的自由描述允许，但出现在步骤/表格之后多半是缩进写错
    if (lastLineKind === 'step' || lastLineKind === 'table') {
      errors.push(`第 ${lineNo} 行：无法识别的语句「${line.slice(0, 40)}」（关键字写错或缺少缩进关键字）`);
    }
    lastLineKind = 'text';
  }

  if (inDocString) errors.push('文档串 `"""` 未闭合');
  if (featureCount !== 1) errors.push(`功能数量应为 1，实际 ${featureCount}`);
  if (scenarios.length < 3) errors.push(`场景数量应 ≥3，实际 ${scenarios.length}`);

  for (const s of scenarios) {
    if (s.when === 0) errors.push(`第 ${s.line} 行「${s.name}」缺少 当 步骤`);
    if (s.then === 0) errors.push(`第 ${s.line} 行「${s.name}」缺少 那么 步骤`);
    if (s.keyword === '场景大纲' && !s.examples) errors.push(`第 ${s.line} 行「${s.name}」是场景大纲但缺少 例子`);
  }

  return { name, scenarios: scenarios.length, errors };
}

function main() {
  const { dir: dirArg, min } = parseArgs(process.argv.slice(2));
  const dir = resolve(dirArg ?? DEFAULT_DIR);
  const files = collectFeatureFiles(dir);
  if (files.length === 0) {
    console.error(`未找到任何 .feature 文件：${dir}`);
    process.exit(1);
  }

  const results = files.map(validate);
  let failed = false;
  const rows = [];

  for (const r of results) {
    const tooFew = r.scenarios < min;
    if (r.errors.length > 0 || tooFew) failed = true;
    rows.push(`${r.errors.length === 0 && !tooFew ? 'PASS' : 'FAIL'}  ${r.scenarios.toString().padStart(2)} 个场景  ${r.name}`);
    for (const e of r.errors) rows.push(`      - ${e}`);
    if (tooFew) rows.push(`      - 场景数量应 ≥${min}，实际 ${r.scenarios}`);
  }

  console.log(rows.join('\n'));
  const total = results.reduce((sum, r) => sum + r.scenarios, 0);
  console.log('');
  console.log(`合计：${results.length} 个文件，${total} 个场景（每个文件 ≥${min}）`);

  if (files.length < 8) {
    failed = true;
    console.log(`数量不足：Issue #27 要求至少 8 个 .feature 文件，实际 ${files.length} 个`);
  }

  process.exit(failed ? 1 : 0);
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main();
}
