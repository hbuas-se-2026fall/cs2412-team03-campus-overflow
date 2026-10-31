# 领域事件清单（Domain Events Catalog）

> 跨上下文集成事件全部以IntegrationEvent record发布，经发件箱(outbox_event)至少一次投递。

## Q&A 发布的事件（7种）

| 事件 | 发布时机 | 消费者 |
|------|----------|--------|
| UserRegisteredEvent | Identity用户注册 | Reputation（开户）、Discovery |
| QuestionPostedEvent | 问题发布 | Discovery（搜索投影） |
| QuestionEditedEvent | 问题编辑 | Discovery |
| QuestionDeletedEvent | 问题软删除 | Discovery（标记删除） |
| AnswerSubmittedEvent | 回答提交 | Discovery、Reputation |
| AnswerAcceptedEvent | 采纳最佳答案 | Reputation（+15声誉、徽章）、Discovery（标记已采纳） |
| AnswerEndorsedEvent | 教师认证回答 | Reputation（徽章）、Discovery |
| VoteCastEvent | 投票/改投/撤销 | Reputation（计分）、Discovery |
| CommentPostedEvent | 评论发布 | Discovery（@提及） |
| ContentDeletedEvent | 内容删除 | Discovery（标记删除） |

## Reputation 发布的事件（5种）

| 事件 | 发布时机 | 消费者 |
|------|----------|--------|
| ReputationChangedEvent | 声誉变动 | Discovery（排行榜） |
| BadgeAwardedEvent | 授予徽章 | Discovery（通知） |
| BountyOpenedEvent | 悬赏发起 | Discovery |
| BountyAwardedEvent | 悬赏结算 | Discovery |
| BountyClosedEvent | 悬赏关闭/退款 | Discovery |

## 幂等要求

- Reputation：账本唯一键 `(eventId, userId, reason)` 保证幂等
- Discovery：`ProcessedEventStore.markProcessed(eventId, consumer)` 保证幂等
- Notification：`(sourceEventId, recipientId)` 唯一键保证幂等
