# 项目数据链路说明

## 1. 文档目的

本文档基于当前仓库代码，记录项目的模块分层、核心数据流转路径、主要落库表、消息链路、缓存/锁/限流等基础设施，以及每条链路对应的具体方法。

当前项目根目录为 `xiaozhaovip-intern8`，采用 Maven 多模块结构：

- `intern-base-web`：Web 层，包含启动类、控制器、安全配置、缓存配置、拦截器、切面。
- `intern-base-service`：业务层，包含动态/评论/通知服务、分布式锁、限流、MQ 生产者、MQ 消费者、补偿任务。
- `intern-base-intf`：接口/实体/Mapper 层，包含 DTO、实体类、MyBatis Mapper 接口、Mapper XML 和 Flyway 迁移资源。

---

## 2. 总体数据链路

### 2.1 请求主链路

前端/调用方请求 → Spring Security/JWT 认证 → `PrehandleInterceptor` 请求拦截日志 → Controller 参数校验 → Service 业务处理 → Mapper/MyBatis SQL → MySQL/Redis/RabbitMQ → 返回 `ResponseDO`

### 2.2 基础设施参与位置

- **认证**：JWT 过滤器负责登录态校验。
- **日志**：`LogAspect` 对 Controller、Service 做环绕日志。
- **限流**：`RateLimiterService` 基于 Redisson `RRateLimiter` 控制发布/点赞/评论频率。
- **分布式锁**：`DistributedLockService` 基于 Redisson `RLock` 防并发重复提交。
- **缓存**：
  - Spring Cache + Caffeine：用于动态详情缓存。
  - Redis：用于未读数、MQ 幂等、任务锁、辅助缓存能力。
- **消息队列**：RabbitMQ 用于通知异步化、动态延迟归档、失败重试与死信。
- **定时补偿**：`ArchiveFallbackTask` 每天扫描补漏归档。

---

## 3. 核心表与数据对象

数据库结构由 `intern-base-intf/src/main/resources/db/migration/V1__init.sql` 和 `V2__indexes.sql` 管理，应用启动时由 Flyway 按版本执行。当前核心表如下：

### 3.1 `tui_dynamic` 动态表

- 主键：`id`，由雪花算法生成。
- 核心字段：`user_id`、`content`、`images`、`like_count`、`comment_count`、`share_count`、`create_time`、`update_time`、`status`
- 状态位：
  - `0`：正常
  - `1`：已删除
  - `2`：已归档

### 3.2 `tui_comment` 评论表

- 主键：`id`
- 核心字段：`dynamic_id`、`user_id`、`content`、`parent_id`、`reply_user_id`、`like_count`、`status`
- 状态位：
  - `0`：正常
  - `1`：已删除

### 3.3 `tui_follow` 关注关系表

- 主键：`id`
- 唯一键：`(user_id, follow_user_id)`
- 状态位：
  - `0`：已关注
  - `1`：已取消关注

### 3.4 `tui_notification` 通知表

- 主键：`id`
- 核心字段：`user_id`、`sender_id`、`type`、`content`、`target_id`、`is_read`、`create_time`
- 通知类型：
  - `1`：点赞
  - `2`：评论
  - `3`：分享
  - `4`：关注
  - `5`：系统通知
- 已读位：
  - `0`：未读
  - `1`：已读

---

## 4. 模块职责与主要方法

## 4.1 Web 层

### 启动与配置

- `vip.xiaozhao.intern.baseUtil.NaofferWebApplication`
  - 开启 Spring Boot、MyBatis Mapper 扫描、缓存、定时任务。
- `vip.xiaozhao.intern.baseUtil.config.WebApplication`
  - 注册 `PrehandleInterceptor`
  - 提供 `RestTemplate`
- `vip.xiaozhao.intern.baseUtil.config.security.SecurityConfig`
  - 注册 JWT 过滤器
  - 对 `/swagger-ui/**`、`/v3/api-docs/**`、`/actuator/**` 放行
- `vip.xiaozhao.intern.baseUtil.aspect.LogAspect`
  - 记录 Controller/Service 调用日志与耗时

### Controller 划分

#### 1）动态控制器 `DynamicController`

路由前缀：`/api/dynamic`

- `publishDynamic()`：发布动态
- `getFeed()`：获取关注流
- `getUserDynamics()`：获取某用户动态列表
- `getDynamicDetail()`：获取动态详情
- `likeDynamic()`：动态点赞
- `commentDynamic()`：动态评论计数/通知链路
- `shareDynamic()`：动态分享
- `deleteDynamic()`：删除动态
- `follow()`：关注用户
- `unfollow()`：取消关注
- `checkFollow()`：查询关注状态
- `getFollowCount()`：查询粉丝/关注数

#### 2）评论控制器 `CommentController`

路由前缀：`/api/comment`

- `getCommentList()`：查询评论列表
- `addComment()`：新增评论
- `likeComment()`：评论点赞
- `deleteComment()`：删除评论

#### 3）通知控制器 `NotificationController`

路由前缀：`/api/notification`

- `getUnreadCount()`：获取未读数
- `markAllAsRead()`：全部标记已读
- `getNotificationList()`：通知列表
- `deleteNotification()`：删除通知
- `getNotificationDetail()`：通知详情
- `sendSystemNotification()`：发送系统通知

#### 4）辅助控制器

- `HomeController.init()`：`/hello` 测试接口
- `OSSController.getUploadToken()`：当前返回 mock 上传凭证
- `UserController.*`：当前多为 mock 用户信息/统计/搜索/推荐接口，未接真实持久层

---

## 5. 业务数据链路详解

## 5.1 动态发布链路

### 请求入口

- Controller：`DynamicController.publishDynamic(userId, content, images)`
- 前置动作：
  - 调用 `RateLimiterService.tryAcquirePublish(userId)`，限制每用户每分钟最多 5 次发布

### 业务处理

- Service：`DynamicServiceImpl.saveDynamic(Long userId, String content, String images)`
- 关键步骤：
  1. 使用 `DistributedLockService.executeWithLockVoid()` 获取发布锁
  2. 使用 `SnowflakeIdGenerator.nextId()` 生成动态 ID
  3. 组装 `TuiDynamic`
  4. 调用 `TuiDynamicMapper.insert()` 写入 `tui_dynamic`
  5. 在事务 `afterCommit` 中调用 `RabbitMQSender.sendArchiveMessage()` 发送延迟归档消息

### 持久化

- Mapper 接口：`TuiDynamicMapper.insert(TuiDynamic dynamic)`
- Mapper XML：向 `tui_dynamic` 插入一条状态为 `0` 的动态记录

### 异步后续

- 发送消息：`ArchiveDynamicEvent.create(dynamicId)`
- 进入队列：`archive.delay.exchange` → 延迟队列 → 死信转发至归档处理队列

### 链路总结

`/api/dynamic/publish`
→ `RateLimiterService.tryAcquirePublish`
→ `DynamicServiceImpl.saveDynamic`
→ `DistributedLockService.executeWithLockVoid`
→ `SnowflakeIdGenerator.nextId`
→ `TuiDynamicMapper.insert`
→ `afterCommit`
→ `RabbitMQSender.sendArchiveMessage`

---

## 5.2 动态详情链路

### 请求入口

- Controller：`DynamicController.getDynamicDetail(id)`

### 业务处理

- Service：`DynamicServiceImpl.getDynamicById(Long id)`
- 特点：
  - 使用 `@Cacheable(value = "dynamic", key = "#id")`
  - 当前缓存实现是 Spring Cache + Caffeine 本地缓存
  - 若数据库中查不到，则抛出 `BusinessException`

### 持久化

- Mapper：`TuiDynamicMapper.selectById(Long id)`
- SQL：仅查询 `status = 0` 的动态

### 链路总结

`/api/dynamic/detail/{id}`
→ `DynamicServiceImpl.getDynamicById`
→ Caffeine Cache
→ `TuiDynamicMapper.selectById`

---

## 5.3 关注流 / 用户动态列表链路

### 关注流

- Controller：`DynamicController.getFeed(userId, cursor, limit)`
- Service：`DynamicServiceImpl.getFeed(Long userId, Long cursor, Integer limit)`
- Mapper：`TuiDynamicMapper.selectFeedByCursor(userId, cursor, limit)`
- SQL 特征：
  - `tui_dynamic d`
  - `INNER JOIN tui_follow f ON d.user_id = f.follow_user_id`
  - 条件：`f.user_id = 当前用户`、`f.status = 0`、`d.status = 0`
  - 分页：按 `d.id DESC` + 游标 `d.id < cursor`

### 用户动态列表

- Controller：`DynamicController.getUserDynamics(userId, cursor, limit)`
- Service：`DynamicServiceImpl.getUserDynamics(Long userId, Long cursor, Integer limit)`
- Mapper：`TuiDynamicMapper.selectByUserId(userId, cursor, limit)`

### 链路总结

- 关注流：`/api/dynamic/feed` → `DynamicServiceImpl.getFeed` → `TuiDynamicMapper.selectFeedByCursor`
- 用户动态：`/api/dynamic/user/list` → `DynamicServiceImpl.getUserDynamics` → `TuiDynamicMapper.selectByUserId`

---

## 5.4 动态点赞链路

### 请求入口

- Controller：`DynamicController.likeDynamic(userId, dynamicId)`
- 前置动作：
  - 调用 `RateLimiterService.tryAcquireLike(userId)`，限制每用户每分钟最多 30 次点赞

### 业务处理

- Service：`DynamicServiceImpl.likeDynamic(Long userId, Long dynamicId)`
- 关键步骤：
  1. 使用锁键 `lock:dynamic:like:userId:dynamicId` 防重复点赞并发
  2. `TuiDynamicMapper.selectById(dynamicId)` 校验动态存在
  3. `TuiDynamicMapper.updateLikeCount(dynamicId)` 原子自增点赞数
  4. 若点赞人不是动态作者，则在 `afterCommit` 中调用 `notificationService.sendNotification(...)`

### 异步通知链路

- `NotificationServiceImpl.sendNotification(...)`
→ `RabbitMQSender.sendNotificationMessage(...)`
→ RabbitMQ 通知队列

### 链路总结

`/api/dynamic/like`
→ `RateLimiterService.tryAcquireLike`
→ `DynamicServiceImpl.likeDynamic`
→ `TuiDynamicMapper.updateLikeCount`
→ `NotificationServiceImpl.sendNotification`
→ `RabbitMQSender.sendNotificationMessage`

---

## 5.5 动态评论链路（当前存在两条实现）

当前代码中，“评论动态”存在两条链路，含义不同，需区分：

### A. 真实评论落库链路

- 入口：`CommentController.addComment()`
- Service：`CommentServiceImpl.addComment(Long userId, Long dynamicId, String content, Long parentId, Long replyUserId)`

关键步骤：

1. 使用评论锁 `lock:dynamic:comment:userId:dynamicId`
2. `TuiDynamicMapper.selectById(dynamicId)` 校验动态存在
3. `TuiDynamicMapper.updateCommentCount(dynamicId)` 更新动态评论数
4. 组装 `TuiComment`
5. `TuiCommentMapper.insert(comment)` 写入 `tui_comment`
6. 若评论人不是动态作者，则事务提交后异步发送评论通知

链路：

`/api/comment/add`
→ `RateLimiterService.tryAcquireComment`
→ `CommentServiceImpl.addComment`
→ `TuiDynamicMapper.updateCommentCount`
→ `TuiCommentMapper.insert`
→ `NotificationServiceImpl.sendNotification`

### B. 动态域内评论计数/通知链路

- 入口：`DynamicController.commentDynamic()`
- Service：`DynamicServiceImpl.commentDynamic(Long userId, Long dynamicId, String content)`

关键步骤：

1. 获取评论锁
2. 校验动态存在
3. 仅执行 `TuiDynamicMapper.updateCommentCount(dynamicId)`
4. 发送评论通知

**说明：该链路不会向 `tui_comment` 插入评论记录，只会增加动态评论数并发送通知。**

这意味着从当前代码实现看：

- 真正的评论内容持久化：走 `CommentController.addComment`
- 仅做评论计数与通知：走 `DynamicController.commentDynamic`

这是当前项目里一个需要特别记录的实现差异。

---

## 5.6 评论列表 / 评论点赞 / 评论删除链路

### 评论列表

- Controller：`CommentController.getCommentList(dynamicId, cursor, limit)`
- Service：`CommentServiceImpl.getCommentList(dynamicId, cursor, limit)`
- Mapper：`TuiCommentMapper.selectByDynamicId(dynamicId, cursor, limit)`
- SQL：查询 `status = 0` 且 `id < cursor` 的评论，按 `id DESC` 游标分页

### 评论点赞

- Controller：`CommentController.likeComment(userId, commentId)`
- Service：`CommentServiceImpl.likeComment(userId, commentId)`
- Mapper：
  - `TuiCommentMapper.selectById(commentId)`
  - `TuiCommentMapper.updateLikeCount(commentId)`
- 后续：若不是本人给自己点赞，则异步发送通知

### 评论删除

- Controller：`CommentController.deleteComment(userId, commentId)`
- Service：`CommentServiceImpl.deleteComment(userId, commentId)`
- 逻辑：
  - 校验评论存在
  - 校验当前用户是否为评论作者
  - `TuiCommentMapper.deleteById(commentId)`，逻辑删除，`status = 1`

---

## 5.7 动态分享链路

### 请求入口

- Controller：`DynamicController.shareDynamic(userId, dynamicId)`

### 业务处理

- Service：`DynamicServiceImpl.shareDynamic(Long userId, Long dynamicId)`
- 关键步骤：
  1. 校验动态存在
  2. `TuiDynamicMapper.updateShareCount(dynamicId)` 增加分享数
  3. 若分享人不是动态作者，则事务提交后异步发送通知

### 链路总结

`/api/dynamic/share`
→ `DynamicServiceImpl.shareDynamic`
→ `TuiDynamicMapper.updateShareCount`
→ `NotificationServiceImpl.sendNotification`

---

## 5.8 动态删除链路

### 请求入口

- Controller：`DynamicController.deleteDynamic(userId, dynamicId)`

### 业务处理

- Service：`DynamicServiceImpl.deleteDynamic(Long userId, Long dynamicId)`
- 关键步骤：
  1. 查询动态
  2. 校验当前用户是否为动态作者
  3. `TuiDynamicMapper.deleteById(dynamicId)` 逻辑删除，设置 `status = 1`
  4. 使用 `@CacheEvict` 清理动态详情缓存

### 链路总结

`/api/dynamic/delete`
→ `DynamicServiceImpl.deleteDynamic`
→ `TuiDynamicMapper.deleteById`
→ `CacheEvict(dynamic)`

---

## 5.9 关注 / 取关 / 查询关系链路

### 关注

- Controller：`DynamicController.follow(userId, followUserId)`
- Service：`DynamicServiceImpl.follow(userId, followUserId)`
- 关键步骤：
  1. 禁止自己关注自己
  2. 获取关注锁 `lock:follow:userId:followUserId`
  3. `TuiFollowMapper.selectByUserAndFollow(...)` 检查是否已关注
  4. 组装 `TuiFollow`
  5. `TuiFollowMapper.insert(follow)`
  6. 提交后异步发送“关注通知”

### 取消关注

- Controller：`DynamicController.unfollow(userId, followUserId)`
- Service：`DynamicServiceImpl.unfollow(userId, followUserId)`
- Mapper：`TuiFollowMapper.deleteByUserAndFollow(userId, followUserId)`
- 注意：这里是逻辑取消关注，更新 `status = 1`

### 查询关系与统计

- `DynamicServiceImpl.isFollowing(userId, followUserId)`
  - `TuiFollowMapper.selectByUserAndFollow(...)`
- `DynamicServiceImpl.countFollowers(userId)`
  - `TuiFollowMapper.countFollowers(userId)`
- `DynamicServiceImpl.countFollowing(userId)`
  - `TuiFollowMapper.countFollowing(userId)`

---

## 5.10 通知发送、消费、未读数链路

通知模块是项目里最典型的“同步发起 + 异步落库”链路。

### 第一步：业务侧发起通知

可能的发起点：

- `DynamicServiceImpl.likeDynamic()`
- `DynamicServiceImpl.commentDynamic()`
- `DynamicServiceImpl.shareDynamic()`
- `DynamicServiceImpl.follow()`
- `CommentServiceImpl.addComment()`
- `CommentServiceImpl.likeComment()`
- `NotificationController.sendSystemNotification()`

这些方法最终统一调用：

- `NotificationServiceImpl.sendNotification(Long userId, Long senderId, Integer type, String content, String targetId)`

### 第二步：发送 RabbitMQ 消息

- `NotificationServiceImpl.sendNotification()`
  - 组装 `NotificationEvent`
  - 调用 `RabbitMQSender.sendNotificationMessage(event)`

### 第三步：MQ 消费落库

- 消费者：`NotificationConsumer.handleNotificationMessage(Message, Channel)`
- 关键步骤：
  1. 反序列化 `NotificationEvent`
  2. 用 Redis 键 `mq:idempotent:notification:{msgId}` 做幂等
  3. 构造 `TuiNotification`
  4. `TuiNotificationMapper.insert(notification)` 写入 `tui_notification`
  5. `redisUtil.incr("notification:unread:count:{userId}")` 增加未读数
  6. Ack 消息

### 第四步：失败重试 / 死信

- 基类：`AbstractMqConsumer.handleRetryOrDlq(...)`
- 规则：
  - 最大重试次数：`3`
  - 失败但未超上限：发送到 retry queue
  - 超过上限：`basicNack(..., requeue=false)` 进入死信链路

### 第五步：未读数查询

- Controller：`NotificationController.getUnreadCount(userId)`
- 查询逻辑：
  1. 优先查 Redis：`notification:unread:count:{userId}`
  2. 若 Redis 为 `0`，则降级查库 `notificationMapper.countUnread(userId)`
  3. 同时返回 `notification:last:refresh:{userId}`

### 第六步：全部已读

- Controller：`NotificationController.markAllAsRead(userId)`
- 操作：
  1. 删除 Redis 未读数键
  2. 写入最后刷新时间键
  3. `notificationMapper.updateIsRead(userId)` 批量更新数据库

### 第七步：通知列表与详情

- `NotificationController.getNotificationList()`
  - 直接调用 `notificationMapper.selectByUserId(userId, cursor, limit)`
- `NotificationController.getNotificationDetail(id)`
  - 直接调用 `notificationMapper.selectById(id)`
- `NotificationController.deleteNotification(notificationId)`
  - 直接调用 `notificationMapper.deleteById(notificationId)`

### 链路总结

业务 Service
→ `NotificationServiceImpl.sendNotification`
→ `RabbitMQSender.sendNotificationMessage`
→ `NotificationConsumer.handleNotificationMessage`
→ `TuiNotificationMapper.insert`
→ Redis 未读数累加
→ `NotificationController` 查询/已读/删除

---

## 5.11 动态归档链路

### 主归档链路：延迟消息

动态发布后，`DynamicServiceImpl.saveDynamic()` 在事务提交后发送归档延迟消息。

#### 发送端

- `RabbitMQSender.sendArchiveMessage(ArchiveDynamicEvent event)`
- 交换机：`archive.delay.exchange`
- Routing Key：`archive.delay.publish`

#### 队列设计

- 延迟队列：`archive.delay.queue`
- TTL：`7 * 24 * 60 * 60 * 1000` 毫秒
- TTL 到期后进入：`archive.exchange`
- 最终消费队列：`archive.queue`

#### 消费端

- `ArchiveConsumer.handleArchiveMessage(Message, Channel)`
- 关键步骤：
  1. 反序列化 `ArchiveDynamicEvent`
  2. Redis 键 `mq:idempotent:archive:{msgId}` 做幂等
  3. `TuiDynamicMapper.archiveById(dynamicId)` 将动态状态改为 `2`
  4. Ack 消息

### 补偿链路：定时任务

- 任务类：`ArchiveFallbackTask`
- 执行时间：每天 `02:00`
- 任务目的：防止 MQ 延迟归档失败，做兜底归档

#### 执行步骤

1. 使用 Redis `setnx(task:archive:lock)` 获取任务锁
2. 查询 `create_time` 早于 7 天前、且 `status = 0` 的动态 ID 列表
3. 分批调用 `TuiDynamicMapper.archiveById(id)`
4. 释放任务锁

### 链路总结

发布动态
→ `afterCommit`
→ `RabbitMQSender.sendArchiveMessage`
→ 延迟队列 TTL 7 天
→ `ArchiveConsumer.handleArchiveMessage`
→ `TuiDynamicMapper.archiveById`

补偿兜底：

`ArchiveFallbackTask` → `TuiDynamicMapper.selectUnarchivedBefore` → `TuiDynamicMapper.archiveById`

---

## 6. Mapper 方法与表操作对照

## 6.1 `TuiDynamicMapper`

- `insert(TuiDynamic dynamic)`：新增动态
- `selectById(Long id)`：按 ID 查动态
- `selectFeedByCursor(Long userId, Long cursor, Integer limit)`：查询关注流
- `selectByUserId(Long userId, Long cursor, Integer limit)`：查询用户动态
- `updateLikeCount(Long id)`：动态点赞数 +1
- `updateCommentCount(Long id)`：动态评论数 +1
- `updateShareCount(Long id)`：动态分享数 +1
- `deleteById(Long id)`：动态逻辑删除
- `archiveById(Long id)`：动态归档
- `selectUnarchivedBefore(Date beforeTime, Integer limit)`：查询待归档动态 ID

## 6.2 `TuiCommentMapper`

- `insert(TuiComment comment)`：新增评论
- `selectById(Long id)`：按 ID 查评论
- `selectByDynamicId(Long dynamicId, Long cursor, Integer limit)`：查询某动态评论列表
- `updateLikeCount(Long id)`：评论点赞数 +1
- `deleteById(Long id)`：评论逻辑删除
- `countByDynamicId(Long dynamicId)`：统计评论数

## 6.3 `TuiFollowMapper`

- `insert(TuiFollow follow)`：新增关注关系
- `deleteByUserAndFollow(Long userId, Long followUserId)`：逻辑取消关注
- `selectByUserAndFollow(Long userId, Long followUserId)`：查询关注关系
- `selectFollowUserIds(Long userId)`：查询当前用户关注的用户 ID 列表
- `countFollowers(Long userId)`：统计粉丝数
- `countFollowing(Long userId)`：统计关注数

## 6.4 `TuiNotificationMapper`

- `insert(TuiNotification notification)`：新增通知
- `selectById(Long id)`：通知详情
- `selectByUserId(Long userId, Long cursor, Integer limit)`：通知列表
- `updateIsRead(Long userId)`：批量标记已读
- `deleteByTargetId(String targetId)`：按业务目标删除通知
- `deleteById(Long id)`：按通知 ID 删除
- `countUnread(Long userId)`：统计未读数

---

## 7. 锁、限流、缓存、MQ 方法清单

## 7.1 限流 `RateLimiterService`

- `tryAcquirePublish(Long userId)`：发布限流，5 次/分钟
- `tryAcquireLike(Long userId)`：点赞限流，30 次/分钟
- `tryAcquireComment(Long userId)`：评论限流，20 次/分钟
- `tryAcquire(String limiterKey, long rate, long interval, RateIntervalUnit intervalUnit)`：通用限流方法

## 7.2 分布式锁 `DistributedLockService`

- `tryLock(String lockKey)`：获取锁
- `tryLock(String lockKey, long waitTime, long leaseTime)`：自定义等待与租期
- `unlock(RLock lock)`：释放锁
- `executeWithLock(String lockKey, LockTask<T> task)`：带返回值执行
- `executeWithLockVoid(String lockKey, Runnable task)`：无返回值执行

当前常见锁键：

- `lock:dynamic:publish:{userId}`
- `lock:dynamic:like:{userId}:{dynamicId}`
- `lock:dynamic:comment:{userId}:{dynamicId}`
- `lock:follow:{userId}:{followUserId}`

## 7.3 缓存

### Spring Cache / Caffeine

- `DynamicServiceImpl.getDynamicById()`：`@Cacheable`
- `DynamicServiceImpl.deleteDynamic()`：`@CacheEvict`
- `CacheConfig.caffeineCacheManager()`：10 分钟过期，本地缓存最大 10000

### 自定义 Redis 二级缓存能力

项目中还存在 `RedisCacheService`，提供：

- `get(key, dbLoader)`
- `get(key, dbLoader, expireSeconds)`
- `evict(key)`
- `clearLocalCache()`

其实现包含：

- Caffeine 本地缓存
- Redis 缓存
- `setnx` 防缓存击穿
- 随机过期时间防雪崩

**但从当前主业务调用链看，`RedisCacheService` 尚未成为动态/评论/通知主流程的默认读取入口。**

## 7.4 MQ 发送

- `RabbitMQSender.sendNotificationMessage(NotificationEvent event)`
- `RabbitMQSender.sendArchiveMessage(ArchiveDynamicEvent event)`
- `RabbitMQSender.sendToRetryQueue(String exchange, String routingKey, BaseMqEvent event, int retryCount)`

## 7.5 MQ 消费公共能力 `AbstractMqConsumer`

- `ackIfDuplicate(...)`：幂等拦截
- `markProcessed(...)`：记录已处理消息
- `handleRetryOrDlq(...)`：重试/死信分流
- `ack(...)`：手动确认

---

## 8. 当前实现中的特别说明

### 8.1 通知读写链路并不完全统一

- 通知**发送**统一走 `NotificationServiceImpl` + RabbitMQ
- 通知**查询/已读/删除**主要在 `NotificationController` 中直接使用 `TuiNotificationMapper` 和 `RedisUtil`

也就是说，通知模块当前是：

- 写入：异步服务化
- 查询：控制器直连 Mapper

### 8.2 评论功能有“双入口”

- `CommentController.addComment`：真实评论落库
- `DynamicController.commentDynamic`：只做评论计数与通知，不插入评论表

如果后续继续演进，建议统一评论写入口，避免业务语义重复。

### 8.3 用户与 OSS 接口当前偏演示/占位

- `UserController` 里的接口大多是 mock 返回
- `OSSController.getUploadToken()` 当前也是 mock 返回临时凭证

这些接口暂未形成完整的数据落库链路。

### 8.4 动态详情缓存是本地缓存优先

当前动态详情读取使用 Spring Cache + Caffeine，本地命中速度快，但未直接展示跨实例共享缓存能力。

---

## 9. 一页式总览

### 9.1 动态域

- 发布：Controller → 限流 → 锁 → `tui_dynamic` → 延迟归档 MQ
- 列表：Controller → Service → `tui_dynamic` / `tui_follow`
- 详情：Controller → Caffeine Cache → `tui_dynamic`
- 点赞/分享：Controller → 锁/校验 → 计数更新 → 通知 MQ
- 删除：Controller → 权限校验 → 逻辑删除 → 清缓存

### 9.2 评论域

- 新增评论：Controller → 限流 → 锁 → 更新动态评论数 → 插入 `tui_comment` → 通知 MQ
- 评论列表：Controller → Service → `tui_comment`
- 评论点赞：Controller → Service → 评论计数更新 → 通知 MQ
- 评论删除：Controller → Service → 权限校验 → 逻辑删除

### 9.3 关注域

- 关注：Controller → 锁 → 插入 `tui_follow` → 通知 MQ
- 取关：Controller → Service → 逻辑删除关注关系
- 关注流：`tui_follow` 关联 `tui_dynamic` 反查动态

### 9.4 通知域

- 发送：业务 Service → `NotificationServiceImpl` → RabbitMQ
- 落库：`NotificationConsumer` → `tui_notification`
- 未读数：Redis 优先，DB 兜底
- 已读：清 Redis + 更新 DB

### 9.5 归档域

- 主流程：发布后发延迟 MQ，7 天后归档
- 兜底流程：每天凌晨 2 点定时扫描补归档

---

## 10. 结论

当前项目的核心数据链路可以概括为：

- **同步主写**：动态、评论、关注先同步入库
- **异步副作用**：通知、延迟归档走 RabbitMQ
- **并发保护**：限流 + 分布式锁双层保护
- **读优化**：动态详情使用 Caffeine，本地快速命中；通知未读数使用 Redis
- **补偿机制**：动态归档有定时任务兜底

从完整度上看，当前真正形成闭环的数据域主要有：

- 动态
- 评论
- 关注
- 通知
- 归档

而用户中心、OSS 上传等能力目前仍偏占位实现。
