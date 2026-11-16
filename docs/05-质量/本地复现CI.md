# 本地复现 CI

`scripts/ci-local.ps1` 把 `.github/workflows/ci.yml` 的检查在本地按顺序重放一遍，**不依赖 GitHub Actions**。

## 什么时候用

| 场景 | 为什么 |
| --- | --- |
| Actions 额度耗尽 / 平台故障 | 2026-11-12 起本仓库（私有）工作流全部在 4–7 秒内 0 步骤失败，见 Issue #120；这段时间想证明「主干是绿的」只能本地跑 |
| 答辩演示 | 演示机上直接 `powershell -File scripts/ci-local.ps1`，比切浏览器看 Actions 更稳 |
| 推送前自检 | 比等 8 分钟云端流水线更快拿到反馈（本地约 2.5 分钟，含后端 27 个模块的全量单测） |

## 覆盖的阶段

| # | 阶段 | 对应 `ci.yml` |
| --- | --- | --- |
| 1 | 编译 `mvn -f backend/pom.xml clean compile` | 阶段 1 |
| 2 | 全后端单元测试 `mvn test` | 阶段 2 |
| 3 | 架构约束 `mvn test -pl co-bootstrap -am -Dtest=ArchitectureTest` | 阶段 3 |
| 4 | 覆盖率门禁 `mvn verify -DskipITs` | 阶段 4 |
| 5 | 行走骨架归零（扫 `src/main` 的 `TODO(Sx)` / `UnsupportedOperationException`） | 对应 `skeleton-check.yml` 里新增的守卫 |
| 6 | 错误码一致性 `node scripts/verify-error-codes.mjs`（代码 ↔ 文档双向核对） | 对应 `skeleton-check.yml` 里新增的检查 |
| 7 | 前端代码规范 `npm run lint` | `frontend` job |
| 8 | 前端单元测试 `npm test`（Vitest，54 个用例） | `frontend` job |
| 9 | 前端类型检查 `npm run typecheck` | `frontend` job |
| 10 | 前端构建 `npm run build` | `frontend` job |

**刻意不做**（都需要 Docker，本机没有）：SonarQube 门禁、Testcontainers 集成测试、镜像构建、compose 全栈。这四块仍然只在云端或装了 Docker 的机器上验证。

## 最近一次实测（2026-11-13，`-Offline`）

```text
  PASS 1/10 编译                       43.5s
  PASS 2/10 单元测试（全后端）            54.1s
  PASS 3/10 架构约束（ArchUnit）          8.0s
  SKIP 4/10 覆盖率门禁（-DskipITs）        0.0s   离线模式跳过（见下方坑 1）
  PASS 5/10 行走骨架归零                  0.4s
  PASS 6/10 错误码一致性                  0.1s
  PASS 7/10 前端代码规范（ESLint）          6.5s
  PASS 8/10 前端单元测试（Vitest）        16.7s
  PASS 9/10 前端类型检查                  2.5s
  PASS 10/10 前端构建                    7.7s

通过（1 项跳过）。
```

Actions 额度没恢复期间，这就是「主干是绿的」的证据——把这段输出贴到 PR 或答辩材料里，比说「我本地跑过了」有说服力。

## 用法

```powershell
# 完整跑（联网，含覆盖率门禁）
powershell -ExecutionPolicy Bypass -File scripts/ci-local.ps1

# 没有网络时（Maven 本地仓库已缓存）：覆盖率门禁会标 SKIP，其余照跑
powershell -ExecutionPolicy Bypass -File scripts/ci-local.ps1 -Offline

# 只验证后端
powershell -ExecutionPolicy Bypass -File scripts/ci-local.ps1 -SkipFrontend
```

退出码：`0` = 无失败阶段（可能有 SKIP）；`1` = 有失败阶段。失败阶段的完整日志在 `target/ci-local/<阶段>.log`，脚本会顺手打印日志尾部 15 行。

## 两个 Windows 上的坑（脚本已处理，写下来免得再踩）

1. **`-Offline` 时覆盖率门禁跑不了**：`verify` 阶段会触发 `co-bootstrap` 的 `spring-boot:repackage`，它依赖 `buildpack-platform` 等插件依赖；本地 Maven 仓库没有这些包时离线直接失败。这是环境问题不是代码问题，所以脚本在该模式下把阶段 4 标 **SKIP** 并说明原因，而不是让它假装失败。
2. **前端 `vite build` 报 `esbuild ... Access is denied`**：esbuild 会在 `%TEMP%` 下解包并清理临时目录，权限受限的机器上会失败。脚本把前端两步的 `TEMP`/`TMP` 指到 `target/ci-local/tmp`，规避该问题。

## 关于 `.ps1` 的编码

PowerShell 5.1 会把 **无 BOM 的 UTF-8** 当 ANSI 解析，中文注释会直接把脚本解析崩（报一堆 `Missing closing ')'`）。本脚本保存为 **UTF-8 with BOM**；如果你用编辑器重存，记得保留 BOM，或者把输出文案改成纯 ASCII。
