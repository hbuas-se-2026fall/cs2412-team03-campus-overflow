# Sprint 2 · 后端骨架与前端骨架

| 项 | 内容 |
| --- | --- |
| 周期 | 2026-11-02 至 2026-11-05 |
| 对应 Lab | Lab4（领域层与应用层骨架）、Lab5（前端骨架）、Lab7/Lab8 前置 |
| 版本 | 未单独打标签 |
| 参与 | 孔志敏（PO）、刘浩然（SM）、肖蕴晨（SM2）、黄振亚（DV1）、易子恒（DV2） |

## Sprint 目标

把 Q&A 的领域层、应用层骨架全部填上，前端课程页与权限控制可用，部署与监控的架子先搭起来，为 S3 的完整链路做铺垫。

## 计划

| 项 | 内容 | 主责 |
| --- | --- | --- |
| S2-A | 领域层：Question / Answer / Vote / Comment 聚合 | DV1 |
| S2-B | 应用层：Question / Answer 命令与查询服务 | DV1 |
| S2-C | 行走骨架：Reputation / Discovery 监听器空实现 | DV1 |
| S2-D | 前端：课程页、编辑删除按钮、权限控制 | DV2 |
| S2-E | 前端工程化：ESLint + Prettier + Husky | DV2 |
| S2-F | 部署：docker-compose 编排 | DV2 |
| S2-G | CI/CD 流水线扩展 | SM/DV |
| S2-H | 监控：Prometheus + Grafana | DV2 |
| S2-I | 运维手册 v1.0 | SM/DV |
| S2-J | 覆盖率冲到 70%，Testcontainers 集成测试 | SM2 |

## 实际完成

**领域层与应用层（13 条）**

| Issue | 内容 |
| --- | --- |
| #9 | `Question.post / edit / ensureNotDeleted / softDelete` |
| #8 | `MarkdownBody.ofQuestion / ofAnswer` + `Tags.names` |
| #11 | `Answer.submit / edit / endorse / softDelete` |
| #13 | `QuestionCommandService.post / edit / delete / load` |
| #14 | `AnswerCommandService` 七个方法 |
| #16 | `QuestionQueryService.list / detail / popularTags` |
| #15 | `VoteService` + `CommentService` |
| #17 | 行走骨架：Reputation / Discovery 监听器空实现 |

**前端（3 条）**

- #4 课程列表、我的课程、加入课程三页。
- #18 编辑/删除按钮按权限显示，错误提示统一。
- #42 ESLint + Prettier + Husky pre-commit。

**工程与运维（5 条）**

- #37 `docker-compose` 编排：app + MySQL + Redis + Nginx + Prometheus + Grafana。
- #38 CI/CD 扩展：build → test → sonar → docker build → push → 部署。
- #39 Prometheus 五条核心指标 + 一条告警规则。
- #40 运维手册 v1.0：启动、停止、回滚、常见故障。
- #31/#34 覆盖率提到 70%，补 Testcontainers 集成测试。
- #19 CI 门禁纳入 `co-qa-domain`，阈值 0.70。

**架构与记录（3 条）**

- #41 ADR-002：Maven 多模块包结构决策。
- #30 事件风暴现场记录与照片。
- #65 Context Map 导出 PNG。

## 评审

| 验收项 | 结果 |
| --- | --- |
| Q&A 领域层单测全绿 | 通过 |
| `co-qa-domain` 覆盖率 ≥70% | 通过 |
| Testcontainers 集成测试可跑 | 通过 |
| 前端 `npm run build` 通过 | 通过 |
| 部署编排文件齐全 | 通过 |
| `http/auth.http` 冒烟脚本 | **未达成** |

## 回顾

**Keep**

- 领域层先写测试再填实现，`Question` / `Vote` / `Comment` 的不变式一次写对，后面没返工。
- 部署编排和监控在骨架阶段就搭，S4 真机验证时省了事。

**Drop**

- **Issue 关闭不看交付物**。#3「编写 `http/auth.http` 冒烟脚本」在 11-05 关闭了，但仓库里从来没出现过 `http/` 目录，`scripts/` 下也没有 `.http` 文件。这是假关闭。
- 覆盖率报 70%，实际口径只是 `co-qa-domain` 一个模块，容易被理解成「全后端 70%」。

**Start**

- 关闭 Issue 前必须能指出文件路径，指不出来就不关。
- 覆盖率口径写清楚是哪个模块，别只写一个百分数。

## 燃尽图

| 日期 | 当日关闭 | 累计关闭 | 剩余 |
| --- | --- | --- | --- |
| 11-01 | — | 18 | 30 |
| 11-02 | 1 | 19 | 29 |
| 11-03 | 2 | 21 | 27 |
| 11-04 | 1 | 22 | 26 |
| 11-05 | 18 | 40 | 8 |

本周期共关闭 22 条，11-05 一天关了 18 条，是集中收口。

## 行动项

| # | 行动 | 负责人 | 状态 |
| --- | --- | --- | --- |
| 1 | 补 `http/auth.http` 冒烟脚本并重开 #3 | DV | **已完成** —— #94 交付、#102 删除根目录重复脚本，统一用 `docs/05-质量/smoke/auth.http` |
| 2 | 覆盖率口径统一成「全后端」或写清模块范围 | SM2 | 待办 |

---

> **后续校正（2026-11-12）**：本文件里提到的 `http/auth.http` 冒烟脚本在 S0/S2 当时确实没交付。
> 它后来由 **#94** 补上，并在 **#102** 收敛为唯一一份 `docs/05-质量/smoke/auth.http`（根目录那份重复且缺 `Cookie: XSRF-TOKEN`，已删除）。
> 上面「回顾 / 行动项」里的原始判断保留不动——那是对当时状态的如实记录；本条只补充「后来怎么解决的」，避免读者按断链去找文件。
