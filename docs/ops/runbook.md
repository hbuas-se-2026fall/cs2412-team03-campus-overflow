# CampusOverflow 运维手册 v1.0

> 对应 Issue #40（Lab8）。所有命令在**仓库根目录**执行，可直接复制。
> 前置条件：Docker Engine + Docker Compose v2、`.env`（见 `.env.example`）。
> 若本机没有 Docker，可用 `node scripts/verify-observability.mjs` 做配置层校验，但那**不能替代**启动验证。

## 0. 首次准备（只做一次）

```bash
cp .env.example deploy/.env
# 立刻改掉 deploy/.env 里的 MYSQL_ROOT_PASSWORD / DB_PASSWORD / CO_FIELD_KEY / GRAFANA_PASSWORD
```

> 坑：用 `-f deploy/docker-compose.yml` 时 Compose 默认到 `deploy/` 找 `.env`。
> 如果不想放 `deploy/.env`，每条命令都要带 `--env-file .env`。

如需连监控一起启动，确认 `${DB_PASSWORD}` 同时被 mysql 与 backend 读取（compose 已共用同一变量，改一处即可）。

## 1. 服务清单

| 服务 | 容器名 | 端口（宿主→容器） | 健康检查 | 说明 |
| --- | --- | --- | --- | --- |
| backend | `co-backend` | 8080→8080 | `wget /actuator/health/readiness`，10s×30 | Spring Boot，`USER app` 非 root |
| web | `co-web` | 80→80 | 无（静态资源） | Nginx，托管 SPA + 反代 `/api/` |
| mysql | `co-mysql` | 3306→3306 | `mysqladmin ping`，5s×20 | `campus_overflow` 库，卷 `mysql-data` |
| redis | `co-redis` | 6379→6379 | `redis-cli ping`，5s×20 | 会话/缓存，卷 `redis-data` |
| prometheus | `co-prometheus` | 9090→9090 | 无 | 仅 `--profile observability` 启动 |
| grafana | `co-grafana` | 3000→3000 | 无 | 同上，卷 `grafana-data` |

`web` 依赖 `backend` 的 **healthy** 状态，不是简单的 started；首次启动请耐心等后端就绪。

## 2. 启动

只起应用（MySQL + Redis + 后端 + 前端）：

```bash
docker compose -f deploy/docker-compose.yml up -d --build
```

连带监控栈（Prometheus + Grafana）：

```bash
docker compose -f deploy/docker-compose.yml --profile observability up -d --build
```

确认全部就绪：

```bash
docker compose -f deploy/docker-compose.yml --profile observability ps
curl -fsS http://localhost:8080/actuator/health
curl -fsS -o /dev/null -w '%{http_code}\n' http://localhost/
```

期望：`health` 返回 `{"status":"UP"...}`，首页返回 `200`。

监控入口：

- Prometheus：`http://localhost:9090/targets`（`campus-overflow` 目标应为 UP）、`/alerts` 查看 7 条告警规则
- Grafana：`http://localhost:3000`，账号密码取 `deploy/.env` 的 `GRAFANA_USER` / `GRAFANA_PASSWORD`；
  数据源与「CampusOverflow 运行态 (Lab8)」看板由 `deploy/grafana/provisioning/` 自动加载，无需手工导入。

## 3. 停止

```bash
# 停服务，保留数据卷
docker compose -f deploy/docker-compose.yml --profile observability down

# 停服务并清理未使用的构建缓存（不影响数据卷）
docker system prune -f
```

**不要**在还有数据的环境里加 `-v`：`docker compose down -v` 会删除 `mysql-data`，等于删库。

## 4. 日志

```bash
docker compose -f deploy/docker-compose.yml logs -f backend     # 后端滚动日志
docker compose -f deploy/docker-compose.yml logs --tail=200 web
docker logs --since 10m co-mysql
docker logs co-prometheus 2>&1 | grep -i 'rule\|error'
docker stats co-backend co-mysql co-redis                      # 实时资源占用
```

后端关键日志：启动失败看 `APPLICATION FAILED TO START`；死信事件看 `OutboxRelay`；对账异常看 `ReputationJobs`。

## 5. 回滚

部署版本由 `deploy/.env` 的 `BACKEND_IMAGE` / `WEB_IMAGE` 两个 tag 决定。

**场景 A：镜像已推送到仓库（CI/CD 部署环境）**

```bash
# 1. 查上一个可用 tag（CI 用 git sha 打 tag）
docker images --format '{{.Tag}} {{.CreatedAt}}' | grep campus-overflow-backend | head

# 2. 把 deploy/.env 的 BACKEND_IMAGE / WEB_IMAGE 改回上一版 tag
#    BACKEND_IMAGE=ghcr.io/<owner>/<repo>-backend:<上一版 sha>
#    WEB_IMAGE=ghcr.io/<owner>/<repo>-web:<上一版 sha>

# 3. 只拉指定 tag，不要 --build（--build 会用当前代码覆盖回滚版本）
docker compose -f deploy/docker-compose.yml pull backend web
docker compose -f deploy/docker-compose.yml up -d --no-build backend web

# 4. 验证
curl -fsS http://localhost:8080/actuator/health
docker compose -f deploy/docker-compose.yml ps
```

**场景 B：纯本地构建、没有镜像仓库**

```bash
git stash -u                       # 保存当前未提交改动
git checkout <上一版 tag 或 commit>
docker compose -f deploy/docker-compose.yml up -d --build
curl -fsS http://localhost:8080/actuator/health
git stash pop                      # 回滚成功后恢复现场
```

**数据库回滚警告**：以上是代码回滚，不包含数据库结构回退。若上一版包含不兼容的 schema 变更，先停写入、从备份恢复 `mysql-data`，再起旧版；不要在跑着新版写入时直接换镜像。

## 6. 常见故障处置

### 6.1 数据库连不上（后端反复重启，日志报 Communications link failure）

```bash
docker compose -f deploy/docker-compose.yml ps mysql          # 看 health 是否 healthy
docker logs --tail=100 co-mysql                              # 常见：数据目录权限、密码含特殊字符
docker compose -f deploy/docker-compose.yml exec mysql \
  mysqladmin ping -h localhost -uroot -p"$MYSQL_ROOT_PASSWORD"
```

排查顺序：
1. `mysql` 未 healthy → 先看 `docker logs co-mysql`，首次初始化需 20–60 秒；
2. healthy 但仍连不上 → 检查 `deploy/.env` 里 `DB_PASSWORD`：mysql 的 `MYSQL_PASSWORD` 与 backend 的 `DB_PASSWORD` 共用它，改成一致后必须**删卷重建**才生效（已初始化的 MySQL 不会因环境变量改变而改密码）；
   ```bash
   docker compose -f deploy/docker-compose.yml down
   docker volume rm campus-overflow_mysql-data      # 会清库，先确认可丢
   docker compose -f deploy/docker-compose.yml up -d --build
   ```
3. 后端 `DB_HOST=mysql`、`DB_USER=campus` 由 compose 固定，不要在 `.env` 里另设一份（compose 不读，容易被误当成已配置）。

### 6.2 前端刷新路由 404 / 页面白屏

预期行为：`deploy/nginx.conf` 里 `try_files $uri $uri/ /index.html` 负责 SPA fallback，`/api/` 反代到 `backend:8080`。

```bash
docker compose -f deploy/docker-compose.yml exec web \
  cat /etc/nginx/conf.d/default.conf | grep -A3 'location /'
docker compose -f deploy/docker-compose.yml logs --tail=50 web
curl -i http://localhost/api/v1/questions            # 应为后端响应，不是 nginx 404
```

- 404 且配置里没有 `try_files` → 镜像用了旧层，执行 `docker compose -f deploy/docker-compose.yml up -d --build web --no-cache`；
- `/api/` 返回 502 → 后端未 healthy，回到 6.1；
- 首页 200 但接口跨域报错 → 前端应走同源 `/api/...`，不要设 `VITE_API_BASE_URL` 指向 8080。

### 6.3 内存溢出 / OOM（容器被 kill、日志含 OutOfMemoryError）

```bash
docker stats --no-stream co-backend
docker inspect co-backend --format '{{.State.OOMKilled}} / {{.HostConfig.Memory}}'
docker compose -f deploy/docker-compose.yml exec backend \
  wget -qO- http://localhost:8080/actuator/metrics/jvm.memory.used
```

处置：
1. 后端镜像设了 `-XX:MaxRAMPercentage=70`，容器内存上限过低时先调高 compose 的 `mem_limit`（若已设置），不要盲目调 JVM 参数；
2. 在 Grafana「JVM 堆内存使用」看板确认是否持续爬升（疑似泄漏）还是尖峰（流量/大对象）；
3. 需要 heap dump 时，在 `deploy/.env` 里给 `JAVA_OPTS` 加 `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/app/dumps`，并挂一个可写卷，再重启后端；
4. 同时看「DB 连接池」面板：`hikaricp_connections_pending` 长期大于 0 通常是慢 SQL 拖住线程，先查 SQL 而不是加内存。

### 6.4 发件箱死信 / 声誉对账不一致

这两类不是重启能解决的，属于数据问题：

- `co_outbox_dead_letters > 0`：Grafana 面板 6 或 Prometheus `/alerts` 会告警。查 `OutboxRelay` 日志定位根因，修复后人工把对应记录的 `attempts` 清零重放（ADR-002），不要直接删事件。
- `co_reputation_mismatches > 0`：查 `ReputationJobs` 对账日志，按 ADR-004 核对账本流水，修正后重跑对账任务。

### 6.5 监控没有数据

```bash
curl -fsS http://localhost:8080/actuator/prometheus | head      # 后端是否暴露指标
curl -fsS http://localhost:9090/api/v1/targets | head           # 抓取目标状态
docker logs co-prometheus 2>&1 | grep -i 'rules\|error'
```

`application.yml` 的 `management.endpoints.web.exposure.include` 必须包含 `prometheus`；Nginx 只放行 `/actuator/health`，所以从**宿主机 80 端口**访问 `/actuator/prometheus` 必然 404，要用 8080 直连或容器内 `backend:8080`。

## 7. 告警与值班

- 规则定义：`deploy/prometheus/rules.yml`（7 条，覆盖 5xx 错误率、P95、实例存活、发件箱积压/死信、声誉对账、JVM 堆水位）。
- 当前状态：规则由 Prometheus 评估，可在 `http://localhost:9090/alerts` 查看；**推送到企业微信/钉钉群尚未接入**，需另配 Alertmanager + 群机器人 webhook（密钥不入仓库），接入方式见 `deploy/prometheus.yml` 注释。
- 值班人与升级路径：见 `docs/01-团队/` 下的团队章程；本手册不写死个人联系方式。

## 8. 交付物对照

| Issue | 输出物 | 位置 |
| --- | --- | --- |
| #35 | 后端 Dockerfile + .dockerignore | `deploy/backend.Dockerfile`（多阶段 / 非 root / JRE；依赖缓存层未生效已在文件头如实标注）、仓库根 `.dockerignore` |
| #36 | 前端 Dockerfile + nginx.conf | `deploy/frontend.Dockerfile`（补 `package-lock.json` + 改用 `npm ci`，修复构建不可复现）、`deploy/nginx.conf` |
| #37 | docker-compose.yml + .env.example | `deploy/docker-compose.yml`、`.env.example` |
| #38 | 部署工作流 | `.github/workflows/deploy.yml`（同源模板 `deploy/deploy.yml.example`） |
| #39 | dashboard.json + rules.yml | `deploy/grafana/dashboards/campus-overflow.json`、`deploy/prometheus/rules.yml` |
| #40 | 运维手册 | 本文件 |

无 Docker 环境下的验收替代：`node scripts/verify-observability.mjs` 共 **14 项静态校验**，覆盖 #35/#36/#37/#39 的关键约束——前端 lock 与 `package.json` 一致、Dockerfile 多阶段与非 root、Nginx 不暴露整个 `/actuator/`、`rule_files` 路径与挂载一致、监控指标在源码有注册点、compose 变量在 `.env.example` 声明。**它不能替代 `docker compose up` 的运行验证。**

## 9. 自动部署（Issue #38）

工作流已正式落地到 **`.github/workflows/deploy.yml`**，`deploy/deploy.yml.example` 保留为同源模板。此前提交用的 GitHub token 只有 `repo` scope、缺 `workflow` scope，被 GitHub 拒绝推送任何 workflow 文件改动；补授权（`gh auth refresh -s workflow`）后已由本 PR 一并提交，不再是待办。

它是**手动触发**（`workflow_dispatch`），不会随 push 自动上线。课程仓库没有长期在线的部署主机，所以设计成：

- `verify-config` 任务永远可跑，执行 `node scripts/verify-observability.mjs`，不需要任何 Secret；这是 Lab8 在无 Docker 环境下也能拿到的 CI 证据。
- `deploy` 任务先检查必备 Secret，缺任意一个就打印 `::notice::` 并**安全跳过**，不会把 CI 变红，也不会假装部署成功。
- 不依赖第三方 Action，只用 runner 自带的 `ssh` / `scp` / `docker`。

真正部署前，在仓库 Settings → Secrets and variables → Actions 里配置：

| Secret | 含义 |
| --- | --- |
| `DEPLOY_HOST` | 目标主机 IP / 域名 |
| `DEPLOY_USER` | SSH 用户名（需有 docker 组权限） |
| `DEPLOY_SSH_KEY` | 私钥全文（对应公钥已在目标机 `authorized_keys`） |
| `DEPLOY_PATH` | 目标机上的部署根目录，例如 `/opt/campus-overflow` |

目标机上还需预先放好 `deploy/.env`（含数据库密码），**密码绝不从 CI 写入或出现在日志里**。触发时可选填 `image_tag`，留空则用触发时的 commit SHA 拼接镜像 tag。

部署动作：同步 `deploy/` → `docker compose pull` → `up -d --no-build` → 轮询 `/actuator/health` 最多 150 秒。任一步失败即让任务变红，不会静默留下半更新状态。

> 边界说清楚：本仓库 CI 目前只 `docker build` 不 `docker push`，所以远端 registry 里若没有对应 tag，`pull` 会失败（脚本对该步做了容错）。要真正跑通持续部署，还得先在 CI 里补镜像推送与 registry 凭据，那属于 Lab8 之后的范围，本次不虚报已完成。
