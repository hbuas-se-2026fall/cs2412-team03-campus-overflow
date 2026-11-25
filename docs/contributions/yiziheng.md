# 个人贡献映射 - 易子恒 (moOvO760)

在 CampusOverflow v1.0.0 的研发与收口过程中，作为 Developer，本人的主要贡献集中在**基础设施排障、质量保障（QA）与冒烟验收**以及**协作开发**三个维度。

| 维度 | 具体任务/贡献描述 | 关联 Issue / PR                   | 产出物/文件路径 |
| :--- | :--- |:--------------------------------| :--- |
| **DevOps** | 本地全栈开发环境搭建与排障（解决 WSL 限制、Docker Desktop 引擎启动、镜像加速器配置、MySQL 排序规则冲突），确保后端 dev,demo profile 与中间件正常连通。 | `fix/mysql-collation-docker` 分支 | `deploy/docker-compose.yml` |
| **Q&A (质量)** | 作为 Assignee 主导完成 Issue #110 的 22 步业务链路冒烟测试。排查并解决了测试中的 CSRF 403 拦截问题，在 IDEA 中成功实跑所有请求，修正预期值并完成最终签收。 | #110 / #143                     | `docs/05-质量/smoke/qa-flow.http` |
| **声誉** | *#164PR* 补充 Vote 领域规则边界测试用例，验证撤销投票后的声誉分数回滚逻辑。 | *(待补 Issue)*                    | `co-qa` 测试模块 |
| **Lab8 部署** | *#165PR* 通过 Cloudflare Tunnel 暴露本地演示环境，提供临时公网 URL 并留存验证截图。 | *(待补 Issue)*                    | `docs/lab8/public-demo-evidence.md` |
| **团队协作** | 参与代码评审，按“问题+依据+建议”标准，累计为队友提交 X 条实质性 Review 意见（包含测试边界覆盖与文档路径修正）。 | -                               | GitHub PR Review 记录 |
| **团队协作** | 完成 Sprint 0 破冰任务，建立个人开发者文档，跑通首次 Fork/Clone -> Branch -> PR 的标准化 Git 协作流。 | -                               | `docs/team/hello-yiziheng.md` |

---
**工具使用说明**：本文档由人工整理，AI 仅辅助格式排版与语言润色。
