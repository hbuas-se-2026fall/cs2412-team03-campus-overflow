# Sprint 记录

五个 Sprint 的过程记录，每个 Sprint 一份，含目标、计划、评审、回顾和燃尽图。

| 文件 | 周期 | 对应 Lab | 版本 |
| --- | --- | --- | --- |
| [sprint-0.md](sprint-0.md) | 10-27 ~ 10-28 | Lab1 启动与基础设施 | `v0.1.0-starter` |
| [sprint-1.md](sprint-1.md) | 10-29 ~ 11-01 | Lab2 需求建模、Lab3 DDD | — |
| [sprint-2.md](sprint-2.md) | 11-02 ~ 11-05 | Lab4 骨架、Lab5 前端 | — |
| [sprint-3.md](sprint-3.md) | 11-06 ~ 11-09 | Lab6 核心链路 | `v0.3.0` |
| [sprint-4.md](sprint-4.md) | 11-10 起 | Lab7 质量门禁、Lab8 部署 | `v0.9.0` → `v1.0.0` |

## 数据从哪来

三处：

1. **Issue 关闭时间** —— 每份的燃尽图按 GitHub Issue 的 `closed_at` 逐日统计，起点是 48 条 PBI。
2. **PR 合并记录** —— 「实际完成」里引用的 commit sha 都是 develop 上真实存在的合并提交。
3. **会议纪要** —— `docs/01-团队/会议纪要/` 下已有的记录，主要是 `2026-W46-Sprint3评审与回顾会.md`。

没有留存会议记录的 Sprint，按仓库实际合并记录和 Issue 状态整理，不补写没发生过的会。

## 看的时候注意

- S0 和 S2 有两处**没做到但已关闭**的 Issue（`v0.1.0` 标签名、`http/auth.http` 脚本），在对应文档的「回顾 → Drop」里写明了，没有粉饰。
- 覆盖率一律标注口径。74.3% 是 Q&A 模块加共享内核，不是全后端。
- Sprint 4 还没结束，文档里标了「进行中」，结束时再补回顾。