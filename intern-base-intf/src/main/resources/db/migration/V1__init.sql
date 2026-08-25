-- Canonical schema baseline for the application.
--
-- This migration intentionally does not use IF NOT EXISTS. A clean database
-- must be created by Flyway, and a partially initialized database must fail
-- loudly instead of being treated as healthy.

CREATE TABLE `tui_dynamic` (
    `id` BIGINT UNSIGNED NOT NULL COMMENT '动态ID（雪花算法生成）',
    `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    `content` TEXT COMMENT '动态内容',
    `images` VARCHAR(2000) COMMENT '图片URL，多个用逗号分隔',
    `like_count` INT DEFAULT 0 COMMENT '点赞数',
    `comment_count` INT DEFAULT 0 COMMENT '评论数',
    `share_count` INT DEFAULT 0 COMMENT '分享数',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `status` TINYINT DEFAULT 0 COMMENT '状态：0-正常，1-删除，2-已归档',
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_user_status` (`user_id`, `status`),
    INDEX `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='动态表';

CREATE TABLE `tui_follow` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '关注ID',
    `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    `follow_user_id` BIGINT UNSIGNED NOT NULL COMMENT '被关注用户ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `status` TINYINT DEFAULT 0 COMMENT '状态：0-正常，1-取消关注',
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_user_follow` (`user_id`, `follow_user_id`),
    INDEX `idx_follow_user_id` (`follow_user_id`),
    INDEX `idx_user_status` (`user_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='关注关系表';

CREATE TABLE `tui_notification` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '通知ID',
    `user_id` BIGINT UNSIGNED NOT NULL COMMENT '用户ID',
    `sender_id` BIGINT UNSIGNED COMMENT '发送者ID',
    `type` TINYINT NOT NULL COMMENT '通知类型：1-点赞，2-评论，3-分享，4-关注，5-系统通知',
    `content` VARCHAR(500) NOT NULL COMMENT '通知内容',
    `target_id` VARCHAR(100) COMMENT '目标ID（动态ID/用户ID等）',
    `is_read` TINYINT DEFAULT 0 COMMENT '是否已读：0-未读，1-已读',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_user_read` (`user_id`, `is_read`),
    INDEX `idx_create_time` (`create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通知表';

CREATE TABLE `tui_comment` (
    `id` BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '评论ID',
    `dynamic_id` BIGINT UNSIGNED NOT NULL COMMENT '动态ID',
    `user_id` BIGINT UNSIGNED NOT NULL COMMENT '评论用户ID',
    `content` VARCHAR(500) NOT NULL COMMENT '评论内容',
    `parent_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '父评论ID（回复评论时使用）',
    `reply_user_id` BIGINT UNSIGNED DEFAULT NULL COMMENT '被回复用户ID',
    `like_count` INT DEFAULT 0 COMMENT '点赞数',
    `status` TINYINT DEFAULT 0 COMMENT '状态：0-正常，1-删除',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    INDEX `idx_dynamic_id` (`dynamic_id`),
    INDEX `idx_user_id` (`user_id`),
    INDEX `idx_dynamic_status` (`dynamic_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评论表';

CREATE TABLE `tui_like` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `user_id` BIGINT NOT NULL COMMENT '点赞用户ID',
    `target_id` BIGINT NOT NULL COMMENT '点赞目标ID（动态或评论）',
    `target_type` TINYINT NOT NULL COMMENT '目标类型：1-动态，2-评论',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0-正常，1-取消',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_user_target_type` (`user_id`, `target_id`, `target_type`),
    INDEX `idx_like_lookup` (`user_id`, `target_id`, `target_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='点赞关系表';

CREATE TABLE `mq_message_status` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `message_id` VARCHAR(64) NOT NULL UNIQUE COMMENT 'Stable message ID',
    `event_type` VARCHAR(64) NOT NULL COMMENT 'Event type',
    `business_key` VARCHAR(128) NULL COMMENT 'Business key',
    `message_body` LONGTEXT NULL COMMENT 'Serialized event payload',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-pending, 1-confirmed, 2-failed, 3-consumed, 4-consume-failed, 5-compensating, 6-dead-lettered',
    `retry_count` INT NOT NULL DEFAULT 0 COMMENT 'Compensation/retry count',
    `last_error` TEXT NULL COMMENT 'Latest failure summary',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    INDEX `idx_mq_status` (`status`),
    INDEX `idx_mq_create_time` (`create_time`),
    INDEX `idx_mq_status_retry` (`status`, `retry_count`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='MQ message status';

CREATE TABLE `mq_outbox` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `event_id` VARCHAR(64) NOT NULL COMMENT 'Stable event ID',
    `event_type` VARCHAR(64) NOT NULL COMMENT 'Event type',
    `exchange_name` VARCHAR(128) NOT NULL COMMENT 'RabbitMQ exchange',
    `routing_key` VARCHAR(128) NOT NULL COMMENT 'RabbitMQ routing key',
    `message_body` LONGTEXT NOT NULL COMMENT 'Serialized event payload',
    `status` TINYINT NOT NULL DEFAULT 0 COMMENT '0-pending, 1-processing, 2-dispatched, 3-failed, 4-dead-lettered',
    `retry_count` INT NOT NULL DEFAULT 0 COMMENT 'Number of relay retries',
    `next_attempt_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `lease_until` DATETIME NULL COMMENT 'Relay lease expiry',
    `last_error` VARCHAR(1000) NULL COMMENT 'Latest relay failure summary',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE INDEX `uk_mq_outbox_event_id` (`event_id`),
    INDEX `idx_mq_outbox_dispatch` (`status`, `next_attempt_time`, `id`),
    INDEX `idx_mq_outbox_lease` (`status`, `lease_until`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Transactional MQ outbox';
