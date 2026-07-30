CREATE TABLE IF NOT EXISTS `tui_dynamic` (
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

CREATE TABLE IF NOT EXISTS `tui_follow` (
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

CREATE TABLE IF NOT EXISTS `tui_notification` (
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

-- ==================== 评论表 ====================
CREATE TABLE IF NOT EXISTS `tui_comment` (
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