-- MQ message lifecycle state used by the conditional state transitions.
CREATE TABLE IF NOT EXISTS mq_message_status (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    message_id VARCHAR(64) NOT NULL UNIQUE COMMENT 'Stable message ID',
    event_type VARCHAR(32) NOT NULL COMMENT 'Event type',
    business_key VARCHAR(128) COMMENT 'Business key',
    message_body LONGTEXT COMMENT 'Serialized event payload',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-pending, 1-confirmed, 2-failed, 3-consumed, 4-consume-failed, 5-compensating, 6-dead-lettered',
    retry_count INT NOT NULL DEFAULT 0 COMMENT 'Compensation/retry count',
    last_error TEXT COMMENT 'Latest failure summary',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_status (status),
    INDEX idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='MQ message status';
