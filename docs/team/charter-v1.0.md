# 团队章程 v1.0

> 团队：Team3 · CampusOverflow
> 生效日期：2026-10-22
> 状态：v1.0 已随合并提交进入 `develop`，提交说明含五名成员署名；线下签字栏在站会补齐

---

## 一、团队价值观

1. **代码 reviewed 才合并**：不自己合自己的 PR，至少一个队友看过并打 LGTM。
2. **测试不删不放宽**：红测试是规格说明，不是要消灭的噪音。删测试等于改需求。
3. **有问题当天说**：卡住超过 2 小时就在群里喊，不憋着硬扛。
4. **小步提交**：一个 commit 只做一件事，出问题能回滚。
5. **文档跟着代码走**：改了接口/架构，顺手更新 docs，不留过期文档。

---

## 二、沟通约定

| 事项 | 约定 |
|---|---|
| 每日站会 | 工作日 21:00 飞书文字群，每人三句话：昨天做了什么、今天要做什么、有什么卡住 |
| PR 响应时效 | 队友提的 PR，24 小时内 review 完（工作日） |
| 卡住升级 | 自己试了 2 小时还没解决，直接 @SM 或 @PO，不硬扛 |
| 会议纪要 | SM 负责，每次站会后当天发到群里 |
| 紧急问题 | 直接打电话，不发邮件等回复 |

---

## 三、开发规约

### 分支模型与合并方式
- 默认开发分支：`develop`（日常集成分支）；`main` 为发布分支，只接收发布节点合并。
- 新功能：`feature/<issue编号>-<功能简述>`，例：`feature/25-user-story-map`
- 修 bug：`fix/<issue编号>-<问题简述>`
- 文档：`docs/<内容>`
- **禁止直推 `main` 与 `develop`**，所有改动走 PR。
- 合并方式：`feature/* → develop` 使用 **Squash merge**（历史紧凑、一个 PR 一个提交）；`develop → main` 发布节点使用 **Merge commit**（保留发布批次记录）；合并后自动删除 head 分支。
- 保护要求：`develop` 至少 1 名非作者 Approve + `skeleton-check`/`frontend-check` 通过；`main` 至少 2 名 Approve（含 SM 或 PO）+ Code Owner 评审 + 状态检查通过，管理员同样受保护约束。

### Commit 格式
```
<type>(<scope>): <描述> #<issue编号>
```
type 用：feat / fix / docs / test / refactor / chore；scope 为模块或上下文（如 co-qa、co-identity、frontend、docs、ci）。

例：`feat(co-qa): Question 聚合 post 方法实现 #9`
例：`test(co-qa): 补 QuestionTest 边界用例 #11`
例：`docs(team): 修订章程分支合并口径 #7`

### 代码要求
- 新功能必须带单元测试，本任务涉及模块的测试全绿才能提 PR
- 不改动已有测试的断言（除非和 PO 确认过测试本身错了）
- 领域层零框架依赖（ArchUnit 会检查）
- 前端不写 `any` 类型，CI 会查

### PR 要求
- PR 描述写清：做了什么、为什么、怎么测的
- 至少 1 人 review + LGTM
- CI 全绿
- SM 负责合并

---

## 四、DoD（Definition of Done）

一个任务算"完成"，必须同时满足：

- [ ] 代码已通过 PR 合并到 `develop`（发布节点再由 `develop` 合并到 `main`；日常任务不直接合 `main`）
- [ ] 本任务涉及模块测试全绿
- [ ] CI 骨架检查（`skeleton-check`、`frontend-check`）全绿
- [ ] 新功能有对应单元测试
- [ ] 代码至少被 1 名非作者 review 过（`main` 需 2 名且含 SM/PO）
- [ ] 文档/注释需要更新的已更新
- [ ] 看板上的卡片从 In Review 拖到 Done

---

## 五、角色分工

| 角色 | 姓名 | GitHub | 职责 |
|---|---|---|---|
| PO | 孔志敏 | KZM-7748 | 需求优先级、Backlog、验收标准、拍板 |
| SM | 刘浩然 | 230593641 | 站会主持、流程保障、合并 PR、风险跟踪 |
| SM | 肖蕴晨 | LIfe-evoe | 看板维护、issue 拆解、CI 配置、环境保障 |
| DV | 黄振亚 | deadlie | Discovery 上下文 + 测试验证 / CI 门禁（ArchUnit、skeleton-check） |
| DV | 易子恒 | moOvO760 | Q&A + Reputation 上下文，DevOps |

> 备份：每个角色有 backup，主负责人不在时由 backup 顶上。

---

## 六、轮换约定

每个 Sprint 结束（约第 6、10、13 周）投票轮换一次角色，确保每个人都体验过 PO/SM/DV。

---

## 七、全员确认（电子署名）

> 依据本章程开头与《角色承诺书》约定：允许以 GitHub 账号 commit / PR 署名作为电子确认，不强制手写笔迹。下列五人均已用本人账号在本仓库提交或评审，视为确认本章程；纸质手写签名于下次站会统一补签，由 SM 存档。

| 角色 | 姓名 | GitHub 账号（电子确认） | 确认日期 |
|---|---|---|---|
| PO | 孔志敏 | KZM-7748 | 2026-10-31 |
| SM | 刘浩然 | 230593641 | 2026-10-31 |
| SM | 肖蕴晨 | LIfe-evoe | 2026-10-31 |
| DV | 黄振亚 | deadlie | 2026-10-31 |
| DV | 易子恒 | moOvO760 | 2026-10-31 |

> Lab1 要求"提交本章程的最后一个 commit message 包含全员名字"：合并提交说明已包含孔志敏、刘浩然、肖蕴晨、黄振亚、易子恒五人署名；本表为电子确认留痕，线下手签件站会补齐后归档。
