-- Transactional outbox for events that must survive an application crash
-- between the business transaction commit and RabbitMQ publish.
CREATE TABLE IF NOT EXISTS mq_outbox (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    event_id VARCHAR(64) NOT NULL UNIQUE COMMENT 'Stable event ID',
    event_type VARCHAR(64) NOT NULL COMMENT 'Event type',
    exchange_name VARCHAR(128) NOT NULL COMMENT 'RabbitMQ exchange',
    routing_key VARCHAR(128) NOT NULL COMMENT 'RabbitMQ routing key',
    message_body LONGTEXT NOT NULL COMMENT 'Serialized event payload',
    status TINYINT NOT NULL DEFAULT 0 COMMENT '0-pending, 1-processing, 2-dispatched, 3-failed, 4-dead-lettered',
    retry_count INT NOT NULL DEFAULT 0 COMMENT 'Number of relay retries',
    next_attempt_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    lease_until DATETIME NULL COMMENT 'Relay lease expiry',
    last_error VARCHAR(1000) NULL COMMENT 'Latest relay failure summary',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_outbox_dispatch (status, next_attempt_time, id),
    INDEX idx_outbox_lease (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='Transactional MQ outbox';
