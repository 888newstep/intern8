# 慢查询分析报告

## 分析时间
2026-08-07

## 分析工具
MySQL EXPLAIN + 代码审查

## 关键查询分析

### 1. Feed 查询（高频）
`sql
SELECT * FROM tui_dynamic 
WHERE user_id IN (SELECT follow_user_id FROM tui_follow WHERE user_id = ? AND status = 0)
AND status = 0 
AND id < ? 
ORDER BY id DESC 
LIMIT ?
`

**问题**：
- 子查询可能导致性能问题
- 缺少合适的联合索引

**优化方案**：
- 已添加索引：idx_feed_query (user_id, status, create_time DESC)
- 建议改为 JOIN 查询而不是子查询
- 考虑使用应用层缓存减少查询频率

### 2. 用户动态列表（高频）
`sql
SELECT * FROM tui_dynamic 
WHERE user_id = ? 
AND status = 0 
AND id < ? 
ORDER BY id DESC 
LIMIT ?
`

**问题**：
- 游标分页需要合适的索引

**优化方案**：
- 已添加索引：idx_user_dynamics (user_id, status, id DESC)
- 该索引可以完美覆盖此查询

### 3. 通知列表查询（高频）
`sql
SELECT * FROM tui_notification 
WHERE user_id = ? 
AND is_read = ? 
ORDER BY create_time DESC 
LIMIT ?
`

**问题**：
- 缺少包含排序字段的联合索引

**优化方案**：
- 已添加索引：idx_notification_list (user_id, is_read, create_time DESC)

### 4. 评论列表查询（高频）
`sql
SELECT * FROM tui_comment 
WHERE dynamic_id = ? 
AND status = 0 
AND id < ? 
ORDER BY id DESC 
LIMIT ?
`

**问题**：
- 游标分页需要合适的索引

**优化方案**：
- 已添加索引：idx_comment_list (dynamic_id, status, id DESC)

## 索引维护建议

1. **定期分析慢查询**
   - 开启 MySQL 慢查询日志
   - 定期使用 EXPLAIN 分析关键查询
   - 监控索引使用情况

2. **索引维护**
   - 定期执行 OPTIMIZE TABLE
   - 监控索引碎片率
   - 根据查询模式调整索引

3. **查询优化**
   - 避免 SELECT *，只查询需要的字段
   - 使用 LIMIT 限制返回结果数量
   - 合理使用缓存减少数据库压力

## 预期效果

- Feed 查询性能提升 50%+
- 通知列表查询性能提升 30%+
- 评论列表查询性能提升 40%+
- 整体数据库负载降低 20%+
