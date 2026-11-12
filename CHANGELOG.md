# 变更日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/) 风格，版本号按里程碑推进。

## [v1.0.0] - 未发布

最终交付版本。内容随 S4 收口持续补齐，发布时确定。

## [v0.9.0] - 未发布

S4 收口版本：把 S3 遗留的质量缺口一次性补平。

### 完整性

- **行走骨架清零**：`LeaderboardService.on/top`、`ReconciliationService.reconcile`、`QaQueryApiImpl.findQuestion` 全部实现，全仓库 `UnsupportedOperationException` 为 0（#96）
  - 排行榜消费端用 `ProcessedEventStore` 去重，保证发件箱「至少一次」投递下分数不会被加两遍
  - 榜单只展示正分，名次在过滤后重排

### 质量

- **覆盖率门禁从 CI 参数搬回根 POM**（#98）：`domain.coverage.minimum` 由 `0` 改为 `0.70`，父 POM 新增 `check-all-modules`，经 `<plugins>` 继承到全部 27 个模块；删除 CI 里的 `-Ddomain.coverage.minimum=0.70` 与 `-pl co-qa/co-qa-domain -am`
- **`co-bootstrap` 补测**：34.2% → 79.4%（204/257 行），新增 `DemoDataSeederTest`、`OutboxRelayConfigurationTest`、`OutboxRelayDispatchTest`、`BootstrapContextTest`、`BootstrapApplicationContextIT`（#98）
- **全后端覆盖率**：69.0% → 76.0%（1249/1643 行）
- 两个既有 IT 加 `@Testcontainers(disabledWithoutDocker = true)`，无 Docker 环境整类跳过而不是把 `mvn verify` 拖红
- `co-reputation-application` 曾因声誉结算分支未就绪而显式豁免（#98），豁免条件由 #100 解除

### 运维

- **compose 全栈冒烟加每周定时触发**（#99）：每周一 06:00（北京时间）跑一次 `docker compose up -d --build`，为主干可部署性提供周期证据；PR 上不出现该项检查
- **GHCR 拉取侧验证**（#89）：实测包可见性，补主机侧 `docker pull` 命令与 token 说明

### 文档

- 补五个 Sprint 的过程记录（#92）：目标、计划、实际完成、评审、Keep/Drop/Start、燃尽图、行动项
- 回填 `Sprint0-计划.md` 的 17 处待填（#94）
- 新增 DDD 文档索引页，同步 ADR-009 状态为「已完成」，成员表去除学号（#91）

## [v0.3.0] - 2026-11-10

Lab1–Lab8 全部实验交付完成。相对基线新增 75 个提交。

### 领域层

- Q&A 聚合不变量：`Question.post/edit/accept/softDelete`、`Answer`、`Vote`、`Comment`（#8–#12）
- 值对象与查询服务，补齐骨架空实现（#69）
- 声誉账本：账户开户、记账、幂等、每日正向上限、徽章授予、悬赏结算（#90）
- Reputation 应用层：`ReputationQueryService`、`BountyService`、`BadgeService`

### 应用层

- Question / Answer 命令服务（#71）
- VoteService / CommentService（#72）
- Discovery：中文全文检索、相关推荐、通知中心与两个事件投影器（#81）

### 前端

- 课程中心页与权限控制（#76）
- 前后端契约对齐，修正登录用户可见教师认证入口的错位（#18）

### 质量与 CI

- 渐进式质量门禁落地 ADR-009（S0 只跑 `co-shared-kernel` + `co-identity`，集成测试不阻断）
- 前端门禁真跑 ESLint，develop 触发骨架检查，修复 husky（#78）
- JaCoCo 覆盖率基线建立，修复两处红测试（#74）
- Q&A 覆盖率门禁接入（#75）、共享内核与读路径契约测试补齐（#77）
- **SonarQube 质量门禁接入**（#85）：自定义门禁要求新代码覆盖率 ≥60%、新 Bug 0、新漏洞 0
- 架构约束 ArchUnit 在 CI 中执行（修复 `-am` 与 surefire 参数）

### 部署与可观测性

- 后端多阶段 Docker 构建 + 非 root 运行（#82）
- jlink 定制 JRE，镜像体积 275MB → 150MB（#79）
- 前端多阶段构建 + Nginx SPA fallback 与 `/api` 反代（#84）
- 可观测性配置、部署工作流、运维手册（#73）
- compose 全栈在带 Docker 的 runner 上跑通，逐项验证健康探针、首页、SPA fallback、`/api` 反代、actuator 暴露边界、Prometheus 抓取、Grafana 数据源

### 文档

- DDD 事件风暴记录与 Context Map（#30、#66、#67）
- 10 个故事的 Gherkin 验收标准（#45）
- 用户故事地图与访谈统计口径（#25、#64）
- ADR-010 Maven 多模块包结构（#68）
- 环境验证记录与截图（全 5 人，#53、#54）
- 团队章程 v1.0、角色承诺书、跨成员 PR 评审清单（#33、#49）
- Sprint3 评审与回顾纪要（#83）
- Lab1 交付物归档（看板终版、5 人首次 PR 合并记录）

### 开发体验

- `dev` 环境基础账号初始化器，修复 JDBC 字符集（#5）
- `auth.http` 认证链路冒烟脚本（#70）

## [v0.1.0] - 2026-10-22

### 基线

- 课程起始框架导入：27 个 Maven 模块、React 前端、Docker 部署、CI 流水线
- 107 个 TODO 方法、38 个待实现测试
- Identity 上下文为完整参考实现

### 团队建设

- 仓库结构重构：团队文档、任务拆解、平台模板、看板与分支保护
- 新增 `skeleton-check` 骨架绿灯流水线，与全量 `ci.yml` 分工
- 32 个开发任务 Issue + S0/S1 任务 Issue，配套标签与里程碑

> 此版本原标签为 `v0.1.0-starter`，因命名不规范已改为 `v0.1.0`、旧标签删除。