# 变更日志

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/) 风格，版本号按里程碑推进。

## [v1.0.1] - 2026-11-25

> v1.0.0 之后的补丁版本：只补测试、覆盖率门禁分级与质量 / 交付证据文档，不含业务领域逻辑改动。

### 测试补强

- 安全过滤链补 8 个用例，`SecurityConfig` 行覆盖 4/48 → 46/48（PR #155）。
- `ActorArgumentResolver` 补 12 个用例，分支覆盖 13/16 → 16/16（PR #166）。
- 发件箱中继补 7 个异常与指标用例，`co-bootstrap` 行覆盖 95.7% → 97.3%（PR #161）。
- Discovery 相关推荐补候选为空分支的边界用例（PR #156）；Q&A 投票变更不变量补边界用例（PR #164）。

### 质量门禁

- 覆盖率门禁按模块分级：全局 0.70 → 0.75，10 个有测试模块各自 0.90~0.95（PR #150）。
- 覆盖率矩阵同步 OutboxRelay 新增用例（PR #162）。

### 文档与交付证据

- 新增「质量门禁一览」，说明提交后会被哪些检查拦住（PR #153）；记录 CI 偶发失败的真实判据与重跑方法（PR #160）。
- 新增 Windows 踩坑集 10 条（PR #154）；补「贡献与 CI 证据」并复核 Lab↔Sprint 映射（PR #158）；归档个人贡献说明（PR #159、#163）。
- 归档 Lab7 QA 证据清单（PR #157）；归档 Lab8 临时公网验证记录与首页 / 登录 / 搜索 / 详情 4 张截图（PR #165）。

### 已知边界

- #101 真机部署仍开放：链路就绪，无真机验证，不表述为已上线；Lab8 临时隧道验证完毕已关闭，非常驻上线。

## [v1.0.0] - 2026-11-17

> 最终交付版本。以下按仓库已合并产物整理，不含未验证事项。

### 交付范围

- Lab1–Lab8 全部实验产物：团队章程与角色分工、用户故事地图与 Product Backlog、事件风暴与限界上下文、Q&A 领域模型与应用层、前后端骨架、测试质量门禁、容器化与 CI/CD 链路。
- S4 收口：#106–#114 拆出的文档、签收、演示与发布链缺口逐项闭环（进展以各 Issue 状态为准）。

### 相对 v0.3.0 的变化

- 行走骨架清零、覆盖率门禁由 CI 参数搬回根 POM 并覆盖全部 27 个模块、`co-bootstrap` 补测（详见下方 v0.9.0 段落）。
- Product Backlog 8 处【待填】补齐，新增 S2-012/013、S3-014~019 共 8 份 Gherkin `.feature` 用例（PR #119）。
- 用户画像去掉【待填】，明确「基于推断而非访谈」口径；按 #109 授权路径未虚构访谈记录（PR #118）。
- 收口文档如实回填：章程与角色分工【待填】清零、W43 纪要按「未单独召开」写、Sprint 4 记录回填至 2026-11-13、新增 `docs/05-质量/smoke/README.md` 说明 `auth.http` / `qa.http` 的新位置（PR #137）。
- 修复 Docker 环境下 MySQL 排序规则冲突导致后端启动失败（PR #123）。
- 补 `.gitattributes` 与 `.editorconfig` 根治 CRLF 假 diff（PR #103）；任务清单与 #107 拆解表标注「已全部实现」（PR #104）。

### 质量证据

- 本地复现 CI 7/7 PASS（PR #122 提供的 `scripts/ci-local.ps1`，在 develop `c633ece` 上实跑）：424 个后端测试全绿、`All coverage checks have been met.`、行走骨架 0、前端 `typecheck` / `build` 通过。该输出不等同于 GitHub 必需检查，仅作补充证据。
- 仓库转 public 后 Actions 恢复，`develop` 上 skeleton-check / CI / Testcontainers 集成测试 / frontend-check / SonarQube 质量门禁均有 success 记录（见 #120 关闭记录）。

### 已知边界（答辩统一口径）

- #101 真机部署缺少常开主机与 4 个 Secret，保持开放；统一表述「链路就绪，无真机验证」，不说「已上线」。
- 用户画像基于推断，未开展 3–5 人访谈，按 #109 授权路径删除画像中两个字段，不补写虚构访谈。
- qa-flow.http 22 步签收（#110）、30–60 秒演示录屏（#111）须以真实运行 / 录屏证据结案；发布前请按 Issue 实际状态复核本段，未闭环的不要写成已完成。

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