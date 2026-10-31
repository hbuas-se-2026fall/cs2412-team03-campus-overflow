# 架构笔记（Architecture Notes）

> Sprint 0 事件风暴产出，对照V3指导书第2-3章整理。

## 技术栈

- 语言：Java 21（record、switch模式匹配、不可变集合）
- 框架：Spring Boot 3.4.5（Web、Data JPA、Security、Session、Actuator）
- 数据库：MySQL 8.0.39（utf8mb4、ngram中文检索）
- 缓存/会话：Redis 7 + Spring Session
- 迁移：Flyway（每个上下文独立迁移目录）
- 前端：React 18 + React Router 6 + TypeScript 5 + Vite 5（端口5173）
- 测试：JUnit 5、AssertJ、ArchUnit、JaCoCo、Testcontainers、Awaitility
- 部署：Docker / Docker Compose、Nginx、Prometheus、Grafana、GitHub Actions

## 模块结构（27个Maven模块）

```
backend/
├── co-shared-kernel/          # 共享内核（异常、Guard、Actor、事件、分页）
├── co-identity/                # Identity上下文（5层，完整参考实现）
│   ├── co-identity-api/        # 发布语言
│   ├── co-identity-domain/     # 领域层
│   ├── co-identity-application/# 应用层
│   ├── co-identity-infrastructure/ # 基础设施
│   └── co-identity-interfaces/ # 接口层
├── co-qa/                      # Q&A上下文（5层，36个TODO）
├── co-reputation/              # Reputation上下文（5层，39个TODO）
├── co-discovery/               # Discovery上下文（5层，32个TODO）
└── co-bootstrap/               # 启动装配、安全链、全局异常、ArchUnit测试
```

## 六边形分层规则

| 层 | 允许依赖 | 禁止 |
|----|---------|------|
| api | JDK + 共享内核 | Spring/JPA/Jackson |
| domain | 共享内核 | Spring/JPA/Jackson |
| application | domain、本上下文api、其他上下文api | 基础设施 |
| infrastructure | domain、application | 接口层 |
| interfaces | application | domain直接依赖 |

## 关键横切机制

1. **事务性发件箱**：应用服务在同一事务内保存聚合+发布事件到outbox_event表；Outbox Relay每500ms投递
2. **安全**：Redis服务端会话（HttpOnly Cookie + XSRF-TOKEN双提交）；ActorArgumentResolver显式传Actor
3. **错误处理**：领域层只抛4类异常，GlobalExceptionHandler统一映射RFC 9457 Problem Details
4. **CQRS-lite**：写路径操作聚合；读路径直接返回投影行，批量取作者避免N+1
5. **软删除**：deleted_at标记，已删除内容不出现在列表/详情/搜索

## 8条ADR索引

- ADR-001：模块化单体+六边形分层
- ADR-002：跨上下文集成为事务性发件箱
- ADR-003：Redis服务端会话而非JWT
- ADR-004：声誉只追加账本+定期对账
- ADR-005：MySQL ngram中文全文索引
- ADR-006：用户内容一律软删除
- ADR-007：前后端以OpenAPI契约协作
- ADR-008：领域对象与JPA实体分离
- ADR-009：渐进式质量门禁（本Sprint新增）
