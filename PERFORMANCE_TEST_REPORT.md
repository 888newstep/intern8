# 性能压测报告

> 状态：2026-08-24 已完成本机混合拓扑只读压测与写入安全测试；结论只适用于下述数据规模与并发模型。

## 0. 最新结论

- 参数：总并发 20（动态详情 10、Feed 6、通知 4），爬坡 5 秒，持续 30 秒。
- 数据：随机隔离 schema，1,000 条关注、5,000 条动态、1,000 条通知；用户 1 关注全部 1,000 名动态作者。
- 结果：39,095 次 HTTP 请求，错误 0；整体吞吐约 1,301.4 RPS。
- Feed：331.673 RPS，P95 24 ms，P99 32 ms。相同规模上一轮为 140.757 RPS、P95 143 ms、P99 242 ms。
- MySQL：生产 `EXISTS` SQL 自适应选择动态倒序计划，`EXPLAIN ANALYZE` 实际扫描 20 条动态并执行 20 次关注唯一索引查询，约 0.34 ms。
- 缓存：`cache.db.load=1`、`cache.lock.contention=1`、`cache.hit.local=21233`、`cache.hit.redis=1`。
- 写入安全：发布/点赞/评论各 4 线程，固定幂等键并发重放 15 秒；37,593 次请求错误 0，数据库三类副作用各 1 次。
- 幂等与消息：`completed=3`、`hit=37590`，不可用/冲突/执行失败均为 0；3 条 Outbox 全部派发，2 条通知消费成功，retry/DLQ 为 0。
- 原始证据：`target/jmeter-results/read-20260824-215138927-bebd41b9/`、`target/jmeter-results/write-20260824-225814565-e35886d0/`。

## 1. 结论边界

本报告只接受来自 `scripts/run-jmeter.ps1` 生成的 `results.jtl`、HTML 报告和 `summary.json` 的数据。静态代码审查、JMX XML 解析、Maven 测试或计划加载 smoke 不能替代真实 HTTP 压测。

当前不能填写以下结论，除非附有可复核的原始结果和环境记录：

- QPS/Throughput 达到某个固定目标；
- P50/P95/P99 低于某个固定阈值；
- 缓存优化“显著提升”或数据库压力“明显降低”；
- 把本轮固定幂等键重放结果外推为任意写入模型、跨进程崩溃恢复或生产容量结论。

## 2. 测试环境记录

| 项目 | 当前记录 |
|---|---|
| 应用 Git revision | `0fbbad4` + 当前未提交工作树（由本轮 Reactor package 产物启动） |
| 应用地址 | `http://127.0.0.1:8080`（本机开发拓扑） |
| JMeter | 5.6.3，CLI 非 GUI 模式 |
| MySQL | Win11 本机 `127.0.0.1:3306` |
| Redis | Win11 本机 `127.0.0.1:6379`，开发默认 single 模式 |
| RabbitMQ | 云服务器 `49.234.187.76:5672`，独立 vhost `/intern8` |
| Milvus | 不属于本项目业务依赖，不纳入本报告 |
| 测试数据 | 只读：随机 schema 内 1,000 关注、5,000 动态、1,000 通知；写入：随机空 schema + 1 条目标动态；测试后均已删除 |
| 应用可达性 | 压测期间监听 `127.0.0.1:8080`；测试后已停止并释放端口 |
| JWT | Dev Profile 临时签发，仅注入 JMeter 子进程；测试后已移除 |

本轮结果可用于同机、同数据、同并发参数下的回归对比，不能外推为生产容量上限。

## 3. 压测资产

### 3.1 只读基线

文件：`scripts/jmeter-test-plan.jmx`

| 线程组 | 接口 | 默认线程数 | 默认持续时间 |
|---|---|---:|---:|
| Dynamic Detail API | `GET /api/dynamic/detail/{id}` | 50 | 60 秒 |
| Feed API | `POST /api/dynamic/feed` | 30 | 60 秒 |
| Notification List API | `POST /api/notification/list` | 20 | 60 秒 |

### 3.2 写入安全测试

文件：`scripts/jmeter-write-safety-test.jmx`

| 线程组 | 接口 | 默认线程数 | 安全措施 |
|---|---|---:|---|
| Publish Dynamic Write Test | `POST /api/dynamic/publish` | 2 | 默认 0 线程；runner `-ConfirmWrite` 后注入正线程数 + 幂等键 |
| Like Dynamic Write Test | `POST /api/dynamic/like` | 2 | 默认 0 线程；runner `-ConfirmWrite` 后注入正线程数 + 幂等键 |
| Comment Dynamic Write Test | `POST /api/dynamic/comment` | 2 | 默认 0 线程；runner `-ConfirmWrite` 后注入正线程数 + 幂等键 |

写入测试必须使用隔离测试数据，不能作为只读性能基线的替代品。

## 4. 结果表

### 4.1 只读场景

| 场景 | Samples | Errors | Error Rate | Throughput | Average | P50 | P95 | P99 | 原始证据 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Dynamic Detail API | 21,235 | 0 | 0% | 710.153 RPS | 12.955 ms | 13 ms | 19 ms | 25 ms | `read-20260824-215138927-bebd41b9/results.jtl` |
| Feed API | 9,919 | 0 | 0% | 331.673 RPS | 16.789 ms | 16 ms | 24 ms | 32 ms | `read-20260824-215138927-bebd41b9/results.jtl` |
| Notification List API | 7,941 | 0 | 0% | 265.559 RPS | 14.045 ms | 14 ms | 20 ms | 27 ms | `read-20260824-215138927-bebd41b9/results.jtl` |

### 4.2 写入场景

最终有效轮次采用发布、点赞、评论各 4 线程，2 秒爬坡、15 秒持续时间。每个接口在整轮测试中使用一个固定 `Idempotency-Key` 和固定请求体，因此 37,593 个 HTTP 样本验证的是“同一请求并发重放”，不是 37,593 次独立业务写入。JMX 同时断言业务成功或预期的处理中 409；HTTP 200 中的业务失败不会被误计为成功。

| 场景 | Samples | Errors | Throughput | Average | P95 | P99 | 实际数据库副作用 | Outbox / 最终状态 |
|---|---:|---:|---:|---:|---:|---:|---|---|
| Publish Dynamic | 12,467 | 0 | 836.375 RPS | 4.451 ms | 9 ms | 13 ms | 新增动态 1 条 | `dynamic.archive` 1 条，Outbox `DISPATCHED`，broker `CONFIRMED` |
| Like Dynamic | 12,582 | 0 | 844.033 RPS | 4.408 ms | 8 ms | 12 ms | 点赞 1 条，`like_count=1` | `notification.created` 1 条，`DISPATCHED -> CONSUMED` |
| Comment Dynamic | 12,544 | 0 | 841.145 RPS | 4.433 ms | 8 ms | 13 ms | 评论 1 条，`comment_count=1` | `notification.created` 1 条，`DISPATCHED -> CONSUMED` |

一致性与幂等指标：

| 指标 / 检查 | 结果 |
|---|---:|
| `api.idempotency.completed` | 3 |
| `api.idempotency.hit` | 37,590 |
| `api.idempotency.in_progress` / `conflict` | 0 / 0 |
| `api.idempotency.unavailable` / `execution_failure` | 0 / 0 |
| `mq.outbox.enqueued` / `dispatched` / `failed` / `dead_lettered` | 3 / 3 / 0 / 0 |
| 通知落库 / RabbitMQ consumer ack | 2 / 2 |
| notification/archive retry 与 DLQ | 全部 0 |

归档事件按设计进入 TTL 为 7 天的 `archive.delay.queue`。测试期间该队列唯一消息的 `messageId=e32de732cd444d6d9a23f10697c7bfec` 与 Outbox `event_id` 一致；取证后定向删除该测试消息。清理完成时 7 个业务、retry、DLQ 队列均为 `ready=0, unacked=0, total=0`，HTTP 幂等键、消费者幂等键、限流键与未读计数已精确恢复，两个随机 schema 已删除，8080 已释放。

首轮诊断结果不计入性能基线。它真实暴露并推动修复了三项问题：生产 Redis Hash 使用默认 codec、Lua 使用 `StringCodec` 导致 `DONE` 响应不可读；动态 Mapper 漏写雪花主键导致发布失败；JMX 误断言 `code` 而非实际字段 `errorCode`。修复后定向单元测试、重新打包和上述最终轮次均通过。

原始证据：`target/jmeter-results/write-20260824-225814565-e35886d0/results.jtl`、`summary.json`、HTML 报告及 `write-safety-evidence.json`。

## 5. 执行步骤

### 5.1 只读压测

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
    -DurationSeconds 60 `
    -RampUpSeconds 10
} finally {
  Remove-Item Env:JWT_TOKEN -ErrorAction SilentlyContinue
}
```

### 5.2 写入安全测试

仅对隔离数据执行，并显式确认数据变更：

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

### 5.3 结果分析

```powershell
powershell -NoProfile -ExecutionPolicy Bypass `
  -File scripts/analyze-jmeter-results.ps1 `
  -JtlPath target/jmeter-results/read-<timestamp>/results.jtl `
  -OutputPath target/jmeter-results/read-<timestamp>/summary.json
```

## 6. 证据边界与待补齐项

本轮已补齐只读 JTL、写入 JTL、数据规模、幂等指标、数据库副作用、Outbox/消息状态和 RabbitMQ 队列终态。以下内容仍需在容量评估或故障演练前补齐：

1. 测试数据规模、缓存预热方式、应用版本和 Git revision。
2. 每个线程组的线程数、爬坡时间、稳定运行时间和有效样本数。
3. 按 sampler label 的 Average、P50、P95、P99、Throughput 和 Error Rate。
4. MySQL `EXPLAIN`/`rows examined`、连接池、慢查询和 Feed 两阶段查询数据。
5. Redis 命中率、命令延迟、single-flight 等待/超时和熔断器状态。
6. 应用提交后、relay 前强制崩溃，租约回收、重复发布和消费者重启恢复证据。
7. JVM CPU、堆、GC、线程池和应用错误日志中的 requestId/messageId。

因此，本报告可证明当前数据规模下的只读性能和“固定 key + 固定 body”并发重放一致性；不能证明生产容量上限、跨进程故障恢复或所有写入模型的一致性。
