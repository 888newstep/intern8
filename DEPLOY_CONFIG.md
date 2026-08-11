# 部署配置说明

本文档说明项目的环境变量配置和部署方式。

## 环境变量配置

项目使用环境变量来管理敏感配置，避免将密钥硬编码在代码库中。Docker Compose 对密码、JWT 密钥和 RabbitMQ 集群 cookie 使用必填变量校验，未配置时会在启动前失败，不再提供弱默认口令。

### 快速开始

1. 复制 .env.example 为 .env
   `ash
   cp .env.example .env
   `

2. 编辑 .env 文件，填入实际的配置值

3. 启动服务
   `ash
   docker-compose up -d
   `

### 环境变量说明

#### MySQL 配置

- MYSQL_HOST: MySQL 主机地址（默认：127.0.0.1）
- MYSQL_PORT: MySQL 端口（默认：3306）
- MYSQL_DATABASE: 数据库名称（默认：xiao）
- MYSQL_USERNAME: 数据库用户名（无默认值，必填）
- MYSQL_PASSWORD: 数据库密码（必填）
- MYSQL_ROOT_PASSWORD: MySQL root 密码（必填，仅 Docker Compose 初始化数据库时使用）

#### Redis 配置

- REDIS_MODE: Redis 模式（single/sentinel/cluster，默认：single）
- REDIS_HOST: Redis 主机地址（默认：127.0.0.1）
- REDIS_PORT: Redis 端口（默认：6379）
- REDIS_AUTH: Redis 密码（本机 Redis 无认证时可为空；Compose/生产环境建议必填）

Sentinel 模式额外配置：
- REDIS_SENTINEL_MASTER: Sentinel 主节点名称（默认：mymaster）
- REDIS_SENTINEL_NODES: Sentinel 节点列表，逗号分隔

Cluster 模式额外配置：
- REDIS_CLUSTER_NODES: Cluster 节点列表，逗号分隔

#### RabbitMQ 配置

- RABBITMQ_HOST: RabbitMQ 主机地址（当前 Win11 开发环境填写云服务器地址，必填）
- RABBITMQ_PORT: RabbitMQ 端口（默认：5672）
- RABBITMQ_USERNAME: RabbitMQ 用户名（必填）
- RABBITMQ_PASSWORD: RabbitMQ 密码（必填）
- RABBITMQ_VHOST: RabbitMQ virtual host（默认：/）
- RABBITMQ_ERLANG_COOKIE: Docker Compose RabbitMQ 集群 cookie（Compose 必填，云服务器现有集群无需写入本仓库）

#### COS 对象存储配置

- COS_ACCESS_KEY_ID: 腾讯云 COS AccessKeyId（必填）
- COS_ACCESS_KEY_SECRET: 腾讯云 COS AccessKeySecret（必填）
- COS_BUCKET: 存储桶名称（默认：newstep-1331140642）
- COS_HOSTNAME: 存储桶访问域名
- COS_REGION: 地域（默认：ap-guangzhou）

#### JWT 配置

- JWT_SECRET: JWT 签名密钥（必填，建议至少 32 字符）

#### 其他配置

- LOGIN_TIME_KEY: 登录时间签名密钥（必填）
- SNOWFLAKE_WORKER_ID: 雪花算法 WorkerID（默认：0）
- GRAFANA_ADMIN_USER: Grafana 管理员用户名（默认：admin）
- GRAFANA_ADMIN_PASSWORD: Grafana 管理员密码（Compose 必填）

## 环境区分

### 开发环境（dev）

- 使用 application.properties
- Redis 使用单机模式
- Win11 本机提供 MySQL 和 Redis；RabbitMQ 通过 `RABBITMQ_HOST` 连接云服务器

### 生产环境（prod）

- 使用 `application-prod.properties`，并通过 `SPRING_PROFILES_ACTIVE=prod` 激活。
- 当前混合拓扑：Win11 本机提供 MySQL 和 Redis，默认使用 `MYSQL_HOST=127.0.0.1`、`REDIS_MODE=single`、`REDIS_HOST=127.0.0.1`；云 RabbitMQ 通过必填的 `RABBITMQ_HOST` 和可选的 `RABBITMQ_PORT` 连接。
- 只有在真实 Sentinel/Cluster 拓扑已部署时，才设置 `REDIS_MODE=sentinel/cluster` 及对应节点变量；不能把容器节点名当作本机或云环境默认值。
- 所有敏感配置必须通过环境变量注入；Milvus 不属于本项目的启动依赖或验证链路。

## Docker Compose 部署

Compose 是独立的全容器演示拓扑，不代表当前 Win11 本机 MySQL/Redis 与云 RabbitMQ 的验证环境。Compose 会显式覆盖 `MYSQL_HOST=mysql`、Redis Sentinel 节点和 `RABBITMQ_HOST=rabbitmq-1`，因此不要把这些容器地址复制到 `application-prod.properties` 的默认值中。

### 启动所有服务

`ash
docker-compose up -d
`

启动前建议执行 `docker compose config --quiet`。该命令会校验必填环境变量；不要把真实 `.env` 提交到代码库。

### 查看服务状态

`ash
docker-compose ps
`

### 查看日志

`ash
docker-compose logs -f app
`

### 停止服务

`ash
docker-compose down
`

## 服务端口

- 应用服务：8080
- MySQL：3306
- Redis：6379（单机）/ 26379-26381（Sentinel）
- RabbitMQ：5672（AMQP）/ 15672（管理界面）
- Prometheus：9090
- Grafana：3000

## 安全建议

1. 不要将 .env 文件提交到代码库
2. 生产环境必须使用强随机密码；Compose 不再为敏感变量提供默认密码
3. JWT_SECRET 建议使用强随机字符串
4. 定期轮换密钥和密码
5. 使用 HTTPS 保护传输层安全

## 故障排查

### 应用无法连接数据库

- 检查 MySQL 服务是否启动
- 检查环境变量是否正确配置
- 检查网络连接和防火墙设置

### Redis 连接失败

- 检查 Redis 模式配置是否正确
- 检查 Redis 密码是否正确
- Sentinel 模式下检查节点列表是否正确

### RabbitMQ 连接失败

- 检查 RabbitMQ 服务是否启动
- 检查用户名密码是否正确
- 集群模式下检查节点配置
