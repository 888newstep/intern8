# 推友圈 · 社交 Feed 性能工程项目（实习)

[![CI](https://github.com/888newstep/intern8/actions/workflows/ci.yml/badge.svg)](https://github.com/888newstep/intern8/actions/workflows/ci.yml)
[![JDK 17](https://img.shields.io/badge/JDK-17-blue.svg)](https://adoptium.net/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3-green.svg)](https://spring.io/projects/spring-boot)
[![License](https://img.shields.io/badge/License-Apache%202.0-orange.svg)](LICENSE)

社交产品「推友圈 P5」后端性能工程实习项目：关注关系驱动的**拉模式信息流**、未读通知与**幂等写入**，按真实业务量级（活跃用户约 1000、动态 5 万条）设计并验证容量特性，不吹高并发、只做可解释的性能工程。

## 亮点数据

| 指标 | 结果 |
|------|------|
| 只读三接口（详情/Feed/未读通知） | 20 并发共 **1301 RPS**、0 错误、P95 均 < 24ms |
| Feed 查询优化 | 250 并发 **28 → 561 RPS**（约 9 倍），P95 **4s → 241ms** |
| 5 万动态量级单接口基线 | Feed 20 并发 P95 **58ms**（SQL 层健康，归因到环境抖动） |
| 写入幂等 | 固定幂等键 1.2 万+ 次并发重放**仅 1 次落库**，Outbox 0 死信、DLQ 0 |
| 测试 | **116 项**单元/集成测试全绿（含 RabbitMqRemoteIT、Flyway 迁移校验） |

## 技术栈

Java 17 / Spring Boot / MySQL 8（覆盖索引 + 快路径）/ Redis / RabbitMQ / JMeter / Prometheus / Grafana / GitHub Actions CI / CodeQL

## 架构要点

- **拉模式信息流**：`FORCE INDEX` 动态倒序候选扫描 + `STRAIGHT_JOIN` 关注探测 + 批量 `selectByIds`，避免"按关注作者展开全量排序"的 N+1 与慢查询
- **缓存防击穿**：Caffeine 本地缓存 + Redis 分布式缓存 + 并发回源保护（锁后二次校验）
- **消息最终一致性**：Outbox 模式 + 幂等写入 + 延迟队列归档 + DLQ 兜底，0 死信闭环
- **可观测**：Trace 链路 + 指标，性能问题归因到 SQL 层而非笼统"加缓存"

## 快速开始

1. 本地启动 MySQL 8 与 Redis（端口 3306 / 6379），按 `deploy/.env.example` 配置连接
2. `mvn test` 运行 116 项测试
3. 压测：`deploy/jmeter/performance-test.jmx`（JMeter 5.x 导入即可，参数见报告）

## 压测与报告

- [`PERFORMANCE_TEST_REPORT.md`](PERFORMANCE_TEST_REPORT.md) — 08-24 只读 + 写入幂等基线验证
- [`PERFORMANCE_TEST_REPORT_20260825.md`](PERFORMANCE_TEST_REPORT_20260825.md) — 08-25 四档容量阶梯 + Outbox/DLQ 全链路验证
- [`PERFORMANCE_TEST_REPORT_20260826.md`](PERFORMANCE_TEST_REPORT_20260826.md) — 08-26 单接口低并发基线，归因修正高 P95 来源

## License

[Apache License 2.0](LICENSE)