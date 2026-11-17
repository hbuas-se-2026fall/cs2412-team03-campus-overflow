# ADR-010：Maven 多模块包结构

**状态**：已接受 ｜ **驱动特征**：边界可验证（编译期就拦住越界依赖）、可测试性、构建成本

> 回填说明：本文件此前只有一行标题（占位，46 字节）。内容按 `backend/pom.xml` 与仓库目录现状回填，
> 与 Issue #41「ADR-002：Maven 多模块包结构」的产物对应（编号偏差的处理见 [`README.md`](README.md) 的注记）。

## 背景

四个限界上下文（Identity / Q&A / Reputation / Discovery）要挤在同一个可部署单元里演进（见 ADR-001）。
问题是「边界清晰」如果只写在文档里，三周后就会被一次顺手 `import` 打穿——评审靠记忆，记忆靠不住。
团队需要一种**编译器能替我拦住越界依赖**的结构，同时不能牺牲本地构建速度：16 周、5 人、零预算，
没有多余的 CI 排队时间可浪费，也没有人愿意为了跑一个领域测试而启动整套 Spring。

## 决策

后端拆成 **27 个 Maven 模块**。根聚合 POM 是 `backend/pom.xml`（`packaging=pom`，
parent 为 `spring-boot-starter-parent 3.4.5`，`java.version=21`，`groupId=com.campusoverflow`）：

| 类别 | 模块 | 数量 |
| --- | --- | --- |
| 根聚合 | `backend` | 1 |
| 上下文聚合 | `co-identity` / `co-qa` / `co-reputation` / `co-discovery` | 4 |
| 分层模块 | 每个上下文 `<ctx>-api` / `-domain` / `-application` / `-infrastructure` / `-interfaces` | 20 |
| 共享与启动 | `co-shared-kernel`、`co-bootstrap` | 2 |

配套规则（**都由 `co-bootstrap` 的 `ArchitectureTest`（ArchUnit 1.3.0）自动验证**，不靠自觉）：

1. **跨上下文只允许依赖对方的 `*-api`**：FF-2 断言任一上下文都不得依赖其他上下文的
   `domain` / `application` / `infrastructure` / `interfaces` 包，需要对方数据只能走 `api` 里的发布语言或集成事件；
2. **六边形分层单向依赖**（FF-4）：`interfaces → application → domain`、`infrastructure → domain`，
   `Interfaces` 与 `Infrastructure` 只允许被 `Bootstrap` 访问，`Application` 只允许被 `Interfaces` / `Infrastructure` / `Bootstrap` 访问；
3. **领域层零框架**（FF-1）：`..domain..` 不得依赖 Spring / JPA / Validation / Hibernate / Jackson / Swagger，
   所以领域测试能纯 JVM 跑，毫秒级；
4. **`api` 包是发布语言**（FF-5）：只依赖 JDK 与 `co-shared-kernel`，保证任何上下文都能安全引用；
5. **版本只在根 POM 统一**：20 个分层 artifact 全部进 `dependencyManagement`，子模块 POM 不写 `<version>`；
6. **质量门禁也放根 POM**：JaCoCo `check-all-modules`（BUNDLE / LINE ≥ 0.70）由 27 个模块继承，
   阈值 `domain.coverage.minimum` 只定义一次，不允许 CI 用 `-D` 临时覆盖（Issue #97）。

## 后果

- **正面**：越界依赖在 `mvn compile` 就失败，而不是等答辩时被问住；改一个上下文可以 `-pl <ctx> -am` 只构建相关模块；
  包结构与架构图一一对应，目录本身就能当答辩材料；JaCoCo、ArchUnit、骨架零容忍只需在根 POM 配一次，27 个模块自动继承。
- **负面**：模块数量多，`mvn` 输出长、冷构建慢；新增一个上下文要写 6 个 POM，属于纯手工重复劳动；
  命令行参数容易误用（例如 `-DskipITs` / `-o` 会改变门禁行为），因此用 `scripts/ci-local.ps1` 把阶段顺序固化；
  `co-shared-kernel` 有演化成「上帝模块」的倾向，需要评审盯住它只放真正共享的值对象与基础类型。
- **演进路径**：如果将来某个上下文真的要独立部署，`*-api` 已经是可以直接抽成独立仓库或 RPC 契约的接缝，
  不需要先拆包——这也是当初把 `api` 单独做成模块而不是塞进 `domain` 的原因。

## 被否决的替代方案

- **单模块 + 包分层**（一个 `backend` 模块，包名分 `identity` / `qa` / …）：构建最快，但边界只存在于命名习惯里，
  一次跨层 `import` 编译期毫无提示；ArchUnit 能补一部分，却挡不住「把领域逻辑写进 controller 同包」这种物理混居。
- **每个限界上下文一个 Git 仓库**：边界最硬，但 5 个人要维护 5 套版本号与依赖发布流程，16 周内不现实，
  而且跨上下文的改动无法原子提交、无法一次性回滚。
- **每个上下文一个 Spring Boot 应用（微服务）**：已在 ADR-001 中按架构量子分析否决——
  四个子域共享同一套用户与同一份数据、必须同步展示声誉与内容，拆开只会换来分布式事务、联调与运维成本。
