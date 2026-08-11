-- 关注关系唯一约束
ALTER TABLE tui_follow ADD UNIQUE INDEX uk_user_follow (user_id, follow_user_id);

-- 如果需要支持软删除，可以使用条件唯一索引（MySQL 8.0+ 不支持，需要应用层保证）
-- 或者在业务逻辑中检查 status 字段

-- 点赞关系表（如果还没有的话）
CREATE TABLE IF NOT EXISTS tui_like (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    target_id BIGINT NOT NULL COMMENT '点赞目标ID（动态ID或评论ID）',
    target_type TINYINT NOT NULL COMMENT '目标类型：1-动态，2-评论',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '状态：0-正常，1-已取消',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE INDEX uk_user_target_type (user_id, target_id, target_type),
    INDEX idx_target_type (target_id, target_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='点赞关系表';
