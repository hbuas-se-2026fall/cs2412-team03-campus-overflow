# 上下文映射图（Context Map）

> 来源：Sprint 0 事件风暴工作坊，对照V3指导书第3章逆向分析结论整理。

## 限界上下文划分

| 上下文 | 类型 | 职责 |
|--------|------|------|
| Identity 身份与课程 | 支撑子域 / 上游 OHS+PL | 注册登录、校园认证、角色、课程成员 |
| Q&A 问答 | 核心域 / OHS+PL | 问题、回答、投票、评论、采纳、教师认证 |
| Reputation 声誉与激励 | 核心域 / 客户-供应商 | 声誉账本、投票计分、每日上限、徽章、悬赏、排行榜 |
| Discovery 内容发现 | 通用/支撑子域 / 遵奉者 | 搜索投影、中文全文检索、相关问题推荐、@提及与通知 |
| co-shared-kernel | 共享内核 | 领域异常、Guard、Actor、IntegrationEvent、分页 |

## 上下文间关系

| 上游 → 下游 | 关系模式 | 集成方式 |
|-------------|----------|----------|
| Identity → Q&A / Reputation / Discovery | 开放主机服务+发布语言(OHS/PL) | 同步查询 IdentityApi；异步 UserRegisteredEvent |
| Q&A → Reputation | 客户/供应商 | 异步 Answer*/VoteCast 事件；同步 QaQueryApi.findQuestion |
| Q&A → Discovery | 遵奉者(Conformist) | 异步 Question*/Answer*/Vote/Comment/ContentDeleted 事件 |
| Reputation → Discovery | 遵奉者 | 异步 Bounty*/BadgeAwarded 事件 |
| 全部 → co-shared-kernel | 共享内核 | Maven 依赖 co-shared-kernel |

## 架构图

```
                Identity (OHS/PL)
               /    |    \
              /     |     \
             v      v      v
    Q&A <---> Reputation    Discovery
    (OHS/PL)  (C/S)         (Conformist)
       \         |            /
        \        |           /
         v        v          v
       co-shared-kernel (Shared Kernel)
```
