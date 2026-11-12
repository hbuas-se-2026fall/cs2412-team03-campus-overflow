# CampusOverflow · 团队 3

> 湖北文理学院《软件工程》课程贯穿项目 · 校园问答平台
> 当前阶段：**Sprint 4 收尾 / 最终交付**，Lab1 → Lab8 已全部交付；开发集成分支 `develop`，发布才合 `main` ｜ 起始基线：`v0.1.0`

[![骨架检查](https://github.com/hbuas-se-2026fall/cs2412-team03-campus-overflow/actions/workflows/skeleton-check.yml/badge.svg)](https://github.com/hbuas-se-2026fall/cs2412-team03-campus-overflow/actions/workflows/skeleton-check.yml)
[![CI](https://github.com/hbuas-se-2026fall/cs2412-team03-campus-overflow/actions/workflows/ci.yml/badge.svg)](https://github.com/hbuas-se-2026fall/cs2412-team03-campus-overflow/actions/workflows/ci.yml)
[![Testcontainers 集成测试](https://github.com/hbuas-se-2026fall/cs2412-team03-campus-overflow/actions/workflows/integration-test.yml/badge.svg)](https://github.com/hbuas-se-2026fall/cs2412-team03-campus-overflow/actions/workflows/integration-test.yml)

## 这是什么

一个面向校园场景的问答平台（大学版 Stack Overflow）：提问、回答、投票、采纳、声誉、搜索、通知。
本仓库是团队 3 的主仓库，基于课程提供的起始框架（27 个 Maven 模块 + React 前端 + Docker + CI）。

## 团队

| 姓名 | GitHub | 角色 | 负责上下文 | 备份 |
| --- | --- | --- | --- | --- |
| 孔志敏 | KZM-7748 | PO | 需求 / Backlog / 验收标准 | 刘浩然 |
| 刘浩然 | 230593641 | SM | 站会主持、流程保障、合并 PR | 肖蕴晨 |
| 肖蕴晨 | LIfe-evoe | SM | 看板维护、Issue 拆解、CI 配置、环境保障 | 刘浩然 |
| 黄振亚 | deadlie | Dev | Discovery 上下文 + 测试验证 / CI 门禁 | 易子恒 |
| 易子恒 | moOvO760 | Dev | Q&A + Reputation 上下文 + 前端 / DevOps | 黄振亚 |

> 说明：负责上下文的最终划分以 [角色与分工](docs/01-团队/角色与分工.md) 团队确认为准。

## 现在到了哪一步

- 八个实验全部交付：领域建模、骨架实现、核心链路、测试与质量门禁、CI/CD 与部署
- S4 在做的是收口：行走骨架清零、覆盖率门禁全模块生效、最终发布
- Sprint 过程记录见 [docs/scrum/](docs/scrum/)（目标、评审、回顾、燃尽图）
- Lab 与 Sprint 对照见 [docs/04-计划/Lab与Sprint映射.md](docs/04-计划/Lab与Sprint映射.md)
- 版本历史见 [CHANGELOG.md](CHANGELOG.md)
- 看板：[Team3 CampusOverflow 开发看板](https://github.com/orgs/hbuas-se-2026fall/projects/10)（列：Todo / In Progress / In Review / Done / Backlog，用 `Lab1`–`Lab8` 标签按实验阶段筛选进度）

## 五分钟跑起来

```bash
# 1) 起中间件（需要 Docker）
docker compose -f deploy/docker-compose.yml up -d mysql redis

# 2) 编译并跑测试（当前应全绿）
cd backend && mvn clean test

# 3) 启动后端（dev profile，不要加 demo）
mvn spring-boot:run -pl co-bootstrap -Dspring-boot.run.profiles=dev

# 4) 启动前端
cd ../frontend && npm install && npm run dev      # http://localhost:5173
```

接口文档：http://localhost:8080/swagger-ui.html

## 测试账号

后端有两套 profile，账号情况完全不一样，联调前先看清：

| profile | 启动方式 | 预置账号 | 适用场景 |
| --- | --- | --- | --- |
| `dev`（默认） | 不加参数 | **没有**，只能用注册接口自己建学生账号 | 日常开发，库是干净的 |
| `demo` | 追加 `-Dspring-boot.run.profiles=demo` | 1 管理员 + 1 教师 + 1 助教 + 3 学生 | 演示、权限联调、验收 |

`demo` profile 的账号，密码统一 `Campus2026`：

| 角色 | 学号 / 工号 | 邮箱 |
| --- | --- | --- |
| 管理员 ADMIN | `admin01` | admin@campus.edu.cn |
| 教师 TEACHER | `T2001` | wang@campus.edu.cn |
| 助教 TA | `TA3001` | li@campus.edu.cn |
| 学生 STUDENT | `2023001` / `2023002` / `2023003` | ming@ / hong@ / gang@campus.edu.cn |

三点注意：

1. 这些账号由 `DemoDataSeeder` 通过**应用服务**创建，bcrypt 加盐、密码策略、发件箱事件全走正常链路。**不要用 SQL 直接往 `id_user` 表插**——手写的散列过不了 `PasswordPolicy` 校验，也会绕过领域校验与事件发布，插进去能查不能用。
2. `demo` 会顺带灌入 1 门课、3 个问题以及投票/认证/采纳/评论/悬赏的完整链路数据，会污染库；要干净库就用 `dev`。
3. `dev` 目前没有 staff 账号的初始化入口（第一个管理员只能由 `system` 虚拟主体创建），见 #5。

## 仓库地图

| 目录 | 内容 | 维护人 |
| --- | --- | --- |
| `backend/` | Spring Boot 多模块后端（27 模块） | 全体 |
| `frontend/` | React + TypeScript 前端 | 易子恒 |
| `deploy/` | Dockerfile、compose、Nginx、Prometheus | 易子恒（黄振亚配合 QA 验证） |
| `docs/team/` | 正式团队章程 v1.0（权威版本） | SM + PO |
| `docs/01-团队/` | DoD、角色分工、角色承诺书、会议纪要（早期草稿章程已标废止） | SM |
| `docs/02-需求/` | 愿景、访谈、故事地图、Backlog、Gherkin 验收标准 | PO |
| `docs/03-设计/` | 上下文图、聚合清单、术语表 | 架构负责人 |
| `docs/04-计划/` | Sprint 计划、风险登记册、Lab 与 Sprint 映射 | SM |
| `docs/05-质量/` | 评审清单、测试策略、覆盖率基线、环境验证、冒烟脚本 | QA |
| `docs/06-交付/` | 运维手册、Lab 交付物归档 | DevOps |
| `docs/07-任务/` | 107 任务拆解表、领取规则 | SM |
| `docs/adr/` | 10 条架构决策（001–010，含 009 渐进式质量门禁） | 不动 |
| `docs/ddd/` | 事件风暴、Context Map、事件目录、统一语言 | 架构负责人 |
| `docs/reports/` | 测试报告、覆盖率说明、缺陷记录 | QA |
| `docs/final/` | 答辩讲稿、演示脚本、最终交付说明 | PO |
| `docs/scrum/` | 五个 Sprint 的过程记录与回顾 | SM |

## 怎么领任务（三步）

1. 打开[看板](https://github.com/orgs/hbuas-se-2026fall/projects/10)，先按左侧 `Lab1`–`Lab8` 标签筛出当前实验的 Issue；从 `Todo` 列挑一个**前置任务已完成**的任务（未来实验的卡片放在 `Backlog`）；
2. 在 Issue 下评论「领取」并把自己设为 Assignee，看板卡片由 `Todo` 移到 `In Progress`；
3. 建分支 `feature/<issue编号>-<简短描述>`，做完跑 Issue 里的验收命令，提 PR（base 选 `develop`）；评审通过移到 `In Review`，合并后归档到 `Done`。

详见 [docs/07-任务/领取规则.md](docs/07-任务/领取规则.md)。

## 验收门槛

| 项目 | 要求 | 自检命令 |
| --- | --- | --- |
| 单元测试 | 全部通过 | `mvn test` |
| 领域覆盖率 | ≥ 70%（门禁已默认开启） | `mvn verify` |
| 架构约束 | ArchUnit 全过 | `mvn test -pl co-bootstrap -Dtest=ArchitectureTest` |
| 集成测试 | 端到端通过 | `mvn verify`（需 Docker，无 Docker 时整类跳过） |
| 前端 | 类型检查与构建通过 | `cd frontend && npm run typecheck && npm run build` |
| 骨架检查 | 每次 PR 必绿 | GitHub Actions `skeleton-check` |

覆盖率门槛写在 `backend/pom.xml` 的 `domain.coverage.minimum`（当前 `0.70`），经父 POM 的 `check-all-modules` 继承到全部 27 个模块。确实无法达标的模块用 `jacoco.check.skip` 显式登记并写明理由，**不允许用命令行 `-D` 参数绕过**。

## 流水线一览

| 流水线 | 触发 | 作用 |
| --- | --- | --- |
| `skeleton-check` | PR / push | 编译 + 架构约束 + 前端构建，保证结构没坏（**必需检查**） |
| `ci.yml` | PR / push | 全量单测 + 覆盖率门禁 + SonarQube 扫描（**必需检查**） |
| `frontend-check` | PR / push | ESLint 真跑 + 类型检查 + 构建（**必需检查**） |
| `integration-test.yml` | push / 手动 | `#34` 验收（真实 MySQL 落库）与 S3 完整业务链路 |
| `compose-smoke.yml` | 每周一 06:00 / 手动 | 六个容器 `up -d --build`，验证健康探针、反代、监控 |
| `publish-images.yml` | push | 构建并推送镜像到 GHCR |
| `deploy.yml` | `publish-images` 完成后 | SSH 到部署主机拉取并重启；未配置 Secrets 时按设计跳过 |

合并到 `develop` 需要三道必需检查全绿 + 1 名非作者批准；`main` 额外要求 2 名批准与 CODEOWNERS 审核。

## 学术诚信

- 不修改任何已有测试的断言；不为通过测试而删测试；
- 允许使用 AI 辅助编码，但必须能讲清每一行代码的设计意图；
- 禁止跨团队复制代码。

## 必读材料

1. [docs/00-导航.md](docs/00-导航.md) —— 全部文档入口
2. [docs/adr/](docs/adr/) —— 10 条架构决策，理解"为什么"
3. 课程《CampusOverflow 软件架构设计文档（arc42）》第 5、6、10 章
4. [docs/错误码.md](docs/错误码.md) —— 错误码分段与 HTTP 映射
