# 技术深化清单

> 项目：xiaozhaovip-intern8
> 技术目标：在原有 Spring Boot 社交平台后端基础上，继续深化分布式系统、并发控制、可靠消息、安全和可观测性能力，形成可以支撑大厂秋招 SP 面试的工程证据链。
> 文档更新：2026-08-11
> 状态定义：`[x]` 表示源码、测试或可复现命令已经验证；`[~]` 表示核心代码已具备但仍缺少真实基础设施或集成验证；`[ ]` 表示尚未实现。

## 1. 目标与约束

### 1.1 目标

- 先在现有 Java 17、Spring Boot 3.3.5、MyBatis、Redis、RabbitMQ 技术栈上深化，不为了堆技术而引入无业务价值的组件。
- 每一个技术项都要回答四个问题：解决什么故障、为什么选择该方案、如何证明有效、失败时如何降级或恢复。
- 代码实现必须有边界条件、错误处理、指标和测试入口；无法在本地验证的能力必须明确标注外部依赖。
- 方案优先级按生产风险排序：数据正确性与消息可靠性高于性能优化，性能优化高于外围平台建设。

### 1.2 当前约束

- 当前验证拓扑是：Win11 本机提供 MySQL 8.0.46 与 Redis 6379，云服务器提供 RabbitMQ；本仓库当前没有 Milvus 业务调用或 SDK 依赖，Milvus 属于外部 `newagent` 系统，不能作为本项目已接入能力记录。
- 本机 MySQL/Redis 端口已可连通，但当前项目业务库 `xiao` 及 `tui_*`、`mq_outbox` 表尚未初始化；本机 MySQL IT 使用随机隔离 schema 验证 Outbox，不等同于 `xiao` 业务链路或 Feed EXPLAIN 已通过。
- 当前业务仍是单体多模块结构，不应在没有流量和部署证据时直接拆分微服务。
- `mq_message_status` 是现有消息状态与补偿基础，但还不是严格意义上的事务消息 Outbox；事务一致性仍需进一步验证。
- 图片 URL 当前只做语法与协议校验，不做远端 HEAD 请求或域名白名单校验，避免把接口变成 SSRF 入口。

## 2. 技术基线与模块边界

| 模块 | 当前职责 | 深化重点 |
| --- | --- | --- |
| `intern-base-intf` | DTO、实体、Mapper、SQL、公共工具 | 输入契约、校验注解、索引与数据结构 |
| `intern-base-service` | 动态、评论、通知、缓存、锁、MQ 业务 | 一致性、幂等、并发、补偿与故障降级 |
| `intern-base-web` | Controller、Security、Filter、配置、Actuator | 请求边界、身份传播、线程池、优雅停机、观测 |
| `deploy` 与脚本 | 本地 MySQL/Redis 与云 RabbitMQ 的运行辅助 | 集成测试、故障演练、可复现环境 |

### 2.1 核心链路

```text
HTTP Request
  -> MdcFilter / XssFilter / JWT Filter
  -> Controller DTO Validation
  -> Service Transaction + Redisson Lock
  -> MySQL Business Write + MQ Outbox Write (same transaction)
  -> Outbox Relay Claim + RabbitMQ Publish
  -> RabbitMQ Confirm / Manual Ack / Retry / DLQ
  -> Consumer Idempotency + MySQL Update
  -> Micrometer Metrics + MDC Logs
```

## 3. 技术文献基线

所有设计讨论优先引用以下官方文档、标准或一手资料。博客只能作为补充，不能替代这些资料作为方案依据。

### 3.1 Spring 与 Web

- Spring Security Servlet Architecture：<https://docs.spring.io/spring-security/reference/servlet/architecture.html>
- Spring Cache Abstraction：<https://docs.spring.io/spring-framework/reference/integration/cache.html>
- Spring Transaction Management：<https://docs.spring.io/spring-framework/reference/data-access/transaction.html>
- Spring MVC Request Body：<https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-methods/requestbody.html>
- Spring `RequestBodyAdvice` Javadoc：<https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/web/servlet/mvc/method/annotation/RequestBodyAdvice.html>
- Spring Boot Metrics：<https://docs.spring.io/spring-boot/reference/actuator/metrics.html>
- Spring Boot Graceful Shutdown：<https://docs.spring.io/spring-boot/reference/web/graceful-shutdown.html>

### 3.2 Redis、锁与本地缓存

- Redis Cache-Aside：<https://redis.io/docs/latest/develop/use-cases/cache-aside/>
- Redis Distributed Locks：<https://redis.io/docs/latest/develop/clients/patterns/distributed-locks/>
- Redis Lua Scripting：<https://redis.io/docs/latest/develop/interact/programmability/eval-intro/>
- Redisson Locks：<https://redisson.pro/docs/data-and-services/locks-and-synchronizers/>
- Caffeine Wiki：<https://github.com/ben-manes/caffeine/wiki>

### 3.3 RabbitMQ 与可靠消息

- RabbitMQ Publisher Confirms：<https://www.rabbitmq.com/docs/4.2/publishers>
- RabbitMQ Reliability：<https://www.rabbitmq.com/docs/reliability>
- RabbitMQ Dead Letter Exchanges：<https://www.rabbitmq.com/docs/next/dlx>
- RabbitMQ Consumer Prefetch：<https://www.rabbitmq.com/docs/consumer-prefetch>
- Transactional Outbox Pattern：<https://microservices.io/patterns/data/transactional-outbox.html>
- AWS Transactional Outbox Guidance：<https://docs.aws.amazon.com/prescriptive-guidance/latest/cloud-design-patterns/transactional-outbox.html>

### 3.4 并发、观测与安全

- Resilience4j CircuitBreaker：<https://resilience4j.readme.io/docs/circuitbreaker>
- Micrometer Reference：<https://docs.micrometer.io/micrometer/reference/>
- SLF4J MDC API：<https://www.slf4j.org/api/org/slf4j/MDC.html>
- OpenTelemetry Java：<https://opentelemetry.io/docs/languages/java/>
- OWASP XSS Prevention：<https://cheatsheetseries.owasp.org/cheatsheets/Cross_Site_Scripting_Prevention_Cheat_Sheet.html>
- OWASP Input Validation：<https://cheatsheetseries.owasp.org/cheatsheets/Input_Validation_Cheat_Sheet.html>
- JWT RFC 7519：<https://www.rfc-editor.org/rfc/rfc7519>

### 3.5 数据库与测试

- MySQL `EXPLAIN`：<https://dev.mysql.com/doc/refman/8.0/en/explain.html>
- MySQL Index Optimization：<https://dev.mysql.com/doc/refman/8.0/en/optimization-indexes.html>
- MySQL Transaction Isolation：<https://dev.mysql.com/doc/refman/8.0/en/innodb-transaction-isolation-levels.html>
- MyBatis Configuration：<https://mybatis.org/mybatis-3/configuration.html>
- Flyway Documentation：<https://documentation.red-gate.com/flyway>
- Testcontainers Java：<https://java.testcontainers.org/>
- JUnit 5 User Guide：<https://junit.org/junit5/docs/current/user-guide/>

## 4. 已完成或已验证能力

### 4.1 身份认证与请求边界 `[x]`

- [x] 写接口从 `SecurityContextHolder` 获取当前用户，不再信任 URL 中的 `userId`。
- [x] JWT 认证成功后立即写入 `MDC.userId`，请求结束由过滤器清理 ThreadLocal 上下文。
- [x] 认证失败返回 401，权限不足返回 403。
- [x] DTO 使用 Jakarta Validation 约束空值、正数、分页范围和文本长度。
- [x] Query 参数、Header 和 JSON 请求体均有 XSS 防护入口；JSON 只清洗标注 `@XssSafe` 的用户文本字段，避免误伤图片 URL 和 ID。
- [x] 图片 URL 使用 `@SafeImageUrls` 校验数量、长度、HTTP/HTTPS 协议、Host 和用户信息字段。

实现锚点：

- `intern-base-web/src/main/java/vip/xiaozhao/intern/baseUtil/config/security/JwtAuthenticationFilter.java`
- `intern-base-web/src/main/java/vip/xiaozhao/intern/baseUtil/config/XssFilter.java`
- `intern-base-web/src/main/java/vip/xiaozhao/intern/baseUtil/config/XssRequestBodyAdvice.java`
- `intern-base-intf/src/main/java/vip/xiaozhao/intern/baseUtil/intf/validation/`

边界说明：XSS 清洗不是输出编码的替代品，前端渲染仍必须使用上下文正确的输出编码；图片 URL 不是域名白名单，后续若服务端主动抓取图片，必须再增加 SSRF 防护。

### 4.2 多级缓存与缓存一致性 `[~]`

- [x] 使用 L1 Caffeine、L2 Redis、L3 MySQL 的 Cache-Aside 读取链路。
- [x] 缓存加载锁使用 Lua 保证加锁与过期时间设置在同一 Redis 原子脚本内完成。
- [x] 锁值使用随机 owner token，释放使用 Lua compare-and-delete，避免过期后旧线程误删新线程的锁。
- [x] 写入和删除路径使用延迟双删，降低并发读写下的短暂脏缓存概率。
- [x] 动态详情的 `RedisCacheService` 与 Spring `CacheManager` 共享同一个 Caffeine 实例，避免两个 L1 缓存不一致。
- [x] 提供默认关闭的热点动态缓存预热，预热失败不阻塞应用启动。
- [x] Redis 不可用时保留数据库降级路径，缓存失败不直接扩大为请求级故障。

实现锚点：

- `intern-base-service/src/main/java/vip/xiaozhao/intern/baseUtil/service/RedisCacheService.java`
- `intern-base-service/src/main/java/vip/xiaozhao/intern/baseUtil/service/DynamicCacheWarmup.java`
- `intern-base-web/src/main/java/vip/xiaozhao/intern/baseUtil/config/CacheConfig.java`

尚未完成：真实 Redis 集成测试、缓存击穿压测、热点 key 分布观测和 Lua 脚本在 Redis Cluster 下的 key-slot 验证。

### 4.3 RabbitMQ 可靠消息 `[~]`

- [x] Publisher Confirm、Mandatory Return、CorrelationData 和消息状态表已接入。
- [x] 消费端使用 manual ack，业务成功后确认，异常进入有限重试、DLQ 和补偿任务链路。
- [x] 消费端按消息 ID 使用 Redis 幂等键，避免重复投递重复落库。
- [x] 发送端接入 RabbitMQ CircuitBreaker；同步连接失败或熔断时把消息状态记为失败，交由补偿任务处理。
- [x] 发送端向消息 Header 传播 `requestId`、`userId` 和 `clientIp`。
- [x] 两个消费者处理前绑定 MDC，处理后恢复原上下文，避免 listener 线程污染后续消息。
- [x] Publisher Confirm、Return 和消费成功/失败统一走条件状态转移，旧回调不能覆盖 `CONSUMED` 或新的补偿状态。
- [x] 重试发布返回本地接受结果；发布失败时不确认原消息，转入 DLQ/补偿路径，避免“重试未发出但原消息已 ack”。
- [x] 补偿任务使用 `COMPENSATING` 抢占状态，并允许回收超时租约，避免多实例重复补偿。
- [x] 无法反序列化或无法解析路由的补偿消息进入 `DEAD_LETTERED`，避免坏消息被调度任务无限重试。
- [x] 业务事务内写入 `mq_outbox`，动态归档和通知事件不再依赖事务提交后的直接发布回调。
- [x] Outbox relay 使用数据库条件抢占、租约恢复、有限重试、指数退避和死信终态；多实例不依赖进程内锁。
- [~] 已有真实 RabbitMQ 的 confirm、return、手动 ack/redelivery 证据；新增 broker `basicNack -> DLQ` 与 TTL retry 测试代码已补齐但待网络恢复后复测，consumer success 回调竞态、消费者进程重启和 Outbox 崩溃恢复仍需集成测试；Outbox 的“本地发送已接受”与 broker confirm 仍由 `mq_message_status` 分层记录。

实现锚点：

- `intern-base-service/src/main/java/vip/xiaozhao/intern/baseUtil/service/RabbitMQSender.java`
- `intern-base-service/src/main/java/vip/xiaozhao/intern/baseUtil/service/MqOutboxService.java`
- `intern-base-service/src/main/java/vip/xiaozhao/intern/baseUtil/service/MqOutboxRelay.java`
- `intern-base-intf/src/main/java/vip/xiaozhao/intern/baseUtil/intf/mapper/MqOutboxMapper.java`
- `intern-base-intf/src/main/resources/sql/mq_outbox.sql`
- `intern-base-service/src/main/java/vip/xiaozhao/intern/baseUtil/service/CircuitBreakerService.java`
- `intern-base-service/src/main/java/vip/xiaozhao/intern/baseUtil/mq/consumer/AbstractMqConsumer.java`
- `intern-base-service/src/main/java/vip/xiaozhao/intern/baseUtil/mq/consumer/NotificationConsumer.java`
- `intern-base-service/src/main/java/vip/xiaozhao/intern/baseUtil/mq/consumer/ArchiveConsumer.java`
- `intern-base-service/src/test/java/vip/xiaozhao/intern/baseUtil/integration/RabbitMqRemoteIT.java`

### 4.4 分布式锁、限流与补偿调度 `[~]`

- [x] 业务写操作使用 Redisson 锁和数据库唯一约束双重保护。
- [x] 点赞、关注、评论和发布路径具备锁粒度与限流入口。
- [x] MQ 补偿任务使用分布式锁，锁等待 1 秒、租约 55 秒，避免默认 10 秒租约覆盖不了批处理。
- [x] 补偿任务设置 45 秒协作式执行截止时间，并通过 `mq.compensation.timeout` 暴露超时指标。
- [~] 截止时间不能中断一个已经阻塞在外部 I/O 的单条消息发送；后续需要结合客户端超时和可中断执行器做硬超时治理。

实现锚点：

- `intern-base-service/src/main/java/vip/xiaozhao/intern/baseUtil/service/DistributedLockService.java`
- `intern-base-service/src/main/java/vip/xiaozhao/intern/baseUtil/service/MessageCompensationTask.java`

### 4.5 线程池、异步和优雅停机 `[x]`

- [x] `businessExecutor` 使用有界队列、CallerRunsPolicy、MDC TaskDecorator 和 shutdown await。
- [x] 通知发送使用指定业务线程池异步执行，避免默认无界异步执行器造成线程膨胀。
- [x] 配置 `server.shutdown=graceful` 和 30 秒 shutdown phase 超时。
- [x] 异步未捕获异常统一记录方法、参数和异常。

实现锚点：

- `intern-base-web/src/main/java/vip/xiaozhao/intern/baseUtil/config/ThreadPoolConfig.java`
- `intern-base-web/src/main/java/vip/xiaozhao/intern/baseUtil/config/MdcTaskDecorator.java`
- `intern-base-web/src/main/java/vip/xiaozhao/intern/baseUtil/config/AsyncConfig.java`
- `intern-base-web/src/main/resources/application.properties`

### 4.6 观测与指标 `[~]`

- [x] 缓存命中、未命中、DB load、锁竞争、Feed Timer、补偿成功/失败/跳过/超时等指标已具备代码入口。
- [x] 请求 MDC 至少包含 requestId、userId、requestUri、clientIp；异步任务可传播 MDC。
- [x] Actuator 暴露 health、metrics 和 Prometheus 端点。
- [x] Redis、RabbitMQ、COS 熔断器暴露状态、状态迁移、fallback、拒绝调用和半开探测结果指标；Redis 缓存故障隔离额外暴露 single-flight leader/waiter/failure/timeout 指标。
- [~] 当前主要是 MDC + Micrometer，尚未完成 OpenTelemetry Trace、统一 JSON 日志 schema 和端到端 traceparent 传播。
- [~] 还没有以 P99、错误率和队列堆积为核心的生产告警规则文件。

## 5. Phase 2 待办清单

### P0：数据正确性和消息可靠性

#### 5.1 Redis 集成测试与缓存击穿验证 `[~]`

- [~] 已补齐 `RedisCacheServiceRedisIT`：覆盖 128 并发慢 loader、错误 token 释放、Lua 脚本失败快速降级和租约过期重新抢锁；当前机器无 Docker，真实 Redis 结果尚未获得。
- [x] 缓存加载竞争改为在锁租约内轮询 Redis，并支持竞争线程重新抢锁；Redis Lua 异常会区分于“锁被占用”，立即降级到 DB，避免固定短等待后的重复 loader 和无限重试。
- [x] Redis 读、写、删除和 Lua 锁操作通过 Redis CircuitBreaker 统一记录失败；Redis 故障时同一实例同一 key 使用 single-flight 合并 DB loader，避免热点请求同时击穿数据库。
- [x] 本地故障注入覆盖 Redis 读取失败、Lua 脚本失败、leader loader 异常和 waiter 超时；`cache.single-flight.wait-timeout-ms` 使用可配置有界等待，不取消共享 future，超时返回 503，leader finally 负责清理 in-flight 状态。
- [x] 新增显式启用的 `RedisCacheServiceLocalIT`，使用 Win11 本机 Redis 6379 和外部认证配置验证真实 Redis 回读、Lua token 保护以及 128 并发 loader 合并。
- [ ] 使用 Testcontainers 启动 Redis，验证 Lua 加锁、随机 token 释放和 token 不匹配时不删除锁。
- [x] 在无真实 Redis 的本地 mock 故障场景中使用 128 个同 key 并发请求验证 DB loader 只执行 1 次；真实 Redis 命中率和网络延迟仍需集成环境验证。
- [x] 在本地模拟 Redis 读取异常、脚本失败和 leader 长时间阻塞，验证降级路径不会无限重试或让 waiter 无限占用线程；真实 Redis 超时与租约到期仍需容器验证。
- 验收：同一 key 的并发 DB load 次数在锁租约内接近 1；owner 不匹配的释放脚本返回 0。

#### 5.2 Feed 查询 EXPLAIN 和索引闭环 `[~]`

- [x] 新增 `FeedExplainMySqlLocalIT`：连接 Win11 MySQL，在随机隔离 schema 中生成 5,000 条关注关系和 50,000 条动态，执行生产 Feed ID 查询的 before/after `EXPLAIN`，并在测试结束清理 schema。
- [x] Feed 服务改为只读事务内的两阶段查询：`selectFeedDynamicIds` 只取游标 ID，`selectByIds` 按主键批量回表，Service 按第一阶段顺序恢复结果；真实 MyBatis IT 覆盖递减游标、状态过滤、空 ID 批次和回表路径。
- [~] smoke 计划确认 `idx_follow_covering`、`idx_dynamic_status_id` 进入 `possible_keys`，但默认 `EXISTS` 计划仍有 `Using temporary; Using filesort`；`STRAIGHT_JOIN` 对照可消除排序，却估算向后扫描约 24,951 行，不能仅凭小数据集切换为生产默认。
- [ ] 在生产规模数据上验证 `tui_follow(user_id, status, follow_user_id)` 与 `tui_dynamic(user_id, status, id)` 的选择性、rows examined、filesort/temporary 和回表范围。
- [ ] 对 EXISTS、STRAIGHT_JOIN、覆盖索引和推拉结合方案执行基准对比，记录 QPS、P50、P95、P99；当前 smoke 不提供生产延迟结论。
- 验收：在明确数据规模下记录 QPS、P50、P95、P99、rows examined 和执行计划；不能只写一个没有数据来源的延迟数字。

#### 5.3 RabbitMQ 集成可靠性 `[~]`

- [x] 已通过远程 RabbitMQ 4.3.3 验证 publisher confirm、mandatory return、手动 ack 和物理 channel 关闭后的 redelivery；测试使用随机命名临时拓扑，结束后显式删除。
- [x] `RabbitMqRemoteIT` 默认关闭，连接地址、vhost、用户名和密码只从环境变量或 JVM 参数读取，不写入源码或文档；2026-08-11 云 broker 真实通过 `basicNack(requeue=false) -> DLQ`、`x-message-ttl -> 主队列` 和 `x-death` 原因断言，AMQP header 的长字符串按值比较。
- [x] 部署辅助配置不再提供 RabbitMQ、Redis、MySQL、Grafana、JWT 的弱默认凭证：Compose 使用 `${VAR:?message}` 强制注入，Windows 启动脚本不回显管理口令，Redis Sentinel 在容器启动时生成权限为 `0600` 的临时配置；当前 Win11 开发拓扑仍使用本机 MySQL/Redis 和云 RabbitMQ，Compose 仅作为独立可复现环境。
- [ ] 使用 Testcontainers RabbitMQ 验证 confirm ack、nack、return、手动 ack、重试、DLQ 和补偿。
- [ ] 验证同一个消息在 confirm、return、consumer success 竞态下的状态单向转移，不允许成功状态被失败回调覆盖。
- [ ] 验证消费者进程重启、网络短断和重复投递，确认 Redis 幂等键与数据库唯一约束共同生效。
- [ ] 补充 `requestId/userId` Header 在生产者到消费者的测试断言。
- 验收：消息最终只能进入成功、可补偿失败或 DLQ 等明确终态；每种异常都有可检索的 messageId。

实现锚点：

- `intern-base-service/src/test/java/vip/xiaozhao/intern/baseUtil/integration/RabbitMqRemoteIT.java`

### P1：接口与故障治理

#### 5.4 API 幂等键 `[~]`

- [x] 发布动态、点赞、评论和分享接口接入 `Idempotency-Key`；缺少 Header 时保留旧客户端兼容行为。
- [x] Redis Hash 保存 request fingerprint、`PROCESSING`/`DONE`/`FAILED` 状态、最终响应和 24 小时 TTL。
- [x] 使用 Lua 原子完成占有、完成和失败回写；处理租约 120 秒，过期后允许恢复，避免永久 `SETNX` 卡死。
- [x] 相同用户、相同接口、相同 key 且请求体一致时返回相同响应；请求体不一致返回 409。
- [x] 处理中返回 409，Redis 不可用时 fail-closed 返回 503，不执行业务副作用。
- [~] 业务完成后 Redis 在响应持久化窗口内崩溃时，仍需结合数据库唯一约束和真实 Redis 故障演练验证重复副作用风险。
- 验收：重复请求不增加动态、点赞、评论或分享记录；并发重复请求只有一个请求执行副作用。

实现锚点：

- `intern-base-service/src/main/java/vip/xiaozhao/intern/baseUtil/service/ApiIdempotencyService.java`
- `intern-base-web/src/main/java/vip/xiaozhao/intern/baseUtil/controller/DynamicController.java`
- `intern-base-web/src/main/java/vip/xiaozhao/intern/baseUtil/controller/CommentController.java`

#### 5.5 MQ 状态机与事务 Outbox `[~]`

- [x] 明确 `PENDING -> CONFIRMED -> CONSUMED` 和 `PENDING/CONFIRMED -> FAILED -> COMPENSATING` 的状态转移。
- [x] 使用带条件的 SQL 更新防止旧回调覆盖新状态。
- [x] 动态归档、点赞/评论/分享/关注通知在业务事务内写入 `mq_outbox`，业务回滚不会留下可发布孤儿消息。
- [x] `MqOutboxRelay` 使用 `status + lease_until` 条件更新抢占任务；进程崩溃后租约到期可被其他实例重新领取。
- [x] relay 失败按重试次数进入 `FAILED`，超过预算或 payload 无法解析进入 `DEAD_LETTERED`，并记录有限长度错误摘要和指标。
- [x] 新增 `MqOutboxMySqlLocalIT`：显式开启后连接 Win11 本机 MySQL，创建随机隔离 schema，直接加载生产 `MqOutboxMapper.xml` 与 `mq_outbox.sql`，覆盖事务提交、事务回滚、条件 claim 互斥和 lease 过期恢复；测试结束自动删除 schema，2026-08-11 四个用例真实通过。
- [~] `MqOutboxMySqlIT` 保留为 Testcontainers 可复现路径，直接加载生产 Mapper 与 SQL；当前机器无 Docker，尚未获得容器环境真实执行结果。
- [~] 真实 MySQL/RabbitMQ 下的提交后立即崩溃、网络短断、confirm/return 竞态仍需集成测试；`DISPATCHED` 只表示 `RabbitTemplate` 接受发布，不等价于 broker confirm。
- 验收：业务事务提交后即使应用立即崩溃，消息仍可由 Outbox relay 找回并发布。

#### 5.6 熔断、超时和限流外部化 `[~]`

- [x] 将 CircuitBreaker 的失败率、慢调用阈值、滑动窗口、最小调用数、半开探测数和 OPEN 等待时间迁移到配置文件，并按 Redis、RabbitMQ、COS 分别绑定。
- [x] 为 Redis、RabbitMQ、外部 COS 分别定义连接/命令/请求超时、熔断和代码降级策略；生产告警规则文件仍未完成。
- [x] 为熔断状态变化、fallback 次数、拒绝调用和半开探测成功/失败注册 Micrometer 指标。
- [x] 通过 `RedisCacheServiceFailureIsolationTest` 注入 Redis 读取/Lua 失败，验证 32 个同 key 并发请求只执行一次 DB loader，并在 breaker OPEN 后快速降级；真实 Redis 网络故障仍未验证。

实现锚点：

- `intern-base-service/src/main/java/vip/xiaozhao/intern/baseUtil/config/CircuitBreakerProperties.java`
- `intern-base-service/src/main/java/vip/xiaozhao/intern/baseUtil/service/CircuitBreakerService.java`
- `intern-base-service/src/main/java/vip/xiaozhao/intern/baseUtil/service/RedisCacheService.java`
- `intern-base-service/src/test/java/vip/xiaozhao/intern/baseUtil/service/RedisCacheServiceFailureIsolationTest.java`
- `intern-base-web/src/main/resources/application.properties`
- `intern-base-web/src/main/resources/application-prod.properties`

#### 5.7 结构化日志与脱敏 `[~]`

- [x] 统一 JSON 日志字段：timestamp、level、service、requestId、userId、messageId、eventType、durationMs、errorCode，并限制输出 MDC 白名单。
- [x] 对 password、token、secret、Authorization、手机号等敏感字段递归脱敏，覆盖对象、嵌套 Map、数组和文本 key-value。
- [x] Controller/Service 不记录完整请求体和响应体；消息与异常栈设置最大长度，MQ 消费异常只记录 `payloadBytes`，不打印原始 body。
- [x] 单实例错误日志按操作和异常类型限速，避免高流量异常反向拖垮服务。
- [~] 生产日志采集、跨实例限流和端到端 HTTP/MQ 链路仍需在部署环境验证。

实现锚点：

- `intern-base-web/src/main/java/vip/xiaozhao/intern/baseUtil/logging/SensitiveDataSanitizer.java`
- `intern-base-web/src/main/java/vip/xiaozhao/intern/baseUtil/logging/StructuredJsonEncoder.java`
- `intern-base-web/src/main/java/vip/xiaozhao/intern/baseUtil/logging/ErrorLogRateLimiter.java`
- `intern-base-web/src/main/java/vip/xiaozhao/intern/baseUtil/aspect/LogAspect.java`
- `intern-base-web/src/main/java/vip/xiaozhao/intern/baseUtil/config/MdcFilter.java`
- `intern-base-web/src/main/java/vip/xiaozhao/intern/baseUtil/config/MdcTaskDecorator.java`
- `intern-base-service/src/main/java/vip/xiaozhao/intern/baseUtil/mq/consumer/AbstractMqConsumer.java`
- `intern-base-service/src/main/java/vip/xiaozhao/intern/baseUtil/mq/consumer/NotificationConsumer.java`
- `intern-base-service/src/main/java/vip/xiaozhao/intern/baseUtil/mq/consumer/ArchiveConsumer.java`
- `intern-base-web/src/main/resources/logback-spring.xml`

验证入口：

- `mvn -q -pl intern-base-web -am -DskipTests test-compile`
- `mvn -q -pl intern-base-web -am -Dtest=SensitiveDataSanitizerTest,StructuredJsonEncoderTest,ErrorLogRateLimiterTest,MdcTaskDecoratorTest,MdcFilterTest -Dsurefire.failIfNoSpecifiedTests=false test`

### P2：平台化能力

#### 5.8 Flyway 数据库版本管理 `[ ]`

- [ ] 将 `init.sql`、索引和唯一约束迁移为 `V1__init.sql`、`V2__indexes.sql` 等版本脚本。
- [ ] 开发、测试、生产环境使用同一迁移历史，不再依赖手工执行散落 SQL。
- [ ] 为破坏性变更设计 expand-contract 兼容期和回滚说明。
- 验收：空数据库和已有数据库均能通过 `flyway migrate` 达到相同 schema 版本。

#### 5.9 Testcontainers 集成测试 `[~]`

- [~] 已创建 MySQL、Redis、RabbitMQ 容器基类，使用 `integration-test` profile 和 `*IT.java` 约定隔离普通单元测试，容器结束自动清理。
- [~] 已增加真实 Redis API 幂等并发测试和 MySQL Outbox 事务/租约测试入口；当前机器无 Docker，Redis 和 MySQL IT 均未获得真实容器结果。
- [~] 已增加 `RedisCacheServiceRedisIT`，当前仅完成测试代码、编译和无 Docker 快速失败验证。
- [x] 已增加远程 RabbitMQ IT 入口；云端验证已通过，且随机临时队列清理后未残留。
- [ ] 覆盖动态发布、缓存重建、点赞幂等、MQ 消费和补偿任务。
- [ ] 集成测试只在 profile 或 CI 阶段启用，普通单元测试保持快速。
- 验收：CI 能在干净环境复现数据库、缓存和消息链路，不依赖开发机服务。

#### 5.10 读写分离 `[ ]`

- [ ] 只有在真实读写比例和数据库瓶颈数据成立后再引入路由数据源。
- [ ] 使用 `@ReadDataSource`、`@WriteDataSource` 或事务只读属性定义路由边界。
- [ ] 写后读关键链路强制走主库，避免复制延迟导致用户刚发布内容不可见。
- [ ] 暴露主库 QPS、从库 QPS、复制延迟和路由错误指标。

#### 5.11 OpenTelemetry 全链路追踪 `[ ]`

- [ ] 将 HTTP requestId、RabbitMQ messageId 和数据库 span 关联到同一个 trace。
- [ ] 使用 W3C Trace Context 传播，不把 MDC 当作完整分布式追踪协议。
- [ ] 采样策略按错误、慢请求和关键写操作提高采样率。
- 验收：一次动态发布能够在 trace backend 中看到 HTTP、事务、DB、发布和消费 span。

#### 5.12 Chaos 与弹性演练 `[ ]`

- [ ] 注入 Redis 延迟、RabbitMQ 断连、MySQL 慢查询、线程池满和消息重复投递。
- [ ] 记录故障开始时间、检测时间、降级时间、恢复时间和数据修复结果。
- [ ] 先在本地 Docker Compose 演练，再迁移到可控测试环境；不直接在生产试验。

## 6. 推荐实施顺序

1. 先完成 Redis、MySQL、RabbitMQ Testcontainers 基础设施和最小集成测试。
2. 再用事务 Outbox、MQ 状态机条件更新和独立 relay 解决消息终态和崩溃窗口。
3. 使用真实数据集完成 Feed EXPLAIN、索引和分页基准，不凭感觉优化 SQL。
4. 引入 API 幂等键，优先覆盖写副作用最强的发布、点赞、评论接口。
5. 外部化熔断、超时、限流和告警配置，补充故障注入场景。
6. 最后建设 Flyway、OpenTelemetry、读写分离和 Chaos 工具链。

## 7. 验证命令

### 7.1 本地快速验证

```powershell
mvn -q -DskipTests compile
mvn test -q
git diff --check
```

### 7.2 依赖真实组件的验证

```powershell
# 当前拓扑：Win11 本机 MySQL。账号需要 CREATE/DROP DATABASE 权限，测试只创建并清理随机隔离 schema。
$env:MYSQL_LOCAL_IT_ENABLED = "true"
$env:MYSQL_HOST = "127.0.0.1"
$env:MYSQL_PORT = "3306"
$env:MYSQL_LOCAL_IT_USERNAME = "<mysql-management-username>"
$env:MYSQL_LOCAL_IT_PASSWORD = "<mysql-management-password>"
mvn -q -pl intern-base-service -am `
  "-Dtest=MqOutboxMySqlLocalIT" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -q -pl intern-base-service -am `
  "-Dtest=FeedExplainMySqlLocalIT" `
  "-Dsurefire.failIfNoSpecifiedTests=false" test
Remove-Item Env:MYSQL_LOCAL_IT_ENABLED,Env:MYSQL_LOCAL_IT_USERNAME,Env:MYSQL_LOCAL_IT_PASSWORD

# 备用方案：仅在 Docker 环境中运行，不代表当前 Win11 + 云 RabbitMQ 拓扑。
docker compose up -d mysql redis rabbitmq
mvn -Pintegration-test verify
docker compose down -v
```

当前拓扑下，MySQL/Redis 使用 Win11 本机服务；RabbitMQ 连接参数通过 `RABBITMQ_HOST`、`RABBITMQ_PORT`、`RABBITMQ_USERNAME`、`RABBITMQ_PASSWORD` 和 `RABBITMQ_VHOST` 注入，不能使用仓库默认 guest 凭证。当前项目不添加 Milvus 启动或验证命令，Milvus 由外部 `newagent` 系统单独负责。

### 7.3 云 RabbitMQ 远程验证

```powershell
$env:RABBITMQ_IT_ENABLED = "true"
$env:RABBITMQ_IT_HOST = "<cloud-rabbitmq-host>"
$env:RABBITMQ_IT_PORT = "5672"
$env:RABBITMQ_IT_USERNAME = "<username>"
$env:RABBITMQ_IT_PASSWORD = "<password>"
$env:RABBITMQ_IT_VHOST = "/"
mvn -q -Pintegration-test -pl intern-base-service -am `
  "-Dit.test=RabbitMqRemoteIT" `
  "-Dfailsafe.failIfNoSpecifiedTests=false" verify
Remove-Item Env:RABBITMQ_IT_ENABLED,Env:RABBITMQ_IT_HOST,Env:RABBITMQ_IT_PORT,
  Env:RABBITMQ_IT_USERNAME,Env:RABBITMQ_IT_PASSWORD,Env:RABBITMQ_IT_VHOST
```

`RabbitMqRemoteIT` 默认关闭，只创建随机命名的 durable + auto-delete exchange/queue；密码不能写入仓库。RabbitMQ 4 默认拒绝 transient non-exclusive queue，因此远程测试不能使用非 durable、非 exclusive 的临时队列。

`integration-test` profile 要求 Docker CLI 和 daemon；Docker 不可用时当前基类会快速失败并保留明确的未验证状态，不能把 `Tests run: 0` 当作真实组件通过。直接在 IDE 运行 IT 且未设置强制属性时，测试才允许安全跳过。

上述集成命令只有在项目补齐 profile、Testcontainers 或可复现初始化脚本后才算有效；当前不能把它当作已经通过的命令。

## 8. 面试表达主线

### 8.1 缓存

先讲 Cache-Aside 和 L1/L2/L3 分层，再讲缓存击穿时为什么需要原子加锁；重点说明随机 owner token 和 compare-and-delete 解决了租约过期后的误删问题。最后说明延迟双删只能降低不一致窗口，不能替代事务和最终一致性验证。

### 8.2 消息

按 publisher confirm、状态持久化、manual ack、幂等、重试、DLQ、补偿的顺序讲完整链路；明确 confirm 只解决 broker 接收确认，不等于业务消费成功，因此必须拆分发送状态和消费状态，并用条件更新避免回调竞态。

### 8.3 高并发

说明锁只解决同一业务键的并发互斥，数据库唯一约束负责最终正确性，限流负责保护系统容量，线程池有界队列负责背压；这四层不能相互替代。

### 8.4 安全

说明输入校验、存储策略和输出编码分别承担不同责任；JSON 请求体只对用户文本字段做清洗，URL 做结构校验，服务端不主动抓取用户 URL，从而避免把图片功能变成 SSRF 入口。

## 9. 假设与验证记录

### 9.1 当前假设

- MySQL 版本目标为 8.x，动态和关注表的数据量会继续增长。
- 开发环境的 MySQL 与 Redis 运行在 Win11 本机；RabbitMQ 运行在云服务器，网络连通性和凭证由环境变量提供。
- Milvus 不属于当前项目的业务依赖；如果未来接入向量检索，必须单独增加模块边界、schema、超时、熔断和集成测试，不能仅凭云端服务在线判定接入完成。
- RabbitMQ 使用 publisher confirms、manual ack、DLX 和有限重试，消息允许至少一次投递。
- Redis 锁租约大于单次正常任务耗时，但不把租约当作绝对执行超时。
- 运行时敏感凭证必须通过环境变量或 JVM 参数注入；仓库示例只保留占位符，不能把默认值视为可启动凭证。

### 9.2 已验证内容

- `mvn -DskipTests compile`：验证三模块源码、Lombok annotation processor、Mapper 参数和配置类可编译。
- `mvn test -q`：验证现有单元测试、MQ 消费异常分支、发送端状态转移分支、缓存与并发相关测试以及图片 URL 校验器。
- `ApiIdempotencyServiceTest`：验证幂等 key 缺失、Redis 不可用、请求指纹冲突、处理中、缓存命中和业务异常回写。
- `MqOutboxServiceTest`、`MqOutboxRelayTest`：验证事务 Outbox 入队唯一性、重复事件幂等、payload 冲突、数据库抢占、发布失败重试和非法 payload 死信。
- `MqOutboxMySqlIT`：已编译并纳入 Failsafe，使用真实 MySQL 容器时验证业务表与 Outbox 同事务提交/回滚、条件抢占和过期租约恢复；当前 Docker 不可用，因此只记录为可复现测试路径。
- `MqOutboxMySqlLocalIT`：通过 `MYSQL_LOCAL_IT_ENABLED=true` 连接 Win11 本机 MySQL，使用随机隔离 schema 和生产 Mapper/SQL，4 个用例通过；覆盖提交、回滚、条件 claim 互斥、lease 过期恢复，测试后 schema 已清理。
- `RedisCacheServiceRedisIT`：已编译并纳入 Failsafe，使用真实 Redis 容器时验证缓存击穿并发、token 释放、脚本故障降级和租约恢复。
- `mvn -q -Pintegration-test -pl intern-base-service -am "-Dit.test=RedisCacheServiceLocalIT" "-Dfailsafe.failIfNoSpecifiedTests=false" "-Dredis.local.it.enabled=true" verify`：使用 `REDIS_AUTH` 环境变量连接 Win11 本机 Redis 6379，3 个真实 Redis 测试通过；认证信息未写入仓库。
- 本机基础设施检查：MySQL 8.0.46 监听 3306，Redis 监听 6379；本机 MySQL 账号可连接并具备隔离 IT 所需权限。当前项目 `xiao` schema 和 `tui_*` 业务表仍未初始化，但 `MqOutboxMySqlLocalIT` 已在随机 schema 中真实验证 Outbox 事务与租约语义，测试后 schema 已清理。
- `mvn -q -pl intern-base-service -am "-Dtest=RedisCacheServiceFailureIsolationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test`：4 个故障隔离用例通过；覆盖 Redis 读取/Lua 失败、128 个同 key 并发只执行 1 次 DB loader、waiter 有界超时和 leader 异常后的 future 清理，并验证 Redis breaker 进入 `OPEN`。
- `mvn -q test` 当前结果：77 个默认单元测试，Failures=0，Errors=0，Skipped=0；该结果仍不替代真实 `xiao` 业务 schema、生产规模 Feed 数据集、云 RabbitMQ 网络和完整消息链路验证。
- 结构化日志定向测试：9 个用例通过；覆盖递归对象/数组脱敏、Bearer 和手机号文本脱敏、长度边界、JSON 字段白名单、异常栈限长、单实例错误限速容量以及异步 MDC 恢复。
- `MdcFilterTest`：验证非法或过长外部 `X-Request-Id` 会被替换为安全格式，并通过响应头返回；`MdcTaskDecoratorTest` 验证线程复用场景不会泄漏任务上下文。
- MQ 日志审查：`NotificationConsumer` 和 `ArchiveConsumer` 异常路径改为记录 `payloadBytes`，不再输出完整消息 body；消费者 MDC 在 finally 中恢复。
- `mvn -q -DskipTests test-compile`：验证新增 MySQL IT、MyBatis XML 加载代码和 Testcontainers 测试依赖可编译。
- `FeedExplainMySqlLocalIT`：2 个本机 MySQL 测试通过；真实加载 `TuiDynamicMapper.xml`，验证两阶段 ID/批量回表、空 ID 分支，并记录 5,000 关注关系、50,000 动态数据集的 EXPLAIN 对照。该数据集不是生产规模，默认计划仍存在 filesort/temporary。
- XML 解析修复：真实 `SqlSessionFactory` 加载暴露并修复 `TuiDynamicMapper.xml`、`TuiNotificationMapper.xml` 中未转义 `<`，以及动态 Mapper 未加引号的 select 属性。
- `RedisUtilTest`：验证普通 Redis 操作保持 fail-soft，同时 `evalStrict` 会把 Lua 异常交给缓存策略判断。
- `RedisCacheServiceFailureIsolationTest`：注入 Redis 读取和 Lua 失败，验证 single-flight leader/waiter、数据库 loader 合并、waiter timeout、leader failure 清理和 breaker fallback 指标。
- `mvn -Pintegration-test verify`：验证 Failsafe profile、容器基类和 IT 发现链路；当前机器无 Docker，Redis 与 MySQL IT 均在前置检查处明确失败，不能记为真实组件通过。
- `mvn -q -pl intern-base-service -am "-Dtest=RabbitMqRemoteIT" "-Dsurefire.failIfNoSpecifiedTests=false" test`：2026-08-11 云 RabbitMQ 4 个远程测试通过；验证 confirm、mandatory return、手动 ack/redelivery、`basicNack -> DLQ`、TTL retry 回流和 `x-death` 原因，随机拓扑清理完成。
- 配置安全扫描：Compose、部署文档和 `start-cluster.bat` 不再包含已知弱默认密码；缺少 Compose 凭证时会在启动前失败，Sentinel 静态配置不再保存 Redis 口令。当前机器未安装 Docker，未执行 Compose 实际启动验证。
- `ProductionTopologyPropertiesTest`：验证 `application-prod.properties` 默认对应 Win11 本机 MySQL/Redis 单机和云 RabbitMQ 注入地址，Redis Sentinel/Cluster 节点保持环境变量驱动，且不包含固定容器地址或 `dev` profile。
- `ProductionTopologyPropertiesTest` 的 2 个配置契约用例通过；`ErrorLogRateLimiterTest` 的容量边界用例验证高基数 key 不会突破单实例 1024 条上限。
- 配置拓扑审查：`application.properties` 与 `application-prod.properties` 均支持 `SPRING_PROFILES_ACTIVE`、`MYSQL_HOST`、`REDIS_HOST`、`REDIS_MODE`、`RABBITMQ_HOST` 注入；Compose 通过 app 环境变量覆盖为独立容器拓扑。
- 源码审查：确认缓存锁释放使用 token 校验、消费者 MDC 使用 finally 恢复、补偿任务锁租约大于截止时间、MQ 状态更新包含前置状态条件。
- 源码审查：确认结构化编码器只输出固定字段白名单，日志消息/异常栈有长度上限，敏感文本先脱敏再截断，错误日志按 key 限速。

### 9.3 尚未验证内容

- 本机 Redis 的正常读写、Lua token 保护和并发缓存链路已验证；真实 Redis 网络异常、重启行为和 Docker/Testcontainers Redis 仍未验证。
- 当前项目 `xiao` schema、`tui_*` 业务表和生产规模 Feed 数据集尚未初始化，因此业务链路集成、rows examined 和 EXPLAIN/P99 仍未闭环；Outbox 与 Feed 两阶段 Mapper 已在本机随机隔离 schema 中验证。
- Redis 容器中 API 幂等 Lua 脚本的真实执行、并发 ownership 和租约恢复；当前仅完成测试代码和无 Docker 快速失败验证。
- Redis 容器中缓存加载锁的 128 并发 loader 次数、错误 token 保护、脚本失败降级和租约过期重抢；当前仅完成 `RedisCacheServiceRedisIT` 代码和无 Docker 快速失败验证。
- MySQL 容器中业务表与 Outbox 的真实事务提交/回滚、条件 claim 互斥和 lease 过期恢复；当前仅完成 `MqOutboxMySqlIT` 代码和无 Docker 快速失败验证。
- Feed SQL 在生产规模数据上的执行计划、rows examined、P99，以及 EXISTS 与 STRAIGHT_JOIN 在不同关注规模下的选择策略。
- `RabbitMqRemoteIT` 已验证 broker 层 confirm、return、手动 ack/redelivery、basicNack -> DLQ 和 TTL retry；`RabbitMQSender` 的 confirm/return/consumer success 竞态、补偿、消费者重启和完整业务链路仍未在真实环境验证。
- 真实 MySQL 事务提交、应用在提交后 relay 前崩溃、Outbox 租约回收以及 RabbitMQ relay 重复发布行为。
- 优雅停机期间正在执行的 HTTP 请求、异步任务和 MQ 消费是否满足 30 秒预算。
- 云 Milvus 的网络可达性、collection schema、索引、读写权限和性能未在本项目验证；当前仓库没有 Milvus 客户端依赖或业务调用链。
- Compose 凭证注入、Sentinel entrypoint 和云 RabbitMQ/本机 Redis 的混合拓扑尚未在同一部署环境端到端启动验证；当前仅完成静态检查、配置审查和已有组件测试。
- `application-prod.properties` 的混合拓扑默认值已通过资源级契约测试，但在同一进程中真实连接 Win11 MySQL/Redis、云 RabbitMQ 并启动完整业务链路仍未重新执行；云 Milvus 仍不属于本项目验证范围。
- 结构化 JSON 日志尚未在真实采集器中验证字段索引、压缩滚动、异常栈截断后的查询体验；当前只有编码器单测和配置审查证据。
- `ErrorLogRateLimiter` 当前为单实例内存限速，尚未接入 Redis 或日志平台进行跨实例全局采样；多副本场景仍需压测和故障演练。
- HTTP 请求到 MQ 消费的跨线程、跨进程完整 trace 传播尚未在真实部署环境验证，当前仅覆盖本地 MDC filter、异步装饰器和消费者 finally 清理。

## 10. 每次迭代完成标准

- [ ] 代码实现与本清单的状态一致，没有把设计文档当作实现证明。
- [ ] 新增路径有单元测试，依赖外部组件的路径有集成测试计划和失败注入场景。
- [ ] 关键失败分支有日志、指标和可追踪 ID，但不泄露凭证和完整用户内容。
- [ ] 说明性能结论的数据规模、压测工具、并发数、P50/P95/P99 和环境。
- [ ] 运行编译、测试、静态检查，并记录未完成验证项。
