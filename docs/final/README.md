# 最终交付

本目录是课程最终交付物的入口。

| 文件 | 内容 |
| --- | --- |
| [答辩讲稿.md](答辩讲稿.md) | 按演示页顺序的讲述要点、每页要传递的信息、可能被追问的问题与回答 |
| [演示脚本.md](演示脚本.md) | 现场演示的操作步骤、每步的预期结果、失败时的兜底方案 |
| `CampusOverflow-答辩演示.pdf` | 答辩用演示稿（导出 PDF，源文件不入库） |

## 交付物一览

| 类别 | 位置 |
| --- | --- |
| 源码 | `backend/`（27 个 Maven 模块）、`frontend/` |
| 架构决策 | [docs/adr/](../adr/)（10 条） |
| 领域模型 | [docs/ddd/](../ddd/) |
| Sprint 过程 | [docs/scrum/](../scrum/)（5 个 Sprint） |
| 测试报告 | [docs/reports/](../reports/) |
| 运维手册 | [docs/ops/runbook.md](../ops/runbook.md) |
| 版本历史 | [CHANGELOG.md](../../CHANGELOG.md) |
| 冒烟脚本 | [docs/05-质量/smoke/](../05-质量/smoke/)（`auth.http`、`qa-flow.http`） |

## 演示环境要求

- Docker（跑 `deploy/docker-compose.yml` 需要）
- JDK 21 + Maven 3.9+
- Node.js 20+（前端单独启动时需要）
- 浏览器

没有 Docker 也能演示：用 `dev,demo` profile 直接 `mvn spring-boot:run` 起后端，前端 `npm run dev`，只是少了监控面板那一段。