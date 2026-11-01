# DV1 本地 mvn test 失败基线核对

> 复核时间：2026-11-01 12:17 · 分支 `develop` @ `4e847d0`
> 命令：`cd backend && mvn -B -Dmaven.test.failure.ignore=true test`
> 统计口径：`backend/**/target/surefire-reports/TEST-*.xml` 汇总（不要数日志 `[ERROR]` 行，会重复计）

## 结论

| 项 | 值 |
| --- | --- |
| `Tests run` | 54 |
| `Failures` | 3 |
| `Errors` | 20 |
| **失败 + 错误** | **23** |
| 根因 | 23/23 全部为 `UnsupportedOperationException: TODO(S2/S3)` 未实现 |
| 与任务书 38 基线 | **不一致（23 ≠ 38）**，原因见下 |

### 为什么不是 38

任务书的 38 是 S0 骨架状态下的基线。`develop` 之后合并了 `#9 question-aggregate`，**Q&A 领域层
（`QuestionTest` / `AnswerTest` / `VoteAndCommentTest`，共 18 个用例）已全部实现转绿**，原先的
15 个失败（2 failures + 13 errors）归零。

38 − 15 = 23，且其余三个模块的失败数与基线**逐一对得上**：

| 模块 | 基线失败数 | 本次失败数 | 一致 |
| --- | --- | --- | --- |
| Reputation（`BountyAndAccountTest` + `ReputationRulebookTest`） | 14 | 14 | ✅ |
| Discovery（`DiscoveryDomainTest`） | 5 | 5 | ✅ |
| Q&A 应用层（`AnswerAndVoteServiceTest`） | 4 | 4 | ✅ |
| Q&A 领域层 | 15 | **0** | 已转绿 |

> 建议 SM 把 `环境验证记录.md` 的「基线说明」从 38 更新为 23，否则后续同学都会被判"少统计了"。

## 23 个失败逐条清单

| # | 测试类 | 用例 | 类型 | 未实现的方法 / 根因 |
| --- | --- | --- | --- | --- |
| 1 | AnswerAndVoteServiceTest | cannotVoteOnOwnContent | failure | VoteService.cast（S2） |
| 2 | AnswerAndVoteServiceTest | changingVoteProducesCorrectDelta | error | AnswerCommandService.submit（S2） |
| 3 | AnswerAndVoteServiceTest | onlyAskerOrCourseManagerCanAccept | error | AnswerCommandService.submit（S2） |
| 4 | AnswerAndVoteServiceTest | submitAnswerPublishesEventAndBumpsCounter | error | AnswerCommandService.submit（S2） |
| 5 | BountyAndAccountTest | academicTermKeysFollowCampusCalendar | error | AcademicTerm.termKey（S3） |
| 6 | BountyAndAccountTest | badgePolicyThresholds | error | BadgePolicy.forAcceptedCount（S3） |
| 7 | BountyAndAccountTest | bountyCanBeSettledOnlyOnce | error | Bounty.open（S3） |
| 8 | BountyAndAccountTest | freezeSpendAndRefundKeepPointsConsistent | error | ReputationAccount.open（S3） |
| 9 | BountyAndAccountTest | onlyCourseManagerCanOpenBountyWithinLimits | failure | Bounty.open（S3） |
| 10 | BountyAndAccountTest | sponsorCannotWinOwnBountyAndDueDetectionWorks | error | Bounty.open（S3） |
| 11 | DiscoveryDomainTest | jaccardRanking | error | RelatedQuestionPolicy.jaccard（S3） |
| 12 | DiscoveryDomainTest | mentionsAreParsedDeduplicatedAndCapped | error | MentionParser.extract（S3） |
| 13 | DiscoveryDomainTest | onlyRecipientCanMarkNotificationRead | error | Notification.create（S3） |
| 14 | DiscoveryDomainTest | searchQueryStripsOperatorsAndPicksDefaultSort | error | SearchQuery.terms（S3） |
| 15 | DiscoveryDomainTest | singleCharacterTermsAreRejectedBecauseOfNgramTokenSize | failure | SearchQuery.terms（S3） |
| 16 | ReputationRulebookTest | dailyCapLimitsOnlyPositiveVoteGains | error | ReputationRulebook.applyDailyCap（S3） |
| 17 | ReputationRulebookTest | teachersStartWithBountyPoints | error | ReputationRulebook.initialPoints（S3） |
| 18 | ReputationRulebookTest | voteDeltaIsNewEffectMinusOldEffect(int, int, int)[1] | error | ReputationRulebook.voteDelta（S3） |
| 19 | ReputationRulebookTest | voteDeltaIsNewEffectMinusOldEffect(int, int, int)[2] | error | ReputationRulebook.voteDelta（S3） |
| 20 | ReputationRulebookTest | voteDeltaIsNewEffectMinusOldEffect(int, int, int)[3] | error | ReputationRulebook.voteDelta（S3） |
| 21 | ReputationRulebookTest | voteDeltaIsNewEffectMinusOldEffect(int, int, int)[4] | error | ReputationRulebook.voteDelta（S3） |
| 22 | ReputationRulebookTest | voteDeltaIsNewEffectMinusOldEffect(int, int, int)[5] | error | ReputationRulebook.voteDelta（S3） |
| 23 | ReputationRulebookTest | voteDeltaIsNewEffectMinusOldEffect(int, int, int)[6] | error | ReputationRulebook.voteDelta（S3） |

### 3 个 `failure` 为什么也算 TODO 桩

它们不是"业务逻辑写错"，而是**断言在等一个业务异常，结果拿到了 TODO 桩抛的
`UnsupportedOperationException`**：

| 用例 | 期望 | 实际 |
| --- | --- | --- |
| `cannotVoteOnOwnContent` | `BusinessRuleException` | `UnsupportedOperationException: TODO(S2)：VoteService.cast` |
| `singleCharacterTermsAreRejectedBecauseOfNgramTokenSize` | `BusinessRuleException` | `UnsupportedOperationException: TODO(S3)：SearchQuery.terms` |
| `onlyCourseManagerCanOpenBountyWithinLimits` | `ForbiddenException` | `UnsupportedOperationException: TODO(S3)：Bounty.open` |

桩实现补上之后，这三条会自动转绿，**不需要改测试断言**。
