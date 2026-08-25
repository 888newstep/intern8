-- Query-path indexes for cursor pagination and feed joins.
-- Base indexes and uniqueness constraints are created by V1__init.sql.

CREATE INDEX `idx_follow_covering`
    ON `tui_follow` (`user_id`, `status`, `follow_user_id`);

CREATE INDEX `idx_dynamic_status_id`
    ON `tui_dynamic` (`status`, `id` DESC, `user_id`);

CREATE INDEX `idx_user_dynamics`
    ON `tui_dynamic` (`user_id`, `status`, `id` DESC);

CREATE INDEX `idx_notification_list`
    ON `tui_notification` (`user_id`, `is_read`, `create_time` DESC);

CREATE INDEX `idx_comment_list`
    ON `tui_comment` (`dynamic_id`, `status`, `id` DESC);
