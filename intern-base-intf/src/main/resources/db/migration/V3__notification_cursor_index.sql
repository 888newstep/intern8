-- Align notification cursor pagination with the actual WHERE and ORDER BY clauses.
-- The existing unread index remains useful for count/read operations.
CREATE INDEX `idx_notification_user_id`
    ON `tui_notification` (`user_id`, `id` DESC);
