# 领域设计文档索引

> 四份文件是 S0 事件风暴工作坊的产出。后面每个 Sprint 写代码、拆 Issue、准备答辩，都从这里查。

## 文件清单

| 文件 | 内容 | 什么时候看它 |
| --- | --- | --- |
| [event-storming.md](event-storming.md) | 事件风暴全过程：角色、命令、领域事件、聚合怎么划分 | 想知道某个业务动作为什么这么拆 |
| [context-map.md](context-map.md) | 五个限界上下文的职责、上下游关系、集成方式 | 要新增跨上下文调用，先看这里 |
| [events-catalog.md](events-catalog.md) | 全部领域事件、发布时机、消费者 | 改事件处理器，或者要加新事件 |
| [ubiquitous-language.md](ubiquitous-language.md) | 术语与代码名对照 | 命名拿不准、Issue 标题怎么写 |

配图：事件风暴数字白板 [event-storming-board.png](event-storming-board.png)

## 建议阅读顺序

新增功能时按这个顺序过一遍：

1. `event-storming.md` —— 业务上这件事怎么走
2. `context-map.md` —— 它落在哪个上下文、跟谁打交道
3. `ubiquitous-language.md` —— 用哪个词，别自己造
4. 写完回头看 `events-catalog.md` —— 有没有该发的事件漏了

## 与代码的对应

| 上下文 | Maven 模块前缀 |
| --- | --- |
| Identity 身份与课程 | `co-identity` |
| Q&A 问答 | `co-qa` |
| Reputation 声誉与激励 | `co-reputation` |
| Discovery 内容发现 | `co-discovery` |
| 共享内核 | `co-shared-kernel` |
| 装配与启动 | `co-bootstrap` |

每个上下文内部再分 `-domain`（领域模型）、`-application`（用例）、`-interfaces`（REST 入口）、`-infrastructure`（JPA/Redis 适配）、`-api`（对外契约）五层，依赖方向由 ArchUnit 守着。