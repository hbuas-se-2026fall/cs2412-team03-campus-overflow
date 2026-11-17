# Windows 开发环境踩坑集

> 这一页只记**真实踩过、并且已经写进代码或脚本**的坑。
> 每条都写清「现象 → 根因 → 做法」，方便下一个人五分钟解决而不是半小时。

## 1. PowerShell 5.1 把无 BOM 的 UTF-8 当 ANSI，中文注释直接解析崩

- **现象**：脚本报一堆 `Missing closing ')'`、`字符串缺少终止符`，行号却指向完全正常的行。
- **根因**：Windows PowerShell 5.1 用系统 ANSI 代码页（中文机器是 GBK）读 `.ps1`，文件若是无 BOM 的 UTF-8，中文会被拆成乱码字节。
- **做法**：所有 `.ps1` 存成 **UTF-8 with BOM**。`scripts/ci-local.ps1` 就是带 BOM 的，改它的时候别让编辑器把 BOM 去掉。

## 2. Java 源码带 BOM 导致 `javac` 报「非法字符: '\ufeff'」

- **现象**：`javac` 报 `非法字符: '\ufeff'` 或 `需要 class、interface 或 enum`。
- **根因**：PowerShell 的 `Set-Content -Encoding utf8`（在 5.1 下）**会加 BOM**，而 `javac` 不认 BOM。
- **做法**：`.java` 一律用 UTF-8 **无 BOM** 写入：

```powershell
[System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))
```

## 3. Maven 多模块单独跑测试报「找不到 artifact」

- **现象**：`mvn -pl co-qa/co-qa-application test` 报 `Could not find artifact co-identity-api:jar:0.1.0-SNAPSHOT`。
- **根因**：依赖模块没装进本地仓库，单独构建时 Maven 不会自动先构建它们。
- **做法**：加 `-am`（also make）：`mvn -o -B -ntp -pl co-qa/co-qa-application -am test`。

## 4. `class file version 65.0` 但 JDK 只到 61.0

- **现象**：Surefire 报 `has been compiled by a more recent version of the Java Runtime (class file version 65.0), this version only recognizes up to 61.0`。
- **根因**：机器上同时有 JDK 17 与 JDK 21，`JAVA_HOME` 指向 17。
- **做法**：`JAVA_HOME` 指向 JDK 21，并把 `%JAVA_HOME%\\bin` 放到 `PATH` 最前。

## 5. 传给 Maven 的 `-D` 参数被 PowerShell 拆开

- **现象**：`mvn test -Dtest=ArchitectureTest -Dsurefire.failIfNoSpecifiedTests=false` 报 `Unknown lifecycle phase '.failIfNoSpecifiedTests=false'`。
- **根因**：PowerShell 把 `-Dsurefire.failIfNoSpecifiedTests=false` 拆成两半。
- **做法**：每个 `-D` 参数单独加引号：`'-Dtest=ArchitectureTest' '-Dsurefire.failIfNoSpecifiedTests=false'`。

## 6. `vite build` 报 `esbuild ... Access is denied`

- **现象**：前端构建在解包 esbuild 时失败，报 `remove ...\\Temp\\esbuild-xxx: Access is denied`。
- **根因**：esbuild 在 `%TEMP%` 下解包并清理临时目录，某些安全策略下该目录权限受限。
- **做法**：把 `TEMP`/`TMP` 指到仓库内的 `target/ci-local/tmp`（`ci-local.ps1` 已经这么做）。

## 7. Inno Setup 安装包报 `Error 5: 拒绝访问`（`%TEMP%\\is-xxx.tmp`）

- **现象**：装某个应用时报 `Setup was unable to create the directory "%TEMP%\\is-xxxx.tmp" — Error 5`，
  但手工在 `%TEMP%` 里建文件却是成功的。
- **根因**：安装器提权后的进程与沙箱临时目录的 ACL 不一致，与磁盘空间、普通读写权限无关。
- **做法**：优先找官方提供的**免安装版 / portable zip**，解压即用，完全不碰 `%TEMP%` 与注册表。

## 8. GitHub 大响应经管道读会被截断成半截 JSON

- **现象**：`JSON.parse` 报 `Expected ',' or '}' after property value in JSON at position ~370000`。
- **根因**：Windows 上把子进程 stdout 用管道抓取时，超大输出会在几百 KB 处被截断。
- **做法**：让 `curl` 用 `-o` 落到文件，再读文件解析，不要走 stdout。同时对 `/commits`、`/actions/runs` 这类大响应单独放宽超时。

## 9. `git fetch` 在隧道代理下直接崩掉 PowerShell 进程

- **现象**：`git ls-remote` 报 `SSL certificate ... unable to get local issuer certificate`；加了 `-c http.sslVerify=false` 后子进程以 `0xC0000409` 退出。
- **根因**：当前网络下 `github.com` 走本地隧道代理，schannel 与代理握手不稳定。
- **做法**：与远程仓库的读写改用 GitHub REST API（`--resolve api.github.com:443:<ip>`），
  本地 `git` 只用于查看历史；要同步工作副本时用 `同步本地仓库.cmd`（内部用 `--resolve` 直连）。

## 10. 端口版 Electron/Flutter 应用启动后窗口在托盘里

- **现象**：程序进程在，但 `MainWindowHandle = 0`，找不到窗口。
- **根因**：托盘类应用启动后主动隐藏主窗口，只留系统托盘图标。
- **做法**：用 `EnumWindows` 按 PID 枚举**包括隐藏窗口**（`IsWindowVisible = false` 也能拿到），
  再用托盘图标唤出；不要直接杀进程重启（会起第二个实例）。
