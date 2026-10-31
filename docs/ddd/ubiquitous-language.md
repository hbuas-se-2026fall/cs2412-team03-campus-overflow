# 统一语言词汇表（Ubiquitous Language）

> 代码中的类名、方法名、Issue标题、站会发言都使用同一套词汇。

## 核心术语

| 术语 | 代码名 | 定义/规则 |
|------|--------|----------|
| 问题 | Question | 学生发布的求助；标题10-150字、正文Markdown、1-5个标签 |
| 回答 | Answer | 对问题的解答；独立聚合，经questionId引用问题 |
| 采纳 | accept | 提问者或课程管理者选定最佳答案；每题最多一次 |
| 教师认证 | endorse | 课程教师/助教为优质回答打的标记，每个回答只能认证一次 |
| 投票 | Vote | 对问题/回答+1或-1；每人每内容一票；改投、撤销都可以 |
| 评论/回复 | Comment | 挂在问题或回答下，最多一层回复 |
| 标签 | TagName/Tags | 统一小写、去掉前导#，每题去重后1-5个 |
| 软删除 | softDelete/deletedAt | 标记删除而非物理删除 |
| 课程管理者 | actorManagesCourse | 管理员，或该课程教师/助教 |
| 声誉 | reputation | 社区信誉分，只能由账本流水改变；采纳+15、被赞+10、被踩-2 |
| 账本流水 | LedgerEntry | 只追加的声誉变动记录 |
| 每日上限 | DAILY_VOTE_CAP | 每人每个UTC自然日因被投票获得正向声誉最多200 |
| 悬赏 | Bounty | 课程管理者用悬赏积分为问题加码 |
| 徽章 | BadgeType | 初来乍到、初露锋芒、解惑达人、名师认可、小有名气、德高望重 |
| 排行榜周期 | LeaderboardPeriod | WEEK/MONTH/TERM/ALL |
| 搜索投影 | SearchDocument | Discovery自己的问题模型，只关心"如何被找到" |
| 相关问题 | RelatedQuestionPolicy | 按标签集合Jaccard相似度推荐 |
| 提及 | MentionParser | 评论中的@昵称，去重，每条最多提醒5人 |
| 通知 | Notification | 站内消息；只有接收者能标记已读 |
| 集成事件 | IntegrationEvent | 跨上下文传播的不可变record，带全局唯一eventId |
| 发布语言 | *-api模块 | 上下文对外暴露的稳定契约（查询接口+DTO+事件） |
| 操作者 | Actor | 当前操作主体（userId、displayName、role），由接口层显式传入 |
