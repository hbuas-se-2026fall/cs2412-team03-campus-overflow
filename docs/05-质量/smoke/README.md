# 冒烟脚本目录说明

本目录是 Issue #3 要求的 `http/auth.http` 与 Issue #20 要求的 `http/qa.http` 的新位置。

| 原要求路径 | 当前文件 | 覆盖内容 |
| --- | --- | --- |
| `http/auth.http` | `docs/05-质量/smoke/auth.http` | 认证链路：注册 / 登录 / 登出 / 错误密码 / CSRF |
| `http/qa.http` | `docs/05-质量/smoke/qa-flow.http` | 业务链路 22 步：课程 → 提问 → 回答 → 投票 → 改投 → 采纳 → 声誉 → 流水 → 排行榜 → 搜索 → 推荐 → 通知 → 登出 |

## 为什么不放根目录

质量相关的冒烟脚本统一归到 `docs/05-质量/` 下，跟测试报告、覆盖率说明、评审清单一处维护。根目录 `http/` 历史上只出现过 `auth.http`（`git log --all -- 'http/*'` 可查），`qa.http` 从未存在过；PR #102 删除了根目录那份重复且缺 `Cookie: XSRF-TOKEN` 的脚本，路径收敛到本目录（见 `docs/scrum/sprint-0.md` 末尾的「后续校正」与 #110 背景）。

## 运行状态（2026-11-13，如实记账）

- `qa-flow.http` 的 **22 步尚未逐条跑通签收**：文件末尾没有验收记录，`@courseId` 仍是默认值 `1`。
- 本机没有可用的 Docker / WSL / MySQL + Redis（`docker: command not found`）。后端 dev profile 依赖 `localhost:3306` MySQL 与 `localhost:6379` Redis（见 `backend/co-bootstrap/src/main/resources/application.yml`），`mvn spring-boot:run -pl co-bootstrap -Dspring-boot.run.profiles=dev,demo` 无法在本机跑起来。
- #110 要求「有不一致就改文件里的预期值，不要改步骤迁就」以及「全通过后追加验收行」。这两条**没有实测输出就不做，不代签**。等 Docker / WSL 环境就绪后由易子恒实跑、贴出每步状态码，再回填 `@courseId` 与验收行。
