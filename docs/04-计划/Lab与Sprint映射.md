# Lab 与 Sprint 映射

> 解决的问题：仓库里有两套编号在并行使用，但从来没对上过——
> **8 个 Lab** 落在 GitHub Milestone 上（`Lab1`–`Lab8`），**5 个 Sprint** 只写在 [sprint-plan.md](../sprint-plan.md) 里。
> 领任务、写站会纪要、对答辩材料时，两套编号要能互相翻译。

## 两套体系是什么关系

| | Lab | Sprint |
| --- | --- | --- |
| 来源 | 实验课次（教学安排） | Scrum 迭代（开发节奏） |
| 数量 | 8 个 | 5 个（S0–S4）+ 答辩周 W16 |
| 载体 | GitHub Milestone + `labN` 标签 | `docs/sprint-plan.md` |
| 粒度 | 一次实验的交付物 | 一段迭代的目标与版本标签 |

不是一一对应：**一个 Sprint 可以覆盖多个 Lab，一个 Lab 也可能横跨两个 Sprint**。

## 映射口径

| Sprint | 周次 | 覆盖的 Lab | Milestone | 版本标签 |
| --- | --- | --- | --- | --- |
| **S0** | 第 1 周 | Lab1 | 启动与基础设施 | `v0.1.0` |
| **S1** | 第 2 周 | Lab2 + Lab3 | 需求建模 + DDD 事件风暴 | — |
| **S2** | 第 3–4 周 | Lab4 + Lab5 | 后端骨架 + 前端骨架 | `v0.2.0` |
| **S3** | 第 5–6 周 | Lab6 | 核心特性开发 | `v0.3.0` → `v0.9.0` |
| **S4** | 第 7–8 周 | Lab7 + Lab8 | 测试质量门禁 + CI/CD 与部署 | `v1.0.0` |
| **W16** | 答辩周 | — | 最终演示与答辩 | — |

**依据**（不是拍脑袋分的）：

- Lab1 = S0：`sprint-plan.md` 的 S0 关键任务（团队、仓库、CI 门禁、Backlog 导入、`v0.1.0`）与 Lab1 的 5 个 issue 逐条对应。
- Lab6 = S3：issue #43 标题就是「[Lab6] **Sprint 3** Review 演示 + Retrospective 回顾纪要」，是仓库里唯一直接写明 Sprint 编号的锚点。
- Lab8 = S4：Lab8 的 issue 是 Docker 多阶段构建、docker-compose 编排、CI/CD 流水线、Prometheus+Grafana、运维手册，与 `sprint-plan.md` 里 S4 的「稳定化、部署、可观测性」完全重合，且对应 `v1.0.0`。
- Lab7 = S4：覆盖率 70%、SonarQube 门禁、Testcontainers，属于「稳定化」范畴；若团队想提前，可整体挪到 S3 末。
- Lab2 + Lab3 = S1：Lab2 有「Sprint 1 Planning 会议」issue（#28），Lab3 的事件风暴与聚合清单是后端骨架（Lab4/S2）的前置输入，放在 S1 收口。
- Lab4 + Lab5 = S2：后端行走骨架与前端骨架同期推进，正好对应 S2「Q&A 核心闭环」。

> S1 与 S4 是双 Lab 合并的 Sprint，也是两个最容易超期的点，排期时按「两个 Lab 的交付物一起算」估工时。

## 当前进度（2026-11-17 复核）

> 下表按 GitHub Milestone 的实际数据复核。与 2026-10-29 那版（Lab1 还剩 3 个、其余基本未动）相比，
> **8 个 Lab 的 Milestone 已全部清零**；唯一还开着的是 `lab8` 标签下的 **#101**（真机部署补测），
> 它是不挂 Milestone 的追踪 issue。

| Milestone | 已关闭 | 未关闭 |
| --- | ---: | ---: |
| Lab1 启动与基础设施 | 8 | 0 |
| Lab2 需求建模 | 6 | 0 |
| Lab3 DDD事件风暴 | 2 | 0 |
| Lab4 后端骨架 | 9 | 0 |
| Lab5 前端骨架 | 3 | 0 |
| Lab6 核心特性开发 | 7 | 0 |
| Lab7 测试质量门禁 | 6 | 0 |
| Lab8 CI、CD与部署 | 7 | 0 |
| **合计** | **48** | **0** |

### Lab8 逐条对照（CI/CD 与部署，对应 S4）

| Issue | 交付物 | 收敛证据 |
| --- | --- | --- |
| #35 | 后端 Docker 多阶段构建（builder → runtime，非 root） | `backend/Dockerfile` + `publish-images.yml` |
| #36 | 前端镜像 + Nginx 反代 | `frontend/Dockerfile`、`deploy/nginx/` |
| #37 | docker-compose 编排（app + MySQL + Redis + Nginx + Prometheus + Grafana） | `docker-compose.yml` + `compose-smoke.yml` 每周定时冒烟 |
| #38 | CI/CD 流水线：build + test + sonar → docker build → push → 自动部署 | `ci.yml` → `publish-images.yml` → `deploy.yml` |
| #39 | Prometheus + Grafana（≥5 个核心指标 + 1 条告警规则） | `deploy/prometheus/`、`deploy/grafana/` |
| #40 | 运维手册 v1.0（启动/停止/回滚/故障处置） | `docs/ops/` |
| #86 | Docker 真机全栈验证追踪 | 已关闭（真机验证拆分到 #101） |
| #101 | **真机部署补测（常开主机 + 4 个 Secret + GHCR 拉取凭据）** | **仍 open**：链路就绪，缺一台常开主机与凭据，属外部依赖 |
| #113 | S4 记录回填 | `docs/scrum/sprint-4.md` |
| #114 | v1.0.0 发布 | PR #151（develop → main 一次性对齐） |

**结论**：Lab8 的工程部分（#35–#40）已全部交付；**唯一未闭环的是 #101 的真机验证**。
这不影响「流水线能构建、能推送、能部署」的事实（`deploy.yml` 里有「部署配置校验（无需 Secrets）」一步，
缺少真机时仍能验证配置正确性），但答辩时应如实说「链路就绪、真机未验证」，**不要写成「已上线」**。
## 怎么用这份映射

1. **领任务**：先看当前在哪个 Sprint，再去对应 Lab 的 Milestone 里挑 issue；不要跨 Sprint 领。
2. **写站会/回顾纪要**：标题统一写「S{N} 第 X 次站会」，正文提一句对应的 Lab 编号，方便老师对实验考勤。
3. **打版本标签**：Sprint 结束打 tag 时，确认该 Sprint 覆盖的所有 Lab Milestone 都已清空。

## 建议补的两件事

- **给 issue 补 Sprint 字段**：目前 issue 只有 `labN` 标签和 Lab Milestone，没有 Sprint 维度，每次都要靠这份文档翻译。推荐在 GitHub Projects 里启用 **Iteration** 字段（S0–S4），或退回用 `sprint:1`–`sprint:5` 标签。
- **把本表回填到 `sprint-plan.md`**：两边各一份容易再次漂移，建议以本文件为准，`sprint-plan.md` 里只留一个链接。
