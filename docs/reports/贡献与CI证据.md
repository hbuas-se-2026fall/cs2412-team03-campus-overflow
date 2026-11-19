# 贡献与 CI 证据（230593641 / 刘浩然 / SM）

> 这一页把「我做了什么、凭什么证明它有效」摊开写，方便答辩、同伴复核与成绩核算。
> **所有数字都能用文末的命令复现**；带日期的数字表示截止日期，不要跨日期混用。

## 一、贡献总览

| 维度 | 数量 | 口径 |
| --- | ---: | --- |
| 我开的 PR | **44** | 已合入 **29**、开放 **4**、关闭未合 11（其中 4 个是实验性批次，见 §五） |
| 在 `develop` 上属于我的提交 | **113** | GitHub Contributors 接口口径 |
| 我评审他人的 PR | **13** | 其中 APPROVE 10、提出实质性修改 3（#102 因口径问题被 dismiss） |
| 新增/改写的测试文件 | 30+ | 覆盖 6 个后端模块 + 前端 8 个页面/模块 |

### 我在各模块留下的测试与门禁

| 模块 | 我做了什么 | 覆盖变化 |
| --- | --- | --- |
| `co-identity-application` | 从 **0 个用例**到 62 个（手写替身，不启 Spring） | 行/分支 **100% / 100%** |
| `co-identity-domain` | 用户管理侧与课程 22 个不变量用例 | 行 75.7%→**93.4%**，分支 60.6%→**83.3%** |
| `co-discovery-domain` | 通知/搜索 41 个用例 | 行 **100%**，分支 97.8% |
| `co-discovery-application` | 索引投影器 10 个处理器逐个重放验证幂等 | 分支 75.9%→**90.7%** |
| `co-reputation-domain` | 悬赏状态机 + 积分守恒 + 时区/ISO 周 67 个用例 | 行/分支 **100% / 100%** |
| `co-qa-domain` | 正文与标签校验边界 9 个用例 | 分支 91.4%→**94.8%** |
| `co-qa-application` | 失败路径 23 个用例 | 行 83.5%→**98.4%**，分支 74.4%→**91%** |
| `co-bootstrap` | 安全过滤链 8 个真实行为用例 | 行 79.4%→**95.7%**，`SecurityConfig` 4/48→**46/48** |
| `frontend` | 引入 Vitest，54 个用例（api/6 页面/登录态/课程中心） | 从 0 到 54 |

## 二、CI 证据

### 2.1 develop 上的必需检查（2026-11-17 实测）

```text
skeleton-check              completed/success
frontend-check              completed/success
后端构建与验证                completed/success
前端构建                     completed/success
#34 验收 / 真实 MySQL 落库    completed/success
S3 规格 / 完整业务链路         completed/success
容器镜像构建                  completed/success
SonarQube 质量门禁            completed/skipped   （develop push 上按设计跳过，见 #117）
```

对应的 workflow run：#43873085606（CI）、#43873085372（skeleton-check）、
#43873085520（Publish Images）、#43873085468（Testcontainers）。

### 2.2 本地复现 CI（Actions 额度耗尽期间的自证手段）

`scripts/ci-local.ps1` 按 `ci.yml` 的阶段顺序在本地重放，2026-11-17 实测 10 个阶段：

```text
PASS 1/10 编译                       43.5s
PASS 2/10 单元测试（全后端）            54.1s
PASS 3/10 架构约束（ArchUnit 11 条）    8.0s
SKIP 4/10 覆盖率门禁（-DskipITs）        离线模式跳过，联网可跑
PASS 5/10 行走骨架归零                  0.4s
PASS 6/10 错误码一致性（代码 ↔ 文档）     0.1s
PASS 7/10 前端代码规范（ESLint）          6.5s
PASS 8/10 前端单元测试（Vitest 54 个）  16.7s
PASS 9/10 前端类型检查                  2.5s
PASS 10/10 前端构建                    7.7s
```

> 这份输出的意义：**2026-11-12 至 11-14 期间 Actions 因私有仓库额度耗尽而全部 0 步骤失败**（Issue #120），
> 那段时间「主干是绿的」无法用 GitHub 证明，只能靠这个脚本。

### 2.3 质量门禁怎么验证「它是活的」

门禁最怕的是「看起来在跑、其实永远绿」。对覆盖率门禁我做了反向验证：

```bash
mvn verify -DskipITs -pl '!co-bootstrap'                          # → All coverage checks have been met.
mvn verify -DskipITs -pl '!co-bootstrap' -Ddomain.coverage.minimum=0.999
# → Rule violated for bundle co-identity-domain: lines covered ratio is 0.933, but expected minimum is 0.999
```

同理，`skeleton-check.yml` 里新加的错误码一致性检查也有双向语义：
**文档里有、代码里没有 → 直接红**；代码里有、文档里没有 → 只告警。

## 三、我做过哪些事（按主题）

| 主题 | 代表 PR | 说明 |
| --- | --- | --- |
| 测试补齐 | #126–#139、#155 | 8 个模块从「0 到有」或从「只测主路径」到「覆盖边界」 |
| 质量门禁 | #51、#97、#100、#105、#121、#132、#150 | 渐进式门禁、覆盖率豁免撤销、行走骨架归零、省额度、错误码一致性、门禁分级 |
| 测试基础设施 | #122、#126、#131 | 本地复现 CI、Vitest 接入 CI、逐模块覆盖率矩阵 |
| 文档 | #68、#104、#115、#119、#124、#125、#131、#153、#154 | ADR、任务清单对齐、Gherkin 用例、错误码目录、质量门禁一览、Windows 踩坑集 |
| 前端 | #144 | 课程中心去掉 `window.location.reload()`，改局部刷新 + 5 个用例 |
| 运维 | #89、#99 | GHCR 包可见性实测、compose 冒烟转每周定时 |
| 架构 | #133 | ArchUnit FF-7/8/9（应用层不碰 SQL、领域不被 Spring 污染、`@Repository` 只在 infrastructure） |

## 四、CI 之外的过程证据

- **本地环境验证**：#59（环境验证截图回填）；
- **评审记录**：见 §五，每条都写明了「我核了什么、发现了什么、建议怎么改」；
- **问题闭环**：#120（Actions 额度）我贴了 run ID 与两条修复路径，最终以仓库转 public 解决。

## 五、如实说明：哪些是实验性产物

为了减轻评审负担，我曾把 16 个 PR 合成 4 个「批次 PR」（#145–#148），并在成员 PR 里留评论说
「内容已并入批次」。**后来 KZM-7748 选择逐个审批原 PR，我又把批次方案撤回**：关闭批次 PR、
重新打开 16 个成员 PR、删掉那 4 条指路评论。批次分支也一并删除。

所以 `#145`–`#148` 在 PR 列表里显示为「关闭未合」，**它们的内容并没有丢失**——都以原始 PR 的形式合入了。
之所以写在这一页，是因为如果有人按「PR 关闭 = 白做」来统计，会得到错误结论。

同样，`#152` 是一个**只用于验证 CI 链路是否可用的探针 PR**（1 个最小用例），确认后立即关闭并删除分支，
**没有真实功能改动**，不应计入贡献。

## 六、怎么复现这些数字

```bash
# develop 上的贡献数（按作者统计）
curl -s "https://api.github.com/repos/hbuas-se-2026fall/cs2412-team03-campus-overflow/contributors" \
  | jq '.[] | {login, contributions}'

# 我开的 PR（含状态）
curl -s "https://api.github.com/search/issues?q=repo:hbuas-se-2026fall/cs2412-team03-campus-overflow+type:pr+author:230593641" \
  | jq '.total_count'

# 覆盖率（每个模块生成 target/site/jacoco/jacoco.csv）
cd backend && mvn -o -B -ntp test jacoco:report

# 本地全量门禁
powershell -ExecutionPolicy Bypass -File scripts/ci-local.ps1
```
