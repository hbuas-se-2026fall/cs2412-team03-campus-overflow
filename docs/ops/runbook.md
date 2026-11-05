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

### 6.6 web 容器起不来：`host not found in upstream "backend"`

这个报错是 CI 里真跑 `nginx -t` 时抓到的，不是配置语法错。`deploy/nginx.conf` 用 `proxy_pass http://backend:8080`，而 `backend` 是 **compose 服务名**；nginx 在配置检查 / 启动阶段会解析这个静态主机名，只要当时 DNS 里没有 `backend` 就直接 `[emerg]` 退出。

- 在 compose 网络内正常：`web` 已配置 `depends_on: backend: condition: service_healthy`，backend 先就绪，DNS 可解析。
- 单独 `docker run` 校验配置时会失败。CI 里的做法是加 `--add-host backend:127.0.0.1` 让语法检查跑完，**不改生产配置**。
- 若生产环境仍偶发（例如 backend 容器换了 IP、nginx 缓存了旧解析），根治方式是改用 Docker 内嵌 DNS + 变量形式：`resolver 127.0.0.11 valid=30s;` 配合 `set $backend backend:8080;` 与 `proxy_pass http://$backend`，让上游在请求时动态解析。这属于后续优化，当前未改。

### 6.7 backend 容器启动即退出：`Unable to access jarfile app.jar`

首次在 CI runner 上真跑 `docker compose up` 时抓到的真实缺陷，**PR 级 `docker build` 检查没发现它**：构建阶段 `docker build` 成功，容器一启动就 `co-backend exited (1)`。

根因：`co-bootstrap/pom.xml` 声明了 `<finalName>campus-overflow</finalName>`，Spring Boot repackage 后的 fat jar 是 `campus-overflow.jar`；旧 `backend.Dockerfile` 却写 `COPY --from=build /src/co-bootstrap/target/co-bootstrap-*.jar app.jar`。通配符一个都没匹配上，`app.jar` 压根不存在，而 Docker 的 `COPY` 通配符无匹配时并不会让构建失败。

之前的镜像冒烟用 `docker run --entrypoint sh ... -c 'java -version'` 覆盖了 ENTRYPOINT，只验证了 JRE 存在，**从没真正执行过 `java -jar app.jar`**，所以「构建成功 + 冒烟通过」是假绿。

修复与防回归：

- 构建阶段显式 `cp target/campus-overflow.jar /app.jar` 并 `test -f /app.jar`，jar 名再变化会当场让镜像构建红，而不是静默产出坏镜像。
- `deploy/backend.Dockerfile` 的 `COPY --from=build /app.jar app.jar` 用固定路径，不再依赖通配符。
- `publish-images.yml` 的 PR 冒烟增加 `test -s /app/app.jar`；`scripts/verify-observability.mjs` 增加一条规则，从 `pom.xml` 读 `finalName` 反查 Dockerfile，防止两侧再次漂移。
- 教训：验证镜像必须包含「按真实 ENTRYPOINT 启动」或至少检查启动产物存在，只跑 `--entrypoint sh` 里的零散命令不足以证明镜像可运行。

### 6.8 `/actuator/prometheus` 从宿主机 80 端口返回 200

全栈联调第一次绿的时候，CI 仍打印了一条 `::warning::预期 404，实际 200，请复核 Nginx 暴露边界`。核查后结论是**指标没有真的泄露**：`location /` 的 `try_files ... /index.html` 把找不到的 `/actuator/prometheus` 回退成了 418 字节的 `index.html`，请求从未到达 backend；容器内 `backend:8080/actuator/prometheus` 才返回真实指标文本，Prometheus 抓取路径正常。

问题在于边界不清晰：一个本应被拒的路径返回 200，容易被误判成「actuator 已暴露」，也不利于安全核查。修复：

- `deploy/nginx.conf` 增加 `location ^~ /actuator/ { return 404; }`。Nginx 中 `=` 精确匹配优先于 `^~` 前缀匹配，所以 `location = /actuator/health` 仍正常放行。
- `compose-smoke.yml` 把该检查从 `::warning::` 升级为**硬断言**，并追加响应体检测（不得含 `# HELP` / `# TYPE` / `*_seconds{` 等 Prometheus 文本特征），双保险防止将来有人把 actuator 全量代理出去。
- `verify-observability.mjs` 同步要求该兜底块存在，防止配置回退。

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

触发方式有三种，各自承担不同职责：

| 触发 | 用途 |
| --- | --- |
| `workflow_run`：上游 `Publish Images (Lab8)` 在 `develop` / `main` **成功完成** | 「push 到 develop 自动部署」。用 `workflow_run.head_sha` 作为镜像 tag，与发布侧的 `GITHUB_SHA` 严格对齐；上游失败或仍在构建时不会抢跑，避免 pull 一个还不存在的 tag |
| `workflow_dispatch` | 人工指定 `image_tag` 补发/回滚某个版本 |
| push 到 `verify/**` | 仅用于验收阶段产生一次真实 run 记录：本 workflow 从未进过默认分支时，GitHub 不会注册它，连手动触发入口都找不到。合并进默认分支后该条件不再匹配，不会误触发生产部署 |

课程仓库没有长期在线的部署主机，所以设计成：

- `verify-config` 任务永远可跑，执行 `node scripts/verify-observability.mjs`，不需要任何 Secret；这是 Lab8 在无 Docker 环境下也能拿到的 CI 证据。`workflow_run` 下显式 `checkout` 到上游 `head_sha`，避免默认检出到默认分支 HEAD 导致配置版本错位。
- `deploy` 任务先检查必备 Secret，缺任意一个就打印 `::notice::` 并**安全跳过**，不会把 CI 变红，也不会假装部署成功；上游发布失败时整个 job 不执行。
- 不依赖第三方 Action，只用 runner 自带的 `ssh` / `scp` / `docker`。

真正部署前，在仓库 Settings → Secrets and variables → Actions 里配置：

| Secret | 含义 |
| --- | --- |
| `DEPLOY_HOST` | 目标主机 IP / 域名 |
| `DEPLOY_USER` | SSH 用户名（需有 docker 组权限） |
| `DEPLOY_SSH_KEY` | 私钥全文（对应公钥已在目标机 `authorized_keys`） |
| `DEPLOY_PATH` | 目标机上的部署根目录，例如 `/opt/campus-overflow` |

目标机上还需预先放好 `deploy/.env`（含数据库密码），**密码绝不从 CI 写入或出现在日志里**。触发时可选填 `image_tag`，留空则用触发时的 commit SHA 拼接镜像 tag。

部署动作：runner 侧校验镜像 → 同步 `deploy/` → `docker compose pull` → `up -d --no-build` → 轮询 `/actuator/health` 最多 150 秒。任一步失败即让任务变红，不会静默留下半更新状态。

### 镜像从哪来（原断链已补上）

旧版本有个实质缺陷：`deploy.yml` 执行 `docker compose pull`，但仓库里没有任何地方把镜像推到 registry，远端必然拉不到，脚本还用 `|| true` 把失败吞了，结果是「部署成功」但实际跑着旧版本。现由 `.github/workflows/publish-images.yml` 补上：

| 事件 | 动作 |
| --- | --- |
| PR 到 `develop` / `main` | 只 `docker build` 两个 Dockerfile，**不推送**——这是 #35 / #36 在带 Docker 的 runner 上的实机构建证据 |
| push 到 `develop` / `main` | 构建并推送到 GHCR，tag 为完整 commit SHA |
| `workflow_dispatch` | 可指定 tag 手工补发某个版本 |

用仓库自带的 `GITHUB_TOKEN` + `packages: write`，**不新增长期凭据**；镜像名 `ghcr.io/<owner>/<repo>-backend:<tag>` / `-web`，与 `.env.example`、`deploy.yml` 完全一致，`scripts/verify-observability.mjs` 会强制检查两侧命名规则一致、PR 不推送、部署侧不再吞 `pull` 失败。

部署前 `deploy.yml` 先在 runner 侧 `docker login ghcr.io` + `docker pull` 两个镜像，**拉不到就直接让任务变红**，不再带着不存在的 tag 去目标机。

> **仍需注意的主机侧准备**：GHCR 包默认继承仓库可见性（私有）。目标主机要执行 `docker compose pull`，必须能读该包——要么在目标机用有 `read:packages` 权限的 PAT 预先 `docker login ghcr.io`，要么把包设为 public 并绑定仓库。**CI 不会替目标机代填这个凭据，密码也不入库。**
>
> **仍未闭环的部分（如实标注）**：
> 1. `workflow_run` 只在**默认分支**上的工作流定义生效。本 workflow 目前仍只存在于 PR 分支与 `verify/**`，所以在 #73 合并进 `develop` 之前，「push develop 自动部署」只是代码就绪、没有生产 run 证据；合并后由 SM 配好 Secrets 才能真正上线。
> 2. 手动 `workflow_dispatch` 指定 `image_tag` 补发时，自动部署侧按 `head_sha` 取 tag，两者不一致会让 `docker pull` 直接变红（设计如此，不静默回退）。补发请用 deploy 自己的 `workflow_dispatch`。
> 3. 本机仍没有 Docker；但 **GitHub Actions 的 ubuntu runner 自带 Docker**，`compose-smoke.yml` 已在 `verify/full-stack` 集成分支真跑过全栈 `docker compose up`（详见 6.7 与 CI 日志）。这不等于有长期在线的生产主机，`deploy.yml` 的 SSH 真机部署路径仍未实跑。
> 4. #35 的「镜像 <200MB」此前未达标：早期实测 209MB，**develop 合入 #72/#73/#76 后复测为 275MB**（`docker image inspect` 换算；`docker image ls` 十进制 288MB），其中 fat jar 76MB、完整 JRE 约 200MB。CI 用 `::warning::` 主动标注，不伪装达标。jlink 定制 JRE 的修复见第 10 节。

---

## 10. jlink 定制 JRE（Issue #35，镜像体积）

### 10.1 为什么必须换

原运行时 `eclipse-temurin:21-jre-alpine` 带完整 JRE（约 200MiB），加 75.4MiB 的 Spring Boot fat jar，实测 275MiB，远超 #35 的 `<200MB`。删几层缓存没用，只有换掉 JRE 本体。

### 10.2 三阶段结构

| 阶段 | 镜像 | 作用 |
| --- | --- | --- |
| `build` | `maven:3.9-eclipse-temurin-21` | 编译 fat jar，`finalName` 硬校验后 cp 到 `/app.jar` |
| `jre-build` | `eclipse-temurin:21-jdk-alpine` | `jdeps` 分析 + `jlink` 生成 `/minijre` |
| 运行时 | `alpine:3.20` | 只放 `/opt/jre` + `/app/app.jar`，非 root |

### 10.3 两个不能踩的坑

**musl 同源**：jlink 阶段与运行时必须都是 musl。若从 glibc 的 JDK 生成 JRE 再放进 alpine，`java` 会因缺 glibc 动态库启动即崩。镜像 build 会成功、`java -version` 冒烟也可能过，直到 `docker compose up` 才暴露 —— 与 6.6 的 jar 名假绿是同一类问题。

**反射兜底**：`jdeps --print-module-deps` 只看静态字节码，看不到 Spring / Hibernate / JDBC / TLS 通过 `Class.forName`、ServiceLoader 加载的类。本机对 131 个依赖 jar 只分析出 14 个模块，必须显式补齐 25 个（`java.sql` / `java.naming` / `java.management` / `java.logging` / `jdk.crypto.ec` / `jdk.unsupported` 等），缺一个就是运行时 `NoClassDefFoundError`。清单在 Dockerfile 的 `EXTRA`，并做去重与 `MODS` 为空时的边界处理。

### 10.4 门禁同步收紧

- `.github/workflows/publish-images.yml`：体积检查从 `::warning::` 升级为**硬断言**，≥200MB 直接 `exit 1`。达标之后不留「超了也只警告」的口子。
- `scripts/verify-observability.mjs`：原规则「运行时镜像名含 jre」在 jlink 后不成立，改为更强的约束 —— 运行时不得是 JDK/Maven 镜像、必须有 `jlink --add-modules`、必须 COPY `/minijre`、必须有 `test -x /minijre/bin/java`、反射兜底模块缺一即失败。

### 10.5 验证方式（本机无 Docker）

`verify/**` 分支推送会触发 `compose-smoke.yml`，在 runner 上真实 `docker compose up -d --build`，逐项验 readiness、SPA fallback、`/api` 反代、actuator 边界、Prometheus 抓取与 Grafana datasource；PR 上的 `publish-images` 冒烟负责实测体积、非 root、`java -version`、jar 存在性。

**只信 runner 结论**。`docker build` 成功不等于应用能启动，静态校验 18 项通过也不等于运行时模块齐备。

### 10.6 实测结果（2026-11-05，GitHub Actions runner）

| 项目 | 修复前 | 修复后 |
| --- | ---: | ---: |
| 后端镜像（`docker image inspect`，MiB） | **275MB** | **150MB** ✅ |
| 后端镜像（`docker image ls`，十进制） | 288MB | 157MB |
| 运行时 JRE | 完整 `21-jre-alpine` 约 200MB | jlink 定制 27 模块约 52MB |
| fat jar | 75.4MiB | 75.4MiB（不变） |

证据：
- `publish-images` run **`42027743402`** — `构建后端镜像` SUCCESS、`PR 仅校验构建，不推送` SUCCESS，日志明确输出 `后端镜像实测 150MB（门槛 <200MB）` / `✅ 满足 #35 的 <200MB`；非 root `uid=100`、`java -version`、`/app/app.jar` 存在性校验均通过；jlink 阶段输出 `27` 个模块。
- `compose-smoke` run **`42027654857`** — `docker compose up -d --build` 后 **readiness / 首页 / SPA fallback / 健康检查与 actuator 边界 / `/api` 反代 / Prometheus 抓取 / Grafana datasource 全部 SUCCESS**。这是关键：证明定制 JRE 不是「能 build 但起不来」，Spring Boot 真实启动了，反射兜底模块清单够用。

`jdeps` 静态只分析出 14 个模块（缺 `java.sql` / `java.logging` / `java.naming` / `java.management` / `jdk.crypto.ec`），兜底清单补到 27 个后启动正常，验证了 10.3 的判断。

> 口径说明：#35 写的是「<200MB」，CI 用 `docker image inspect .Size / 1024 / 1024` 的 MiB 口径判断（150MB）。即使按更宽松的 `docker image ls` 十进制 157MB，同样达标，不存在挑口径抬数字。
