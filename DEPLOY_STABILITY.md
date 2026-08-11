# 部署稳定性指南

## 1. 健康检查配置

### 1.1 应用健康检查
应用已集成 Spring Boot Actuator，提供以下健康检查端点：

- /actuator/health - 应用健康状态
- /actuator/info - 应用信息
- /actuator/metrics - 性能指标
- /actuator/prometheus - Prometheus 指标

### 1.2 Docker Compose 健康检查
所有服务已配置健康检查：

`yaml
healthcheck:
  test: ["CMD", "curl", "-f", "http://localhost:8080/actuator/health"]
  interval: 30s
  timeout: 10s
  retries: 3
  start_period: 40s
`

## 2. 启动顺序与依赖

### 2.1 推荐启动顺序
1. **基础设施层**
   - MySQL（数据库）
   - Redis Sentinel（缓存）
   - RabbitMQ Cluster（消息队列）

2. **监控层**
   - Prometheus（指标收集）
   - Grafana（可视化）

3. **应用层**
   - intern-base-web（业务应用）

### 2.2 依赖关系
`
应用启动依赖：
├── MySQL（必需）
├── Redis（必需）
├── RabbitMQ（必需）
└── Prometheus/Grafana（可选）
`

### 2.3 启动命令
`ash
# 启动所有服务
docker-compose up -d

# 按顺序启动
docker-compose up -d mysql redis-master redis-sentinel-1 rabbitmq-1
docker-compose up -d prometheus grafana
docker-compose up -d app
`

## 3. 故障演练

### 3.1 Redis Sentinel 故障切换
**场景**：Redis Master 节点故障

**演练步骤**：
1. 停止 Redis Master
   `ash
   docker-compose stop redis-master
   `

2. 观察 Sentinel 自动故障切换
   `ash
   docker-compose logs redis-sentinel-1 | grep "failover"
   `

3. 验证应用自动重连
   `ash
   curl http://localhost:8080/actuator/health
   `

4. 恢复 Master 节点
   `ash
   docker-compose start redis-master
   `

**预期结果**：
- Sentinel 在 5-10 秒内完成故障切换
- 应用自动重连到新的 Master
- 缓存服务不中断

### 3.2 RabbitMQ 集群故障恢复
**场景**：RabbitMQ 节点故障

**演练步骤**：
1. 停止一个 RabbitMQ 节点
   `ash
   docker-compose stop rabbitmq-2
   `

2. 验证集群状态
   `ash
   docker-compose exec rabbitmq-1 rabbitmqctl cluster_status
   `

3. 验证消息投递
   - 发布动态，验证通知正常发送
   - 检查消息状态表

4. 恢复节点
   `ash
   docker-compose start rabbitmq-2
   `

**预期结果**：
- 集群自动剔除故障节点
- 消息投递不中断
- 恢复后节点自动加入集群

### 3.3 MySQL 主从切换
**场景**：MySQL 主库故障（需要手动切换）

**演练步骤**：
1. 停止 MySQL 主库
   `ash
   docker-compose stop mysql
   `

2. 提升从库为主库（需要手动配置）
   `sql
   STOP SLAVE;
   RESET MASTER;
   `

3. 更新应用配置
   `properties
   spring.datasource.url=jdbc:mysql://new-master:3306/xiao
   `

4. 重启应用
   `ash
   docker-compose restart app
   `

**预期结果**：
- 应用自动重连到新的主库
- 数据一致性得到保证

## 4. 监控告警

### 4.1 关键指标监控
- **应用层**
  - JVM 内存使用率 > 80%
  - GC 频率异常
  - 线程池使用率 > 90%

- **缓存层**
  - Redis 内存使用率 > 70%
  - 缓存命中率 < 80%
  - 连接池使用率 > 80%

- **消息队列**
  - 队列堆积 > 1000 条
  - 消费者延迟 > 100 条/秒
  - 死信队列消息 > 10 条

- **数据库**
  - 慢查询 > 1 秒
  - 连接池使用率 > 80%
  - 主从延迟 > 5 秒

### 4.2 告警配置
在 Grafana 中配置告警规则：

`yaml
# 示例告警规则
- alert: HighMemoryUsage
  expr: jvm_memory_used_bytes / jvm_memory_max_bytes > 0.8
  for: 5m
  labels:
    severity: warning
  annotations:
    summary: "High memory usage detected"

- alert: LowCacheHitRate
  expr: cache_hit_rate < 0.8
  for: 10m
  labels:
    severity: warning
  annotations:
    summary: "Cache hit rate is too low"
`

## 5. 滚动发布

### 5.1 滚动发布策略
使用 Docker Compose 实现滚动发布：

`ash
# 1. 启动新版本（不删除旧版本）
docker-compose up -d --no-deps --build app-new

# 2. 健康检查
curl http://localhost:8081/actuator/health

# 3. 切换流量（通过负载均衡）
# 更新 Nginx 配置指向新版本

# 4. 停止旧版本
docker-compose stop app-old

# 5. 清理旧版本
docker-compose rm -f app-old
`

### 5.2 回滚策略
`ash
# 快速回滚到上一版本
docker-compose up -d --no-deps app-old
docker-compose stop app-new
`

## 6. 备份与恢复

### 6.1 数据库备份
`ash
# 每日自动备份
docker-compose exec mysql mysqldump -u root -p xiao > backup_.sql

# 恢复备份
docker-compose exec -T mysql mysql -u root -p xiao < backup_20260807.sql
`

### 6.2 Redis 备份
`ash
# 触发 RDB 快照
docker-compose exec redis-master redis-cli BGSAVE

# 复制备份文件
docker cp redis-master:/data/dump.rdb ./backup/
`

### 6.3 RabbitMQ 备份
`ash
# 导出定义
docker-compose exec rabbitmq-1 rabbitmqctl export_definitions > definitions.json

# 导入定义
docker-compose exec -T rabbitmq-1 rabbitmqctl import_definitions < definitions.json
`

## 7. 性能调优

### 7.1 JVM 参数优化
`ash
JAVA_OPTS="-Xms512m -Xmx1024m \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+HeapDumpOnOutOfMemoryError \
  -XX:HeapDumpPath=/logs/heapdump.hprof"
`

### 7.2 数据库连接池优化
`properties
# Druid 连接池配置
spring.datasource.druid.initial-size=10
spring.datasource.druid.min-idle=10
spring.datasource.druid.max-active=50
spring.datasource.druid.max-wait=60000
`

### 7.3 Redis 连接池优化
`properties
# Redisson 连接池配置
redis.pool.max-active=50
redis.pool.max-idle=10
redis.pool.min-idle=5
redis.pool.max-wait=3000
`

## 8. 故障排查清单

### 8.1 应用无法启动
- [ ] 检查 MySQL 是否正常运行
- [ ] 检查 Redis 是否正常运行
- [ ] 检查 RabbitMQ 是否正常运行
- [ ] 检查数据库连接配置
- [ ] 检查应用日志

### 8.2 缓存失效
- [ ] 检查 Redis 连接状态
- [ ] 检查缓存 Key 是否过期
- [ ] 检查缓存命中率
- [ ] 检查 Redis 内存使用率

### 8.3 消息堆积
- [ ] 检查消费者是否正常运行
- [ ] 检查消息处理速度
- [ ] 检查死信队列
- [ ] 检查 RabbitMQ 集群状态

### 8.4 性能下降
- [ ] 检查 JVM 内存和 GC
- [ ] 检查数据库慢查询
- [ ] 检查 Redis 响应时间
- [ ] 检查网络延迟

## 9. 运维自动化

### 9.1 自动化脚本
`ash
#!/bin/bash
# deploy.sh - 自动化部署脚本

# 1. 备份数据库
./backup_db.sh

# 2. 构建新版本
docker-compose build app

# 3. 滚动发布
./rolling_update.sh

# 4. 健康检查
./health_check.sh

# 5. 清理旧版本
./cleanup.sh
`

### 9.2 监控自动化
- 使用 Prometheus Alertmanager 自动告警
- 使用 Grafana 自动可视化
- 使用 ELK 自动日志分析

## 10. 最佳实践

1. **定期演练**：每月进行一次故障演练
2. **监控覆盖**：确保所有关键指标都有监控
3. **备份验证**：定期验证备份可恢复性
4. **文档更新**：及时更新部署文档
5. **性能基线**：建立性能基线，及时发现性能退化
