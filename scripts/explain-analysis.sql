-- EXPLAIN ????
-- ?????????????? MySQL ?????

-- ==================== ?????? ====================
-- ???type=const, key=PRIMARY
EXPLAIN SELECT id, user_id, content, images, like_count, comment_count, share_count, create_time, update_time, status
FROM tui_dynamic
WHERE id = 12345 AND status = 0;

-- ==================== Feed ??????? ====================
-- ????? idx_feed_query ? idx_user_dynamics
EXPLAIN SELECT d.id, d.user_id, d.content, d.images, d.like_count, d.comment_count, d.share_count, d.create_time, d.update_time, d.status
FROM tui_dynamic d
INNER JOIN tui_follow f ON d.user_id = f.follow_user_id
WHERE f.user_id = 100 AND f.status = 0 AND d.status = 0 AND d.id < 999999
ORDER BY d.id DESC
LIMIT 20;

-- ==================== ?????? ====================
-- ????? idx_user_dynamics
EXPLAIN SELECT id, user_id, content, images, like_count, comment_count, share_count, create_time, update_time, status
FROM tui_dynamic
WHERE user_id = 100 AND status = 0 AND id < 999999
ORDER BY id DESC
LIMIT 20;

-- ==================== ?????? ====================
-- ????? idx_notification_list
EXPLAIN SELECT id, user_id, sender_id, type, content, target_id, is_read, create_time
FROM tui_notification
WHERE user_id = 100 AND id < 999999
ORDER BY id DESC
LIMIT 20;

-- ==================== ????? ====================
-- ????? idx_notification_list ?????
EXPLAIN SELECT COUNT(*) FROM tui_notification WHERE user_id = 100 AND is_read = 0;

-- ==================== ?????? ====================
-- ????? idx_comment_list
EXPLAIN SELECT id, dynamic_id, user_id, content, parent_id, reply_user_id, like_count, status, create_time
FROM tui_comment
WHERE dynamic_id = 12345 AND status = 0 AND id < 999999
ORDER BY id DESC
LIMIT 20;

-- ==================== ?????? ====================
-- ????? uk_user_target_type ????
EXPLAIN SELECT id, user_id, target_id, target_type, status, create_time, update_time
FROM tui_like
WHERE user_id = 100 AND target_id = 12345 AND target_type = 1 AND status = 0;

-- ==================== ?????? ====================
-- ????? uk_user_follow ????
EXPLAIN SELECT id, user_id, follow_user_id, create_time, status
FROM tui_follow
WHERE user_id = 100 AND follow_user_id = 200 AND status = 0;

-- ==================== ?????? ====================
-- type ??
--   const: ????????????
--   ref: ???????
--   range: ????
--   index: ?????
--   ALL: ?????????
--
-- key ??
--   ?????????
--   NULL ????????
--
-- rows ??
--   ???????????
--
-- Extra ??
--   Using index: ??????????
--   Using where: ????? WHERE ??
--   Using filesort: ??????????????
