-- Index optimization script
-- For improving query performance, especially cursor pagination and JOIN queries

-- ==================== tui_follow covering index for Feed JOIN ====================
-- Feed query: INNER JOIN tui_follow f ON d.user_id = f.follow_user_id
-- WHERE f.user_id = #{userId} AND f.status = 0
-- Covering index avoids table lookup for the follow side of the JOIN
CREATE INDEX idx_follow_covering ON tui_follow (user_id, status, follow_user_id);

-- ==================== tui_dynamic indexes ====================
-- Feed query: WHERE d.status = 0 AND d.id < #{cursor} ORDER BY d.id DESC
-- Combined with JOIN condition d.user_id = f.follow_user_id
CREATE INDEX idx_dynamic_status_id ON tui_dynamic (status, id DESC);

-- User dynamics list: cursor pagination needs user_id + status + id
CREATE INDEX idx_user_dynamics ON tui_dynamic (user_id, status, id DESC);

-- ==================== tui_notification indexes ====================
-- Notification list: user_id + is_read + create_time
CREATE INDEX idx_notification_list ON tui_notification (user_id, is_read, create_time DESC);

-- ==================== tui_comment indexes ====================
-- Comment list: cursor pagination needs dynamic_id + status + id
CREATE INDEX idx_comment_list ON tui_comment (dynamic_id, status, id DESC);

-- ==================== tui_like indexes ====================
-- Like lookup: user_id + target_id + target_type for dedup checks
CREATE INDEX idx_like_lookup ON tui_like (user_id, target_id, target_type);

-- ==================== mq_message_status indexes ====================
-- Compensation task: status + retry_count for failed message pickup
CREATE INDEX idx_mq_msg_status_retry ON mq_message_status (status, retry_count, update_time);

-- ==================== Notes ====================
-- 1. All DESC indexes match the ORDER BY direction used in queries
-- 2. Covering indexes follow the leftmost prefix principle
-- 3. Feed query benefits most from idx_follow_covering + idx_dynamic_status_id
-- 4. Run during low-traffic periods for production databases