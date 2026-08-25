# JMeter 性能验证指南

## 1. 目的与边界

本目录只保留与当前接口和当前拓扑匹配的 JMeter 资产：

- `jmeter-test-plan.jmx`：只读基线，覆盖动态详情、Feed 和通知列表。
- `jmeter-write-safety-test.jmx`：写入安全测试，覆盖发布、点赞和评论；默认不会执行写请求。
- `run-jmeter.ps1`：统一参数、输出目录和凭证门禁。
- `analyze-jmeter-results.ps1`：从 JTL 原始结果计算 Samples、Errors、Error Rate、Throughput、Average、P50、P95 和 P99。

JMeter 的静态解析、计划加载 smoke 和脚本执行成功，只能证明压测资产可运行，不能证明接口性能。只有在应用、数据集、JWT 和依赖组件均满足条件后，才可以把 JTL/HTML 报告中的指标写入 `PERFORMANCE_TEST_REPORT.md`。

## 2. 当前验证拓扑

| 组件 | 位置 | 本项目使用方式 |
|---|---|---|
| MySQL | Win11 本机 `127.0.0.1:3306` | 应用业务库和 Flyway 迁移目标 |
| Redis | Win11 本机 `127.0.0.1:6379` | `redis.mode=single` 的缓存、幂等和锁依赖 |
| RabbitMQ | 云服务器 | 通过 `RABBITMQ_HOST`、账号、密码和 vhost 注入 |
| Milvus | 外部 `newagent` 系统 | 当前仓库没有客户端依赖或业务调用，不纳入本压测 |
| JMeter | Win11 本机 | 产生 HTTP 并发流量和原始 JTL 证据 |

压测前必须确认应用实际使用的配置与上述拓扑一致。不要把 Compose 内部的 `mysql`、`redis` 或 `rabbitmq-1` 主机名直接用于 Win11 本机混合拓扑。

## 3. 前置条件

1. 安装 Apache JMeter 5.6.3，并设置 `JMETER_HOME`；如果不设置，也必须确认 `jmeter.bat` 在 `PATH` 中。
2. 启动应用并确认 `http://127.0.0.1:8080/actuator/health` 或业务入口可访问。
3. 准备隔离测试数据：一个可读的 `DYNAMIC_ID`，以及拥有该数据访问权限的临时 JWT。
4. 确认 MySQL/Redis 已初始化，RabbitMQ 凭证通过环境变量注入；不要把凭证写入仓库。
5. 只读场景可以在共享测试数据上执行；写入场景必须使用隔离用户、隔离动态和可清理的测试数据。

JWT 只从当前 PowerShell 会话的 `JWT_TOKEN` 环境变量读取。不要把真实 JWT 放到 JMX、`.md`、`.env`、命令行历史或 JTL 中。

## 4. 只读基线

### 4.1 覆盖接口

| 线程组 | 接口 | 默认线程数 | 请求体/参数 |
|---|---|---:|---|
| Dynamic Detail API | `GET /api/dynamic/detail/{id}` | 50 | `DYNAMIC_ID` |
| Feed API | `POST /api/dynamic/feed` | 30 | `FEED_CURSOR`、`PAGE_LIMIT` |
| Notification List API | `POST /api/notification/list` | 20 | `NOTIFICATION_CURSOR`、`PAGE_LIMIT` |

三个线程组都使用持续时间模型：`RAMP_UP_SECONDS` 负责爬坡，`DURATION_SECONDS` 负责稳定运行，不使用固定循环次数代替持续压测。

### 4.2 推荐执行命令

```powershell
$secureToken = Read-Host "Enter temporary JWT token" -AsSecureString
$env:JWT_TOKEN = [System.Net.NetworkCredential]::new('', $secureToken).Password

try {
  powershell -NoProfile -ExecutionPolicy Bypass `
    -File scripts/run-jmeter.ps1 `
    -Scenario read `
    -BaseUrl 127.0.0.1 `
    -Port 8080 `
    -DynamicId 12345 `
    -FeedCursor 999999 `
    -NotificationCursor 999999 `
    -PageLimit 20 `
    -DurationSeconds 60 `
    -RampUpSeconds 10
} finally {
  Remove-Item Env:JWT_TOKEN -ErrorAction SilentlyContinue
}
```

`12345` 和 `999999` 只是示例值，必须替换成当前测试库中存在且有权限访问的 ID/游标。脚本会先检查应用端口，再执行 JMeter，结果默认输出到 `target/jmeter-results/read-<timestamp>/`。

## 5. 写入安全测试

写入计划会产生 MySQL 数据和 RabbitMQ Outbox 事件，不能和只读基线混跑。默认情况下没有显式确认时，计划中的请求不会执行；PowerShell runner 还会在启动前拒绝未确认的命令。

```powershell
$secureToken = Read-Host "Enter temporary JWT token" -AsSecureString
$env:JWT_TOKEN = [System.Net.NetworkCredential]::new('', $secureToken).Password

try {
  powershell -NoProfile -ExecutionPolicy Bypass `
    -File scripts/run-jmeter.ps1 `
    -Scenario write `
    -ConfirmWrite `
    -BaseUrl 127.0.0.1 `
    -Port 8080 `
    -DynamicId 12345 `
    -DurationSeconds 30 `
    -RampUpSeconds 10
} finally {
  Remove-Item Env:JWT_TOKEN -ErrorAction SilentlyContinue
}
```

写入场景的验收重点不是单纯吞吐，而是：幂等键不重复执行副作用、数据库唯一约束仍然生效、限流/锁冲突可观测、Outbox 事件可追踪且没有孤儿消息。测试后必须清理动态、评论、点赞和 Outbox 数据，并检查 RabbitMQ 测试队列或死信队列。

## 6. 结果分析与证据记录

runner 会生成：

- `results.jtl`：原始样本，作为唯一的延迟和错误率证据。
- `html-report/`：JMeter HTML 报告，用于人工查看趋势。
- `summary.json`：按 sampler label 聚合的统计结果。

也可以单独分析已有 JTL：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/analyze-jmeter-results.ps1 `
  -JtlPath target/jmeter-results/read-<timestamp>/results.jtl `
  -OutputPath target/jmeter-results/read-<timestamp>/summary.json
```

写入 `PERFORMANCE_TEST_REPORT.md` 前，至少记录：

- Git revision、应用配置摘要和测试数据规模；
- JMeter 版本、线程数、爬坡时间、稳定运行时间和接口权重；
- Samples、Errors、Error Rate、Throughput、Average、P50、P95、P99；
- MySQL QPS/连接池、Redis 命中率和延迟、RabbitMQ ready/unacked/dead-letter 数量；
- 测试期间的 CPU、堆、GC、线程池、慢查询和熔断器状态。

没有 `results.jtl` 和对应环境记录时，不能填写“QPS 2000+”“P99 < 100ms”“性能提升显著”等结论。优化前后对比必须使用同一数据集、同一并发模型和可解释的缓存预热策略。

## 7. 计划加载 smoke

计划加载 smoke 只验证 XML、变量默认值和线程组能被 JMeter 解析，不验证应用性能。可以将请求发往本机未占用端口，避免误写业务数据：

```powershell
$env:JWT_TOKEN = 'smoke-token'
$repoRoot = (Get-Location).Path
$smokeDir = [IO.Path]::GetFullPath((Join-Path $repoRoot 'target/jmeter-smoke'))
$readPlan = Join-Path $repoRoot 'scripts/jmeter-test-plan.jmx'
$writePlan = Join-Path $repoRoot 'scripts/jmeter-write-safety-test.jmx'
New-Item -ItemType Directory -Force -Path $smokeDir | Out-Null
$jmeterCommand = Get-Command jmeter.bat -ErrorAction Stop
$jmeterBin = Split-Path $jmeterCommand.Source -Parent
$jmeterJar = Join-Path $jmeterBin 'ApacheJMeter.jar'
if (-not (Test-Path -LiteralPath $jmeterJar -PathType Leaf)) {
  throw "ApacheJMeter.jar was not found: $jmeterJar"
}

Push-Location $jmeterBin
try {
  & java.exe -jar $jmeterJar -n `
    -t $readPlan `
    -l (Join-Path $smokeDir 'read.jtl') `
    '-JBASE_URL=127.0.0.1' `
    '-JPORT=1' `
    '-JDETAIL_THREADS=1' `
    '-JFEED_THREADS=1' `
    '-JNOTIFICATION_THREADS=1' `
    '-JRAMP_UP_SECONDS=1' `
    '-JDURATION_SECONDS=1'

  & java.exe -jar $jmeterJar -n `
    -t $writePlan `
    -l (Join-Path $smokeDir 'write.jtl') `
    '-JBASE_URL=127.0.0.1' `
    '-JPORT=1' `
    '-JPUBLISH_THREADS=0' `
    '-JLIKE_THREADS=0' `
    '-JCOMMENT_THREADS=0' `
    '-JRAMP_UP_SECONDS=1' `
    '-JDURATION_SECONDS=1'
} finally {
  Pop-Location
  Remove-Item Env:JWT_TOKEN -ErrorAction SilentlyContinue
}
```

该 smoke 预期可能产生连接错误；这不代表业务失败，也不应被记录成性能结果。真实压测必须使用 `run-jmeter.ps1`，由应用可达性检查和写入确认门禁保护。

## 8. EXPLAIN 与应用指标配套

- MySQL 查询计划使用 `scripts/explain-analysis.sql`，数据规模必须与压测报告一起记录。
- Feed 需要同时观察两阶段查询的 `rows examined`、temporary/filesort 和回表数量。
- `/actuator/metrics`、`/actuator/prometheus` 用于应用指标；缓存专用指标使用 `/api/metrics/cache`。
- JMeter 延迟不能替代数据库、Redis、RabbitMQ 和 JVM 指标；出现 P99 抖动时必须按请求 ID、数据库慢查询和队列堆积定位原因。

## 9. 安全与清理

- 不在命令行传递真实 JWT、RabbitMQ 密码或 MySQL 密码。
- 写入测试仅针对隔离数据，并在测试后清理业务数据、Outbox 记录和临时 RabbitMQ 拓扑。
- JTL 默认不保存响应体，但仍可能包含 URL、线程名和错误信息；不要把包含用户数据的 JTL 上传到公共位置。
- 压测结束后确认应用、数据库、Redis 和 RabbitMQ 没有持续增长的连接、队列或临时键。
