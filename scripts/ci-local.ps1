# 本地复现 CI（不依赖 GitHub Actions）
#
# 为什么需要：2026-11-12 起，本仓库（私有）的 Actions 额度耗尽，所有工作流在 4-7 秒内
# 0 步骤失败（见 Issue #120）。额度恢复前，想证明「主干是绿的」只能本地跑；答辩演示时
# 也能用它替代 Actions 截图。
#
# 本脚本按 .github/workflows/ci.yml 的阶段顺序在本地重放，**只跳过需要 Docker 的部分**
# （SonarQube 门禁、Testcontainers 集成测试、镜像构建、compose 全栈）：
#   1)  编译            mvn -f backend/pom.xml clean compile
#   2)  单元测试        mvn -f backend/pom.xml test
#   3)  架构约束        mvn -f backend/pom.xml test -pl co-bootstrap -am -Dtest=ArchitectureTest
#   4)  覆盖率门禁      mvn -f backend/pom.xml verify -DskipITs        （离线模式会跳过，见下）
#   5)  行走骨架归零    扫描 src/main 下的 TODO(Sx) 与 UnsupportedOperationException
#   6)  错误码一致性    node scripts/verify-error-codes.mjs（代码 ↔ 文档双向核对）
#   7)  前端代码规范    npm run lint
#   8)  前端单元测试    npm test（Vitest）
#   9)  前端类型检查    npm run typecheck
#   10) 前端构建        npm run build
#
# 用法：
#   powershell -ExecutionPolicy Bypass -File scripts/ci-local.ps1
#   powershell -ExecutionPolicy Bypass -File scripts/ci-local.ps1 -Offline -SkipFrontend
#
# 关于 -Offline：覆盖率门禁（阶段 4）要走到 verify 阶段，会触发 co-bootstrap 的
# spring-boot:repackage；该插件依赖（buildpack-platform 等）若未进本地 Maven 仓库，
# 离线会直接失败——那是环境问题、不是代码问题，所以离线模式下阶段 4 标 SKIP 并说明原因。
#
# 退出码：0 = 没有失败阶段；1 = 有阶段失败。

param(
    [switch]$Offline,
    [switch]$SkipFrontend,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path -Parent (Split-Path -Parent $PSCommandPath)
$backend = Join-Path $repoRoot 'backend'
$frontend = Join-Path $repoRoot 'frontend'
$logDir = Join-Path $repoRoot 'target\ci-local'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null

if ($JavaHome) { $env:JAVA_HOME = $JavaHome }
$offlineArg = @()
if ($Offline) { $offlineArg = @('-o') }

$results = New-Object System.Collections.ArrayList
function Record([string]$name, [string]$status, [int]$code, [double]$seconds, [string]$note) {
    [void]$results.Add([pscustomobject]@{
        Stage = $name; Status = $status; Code = $code
        Seconds = [math]::Round($seconds, 1); Note = $note
    })
}

function Run-Stage {
    param([string]$Name, [string]$Slug, [scriptblock]$Body, [string]$SkipReason)
    Write-Host ''
    Write-Host "==> $Name" -ForegroundColor Cyan

    if ($SkipReason) {
        Write-Host "    [SKIP] $SkipReason" -ForegroundColor DarkYellow
        Record $Name 'SKIP' 0 0 $SkipReason
        return
    }

    $log = Join-Path $logDir "$Slug.log"
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    & $Body *>&1 | Tee-Object -FilePath $log -Append | Out-Null
    $code = $LASTEXITCODE
    $sw.Stop()

    if ($code -eq 0) {
        Write-Host ("    [ok] {0:N1}s   log: {1}" -f $sw.Elapsed.TotalSeconds, $log) -ForegroundColor Green
        Record $Name 'PASS' 0 $sw.Elapsed.TotalSeconds ''
    } else {
        Write-Host ("    [FAIL] exit {0}   log: {1}" -f $code, $log) -ForegroundColor Red
        Write-Host '    ---- 日志尾部 ----' -ForegroundColor DarkGray
        Get-Content $log -Tail 15 -ErrorAction SilentlyContinue | ForEach-Object { Write-Host "      $_" -ForegroundColor DarkGray }
        Record $Name 'FAIL' $code $sw.Elapsed.TotalSeconds ''
    }
}

Write-Host "repo:    $repoRoot"
Write-Host "java:    $env:JAVA_HOME"
Write-Host "offline: $($Offline.IsPresent)   skipFrontend: $($SkipFrontend.IsPresent)"
Write-Host "logs:    $logDir"

$pom = Join-Path $backend 'pom.xml'

Run-Stage -Name '1/10 编译' -Slug '01-compile' -Body {
    & mvn @offlineArg -B -ntp -f $pom clean compile
}

Run-Stage -Name '2/10 单元测试（全后端）' -Slug '02-test' -Body {
    & mvn @offlineArg -B -ntp -f $pom test
}

Run-Stage -Name '3/10 架构约束（ArchUnit）' -Slug '03-archunit' -Body {
    & mvn @offlineArg -B -ntp -f $pom test -pl co-bootstrap -am '-Dtest=ArchitectureTest' `
        '-DfailIfNoTests=false' '-Dsurefire.failIfNoSpecifiedTests=false'
}

$coverageSkip = ''
if ($Offline) {
    $coverageSkip = '离线模式跳过：verify 阶段会触发 co-bootstrap 的 spring-boot:repackage，其插件依赖未进本地仓库；联网跑一次或缓存后即可执行'
}
Run-Stage -Name '4/10 覆盖率门禁（-DskipITs）' -Slug '04-coverage' -SkipReason $coverageSkip -Body {
    & mvn @offlineArg -B -ntp -f $pom verify '-DskipITs'
}

Write-Host ''
Write-Host '==> 5/10 行走骨架归零检查' -ForegroundColor Cyan
$sw = [System.Diagnostics.Stopwatch]::StartNew()
$hits = Get-ChildItem -LiteralPath $backend -Recurse -Filter *.java -File |
    Where-Object { $_.FullName -match '[\\/]src[\\/]main[\\/]' } |
    Select-String -Pattern 'TODO\(S[0-9]+\)|UnsupportedOperationException' |
    Select-Object -First 50
$sw.Stop()
if ($hits) {
    Write-Host '    main 代码里检测到行走骨架残留（§6.5 要求为 0）：' -ForegroundColor Red
    $hits | Select-Object -First 20 | ForEach-Object { Write-Host ("      {0}:{1}: {2}" -f $_.Filename, $_.LineNumber, $_.Line.Trim()) }
    Record '5/10 行走骨架归零' 'FAIL' 1 $sw.Elapsed.TotalSeconds ''
} else {
    Write-Host '    [ok] main 代码中 TODO(Sx) 与 UnsupportedOperationException 均为 0' -ForegroundColor Green
    Record '5/10 行走骨架归零' 'PASS' 0 $sw.Elapsed.TotalSeconds ''
}

Write-Host ''
Write-Host '==> 6/10 错误码一致性检查（代码 ↔ 文档）' -ForegroundColor Cyan
$sw = [System.Diagnostics.Stopwatch]::StartNew()
Push-Location $repoRoot
& node scripts/verify-error-codes.mjs
$c = $LASTEXITCODE
Pop-Location
$sw.Stop()
if ($c -eq 0) {
    Write-Host '    [ok] 文档里出现的错误码都能在 src/main 中找到' -ForegroundColor Green
    Record '6/10 错误码一致性' 'PASS' 0 $sw.Elapsed.TotalSeconds ''
} else {
    Write-Host '    文档里出现了代码中不存在的错误码（过期或错别字）' -ForegroundColor Red
    Record '6/10 错误码一致性' 'FAIL' $c $sw.Elapsed.TotalSeconds '见上方输出'
}

$frontendSkip = ''
if ($SkipFrontend) { $frontendSkip = '按参数 -SkipFrontend 跳过' }

# 前端构建链（vite/esbuild）会在 %TEMP% 下解包并清理临时目录；若该目录权限受限，
# esbuild 会报 "remove ...\Temp\esbuild-xxx: Access is denied"。把 TEMP/TMP 指到仓库内即可绕开。
$tmpDir = Join-Path $logDir 'tmp'
New-Item -ItemType Directory -Force -Path $tmpDir | Out-Null
$savedTemp = @{ TEMP = $env:TEMP; TMP = $env:TMP }

Run-Stage -Name '7/10 前端代码规范（ESLint）' -Slug '07-lint' -SkipReason $frontendSkip -Body {
    $env:TEMP = $tmpDir
    $env:TMP = $tmpDir
    Push-Location $frontend
    if (-not (Test-Path 'node_modules')) { & npm install --no-audit --no-fund }
    & npm run lint
    $c = $LASTEXITCODE
    Pop-Location
    $env:TEMP = $savedTemp.TEMP
    $env:TMP = $savedTemp.TMP
    $global:LASTEXITCODE = $c
}

Run-Stage -Name '8/10 前端单元测试（Vitest）' -Slug '08-unit' -SkipReason $frontendSkip -Body {
    $env:TEMP = $tmpDir
    $env:TMP = $tmpDir
    Push-Location $frontend
    if (-not (Test-Path 'node_modules')) { & npm install --no-audit --no-fund }
    & npm test
    $c = $LASTEXITCODE
    Pop-Location
    $env:TEMP = $savedTemp.TEMP
    $env:TMP = $savedTemp.TMP
    $global:LASTEXITCODE = $c
}

Run-Stage -Name '9/10 前端类型检查' -Slug '09-typecheck' -SkipReason $frontendSkip -Body {
    $env:TEMP = $tmpDir
    $env:TMP = $tmpDir
    Push-Location $frontend
    if (-not (Test-Path 'node_modules')) { & npm install --no-audit --no-fund }
    & npm run typecheck
    $c = $LASTEXITCODE
    Pop-Location
    $env:TEMP = $savedTemp.TEMP
    $env:TMP = $savedTemp.TMP
    $global:LASTEXITCODE = $c
}

Run-Stage -Name '10/10 前端构建' -Slug '10-build' -SkipReason $frontendSkip -Body {
    $env:TEMP = $tmpDir
    $env:TMP = $tmpDir
    Push-Location $frontend
    & npm run build
    $c = $LASTEXITCODE
    Pop-Location
    $env:TEMP = $savedTemp.TEMP
    $env:TMP = $savedTemp.TMP
    $global:LASTEXITCODE = $c
}

Write-Host ''
Write-Host '================ 汇总 ================' -ForegroundColor Cyan
$results | ForEach-Object {
    $color = switch ($_.Status) { 'PASS' { 'Green' } 'SKIP' { 'DarkYellow' } default { 'Red' } }
    Write-Host ("  {0,-4} {1,-30} {2,7:N1}s  {3}" -f $_.Status, $_.Stage, $_.Seconds, $_.Note) -ForegroundColor $color
}
$failed = @($results | Where-Object { $_.Status -eq 'FAIL' })
$skipped = @($results | Where-Object { $_.Status -eq 'SKIP' })
Write-Host ''
if ($failed.Count -gt 0) {
    Write-Host ("失败阶段：" + (($failed | ForEach-Object { $_.Stage }) -join '、')) -ForegroundColor Red
    exit 1
}
if ($skipped.Count -gt 0) {
    Write-Host ("通过（{0} 项跳过）。" -f $skipped.Count) -ForegroundColor Yellow
} else {
    Write-Host '全部通过。' -ForegroundColor Green
}
exit 0
