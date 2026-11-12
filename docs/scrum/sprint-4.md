# Sprint 4 · 稳定化、部署与发布

| 项 | 内容 |
| --- | --- |
| 周期 | 2026-11-10 起（进行中） |
| 对应 Lab | Lab7（质量门禁）、Lab8（部署与交付） |
| 目标版本 | `v0.9.0` → `v1.0.0` |
| 参与 | 孔志敏（PO）、刘浩然（SM）、肖蕴晨（SM2）、黄振亚（DV1）、易子恒（DV2） |

> 本 Sprint 尚未结束，下文「实际完成」为截至 2026-11-10 21:45 的状态。

## Sprint 目标

不破坏质量门禁的前提下收口：清理行走骨架、把覆盖率提到全后端 70%、SonarQube 接入并验证、完成部署链的真机验证，最后走 `develop → main` 发布 `v1.0.0` 并出 GitHub Release。

## 计划

| 项 | 内容 | 主责 |
| --- | --- | --- |
| S4-A | SonarQube 质量门禁接入 CI 并实测 | SM2 |
| S4-B | 后端 Docker 多阶段构建收口 | DV2 |
| S4-C | 前端镜像 + Nginx 反代 | DV2 |
| S4-D | compose 全栈真机验证 | DV2 |
| S4-E | GHCR 拉取侧验证与部署 Secrets | DV2 |
| S4-F | 移除剩余行走骨架 | DV1 |
| S4-G | 覆盖率提升到全后端 70%，根 POM 门禁打开 | DV1 |
| S4-H | 补 `docs/scrum/`、`docs/reports/`、`docs/final/` | 全员 |
| S4-I | `develop → main`，打 `v0.9.0`、`v1.0.0` | PO |
| S4-J | GitHub Release 与答辩材料 | PO |

## 实际完成

**SonarQube（#32）**

- PR #85 合入 `ec6a7b3`。CI 里起临时 SonarQube 容器，运行时生成 token，自定义门禁 `team3-gate`：新代码覆盖率 ≥60、新 Bug = 0、新漏洞 = 0、新代码异味 <10。
- 在 PR #90、#91 上实测通过。
- **尚未纳入必需检查**，红了不拦合并。

**S3 链路收口（#86 一部分）**

- PR #90 合入 `2ca1993`，补齐 9 处声誉骨架：查询、事件处理器、徽章、悬赏。集成测试从 `b225a42` 的 **failure** 转为 **success**，这是 S3 门槛里最硬的一道。

**部署链**

- `deploy/` 全套就绪：compose、Dockerfile、Nginx、Prometheus 规则、Grafana。
- `Publish Images (Lab8)` 成功，镜像推送到 GHCR。
- `Compose Smoke (Lab8)` 通过。
- `Deploy (Lab8)` 一直走跳过分支，因为 4 个 Secrets 一个都没配。

**文档与仓库卫生**

- PR #91 合入 `77ae2de`：新增 `docs/ddd/README.md` 索引、ADR-009 状态改为「已完成」、成员表去掉学号字段。
- 已合并分支从 13 个清到 5 个。
- 新增 `docs/scrum/` 五份 Sprint 记录。

## 未完成

| 项 | 现状 | 说明 |
| --- | --- | --- |
| 剩余行走骨架 4 处 | `LeaderboardService.on / top`、`ReconciliationService.reconcile`、`QaQueryApiImpl.findQuestion` | 集成测试不经过排行榜和对账，所以绿了也说明不了这两块 |
| 全后端覆盖率 70% | 74.3% 是 Q&A + 共享内核口径；`co-bootstrap` 零覆盖 | 根 POM `domain.coverage.minimum` 仍是 0，CI 里靠 `-D` 临时提 |
| `docs/reports/` | 目录不存在 | 测试报告、覆盖率截图未归档 |
| `docs/final/` | 目录不存在 | 答辩 PPT、演示视频未产出 |
| `v0.9.0` / `v1.0.0` | 都没打 | `v0.3.0` 打在 `ec6a7b3`（develop 线上），不在 main |
| `develop → main` | 未执行 | main 停在 `1c2db04` |
| GitHub Release | 0 个 | 依赖上面两项 |

## 评审

Sprint 尚未结束，以下为当前对照 §6.6 / §9.4 的结果：

| 验收项 | 结果 |
| --- | --- |
| SonarQube 接入 | 通过（未入必需检查） |
| 镜像构建成功 | 通过 |
| compose 全栈跑通 | 通过 |
| 自动部署 | 未达成，缺 Secrets |
| GHCR 拉取侧验证 | 进行中，PR #89 |
| 覆盖率全后端 70% | 未达成 |
| 移除全部骨架 | 未达成，剩 4 处 |
| `v0.9.0` / `v1.0.0` | 未达成 |

## 回顾

（Sprint 未结束，结束时补。）

阶段性观察：

**Keep**

- 集成测试当成硬门守，宁可红着不合，这次骨架一补完立刻转绿，说明门禁是真在拦东西。
- 合并前后都回读快照，HEAD 和 Issue 状态对得上。

**Drop**

- 拿 token 拼进 git 远程 URL 推送，被 Windows 凭据管理器存成一个假账号，后面要避免。
- 覆盖率一直报单模块口径，容易被误读成全后端达标。

**Start**

- 发布前先确认「门禁在 main 上也能过」，不是只在 PR 上过。

## 燃尽图

| 日期 | 当日关闭 | 累计关闭 | 剩余 |
| --- | --- | --- | --- |
| 11-09 | — | 46 | 2 |
| 11-10 | 1 | 47 | 1 |

剩余 1 条为 #86（Docker 真机验证追踪），Sprint 结束时关闭。

## 行动项

| # | 行动 | 负责人 | 状态 |
| --- | --- | --- | --- |
| 1 | 补完 4 处行走骨架 | DV2 | 待办 |
| 2 | 覆盖率冲全后端 70%，打开根 POM 门禁 | DV1 | 待办 |
| 3 | 产出 `docs/reports/` 测试报告 | SM2 | 待办 |
| 4 | 产出 `docs/final/` 答辩 PPT 与视频 | PO | 待办 |
| 5 | `develop → main`，打 `v0.9.0`、`v1.0.0`，建 Release | PO | 待办 |
| 6 | SonarQube 纳入必需检查 | SM2 | 待办 |