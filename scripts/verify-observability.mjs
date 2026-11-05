#!/usr/bin/env node
/**
 * Lab8 可观测性 / 部署配置校验（Issue #37 / #38 / #39 / #40）
 *
 * 为什么存在：本机和 CI 都不一定有 Docker，没法真的 `docker compose up` 验证。
 * 这个脚本零依赖，兜住最容易出错、且肉眼很难发现的几类配置漂移：
 *   1. 交付文件漏写；
 *   2. dashboard / 告警规则引用了代码里根本没注册的自定义指标（编造指标）；
 *   3. Prometheus rule_files 路径与 compose 挂载路径对不上（规则静默不生效）；
 *   4. compose 引用的环境变量没在 .env.example 里（照样例填等于密码配置失效）；
 *   5. Grafana provisioning / dashboard 目录没挂载（看板静默不出现）；
 *   6. .dockerignore 漏掉 .git / .env / target / node_modules（构建上下文泄密）。
 *
 * 它只做配置层校验，不替代 `docker compose up` 的运行验证。
 *
 * 用法：node scripts/verify-observability.mjs
 */

import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { join, dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const ok = [];
const bad = [];

const read = (p) => readFileSync(join(ROOT, p), 'utf8');
const has = (p) => existsSync(join(ROOT, p));
const assert = (cond, msg) => {
  if (!cond) throw new Error(msg);
};

function check(name, fn) {
  try {
    const detail = fn();
    ok.push(`[OK]   ${name}${detail ? ` — ${detail}` : ''}`);
  } catch (e) {
    bad.push(`${name}: ${e.message}`);
  }
}

function walk(dir, filter, out = []) {
  const abs = join(ROOT, dir);
  if (!existsSync(abs)) return out;
  for (const name of readdirSync(abs)) {
    const p = join(dir, name);
    const s = statSync(join(ROOT, p));
    if (s.isDirectory()) {
      if (name !== 'target' && name !== 'node_modules' && name !== '.git') walk(p, filter, out);
    } else if (filter(p)) {
      out.push(p);
    }
  }
  return out;
}

function dashboardExpressions() {
  const doc = JSON.parse(read('deploy/grafana/dashboards/campus-overflow.json'));
  const exprs = [];
  const visit = (panels) => {
    for (const p of panels || []) {
      for (const t of p.targets || []) if (t.expr) exprs.push(t.expr);
      if (p.panels) visit(p.panels);
    }
  };
  visit(doc.panels);
  return { doc, exprs };
}

console.log('Lab8 可观测性 / 部署配置校验\n');

check('交付文件齐备', () => {
  const required = [
    '.env.example',
    '.dockerignore',
    'deploy/docker-compose.yml',
    'deploy/prometheus.yml',
    'deploy/prometheus/rules.yml',
    'deploy/grafana/provisioning/datasources/prometheus.yml',
    'deploy/grafana/provisioning/dashboards/dashboards.yml',
    'deploy/grafana/dashboards/campus-overflow.json',
    'deploy/deploy.yml.example',
    'docs/ops/runbook.md',
  ];
  const missing = required.filter((p) => !has(p));
  assert(missing.length === 0, `缺少 ${missing.join(', ')}`);
  return `${required.length} 个文件`;
});

check('Grafana 看板 JSON 可解析且有面板', () => {
  const { doc, exprs } = dashboardExpressions();
  assert(Array.isArray(doc.panels) && doc.panels.length > 0, 'panels 为空');
  assert(exprs.length > 0, '没有任何 PromQL target');
  return `${doc.panels.length} 个顶层面板 / ${exprs.length} 条查询`;
});

check('自定义指标在后端源码中真实注册（防编造）', () => {
  const ruleText = read('deploy/prometheus/rules.yml');
  // 规则用 >- 折行时表达式散落在后续缩进行，因此按指标名而不是按表达式块提取
  const { exprs: dashExprs } = dashboardExpressions();
  const allText = `${ruleText}\n${dashExprs.join('\n')}`;
  const referenced = [...new Set((allText.match(/\bco_[a-z0-9_]+\b/g) || []))].sort();
  assert(referenced.length >= 3, `只找到 ${referenced.length} 个自定义指标，配置可能不完整`);
  const javaFiles = walk('backend', (p) => p.endsWith('.java'));
  const source = javaFiles.map((p) => read(p)).join('\n');
  const unregistered = referenced.filter((m) => !source.includes(m));
  assert(unregistered.length === 0, `以下指标在 backend/**/*.java 中找不到注册点: ${unregistered.join(', ')}`);
  return `${referenced.length} 个指标均已注册（${javaFiles.length} 个 Java 文件）`;
});

check('Prometheus rule_files 与 compose 挂载路径一致', () => {
  const prom = read('deploy/prometheus.yml');
  const compose = read('deploy/docker-compose.yml');
  assert(/rule_files:/.test(prom), 'prometheus.yml 没有 rule_files');
  const rulePath = (prom.match(/-\s+(\/etc\/prometheus\/[^\s:]+\.ya?ml)/) || [])[1];
  assert(rulePath, 'rule_files 里没有 /etc/prometheus/*.yml 路径');
  assert(compose.includes(`:${rulePath}:ro`), `compose 没有把规则挂到 ${rulePath}`);
  assert(has('deploy/prometheus/rules.yml'), '本地规则文件不存在');
  return rulePath;
});

check('告警规则数量与表达式完整', () => {
  const text = read('deploy/prometheus/rules.yml');
  const alerts = (text.match(/^\s{2,}-\s+alert:/gm) || []).length;
  assert(alerts >= 5, `只定义了 ${alerts} 条告警，Issue #39 要求覆盖主要故障场景`);
  assert(/expr:/.test(text), '没有 expr');
  return `${alerts} 条告警规则`;
});

check('compose 环境变量都在 .env.example 中声明', () => {
  const compose = read('deploy/docker-compose.yml');
  const envExample = read('.env.example');
  const vars = [...new Set([...compose.matchAll(/\$\{([A-Z_][A-Z0-9_]*)/g)].map((m) => m[1]))].sort();
  const declared = new Set(
    envExample.split(/\r?\n/)
      .map((l) => (l.match(/^([A-Z_][A-Z0-9_]*)\s*=/) || [])[1])
      .filter(Boolean),
  );
  const missing = vars.filter((v) => !declared.has(v));
  assert(missing.length === 0, `.env.example 缺少 ${missing.join(', ')}`);
  return `${vars.length} 个变量全部对齐`;
});

check('Grafana provisioning 与看板目录已挂载', () => {
  const compose = read('deploy/docker-compose.yml');
  const prov = read('deploy/grafana/provisioning/dashboards/dashboards.yml');
  assert(compose.includes('./grafana/provisioning/datasources'), 'datasource provisioning 未挂载');
  assert(compose.includes('./grafana/provisioning/dashboards'), 'dashboard provisioning 未挂载');
  assert(compose.includes('./grafana/dashboards'), '看板 JSON 目录未挂载');
  const path = (prov.match(/path:\s*(\S+)/) || [])[1];
  assert(path && compose.includes(path.replace(/\/var\/lib\/grafana/, './grafana')), `provisioning path ${path} 与挂载不匹配`);
  return path;
});

check('.dockerignore 覆盖敏感与超大目录', () => {
  const text = read('.dockerignore');
  const must = ['.git', '.env', 'target', 'node_modules'];
  const missing = must.filter((m) => !text.includes(m));
  assert(missing.length === 0, `缺少 ${missing.join(', ')}`);
  return must.join(' / ');
});

check('部署工作流模板具备「无 Secret 可验 / 缺 Secret 跳过」结构', () => {
  const wf = read('deploy/deploy.yml.example');
  assert(wf.includes('workflow_dispatch'), '不是手动触发，会在没配 Secrets 时自动跑红');
  assert(wf.includes('verify-config'), '缺少无需 Secret 的配置校验任务');
  assert(wf.includes('enabled=false'), '缺少「缺 Secrets 安全跳过」分支');
  // 模板必须留在 deploy/ 下：一旦被直接放进 .github/workflows/，无 workflow scope 的 token 无法推送
  return existsSync(join(ROOT, '.github/workflows/deploy.yml')) ? '已落地到 .github/workflows/' : '模板待复制到 .github/workflows/';
});

check('运维手册指向的校验脚本存在', () => {
  const runbook = read('docs/ops/runbook.md');
  if (runbook.includes('verify-observability.mjs')) {
    assert(has('scripts/verify-observability.mjs'), 'runbook 引用了不存在的校验脚本');
  }
  assert(runbook.length > 2000, 'runbook 内容过短，疑似占位骨架');
  return `${runbook.length} 字符`;
});

for (const line of ok) console.log(line);
if (bad.length) {
  console.log('\n失败项：');
  for (const line of bad) console.log(`[FAIL] ${line}`);
  console.error(`\n校验未通过：${bad.length} 项失败 / ${ok.length} 项通过`);
  process.exit(1);
}
console.log(`\n全部通过：${ok.length} 项配置校验 OK（不等价于 docker compose up 的运行验证）`);
